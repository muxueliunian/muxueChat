package com.muxuechat.data.remote

/** 供应商的 OpenAI 兼容 SSE 事件。 */
sealed interface OpenAiCompatibleStreamEvent {
    data class Delta(
        val content: String?,
        /** 思考过程增量；服务不返回或模型不思考时为 null。 */
        val reasoningContent: String?,
        val finishReason: String?,
        val usage: OpenAiCompatibleUsageDto?,
        /** 工具调用分片。name 和 arguments 可能分多个 chunk 到达，需要按 index 拼接。 */
        val toolCalls: List<OpenAiCompatibleToolCallDto>? = null,
    ) : OpenAiCompatibleStreamEvent

    data object Done : OpenAiCompatibleStreamEvent
}
