package com.muxuechat.domain.context

import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.DeepSeekModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelContextSpecTest {

    private fun customProvider(
        models: List<String> = listOf("some-model"),
        selected: String = "some-model",
        windows: Map<String, Long> = emptyMap(),
    ) = CustomProvider(
        id = "provider-1",
        name = "自建",
        baseUrl = "http://192.168.1.2:8080",
        models = models,
        selectedModel = selected,
        modelContextWindows = windows,
    )

    private fun customSettings(
        provider: CustomProvider = customProvider(),
        maxTokens: Int = ChatSettings.DEFAULT_MAX_TOKENS,
    ) = ChatSettings(
        provider = ChatProviderId.CUSTOM_OPENAI,
        customProviders = listOf(provider),
        activeCustomProviderId = provider.id,
        maxTokens = maxTokens,
    ).normalized()

    // ---------- DeepSeek 内置窗口 ----------

    /**
     * 默认 DeepSeek 配置下的完整推导链：
     * 1,000,000 → 95% 安全窗口 950,000 → 扣掉 4,096 输出预留 → 945,904。
     */
    @Test
    fun deepSeekDefault_derivesNineHundredFortyFiveThousandInputBudget() {
        val spec = ModelContextCatalog.resolve(ChatSettings().normalized())

        assertEquals(ContextCapacitySource.BUILT_IN, spec.source)
        assertEquals(1_000_000L, spec.contextWindowTokens)
        assertEquals(950_000L, spec.effectiveContextTokens)
        assertEquals(4_096L, spec.maxOutputTokens)
        assertEquals(945_904L, spec.inputBudgetTokens)
        assertFalse(spec.isFallback)
        assertFalse(spec.isCostCapped)
    }

    /** 旧实现的 40,000 字符预算约合 2.5~3 万 token，新口径必须远高于它。 */
    @Test
    fun deepSeekDefault_isNotCappedByLegacyCharacterBudget() {
        val spec = ModelContextCatalog.resolve(ChatSettings().normalized())

        assertTrue(spec.inputBudgetTokens > 900_000L)
    }

    @Test
    fun maxTokens_isReservedFromSafetyWindow() {
        val spec = ModelContextCatalog.resolve(ChatSettings(maxTokens = 65_536).normalized())

        assertEquals(950_000L - 65_536L, spec.inputBudgetTokens)
    }

    @Test
    fun knownDeepSeekModels_allUseOneMillionWindow() {
        listOf(
            DeepSeekModels.FLASH,
            DeepSeekModels.V4_FLASH,
            DeepSeekModels.V4_PRO,
            DeepSeekModels.V4_FLASH_VISION_EXP,
        ).forEach { modelId ->
            val spec = ModelContextCatalog.resolve(ChatSettings(model = modelId).normalized())
            assertEquals(modelId, spec.modelId)
            assertEquals(ContextCapacitySource.BUILT_IN, spec.source)
            assertEquals(ModelContextCatalog.DEEPSEEK_V4_CONTEXT_TOKENS, spec.contextWindowTokens)
        }
    }

    @Test
    fun unknownDeepSeekModel_usesConservativeFallbackAndDoesNotGuessFromName() {
        listOf(
            "deepseek-v4-future",
            "deepseek-v4-flash-vision-exp-2",
            "some-vision-model",
        ).forEach { modelId ->
            val spec = ModelContextCatalog.resolve(ChatSettings(model = modelId).normalized())
            assertEquals(ContextCapacitySource.FALLBACK, spec.source)
            assertEquals(ModelContextCatalog.FALLBACK_CONTEXT_TOKENS, spec.contextWindowTokens)
            assertTrue(spec.isFallback)
        }
    }

    @Test
    fun deepSeekModel_acceptsUserOverride() {
        val settings = ChatSettings(
            model = DeepSeekModels.V4_PRO,
            deepSeekModelContextWindows = mapOf(DeepSeekModels.V4_PRO to 200_000L),
        ).normalized()

        val spec = ModelContextCatalog.resolve(settings)

        assertEquals(ContextCapacitySource.USER_CONFIGURED, spec.source)
        assertEquals(200_000L, spec.contextWindowTokens)
    }

    // ---------- 自定义供应商 ----------

    /** 标准 `/models` 拿不到 context 长度，未配置只能保守回退，并且必须能被 UI 识别为回退。 */
    @Test
    fun customModel_withoutConfiguration_fallsBackAndIsMarked() {
        val spec = ModelContextCatalog.resolve(customSettings())

        assertEquals(ContextCapacitySource.FALLBACK, spec.source)
        assertEquals(ModelContextCatalog.FALLBACK_CONTEXT_TOKENS, spec.contextWindowTokens)
        assertTrue(spec.isFallback)
    }

    @Test
    fun customModel_usesUserConfiguredWindow() {
        val spec = ModelContextCatalog.resolve(
            customSettings(customProvider(windows = mapOf("some-model" to 128_000L))),
        )

        assertEquals(ContextCapacitySource.USER_CONFIGURED, spec.source)
        assertEquals(128_000L, spec.contextWindowTokens)
        assertEquals(128_000L * 95 / 100 - 4_096L, spec.inputBudgetTokens)
    }

    /** 覆盖值属于某个具体模型；切到别的模型不该继承它。 */
    @Test
    fun customModel_configurationIsPerModel() {
        val provider = customProvider(
            models = listOf("model-a", "model-b"),
            selected = "model-b",
            windows = mapOf("model-a" to 128_000L),
        )

        val spec = ModelContextCatalog.resolve(customSettings(provider))

        assertEquals(ContextCapacitySource.FALLBACK, spec.source)
    }

    /** 自定义供应商没有内置窗口，绝不能按模型名猜成 DeepSeek。 */
    @Test
    fun customProvider_neverInheritsDeepSeekBuiltIn() {
        val provider = customProvider(models = listOf("deepseek-v4-flash"), selected = "deepseek-v4-flash")

        val spec = ModelContextCatalog.resolve(customSettings(provider))

        assertEquals(ContextCapacitySource.FALLBACK, spec.source)
    }

    // ---------- 成本上限 ----------

    @Test
    fun costCap_takesTheSmallerOfCapAndModelBudget() {
        val settings = ChatSettings(
            contextCostCapEnabled = true,
            contextCostCapTokens = 60_000L,
        ).normalized()

        val spec = ModelContextCatalog.resolve(settings)

        assertEquals(60_000L, spec.inputBudgetTokens)
        assertEquals(945_904L, spec.modelInputBudgetTokens)
        assertTrue(spec.isCostCapped)
    }

    /** 上限高于模型本身能装下的量时不生效，也不该被标成「成本受限」。 */
    @Test
    fun costCap_aboveModelBudget_doesNotApply() {
        val settings = ChatSettings(
            contextCostCapEnabled = true,
            contextCostCapTokens = ModelContextCatalog.MAX_COST_CAP_TOKENS,
        ).normalized()

        val spec = ModelContextCatalog.resolve(settings)

        assertEquals(945_904L, spec.inputBudgetTokens)
        assertFalse(spec.isCostCapped)
    }

    @Test
    fun costCap_disabledByDefault() {
        assertFalse(ChatSettings().normalized().contextCostCapEnabled)
        assertEquals(null, ChatSettings().normalized().effectiveContextCostCapTokens)
    }

    // ---------- 边界 ----------

    /**
     * 输出预留大于安全窗口时（32K 回退 + 65,536 max_tokens），预算不能变成负数，
     * 否则百分比会算出负值甚至除零。
     */
    @Test
    fun inputBudget_neverGoesNonPositive() {
        val settings = customSettings(maxTokens = 65_536)

        val spec = ModelContextCatalog.resolve(settings)

        assertEquals(ModelContextSpec.MIN_INPUT_BUDGET_TOKENS, spec.inputBudgetTokens)
    }

    /** 窗口已经到百万级，未来更长的模型不能因为 Int 溢出算出负预算。 */
    @Test
    fun hugeWindow_doesNotOverflow() {
        val spec = ModelContextSpec(
            providerKey = "TEST",
            providerLabel = "Test",
            modelId = "huge",
            contextWindowTokens = 10_000_000_000L,
            source = ContextCapacitySource.USER_CONFIGURED,
            maxOutputTokens = 4_096L,
            costCapTokens = null,
        )

        assertEquals(9_500_000_000L, spec.effectiveContextTokens)
        assertEquals(9_500_000_000L - 4_096L, spec.inputBudgetTokens)
    }

    @Test
    fun configuredWindow_isCoercedIntoAllowedRange() {
        assertEquals(
            ModelContextCatalog.MAX_CONFIGURABLE_CONTEXT_TOKENS,
            ModelContextCatalog.coerceContextWindow(Long.MAX_VALUE),
        )
        assertEquals(
            ModelContextCatalog.MIN_CONFIGURABLE_CONTEXT_TOKENS,
            ModelContextCatalog.coerceContextWindow(1L),
        )
        assertEquals(null, ModelContextCatalog.parseContextWindowOrNull("0"))
        assertEquals(null, ModelContextCatalog.parseContextWindowOrNull("abc"))
        assertEquals(128_000L, ModelContextCatalog.parseContextWindowOrNull(" 128000 "))
    }

    /** DeepSeek 与自定义端点即使模型同名也不能共用一份用量记录。 */
    @Test
    fun providerKey_distinguishesDeepSeekFromCustomEndpoint() {
        assertEquals(
            ChatProviderId.DEEPSEEK.storageValue,
            ModelContextCatalog.providerKeyOf(ChatSettings().normalized()),
        )
        assertEquals("provider-1", ModelContextCatalog.providerKeyOf(customSettings()))
    }
}
