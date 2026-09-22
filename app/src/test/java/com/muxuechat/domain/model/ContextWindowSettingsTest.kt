package com.muxuechat.domain.model

import com.muxuechat.domain.context.ModelContextCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 逐模型上下文长度、成本上限与压缩阈值的持久化前收敛规则。 */
class ContextWindowSettingsTest {

    private fun provider(
        models: List<String> = listOf("model-a"),
        windows: Map<String, Long> = emptyMap(),
    ) = CustomProvider(
        id = "p",
        name = "自建",
        baseUrl = "https://example.com",
        models = models,
        selectedModel = models.firstOrNull().orEmpty(),
        modelContextWindows = windows,
    )

    // ---------- 逐模型窗口 ----------

    @Test
    fun contextWindows_trimKeysAndDropInvalidValues() {
        val normalized = provider(
            windows = mapOf(
                " model-a " to 128_000L,
                "" to 64_000L,
                "model-b" to 0L,
                "model-c" to -1L,
            ),
        ).normalized()

        assertEquals(mapOf("model-a" to 128_000L), normalized.modelContextWindows)
    }

    @Test
    fun contextWindows_areCoercedIntoAllowedRange() {
        val normalized = provider(windows = mapOf("model-a" to Long.MAX_VALUE)).normalized()

        assertEquals(
            ModelContextCatalog.MAX_CONFIGURABLE_CONTEXT_TOKENS,
            normalized.modelContextWindows["model-a"],
        )
    }

    /**
     * `/models` 刷新会整体替换 models 列表。上游临时不列出某个模型时，
     * 用户手填过的窗口必须原样保留——否则每刷新一次配置就丢一次。
     */
    @Test
    fun refreshingModels_keepsConfiguredWindows() {
        val configured = provider(
            models = listOf("model-a", "model-b"),
            windows = mapOf("model-a" to 128_000L, "model-b" to 64_000L),
        )

        val refreshed = configured.copy(models = listOf("model-a"), selectedModel = "model-a").normalized()

        assertEquals(128_000L, refreshed.modelContextWindows["model-a"])
        assertEquals(64_000L, refreshed.modelContextWindows["model-b"])
    }

    /** 只有用户在设置页显式移除模型才会删掉对应配置。 */
    @Test
    fun removingModelExplicitly_alsoDropsItsWindow() {
        val configured = provider(
            models = listOf("model-a", "model-b"),
            windows = mapOf("model-a" to 128_000L, "model-b" to 64_000L),
        )

        val afterRemoval = configured.copy(
            models = configured.models - "model-b",
            modelContextWindows = configured.modelContextWindows - "model-b",
        ).normalized()

        assertEquals(mapOf("model-a" to 128_000L), afterRemoval.modelContextWindows)
    }

    @Test
    fun deepSeekWindows_areNormalizedTheSameWay() {
        val settings = ChatSettings(
            deepSeekModelContextWindows = mapOf(
                " deepseek-v4-pro " to 500_000L,
                "bad" to -3L,
            ),
        ).normalized()

        assertEquals(mapOf("deepseek-v4-pro" to 500_000L), settings.deepSeekModelContextWindows)
    }

    // ---------- 成本上限 ----------

    @Test
    fun costCap_defaultsToDisabled() {
        val settings = ChatSettings().normalized()

        assertFalse(settings.contextCostCapEnabled)
        assertNull(settings.effectiveContextCostCapTokens)
        assertEquals(ModelContextCatalog.DEFAULT_COST_CAP_TOKENS, settings.contextCostCapTokens)
    }

    @Test
    fun costCap_isCoercedIntoAllowedRange() {
        assertEquals(
            ModelContextCatalog.MIN_COST_CAP_TOKENS,
            ChatSettings(contextCostCapTokens = 1L).normalized().contextCostCapTokens,
        )
        assertEquals(
            ModelContextCatalog.MAX_COST_CAP_TOKENS,
            ChatSettings(contextCostCapTokens = Long.MAX_VALUE).normalized().contextCostCapTokens,
        )
        assertNull(ModelContextCatalog.parseCostCapOrNull("10"))
        assertEquals(60_000L, ModelContextCatalog.parseCostCapOrNull("60000"))
    }

    // ---------- 压缩阈值 ----------

    /** targetPercent 现在真正影响行为，必须能被持久化并从设置里读回来。 */
    @Test
    fun compressionPolicy_carriesTargetPercentFromSettings() {
        val settings = ChatSettings(contextAutoTriggerPercent = 80, contextTargetPercent = 30).normalized()

        assertEquals(80, settings.contextCompressionPolicy.autoTriggerPercent)
        assertEquals(30, settings.contextCompressionPolicy.targetPercent)
        assertEquals(30, settings.contextTargetPercent)
    }

    /** 旧的字符预算只保留兼容读写，不参与任何决策。 */
    @Test
    fun legacyCharacterBudget_isNotPartOfTheCompressionPolicy() {
        @Suppress("DEPRECATION")
        val settings = ChatSettings(contextBudgetChars = 40_000).normalized()

        val policyText = settings.contextCompressionPolicy.toString()
        assertFalse(policyText.contains("budgetChars"))
        assertTrue(policyText.contains("autoTriggerPercent"))
    }
}
