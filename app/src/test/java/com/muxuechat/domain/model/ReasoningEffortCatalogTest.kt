package com.muxuechat.domain.model

import com.muxuechat.presentation.chat.components.ThinkingChoice
import com.muxuechat.presentation.chat.components.thinkingControlState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningEffortCatalogTest {
    @Test fun knownModelsReturnVerifiedLevels() {
        assertEquals(listOf("low", "medium", "high"), ReasoningEffortCatalog.optionsFor("grok-4.5"))
        assertEquals(listOf("low", "medium", "high", "xhigh"), ReasoningEffortCatalog.optionsFor("grok-4.7"))
        assertEquals(listOf("low", "medium", "high"), ReasoningEffortCatalog.optionsFor("gemini-3.8-flash"))
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), ReasoningEffortCatalog.optionsFor("claude-opus-5"))
        assertEquals(listOf("low", "high", "max"), ReasoningEffortCatalog.optionsFor("deepseek-v4-pro"))
        assertTrue("none" in ReasoningEffortCatalog.optionsFor("gpt-5.6").orEmpty())
        assertFalse("none" in ReasoningEffortCatalog.optionsFor("gpt-6.1-sol").orEmpty())
    }

    @Test fun oneNamespaceSegmentIsStrippedButNamesAreNeverGuessed() {
        assertEquals(listOf("low", "medium", "high"), ReasoningEffortCatalog.optionsFor("google/gemini-4-argon"))
        assertEquals(ReasoningEffortCatalog.optionsFor("grok-4.7"), ReasoningEffortCatalog.optionsFor("x-ai/grok-4.7"))
        // 版本号相近、前缀相同或多段命名空间都不算命中。
        assertNull(ReasoningEffortCatalog.optionsFor("grok-4.8"))
        assertNull(ReasoningEffortCatalog.optionsFor("gpt-5.6-mini"))
        assertNull(ReasoningEffortCatalog.optionsFor("claude-opus-5-20260101"))
        assertNull(ReasoningEffortCatalog.optionsFor("a/b/grok-4.7"))
        assertNull(ReasoningEffortCatalog.optionsFor(""))
    }

    @Test fun customProviderMenuKeepsHandTypedValueAndOffersDefault() {
        val provider = CustomProvider(id = "p", name = "relay", baseUrl = "https://example.test/v1", selectedModel = "grok-4.5", reasoningEffort = "turbo")
        val state = ChatSettings(provider = ChatProviderId.CUSTOM_OPENAI, customProviders = listOf(provider), activeCustomProviderId = "p")
            .thinkingControlState()!!
        val values = state.options.map { (it.choice as ThinkingChoice.ProviderEffort).value }
        assertEquals(listOf("", "low", "medium", "high", "turbo"), values)
        assertEquals(ThinkingChoice.ProviderEffort("turbo"), state.selected)
        assertTrue(state.isActive)
        assertTrue(state.allowsCustom)
    }

    @Test fun deepSeekMenuMapsToThinkingSwitch() {
        val off = ChatSettings(thinkingMode = ThinkingMode.DISABLED).thinkingControlState()!!
        assertEquals(ThinkingChoice.DeepSeekOff, off.selected)
        assertFalse(off.isActive)
        assertFalse(off.allowsCustom)
        val on = ChatSettings(thinkingMode = ThinkingMode.ENABLED, reasoningEffort = ReasoningEffort.MAX).thinkingControlState()!!
        assertEquals(ThinkingChoice.DeepSeekEffort(ReasoningEffort.MAX), on.selected)
        assertEquals("思考 · 最高", on.chipLabel)
    }
}
