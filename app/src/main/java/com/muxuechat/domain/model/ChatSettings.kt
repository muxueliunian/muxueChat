package com.muxuechat.domain.model

import com.muxuechat.domain.context.ContextCompressionMode
import com.muxuechat.domain.context.ContextCompressionPolicy
import com.muxuechat.domain.context.ModelContextCatalog

data class ChatSettings(
    val baseUrl: String = "https://api.deepseek.com",
    /** DeepSeek 当前选中的模型 ID；任意非空字符串，请求时原样透传。 */
    val model: String = DeepSeekModels.DEFAULT_ID,
    /**
     * 最近一次成功 `GET /models` 缓存的 DeepSeek 模型 ID。
     *
     * 空列表表示从未成功刷新，此时 UI 使用 [DeepSeekModels.BUILTIN_FALLBACK_IDS]，
     * 二者不是同一种状态。
     */
    val deepSeekCachedModelIds: List<String> = emptyList(),
    /** 最近一次成功刷新 DeepSeek 模型目录的时间；0 表示从未成功刷新。 */
    val deepSeekModelsUpdatedAtMillis: Long = 0L,
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
    val contextTargetPercent: Int = ContextCompressionPolicy.DEFAULT_TARGET_PERCENT,
    /**
     * 旧版的字符预算。
     *
     * 上下文治理已改为按模型真实 token 窗口计算，本字段**不再参与任何显示、裁剪或压缩决策**，
     * 仅为兼容旧 DataStore 数据而保留，避免升级时读到缺失键。不要在新代码里引用它。
     */
    @Deprecated("上下文预算已改为按模型 token 窗口计算，本字段仅作旧数据兼容")
    val contextBudgetChars: Int = LEGACY_DEFAULT_BUDGET_CHARS,
    /** DeepSeek 各模型的上下文窗口覆盖值；留空表示使用 App 内置窗口。 */
    val deepSeekModelContextWindows: Map<String, Long> = emptyMap(),
    /** 是否启用单轮输入 token 的成本软上限。 */
    val contextCostCapEnabled: Boolean = false,
    /** 成本软上限的具体数值；仅在 [contextCostCapEnabled] 为 true 时生效。 */
    val contextCostCapTokens: Long = ModelContextCatalog.DEFAULT_COST_CAP_TOKENS,
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
    /**
     * RP 辅助请求（剧情推进建议、元数据补写与状态兜底）单独使用的模型名。留空表示用默认模型。
     *
     * 这几类请求只要求稳定的结构化 JSON，不需要正文那档推理能力，
     * 用便宜模型即可，正文仍走用户选定的主模型。
     *
     * 模型名只在**它所属的供应商**下才有意义，因此必须同 [rpAuxiliaryModelScope] 一起读：
     * 两者对不上（例如填的是 DeepSeek 的名字、后来正文切到了 Vertex）时一律当作没填，
     * 绝不把一家的模型名发给另一家。请通过 [rpAuxiliaryModelOverride] 读取。
     */
    val rpAuxiliaryModel: String = "",
    /**
     * [rpAuxiliaryModel] 是在哪个供应商下填写的（[ModelContextCatalog.providerKeyOf] 的口径）。
     * 旧版本没有这个字段，升级后为空，空值永远对不上任何供应商，需要用户重新选一次。
     */
    val rpAuxiliaryModelScope: String = "",
    /** RP 辅助请求发往哪家供应商；默认跟随正文，行为与升级前一致。 */
    val rpAuxiliaryProvider: RpAuxiliaryProvider = RpAuxiliaryProvider.FOLLOW_ACTIVE,
    /** 仅用于新会话首次打开 RP 设置时预选；不会自动绑定或开启 RP。 */
    val defaultProtagonistProfileId: String? = null,
) {
    /**
     * RP 辅助请求是否改道发往 DeepSeek：用户选了「固定走 DeepSeek」，且正文并不是 DeepSeek。
     * 正文本来就是 DeepSeek 时不存在改道，辅助请求照旧跟随正文。
     */
    val rpAuxiliaryDivertsToDeepSeek: Boolean
        get() = rpAuxiliaryProvider == RpAuxiliaryProvider.DEEPSEEK && provider != ChatProviderId.DEEPSEEK

    /** 辅助请求实际发往的供应商标识，口径与 [rpAuxiliaryModelScope] 相同。 */
    val rpAuxiliaryTargetKey: String
        get() = if (provider == ChatProviderId.DEEPSEEK || rpAuxiliaryProvider == RpAuxiliaryProvider.DEEPSEEK) {
            ChatProviderId.DEEPSEEK.storageValue
        } else {
            ModelContextCatalog.providerKeyOf(this)
        }

    /**
     * 用户填的辅助模型，且仅当它确实属于辅助请求的目标供应商时才返回；否则为 null。
     * 这是「不把一家的模型名发给另一家」的唯一判据。
     */
    val rpAuxiliaryModelOverride: String?
        get() = rpAuxiliaryModel.trim().takeIf { it.isNotEmpty() && rpAuxiliaryModelScope == rpAuxiliaryTargetKey }

    /** 写入辅助模型并记下它属于哪个供应商；清空时一并清掉归属。 */
    fun withRpAuxiliaryModel(model: String): ChatSettings {
        val trimmed = model.trim()
        return copy(
            rpAuxiliaryModel = trimmed,
            rpAuxiliaryModelScope = if (trimmed.isEmpty()) "" else rpAuxiliaryTargetKey,
        )
    }

    /** 用户填了辅助模型，但它属于别的供应商，当前不会被使用。UI 据此提醒。 */
    val rpAuxiliaryModelIsStale: Boolean
        get() = rpAuxiliaryModel.isNotBlank() && rpAuxiliaryModelOverride == null

    /**
     * RP 辅助请求实际使用的模型名，绝不会是空串。
     *
     * 没有可用的覆盖值时：改道到 DeepSeek 用 [DeepSeekModels.FLASH]（App 的标准 Flash 名，
     * 不按名字去猜缓存列表）；否则回落到正文模型。
     */
    val effectiveRpAuxiliaryModel: String
        get() = rpAuxiliaryModelOverride
            ?: if (rpAuxiliaryDivertsToDeepSeek) DeepSeekModels.FLASH else activeModel

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
            ChatProviderId.DEEPSEEK -> model
            ChatProviderId.CUSTOM_OPENAI -> activeCustomProvider?.selectedModel.orEmpty()
        }

    val deepSeekModelDirectory: DeepSeekModelDirectory
        get() = DeepSeekModels.directory(
            selectedModelId = model,
            cachedModelIds = deepSeekCachedModelIds,
            lastRefreshedAtMillis = deepSeekModelsUpdatedAtMillis,
        )

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
            targetPercent = contextTargetPercent,
        ).normalized()

    /** 当前生效的成本上限；未启用时为 null，表示只受模型窗口约束。 */
    val effectiveContextCostCapTokens: Long?
        get() = contextCostCapTokens.takeIf { contextCostCapEnabled }

    /**
     * 取某个模型的上下文窗口覆盖值。
     *
     * DeepSeek 与自定义供应商共用同一套「逐模型覆盖」语义，只是存放位置不同：
     * 前者在全局设置里，后者挂在各自的 [CustomProvider] 上。
     */
    fun contextWindowOverrideFor(modelId: String): Long? {
        val key = modelId.trim()
        if (key.isEmpty()) return null
        return when (provider) {
            ChatProviderId.DEEPSEEK -> deepSeekModelContextWindows[key]
            ChatProviderId.CUSTOM_OPENAI -> activeCustomProvider?.modelContextWindows?.get(key)
        }
    }

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
        model = DeepSeekModels.normalizeSelectedId(model),
        deepSeekCachedModelIds = DeepSeekModels.normalizeCachedIds(deepSeekCachedModelIds),
        customProviders = customProviders.map { it.normalized() },
        // 指向已删除供应商的 id 会让 UI 停留在一个不存在的选择上，这里直接收敛。
        activeCustomProviderId = activeCustomProviderId
            ?.takeIf { id -> customProviders.any { it.id == id } }
            ?: customProviders.firstOrNull()?.id,
        maxTokens = coerceMaxTokens(maxTokens),
        temperature = temperature.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE),
        topP = topP.coerceIn(MIN_TOP_P, MAX_TOP_P),
        contextAutoTriggerPercent = contextCompressionPolicy.autoTriggerPercent,
        contextTargetPercent = contextCompressionPolicy.targetPercent,
        deepSeekModelContextWindows = normalizeContextWindows(deepSeekModelContextWindows),
        contextCostCapTokens = ModelContextCatalog.coerceCostCap(contextCostCapTokens),
        rpAuxiliaryModel = rpAuxiliaryModel.trim(),
        rpAuxiliaryModelScope = rpAuxiliaryModelScope.trim(),
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"

        /** 旧字符预算的默认值，仅用于兼容读取，不参与任何决策。 */
        const val LEGACY_DEFAULT_BUDGET_CHARS = 40_000

        /**
         * 收敛逐模型上下文窗口配置：键去空白、丢弃空键与越界值。
         *
         * 注意**不按当前模型列表裁剪**——上游 `/models` 临时不列出某个模型时，
         * 不应把用户手动配置过的窗口悄悄删掉。只有用户显式移除模型时才删对应项。
         */
        fun normalizeContextWindows(source: Map<String, Long>): Map<String, Long> =
            source.mapNotNull { (model, window) ->
                val key = model.trim()
                if (key.isEmpty() || window <= 0L) {
                    null
                } else {
                    key to ModelContextCatalog.coerceContextWindow(window)
                }
            }.toMap()

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
