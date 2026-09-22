package com.muxuechat.data.remote

import kotlinx.serialization.json.Json

/**
 * 把 xAI Responses 的 SSE 数据行翻译成上层认识的 [OpenAiCompatibleStreamEvent]。
 *
 * ## 为什么不按 `output_index` 维护「当前活动项」
 *
 * 线上格式 §3.2 实测：多个 output item 会**并发交错**——正文项还没收尾，
 * 工具调用项就插进来了，正文的 `output_text.done` 反而在后面才到。
 * 任何「记住当前 item 类型，再按它路由增量」的写法都会在这里串台。
 *
 * 本解析器**按事件名直接路由**：`output_text.delta` 恒为正文，
 * `reasoning_summary_text.delta` 恒为思考。事件名自带归属，交错因此不构成问题，
 * 也不需要维护任何跨事件状态。这不是偷懒，是针对实测行为选的唯一不会错的解法。
 *
 * ## 未采集的事件
 *
 * 线上格式 §3.4 列出的场景（截断、错误事件、取消断流、429、无终止事件断流）
 * 事件名未知。按 AGENT.md §6：**未知 `type` 一律忽略，不臆测语义**。
 * 「流结束却没见过 `response.completed`」由 [XaiResponsesTransport] 判定为失败，
 * 不在这里猜。
 */
internal class XaiResponsesStreamParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * 解析一行 SSE。
     *
     * 返回 null 表示这一行不产生上层事件（非 data 行、心跳、或未采集的未知事件）。
     * [ParsedEvent.completed] 为 true 时表示收到了终止事件。
     */
    fun parseLine(line: String): ParsedEvent? {
        val trimmed = line.trim()
        // `event:` 行与 data 里的 type 取值相同，只认后者，少一处不一致的来源。
        if (!trimmed.startsWith("data:")) return null

        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return null

        val event = runCatching { json.decodeFromString<XaiResponseEventDto>(payload) }.getOrNull()
            ?: return null

        return when (event.type) {
            XaiResponsesEventNames.OUTPUT_TEXT_DELTA -> event.delta
                ?.takeIf { it.isNotEmpty() }
                ?.let { ParsedEvent(delta = textDelta(content = it)) }

            XaiResponsesEventNames.REASONING_SUMMARY_DELTA -> event.delta
                ?.takeIf { it.isNotEmpty() }
                ?.let { ParsedEvent(delta = textDelta(reasoning = it)) }

            XaiResponsesEventNames.COMPLETED -> ParsedEvent(
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

            // 其余实测事件（output_item.added/done、content_part.*、*_text.done、
            // function_call_arguments.*）都不产生上层增量；未采集的未知事件同样忽略。
            else -> null
        }
    }

    private fun textDelta(content: String? = null, reasoning: String? = null) =
        OpenAiCompatibleStreamEvent.Delta(
            content = content,
            reasoningContent = reasoning,
            finishReason = null,
            usage = null,
        )

    internal data class ParsedEvent(
        val delta: OpenAiCompatibleStreamEvent.Delta,
        /** 收到 `response.completed`：这是唯一实测过的正常终止信号。 */
        val completed: Boolean = false,
    )

    internal companion object {
        const val FINISH_REASON_STOP = "stop"
    }
}
