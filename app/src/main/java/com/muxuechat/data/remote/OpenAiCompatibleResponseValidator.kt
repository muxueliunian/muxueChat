package com.muxuechat.data.remote

/** 摘要、标题和 RP 辅助结果只有完整文本才能提交，避免截断摘要覆盖完整历史。 */
internal fun OpenAiCompatibleChatChunkDto.requireCompleteText(): String {
    val choice = choices.firstOrNull()
        ?: throw OpenAiCompatibleProtocolException("供应商未返回有效回复")
    when (choice.finishReason) {
        "length" -> throw OpenAiCompatibleProtocolException("辅助请求达到输出上限，结果不完整，已保留原数据")
        "content_filter" -> throw OpenAiCompatibleProtocolException("供应商未返回完整辅助结果，已保留原数据")
        null, "stop" -> Unit
        else -> throw OpenAiCompatibleProtocolException("供应商未正常完成辅助请求，已保留原数据")
    }
    val message = choice.message
    if (!message?.toolCalls.isNullOrEmpty() || !message?.refusal.isNullOrBlank()) {
        throw OpenAiCompatibleProtocolException("供应商没有提供所需的辅助结果，已保留原数据")
    }
    return message?.content?.takeIf { it.isNotBlank() }
        ?: throw OpenAiCompatibleProtocolException("供应商未返回辅助结果正文，已保留原数据")
}
