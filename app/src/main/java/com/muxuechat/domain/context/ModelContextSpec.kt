package com.muxuechat.domain.context

import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.DeepSeekModels

/** 当前模型的上下文容量是从哪来的。UI 据此决定是否要提示用户去配置。 */
enum class ContextCapacitySource {
    /** App 内置的已知模型窗口。 */
    BUILT_IN,

    /** 用户在设置页为该模型手填的窗口。 */
    USER_CONFIGURED,

    /** 既没有内置也没有用户配置，只能用一个保守回退值。必须在 UI 上警告。 */
    FALLBACK,
}

/**
 * 当前请求所用模型的上下文容量与由它推导出的输入预算。
 *
 * 全部使用 [Long]：声明窗口已经到百万级，未来更长的模型不应因为 Int 溢出而算出负预算。
 */
data class ModelContextSpec(
    /**
     * 供应商的稳定标识：DeepSeek 固定为 `DEEPSEEK`，自定义供应商为其 id。
     * 用于判断持久化的用量快照是否还属于当前供应商，不用于展示。
     */
    val providerKey: String,
    val providerLabel: String,
    val modelId: String,
    /** 模型声明的上下文窗口。 */
    val contextWindowTokens: Long,
    val source: ContextCapacitySource,
    /** 本次请求为输出预留的 token（即 max_tokens）。 */
    val maxOutputTokens: Long,
    /**
     * 用户开启的成本软上限；null 表示只受模型窗口约束。
     *
     * 模型窗口到了百万级之后，真正的约束不再是「装不装得下」，而是「每轮重发全量历史有多贵、
     * 首 token 有多慢」。这个上限就是为此存在的，默认关闭。
     */
    val costCapTokens: Long?,
) {
    /**
     * 客户端统一留出的安全窗口。
     *
     * 这不是服务端的真实限制，只是本地估算必然有误差，不能贴着声明窗口发。
     */
    val effectiveContextTokens: Long
        get() = contextWindowTokens * SAFETY_WINDOW_PERCENT / 100

    /** 只考虑模型窗口时的可用输入预算。 */
    val modelInputBudgetTokens: Long
        get() = (effectiveContextTokens - maxOutputTokens).coerceAtLeast(MIN_INPUT_BUDGET_TOKENS)

    /** 本次真正生效的可用输入预算。 */
    val inputBudgetTokens: Long
        get() = costCapTokens
            ?.coerceAtLeast(MIN_INPUT_BUDGET_TOKENS)
            ?.let { minOf(it, modelInputBudgetTokens) }
            ?: modelInputBudgetTokens

    /** 当前预算是被成本上限而不是模型窗口卡住的。UI 要说清楚是哪一个在起作用。 */
    val isCostCapped: Boolean
        get() = costCapTokens != null && inputBudgetTokens < modelInputBudgetTokens

    val isFallback: Boolean
        get() = source == ContextCapacitySource.FALLBACK

    companion object {
        const val SAFETY_WINDOW_PERCENT = 95L

        /** 再离谱的配置也要留出一点输入空间，否则预算变成 0 会让百分比除零。 */
        const val MIN_INPUT_BUDGET_TOKENS = 1_024L
    }
}

/**
 * 模型上下文容量的唯一解析入口。
 *
 * 解析顺序固定为：**用户逐模型覆盖 → 内置常量 → 回退值**。
 * DeepSeek 与自定义 OpenAI 兼容供应商走同一条链路，不为任何具体厂商开分支。
 */
object ModelContextCatalog {
    /**
     * DeepSeek V4 系列的声明窗口。
     *
     * 这是内置默认值而不是硬约束：设置页允许用户逐模型覆盖，
     * 以免上游调整窗口后必须改代码重新发版。
     */
    const val DEEPSEEK_V4_CONTEXT_TOKENS = 1_000_000L

    /**
     * 未知模型的回退窗口。
     *
     * 标准 `GET /models` 响应只保证有 `id`，拿不到 context 长度，所以只能保守取一个
     * 常见的小窗口。UI 必须显示这是回退值，绝不能伪装成供应商的真实能力。
     */
    const val FALLBACK_CONTEXT_TOKENS = 32_768L

    /** 用户手填窗口的合法区间。 */
    const val MIN_CONFIGURABLE_CONTEXT_TOKENS = 1_024L
    const val MAX_CONFIGURABLE_CONTEXT_TOKENS = 10_000_000L

    /** 成本上限的合法区间。 */
    const val MIN_COST_CAP_TOKENS = 2_048L
    const val MAX_COST_CAP_TOKENS = 10_000_000L
    const val DEFAULT_COST_CAP_TOKENS = 60_000L

    fun coerceContextWindow(value: Long): Long =
        value.coerceIn(MIN_CONFIGURABLE_CONTEXT_TOKENS, MAX_CONFIGURABLE_CONTEXT_TOKENS)

    fun coerceCostCap(value: Long): Long =
        value.coerceIn(MIN_COST_CAP_TOKENS, MAX_COST_CAP_TOKENS)

    /** 供 UI 使用：把用户正在输入的文本解析成合法值，非法输入返回 null 表示「先不落库」。 */
    fun parseContextWindowOrNull(input: String): Long? =
        input.trim().toLongOrNull()
            ?.takeIf { it in MIN_CONFIGURABLE_CONTEXT_TOKENS..MAX_CONFIGURABLE_CONTEXT_TOKENS }

    fun parseCostCapOrNull(input: String): Long? =
        input.trim().toLongOrNull()?.takeIf { it in MIN_COST_CAP_TOKENS..MAX_COST_CAP_TOKENS }

    /** 该模型是否有内置窗口。目前只覆盖 DeepSeek 三个已知官方 ID。 */
    fun builtInContextWindow(providerId: ChatProviderId, modelId: String): Long? =
        when (providerId) {
            ChatProviderId.DEEPSEEK -> DeepSeekModels.knownContextWindowTokens(modelId)

            // 自定义供应商背后是谁与 App 无关，不做任何按名字的猜测。
            ChatProviderId.CUSTOM_OPENAI -> null
        }

    fun resolve(settings: ChatSettings): ModelContextSpec {
        val modelId = settings.activeModel
        val userConfigured = settings.contextWindowOverrideFor(modelId)
        val builtIn = builtInContextWindow(settings.provider, modelId)

        val (window, source) = when {
            userConfigured != null -> userConfigured to ContextCapacitySource.USER_CONFIGURED
            builtIn != null -> builtIn to ContextCapacitySource.BUILT_IN
            else -> FALLBACK_CONTEXT_TOKENS to ContextCapacitySource.FALLBACK
        }

        return ModelContextSpec(
            providerKey = providerKeyOf(settings),
            providerLabel = settings.activeProviderLabel,
            modelId = modelId,
            contextWindowTokens = window,
            source = source,
            maxOutputTokens = settings.maxTokens.toLong(),
            costCapTokens = settings.effectiveContextCostCapTokens,
        )
    }

    /** DeepSeek 用协议名，自定义供应商用 id——两个不同端点即使模型同名也不该混为一谈。 */
    fun providerKeyOf(settings: ChatSettings): String = when (settings.provider) {
        ChatProviderId.DEEPSEEK -> ChatProviderId.DEEPSEEK.storageValue
        ChatProviderId.CUSTOM_OPENAI ->
            settings.activeCustomProvider?.id ?: ChatProviderId.CUSTOM_OPENAI.storageValue
    }

}
