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
                    null
                }

                XaiResponsesEventNames.OUTPUT_TEXT_DELTA -> event.delta
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { ParsedEvent(delta = textDelta(content = it)) }

                XaiResponsesEventNames.REASONING_SUMMARY_DELTA -> event.delta
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { ParsedEvent(delta = textDelta(reasoning = it)) }

                XaiResponsesEventNames.COMPLETED -> completed(event)

                // 其余实测事件（content_part.*、*_text.done、function_call_arguments.*）
                // 不产生上层增量；合法但未采集的事件类型同样忽略。
                else -> null
            }
        }

        private fun trackItemAdded(event: XaiResponseEventDto) {
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

        private fun completed(event: XaiResponseEventDto): ParsedEvent {
            val status = event.response?.status
            // 只承认实测过的成功形状。其它 status 的含义未采集（截断？失败？），
            // 不能一律当作正常结束，否则会把残缺回复当成完整回复落库。
            if (status != null && status != STATUS_COMPLETED) {
                throw XaiResponsesTransport.XaiResponsesException("xAI 回复未正常结束，请重试")
            }
            if (openMessageIndices.isNotEmpty()) {
                throw XaiResponsesTransport.XaiResponsesException("xAI 回复未正常结束，请重试")
            }
            return ParsedEvent(
                delta = OpenAiCompatibleStreamEvent.Delta(
                    content = null,
                    reasoningContent = null,
                    // 上层按 OpenAI 兼容协议理解结束原因；截断的事件形状未采集，
                    // 因此这里只报 stop，不猜 length/content_filter 之类的取值。
                    finishReason = FINISH_REASON_STOP,
                    usage = event.response?.usage?.toOpenAiCompatible(),
                ),
                completed = true,
            )
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
        private const val ITEM_TYPE_MESSAGE = "message"
        private const val STATUS_COMPLETED = "completed"
    }
}
