package com.muxuechat.data.remote

sealed interface DeepSeekStreamEvent {
    data class Delta(
        val content: String?,
        val reasoningContent: String?,
        val finishReason: String?,
        val usage: DeepSeekUsageDto?,
        /** 工具调用分片。流式下 name 和 arguments 会分多个 chunk 到达，需要按 index 拼接。 */
        val toolCalls: List<DeepSeekToolCallDto>? = null,
    ) : DeepSeekStreamEvent

    data object Done : DeepSeekStreamEvent
}
