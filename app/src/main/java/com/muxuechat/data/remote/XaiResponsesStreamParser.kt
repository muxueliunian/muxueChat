package com.muxuechat.data.remote

import kotlinx.serialization.json.Json

/**
 * 把 xAI Responses 的 SSE 数据行翻译成上层认识的 [OpenAiCompatibleStreamEvent]。
 *
 * ## 按事件名路由，另外按 `output_index` 做防御
 *
 * 线上格式 §3.2 实测：多个 output item 会**并发交错**——正文项还没收尾，
 * 工具调用项就插进来了，正文的 `output_text.done` 反而在后面才到。
 * 因此增量的归属**只看事件名**（`output_text.delta` 恒为正文，
 * `reasoning_summary_text.delta` 恒为思考），不靠「当前活动 item」推断，
 * 否则交错必然串台。
 *
 * 但按事件名路由**只解决了跨类型串台，解决不了同类型的顺序**：
 * 若同时存在两个 `message` item，它们的 delta 会交错到达，直接拼接就是混排。
 * 抓包里没有出现过这种形状，语义因此未知，所以本解析器**检测到就失败**，
 * 而不是拼出一段顺序不明的正文（AGENT.md：未采集的形状不得凭猜实现）。
 *
 * ## 解析失败不等于未知事件
 *
 * 「合法但没见过的事件类型」可以安全忽略；「JSON 解析失败或结构不合法」不行——
 * 那说明我们对这条流的理解已经不成立，继续拼下去会把残缺内容当成完整回复落库。
 * 后者一律抛错。
 *
 * ## 工具调用在项完成时一次性给出
 *
 * function_call 项的参数先分片（`function_call_arguments.delta`），再由 `.done` 给出权威全文
 * （线上格式 §3.3）。本解析器按 `output_index` 自己拼，等 `output_item.done` 才把
 * **完整的一次调用**交给上层，而不是把分片透传下去——上层的累加器只会追加，
 * 透传 delta 再补一次 `.done` 全文就会拼成两份参数。
 *
 * 调用项在 `response.completed` 前没有收尾、或缺 `call_id` / `name`，都按失败处理：
 * 这样的调用无法回传结果，静默丢掉会让模型以为工具没被执行，用户也看不到草稿。
 *
 * ## 有状态，必须每条流新建
 *
 * [newSession] 每次返回独立实例；解析器本身不持有任何流级状态。
 */
