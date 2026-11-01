package com.muxuechat.domain.context

import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ImageInputSpec
import com.muxuechat.domain.prompt.PromptMessage

/**
 * 每张图片的预计 token（MULTIMODAL_PLAN 3.2）。只按协议区分：上层只认两条协议，不按 Vertex / xAI 分叉。
 *
 * - DeepSeek：官方写明每张图最多计 1024 tokens；
 * - OpenAI 兼容（含 Vertex、xAI）：没有统一口径，取保守值。Vertex Gemini 3 默认 1120，xAI 未公布，
 *   都不超过它。结果和其它估算一样只能标「预计」。
 */
object ImageTokenEstimate {
    const val DEEPSEEK_TOKENS_PER_IMAGE = 1024L
    const val OPENAI_COMPATIBLE_TOKENS_PER_IMAGE = 1600L

    fun perImage(protocol: ChatProviderId): Long = when (protocol) {
        ChatProviderId.DEEPSEEK -> DEEPSEEK_TOKENS_PER_IMAGE
        ChatProviderId.CUSTOM_OPENAI -> OPENAI_COMPATIBLE_TOKENS_PER_IMAGE
    }
}

/**
 * 请求体里图片总量的安全上限（D2 的唯一例外，MULTIMODAL_PLAN 3.3 第 2 步）。
 *
 * 历史图片每轮重发；总量超过 [ImageInputSpec.MAX_IMAGE_PAYLOAD_BYTES] 时从**最旧**的消息开始
 * 把图片换成文字占位，直到放得下。**本轮用户消息（最后一条 user）的图片永不省略**——
 * 即使它自己就超过上限，也照发，让上游给出明确的错误。
 */
internal object ImagePayloadBudget {
    data class Result(
        val messages: List<PromptMessage>,
        val omittedImageCount: Int,
    )

    fun apply(messages: List<PromptMessage>, maxBytes: Long = ImageInputSpec.MAX_IMAGE_PAYLOAD_BYTES): Result {
        var total = messages.sumOf { message -> message.payloadBytes() }
        if (total <= maxBytes) return Result(messages, 0)

        val currentTurnIndex = messages.indexOfLast { it.role.equals("user", ignoreCase = true) }
        var omitted = 0
        val result = messages.mapIndexed { index, message ->
            if (total <= maxBytes || index == currentTurnIndex || message.images.isEmpty()) {
                message
            } else {
                total -= message.payloadBytes()
                omitted += message.images.size
                message.copy(content = omittedNote(message.content, message.images.size), images = emptyList())
            }
        }
        return Result(result, omitted)
    }

    private fun PromptMessage.payloadBytes(): Long = images.sumOf { ImageInputSpec.base64Size(it.byteSize) }

    private fun omittedNote(content: String, count: Int): String {
        val note = "[更早的 $count 张图片因请求体积上限已省略]"
        return if (content.isBlank()) note else content.trimEnd() + "\n" + note
    }
}
