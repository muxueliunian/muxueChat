package com.muxuechat.domain.model

import com.muxuechat.domain.context.ContextCompressionMode
import com.muxuechat.domain.context.ContextCompressionPolicy

data class ChatSettings(
    val baseUrl: String = "https://api.deepseek.com",
    val model: DeepSeekModel = DeepSeekModel.V4_FLASH,
    val systemPrompt: String = "",
    val streamEnabled: Boolean = true,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val thinkingMode: ThinkingMode = ThinkingMode.DISABLED,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.HIGH,
    val temperature: Double = 0.7,
    val topP: Double = 1.0,
    // presence_penalty / frequency_penalty 已被 DeepSeek 废弃且永不生效，故不再建模。
    val contextCompressionMode: ContextCompressionMode = ContextCompressionMode.DEFAULT,
    val contextAutoTriggerPercent: Int = ContextCompressionPolicy.DEFAULT_AUTO_TRIGGER_PERCENT,
    val contextBudgetChars: Int = ContextCompressionPolicy.DEFAULT_BUDGET_CHARS,
    /** 非 RP 模式下是否允许模型调用「创建角色卡/世界观/文风」工具的总开关。 */
    val settingsToolsEnabled: Boolean = true,
    /**
     * 活跃请求的 Provider。默认 DeepSeek，保证升级后沿用既有偏好和请求行为。
     *
     * 凭据不属于 ChatSettings，分别由 Keystore 存储。
     */
    val provider: ChatProviderId = ChatProviderId.DEEPSEEK,
    /** 已保存的自定义 OpenAI 兼容供应商；不含任何 API Key。 */
    val customProviders: List<CustomProvider> = emptyList(),
    /** 当前选中的自定义供应商 id；provider 为 DEEPSEEK 时仍会保留，方便来回切换。 */
    val activeCustomProviderId: String? = null,
) {
    /** 当前选中的自定义供应商。id 失效（例如已被删除）时回退到列表首项。 */
    val activeCustomProvider: CustomProvider?
        get() = customProviders.firstOrNull { it.id == activeCustomProviderId }
            ?: customProviders.firstOrNull()

    /** 当前请求应发往的 Base URL；不包含任何鉴权信息。 */
    val activeBaseUrl: String
        get() = when (provider) {
            ChatProviderId.DEEPSEEK -> baseUrl
            ChatProviderId.CUSTOM_OPENAI -> activeCustomProvider?.baseUrl.orEmpty()
        }

    /** 当前请求使用的模型名；不包含任何鉴权信息。 */
    val activeModel: String
        get() = when (provider) {
            ChatProviderId.DEEPSEEK -> model.apiName
            ChatProviderId.CUSTOM_OPENAI -> activeCustomProvider?.selectedModel.orEmpty()
        }

    /** 顶栏等 UI 用的服务显示名。 */
    val activeProviderLabel: String
        get() = when (provider) {
            ChatProviderId.DEEPSEEK -> "DeepSeek"
            ChatProviderId.CUSTOM_OPENAI -> activeCustomProvider?.displayName ?: "未配置供应商"
        }

    /** 供 UI 与请求层判断是否应展示/发送 DeepSeek 专属参数。 */
    val usesDeepSeekParameters: Boolean
        get() = provider == ChatProviderId.DEEPSEEK

    val isCustomProvider: Boolean
        get() = provider == ChatProviderId.CUSTOM_OPENAI

    /** 自定义供应商必须同时具有合法地址和非空模型名才可发送请求。 */
    val isCustomProviderConfigured: Boolean
        get() = isCustomProvider && activeCustomProvider?.isConfigured == true

    /**
     * 当前供应商是否允许挂载设定生成工具。
     *
     * DeepSeek 沿用全局开关；自定义 OpenAI 兼容供应商还必须由用户确认其端点支持
     * 标准 `tools` / `tool_calls`，以免影响只支持普通聊天的既有反代。
     */
    val settingsToolsEnabledForActiveProvider: Boolean
        get() = settingsToolsEnabled && when (provider) {
            ChatProviderId.DEEPSEEK -> true
            ChatProviderId.CUSTOM_OPENAI -> activeCustomProvider?.toolCallingEnabled == true
        }

    /** 上下文压缩策略。摘要状态是每个会话独立的，但策略本身是全局设置。 */
    val contextCompressionPolicy: ContextCompressionPolicy
        get() = ContextCompressionPolicy(
            mode = contextCompressionMode,
            autoTriggerPercent = contextAutoTriggerPercent,
            budgetChars = contextBudgetChars,
        ).normalized()

    /**
     * temperature / top_p 是否会被 DeepSeek 实际采纳。
     * 思考模式开启时官方文档明确说明这些参数不生效（不报错，但被忽略）。
     * 自定义供应商不使用这套 DeepSeek thinking 协议，因此不能据此把其采样设置误标为无效。
     */
    val samplingParametersEffective: Boolean
        get() = !usesDeepSeekParameters || thinkingMode == ThinkingMode.DISABLED

    /** 把可能越界的数值收敛到合法范围，用于持久化读写与请求构造前。 */
    fun normalized(): ChatSettings = copy(
        // DeepSeek 的旧字段和归一化逻辑保持不变，确保已有偏好升级后行为一致。
        baseUrl = baseUrl.trim().ifBlank { DEFAULT_BASE_URL },
        customProviders = customProviders.map { it.normalized() },
        // 指向已删除供应商的 id 会让 UI 停留在一个不存在的选择上，这里直接收敛。
        activeCustomProviderId = activeCustomProviderId
            ?.takeIf { id -> customProviders.any { it.id == id } }
            ?: customProviders.firstOrNull()?.id,
        maxTokens = coerceMaxTokens(maxTokens),
        temperature = temperature.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE),
        topP = topP.coerceIn(MIN_TOP_P, MAX_TOP_P),
        contextAutoTriggerPercent = contextCompressionPolicy.autoTriggerPercent,
        contextBudgetChars = contextCompressionPolicy.budgetChars,
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"

        /**
         * max_tokens 的客户端护栏。DeepSeek 的真实上限由模型上下文长度决定且随模型不同，
         * 这里只负责挡住明显非法的输入（0、负数、几亿），避免直接打到服务端拿 400。
         */
        const val MIN_MAX_TOKENS = 256
        const val MAX_MAX_TOKENS = 65_536
        const val DEFAULT_MAX_TOKENS = 4096

        const val MIN_TEMPERATURE = 0.0
        const val MAX_TEMPERATURE = 2.0
        const val MIN_TOP_P = 0.0
        const val MAX_TOP_P = 1.0

        fun coerceMaxTokens(value: Int): Int = value.coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS)

        /** 供 UI 使用：把用户正在输入的文本解析成合法值，非法输入返回 null 表示"先不落库"。 */
        fun parseMaxTokensOrNull(input: String): Int? =
            input.trim().toIntOrNull()?.takeIf { it in MIN_MAX_TOKENS..MAX_MAX_TOKENS }
    }
}