internal class XaiResponsesStreamParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun newSession(): Session = Session(json)

    internal class Session(private val json: Json) {
        /** 当前处于 added 与 done 之间的 message item。超过一个即为未采集形状。 */
        private val openMessageIndices = mutableSetOf<Int>()

        /** 尚未收尾的 function_call 项，按 `output_index` 分别拼接（线上格式 §3.2）。 */
        private val openFunctionCalls = mutableMapOf<Int, FunctionCallBuilder>()

        /** 已交给上层的调用数，同时作为上层累加器的 index，保证按到达顺序排列。 */
        private var emittedToolCalls = 0

        /**
         * 解析一行 SSE。
         *
         * 返回 null 表示这一行不产生上层事件（非 data 行、心跳、或合法但未知的事件类型）。
         * 解析失败或遇到未采集的并发形状时抛出 [XaiResponsesTransport.XaiResponsesException]。
         */
        fun parseLine(line: String): ParsedEvent? {
            val trimmed = line.trim()
            // `event:` 行与 data 里的 type 取值相同，只认后者，少一处不一致的来源。
            if (!trimmed.startsWith("data:")) return null

            val payload = trimmed.removePrefix("data:").trim()
            if (payload.isEmpty()) return null

            val event = runCatching { json.decodeFromString<XaiResponseEventDto>(payload) }
                .getOrElse {
                    // 不能当成「未知事件」吞掉：那会让残缺的正文照样走到成功落库。
                    throw XaiResponsesTransport.XaiResponsesException("xAI 返回内容无法解析")
                }

            return when (event.type) {
                XaiResponsesEventNames.OUTPUT_ITEM_ADDED -> {
                    trackItemAdded(event)
                    null
                }

                XaiResponsesEventNames.OUTPUT_ITEM_DONE -> {
                    event.outputIndex?.let(openMessageIndices::remove)
                    finishFunctionCall(event)
                }

                XaiResponsesEventNames.FUNCTION_CALL_ARGUMENTS_DELTA -> {
                    openFunctionCall(event).arguments.append(event.delta.orEmpty())
                    null
                }

                XaiResponsesEventNames.FUNCTION_CALL_ARGUMENTS_DONE -> {
                    openFunctionCall(event).doneArguments = event.arguments
                    null
                }

                XaiResponsesEventNames.OUTPUT_TEXT_DELTA -> event.delta
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { ParsedEvent(delta = textDelta(content = it)) }

                XaiResponsesEventNames.REASONING_SUMMARY_DELTA -> event.delta
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { ParsedEvent(delta = textDelta(reasoning = it)) }

                XaiResponsesEventNames.COMPLETED -> completed(event)

                // 其余实测事件（content_part.*、*_text.done）不产生上层增量；
                // 合法但未采集的事件类型同样忽略。
                else -> null
            }
        }

        private fun trackItemAdded(event: XaiResponseEventDto) {
            if (event.item?.type == ITEM_TYPE_FUNCTION_CALL) {
                // 没有 output_index 就无法把后续分片归到这一项上。
                val index = event.outputIndex ?: throw unparseable()
                if (index in openFunctionCalls) throw unparseable()
                openFunctionCalls[index] = FunctionCallBuilder(
                    callId = event.item.callId,
                    name = event.item.name,
                )
                return
            }
            if (event.item?.type != ITEM_TYPE_MESSAGE) return
            val index = event.outputIndex ?: return
            openMessageIndices += index
            if (openMessageIndices.size > 1) {
                // 抓包中从未出现两个并发正文项，合并顺序因此没有依据。
                throw XaiResponsesTransport.XaiResponsesException(
                    "xAI 返回了多段并行正文，当前版本无法可靠合并，请重试",
                )
            }
        }

        /** 参数事件必须落在一个已登记的调用项上；对不上说明我们对这条流的理解已经不成立。 */
        private fun openFunctionCall(event: XaiResponseEventDto): FunctionCallBuilder =
            event.outputIndex?.let(openFunctionCalls::get) ?: throw unparseable()

        /**
         * 调用项收尾：交出一次完整调用。不是调用项的 done 返回 null。
         *
         * 参数取值顺序：`.done` 的全文 → done 项自带的全文 → 累积的 delta。
         * 实测三者相同；以 `.done` 为准是线上格式 §3.3 的约定。
         */
        private fun finishFunctionCall(event: XaiResponseEventDto): ParsedEvent? {
            val index = event.outputIndex ?: return null
            val builder = openFunctionCalls.remove(index) ?: return null
            val callId = event.item?.callId?.takeIf { it.isNotBlank() } ?: builder.callId
            val name = event.item?.name?.takeIf { it.isNotBlank() } ?: builder.name
            if (callId.isNullOrBlank() || name.isNullOrBlank()) {
                throw XaiResponsesTransport.XaiResponsesException("xAI 返回的工具调用不完整，请重试")
            }
            val arguments = builder.doneArguments
                ?: event.item?.arguments?.takeIf { it.isNotEmpty() }
                ?: builder.arguments.toString()
            val call = OpenAiCompatibleToolCallDto(
                // 上层把它当 tool_call_id 原样回传，回到本类后再作为 call_id 发出（§3.3）。
                id = callId,
                function = OpenAiCompatibleFunctionCallDto(name = name, arguments = arguments),
                index = emittedToolCalls++,
            )
            return ParsedEvent(
                delta = OpenAiCompatibleStreamEvent.Delta(
                    content = null,
                    reasoningContent = null,
                    finishReason = null,
                    usage = null,
                    toolCalls = listOf(call),
                ),
            )
        }

        private fun completed(event: XaiResponseEventDto): ParsedEvent {
            val status = event.response?.status
            // 只承认实测过的成功形状。其它 status 的含义未采集（截断？失败？），
            // 不能一律当作正常结束，否则会把残缺回复当成完整回复落库。
            if (status != STATUS_COMPLETED) {
                throw XaiResponsesTransport.XaiResponsesException("xAI 回复未正常结束，请重试")
            }
            if (openMessageIndices.isNotEmpty() || openFunctionCalls.isNotEmpty()) {
                throw XaiResponsesTransport.XaiResponsesException("xAI 回复未正常结束，请重试")
            }
            return ParsedEvent(
                delta = OpenAiCompatibleStreamEvent.Delta(
                    content = null,
                    reasoningContent = null,
                    // 上层按 OpenAI 兼容协议理解结束原因。以工具调用收尾的一轮，上游的 status
                    // 仍是 completed（线上格式 §3.3），所以按本轮是否交出过调用来区分；
                    // 截断的事件形状未采集，不猜 length/content_filter 之类的取值。
                    finishReason = if (emittedToolCalls > 0) FINISH_REASON_TOOL_CALLS else FINISH_REASON_STOP,
                    usage = event.response?.usage?.toOpenAiCompatible(),
                ),
                completed = true,
            )
        }

        private fun unparseable() = XaiResponsesTransport.XaiResponsesException("xAI 返回内容无法解析")

        private class FunctionCallBuilder(val callId: String?, val name: String?) {
            val arguments = StringBuilder()
            var doneArguments: String? = null
        }

        private fun textDelta(content: String? = null, reasoning: String? = null) =
            OpenAiCompatibleStreamEvent.Delta(
                content = content,
                reasoningContent = reasoning,
                finishReason = null,
                usage = null,
            )
    }

    internal data class ParsedEvent(
        val delta: OpenAiCompatibleStreamEvent.Delta,
        /** 收到 `response.completed`：这是唯一实测过的正常终止信号。 */
        val completed: Boolean = false,
    )

    internal companion object {
        const val FINISH_REASON_STOP = "stop"
        const val FINISH_REASON_TOOL_CALLS = "tool_calls"
        private const val ITEM_TYPE_MESSAGE = "message"
        private const val ITEM_TYPE_FUNCTION_CALL = "function_call"
        private const val STATUS_COMPLETED = "completed"
    }
}
