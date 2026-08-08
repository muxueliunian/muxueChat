package com.muxuechat.data.remote

import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.ThinkingMode

/**
 * 集中构造 DeepSeek 请求体，保证不同用途的请求遵守同一套参数规则。
 *
 * 依据 DeepSeek 官方文档（api-docs.deepseek.com）：
 * - `thinking` 的缺省值是 `enabled`。因此"关闭思考模式"必须显式发送
 *   `{"thinking":{"type":"disabled"}}`，否则服务端仍会思考。
 * - 思考模式开启时 `temperature` / `top_p` 不生效（不报错，静默忽略）。
 * - `presence_penalty` / `frequency_penalty` 已被标记为废弃，任何情况下都不生效，
 *   因此已从 DTO、设置模型和设置页中整体移除。
 *
 * 因此这里的原则是：不生效的参数一律不发送，让请求体和 UI 语义保持一致。
 */
object DeepSeekRequestFactory {
    /** 请求用途。不同用途的采样策略和 token 预算互相独立，避免普通聊天设置污染结构化请求。 */
    enum class Purpose {
        /** 普通聊天 / RP 正文，跟随用户的全部生成设置。 */
        CHAT,

        /** RP 元数据（内心独白、剧情建议、状态）。要求稳定 JSON，固定低随机性。 */
        RP_METADATA,

        /** RP 状态兜底更新。要求几乎确定性的输出。 */
        RP_STATE_UPDATE,

        /** 从文档/文本生成 RP 设定。要求完整可校验 JSON，独立的大 token 预算。 */
        SETTINGS_GENERATION,

        /** 把一段旧历史压成摘要。输出是自然语言，预算按摘要篇幅上限给。 */
        CONTEXT_SUMMARY,
    }

    fun build(
        settings: ChatSettings,
        messages: List<DeepSeekMessageDto>,
        purpose: Purpose,
        streamOverride: Boolean? = null,
        tools: List<DeepSeekToolDto>? = null,
        toolChoice: String? = null,
    ): DeepSeekChatRequest {
        val normalized = settings.normalized()
        val thinkingEnabled = purpose.thinkingEnabled(normalized)
        val samplingEffective = !thinkingEnabled

        return DeepSeekChatRequest(
            model = normalized.model.apiName,
            messages = messages,
            stream = streamOverride ?: (purpose == Purpose.CHAT && normalized.streamEnabled),
            maxTokens = purpose.maxTokens(normalized),
            // 必须显式发送，缺省是 enabled。
            thinking = DeepSeekThinkingDto(
                type = if (thinkingEnabled) ThinkingMode.ENABLED.apiType else ThinkingMode.DISABLED.apiType,
                // 只在思考开启时才有意义，且必须嵌在 thinking 里。
                reasoningEffort = if (thinkingEnabled) normalized.reasoningEffort.apiName else null,
            ),
            // 思考开启时会被服务端忽略，不发送以免造成"设置生效"的错觉。
            temperature = if (samplingEffective) purpose.temperature(normalized) else null,
            topP = if (samplingEffective) purpose.topP(normalized) else null,
            tools = tools,
            toolChoice = toolChoice,
        )
    }

    private fun Purpose.thinkingEnabled(settings: ChatSettings): Boolean = when (this) {
        Purpose.CHAT -> settings.thinkingMode == ThinkingMode.ENABLED
        // 结构化请求只要一段稳定 JSON，思考既慢又贵，且会让输出更容易夹带解释文字。
        Purpose.RP_METADATA, Purpose.RP_STATE_UPDATE, Purpose.SETTINGS_GENERATION,
        Purpose.CONTEXT_SUMMARY,
        -> false
    }

    private fun Purpose.maxTokens(settings: ChatSettings): Int = when (this) {
        Purpose.CHAT -> settings.maxTokens
        Purpose.RP_METADATA -> RP_METADATA_MAX_TOKENS
        Purpose.RP_STATE_UPDATE -> RP_STATE_UPDATE_MAX_TOKENS
        Purpose.SETTINGS_GENERATION -> SETTINGS_GENERATION_MAX_TOKENS
        Purpose.CONTEXT_SUMMARY -> CONTEXT_SUMMARY_MAX_TOKENS
    }

    private fun Purpose.temperature(settings: ChatSettings): Double = when (this) {
        Purpose.CHAT -> settings.temperature
        Purpose.RP_METADATA -> RP_METADATA_TEMPERATURE
        Purpose.RP_STATE_UPDATE -> RP_STATE_UPDATE_TEMPERATURE
        Purpose.SETTINGS_GENERATION -> SETTINGS_GENERATION_TEMPERATURE
        // 摘要要忠于原文，随机性越低越好。
        Purpose.CONTEXT_SUMMARY -> CONTEXT_SUMMARY_TEMPERATURE
    }

    private fun Purpose.topP(settings: ChatSettings): Double = when (this) {
        Purpose.CHAT -> settings.topP
        else -> 1.0
    }

    const val RP_METADATA_MAX_TOKENS = 900
    const val RP_STATE_UPDATE_MAX_TOKENS = 512
    const val SETTINGS_GENERATION_MAX_TOKENS = 12_000
    const val CONTEXT_SUMMARY_MAX_TOKENS = 2_000

    private const val RP_METADATA_TEMPERATURE = 0.35
    private const val RP_STATE_UPDATE_TEMPERATURE = 0.1
    private const val SETTINGS_GENERATION_TEMPERATURE = 0.3
    private const val CONTEXT_SUMMARY_TEMPERATURE = 0.2
}
