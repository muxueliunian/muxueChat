package com.muxuechat.data.remote

import com.muxuechat.domain.model.ChatSettings

/**
 * 集中构造 供应商的 OpenAI 兼容请求。
 *
 * 它与 DeepSeekRequestFactory 并列，而不是把两个协议抽象成通用 Provider SDK。
 * 自定义供应商可以承载标准 OpenAI function tools，但永远不发送 DeepSeek 的
 * thinking 字段或 OAuth 相关字段。
 */
object OpenAiCompatibleRequestFactory {
    /** 不同用途保持独立预算，避免普通聊天设置污染 RP 元数据与上下文摘要。 */
    enum class Purpose {
        CHAT,
        RP_METADATA,
        RP_STATE_UPDATE,
        CONTEXT_SUMMARY,
    }

    fun build(
        settings: ChatSettings,
        model: String,
        messages: List<OpenAiCompatibleMessageDto>,
        purpose: Purpose,
        streamOverride: Boolean? = null,
        /** 用户为该供应商填写的 `reasoning_effort`；空或 null 表示不发送。 */
        reasoningEffort: String? = null,
        /** 仅 [Purpose.CHAT] 可用的标准 OpenAI function 工具定义。 */
        tools: List<OpenAiCompatibleToolDto>? = null,
        toolChoice: String? = null,
    ): OpenAiCompatibleChatRequest {
        val normalized = settings.normalized()
        val resolvedModel = model.trim()
        require(resolvedModel.isNotBlank()) { "请配置供应商模型名" }

        return OpenAiCompatibleChatRequest(
            model = resolvedModel,
            messages = messages,
            // stream 不带默认值，false 也必须被实际编码。
            stream = streamOverride ?: (purpose == Purpose.CHAT && normalized.streamEnabled),
            maxTokens = purpose.maxTokens(normalized),
            temperature = purpose.temperature(normalized),
            topP = purpose.topP(normalized),
            reasoningEffort = purpose.reasoningEffort(reasoningEffort),
            // RP 补写和摘要是内部结构化请求，绝不能被调用点误挂载工具。
            tools = purpose.tools(tools),
            toolChoice = purpose.toolChoice(tools, toolChoice),
        )
    }

    private fun Purpose.tools(value: List<OpenAiCompatibleToolDto>?): List<OpenAiCompatibleToolDto>? =
        value?.takeIf { this == Purpose.CHAT && it.isNotEmpty() }

    private fun Purpose.toolChoice(
        tools: List<OpenAiCompatibleToolDto>?,
        value: String?,
    ): String? = value?.trim()?.takeIf { this == Purpose.CHAT && !tools.isNullOrEmpty() && it.isNotEmpty() }

    /**
     * 思考等级只用于正文对话。
     *
     * RP 元数据、状态兜底、上下文摘要都是短小的结构化输出，让模型在这些请求上思考
     * 只会拖慢补写、额外烧 token，对结果没有帮助。
     */
    private fun Purpose.reasoningEffort(value: String?): String? = when (this) {
        Purpose.CHAT -> value?.trim()?.takeIf { it.isNotEmpty() }
        else -> null
    }

    private fun Purpose.maxTokens(settings: ChatSettings): Int = when (this) {
        Purpose.CHAT -> settings.maxTokens
        Purpose.RP_METADATA -> RP_METADATA_MAX_TOKENS
        Purpose.RP_STATE_UPDATE -> RP_STATE_UPDATE_MAX_TOKENS
        Purpose.CONTEXT_SUMMARY -> CONTEXT_SUMMARY_MAX_TOKENS
    }

    private fun Purpose.temperature(settings: ChatSettings): Double = when (this) {
        Purpose.CHAT -> settings.temperature
        Purpose.RP_METADATA -> RP_METADATA_TEMPERATURE
        Purpose.RP_STATE_UPDATE -> RP_STATE_UPDATE_TEMPERATURE
        Purpose.CONTEXT_SUMMARY -> CONTEXT_SUMMARY_TEMPERATURE
    }

    private fun Purpose.topP(settings: ChatSettings): Double = when (this) {
        Purpose.CHAT -> settings.topP
        else -> 1.0
    }

    const val RP_METADATA_MAX_TOKENS = 900
    const val RP_STATE_UPDATE_MAX_TOKENS = 512
    const val CONTEXT_SUMMARY_MAX_TOKENS = 2_000

    private const val RP_METADATA_TEMPERATURE = 0.35
    private const val RP_STATE_UPDATE_TEMPERATURE = 0.1
    private const val CONTEXT_SUMMARY_TEMPERATURE = 0.2
}
