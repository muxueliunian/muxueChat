package com.muxuechat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekModelsTest {

    @Test
    fun defaultAndBuiltinFallback_useCurrentOfficialIds() {
        assertEquals("deepseek-flash", DeepSeekModels.DEFAULT_ID)
        assertEquals(
            listOf(
                "deepseek-flash",
                "deepseek-v4-pro",
            ),
            DeepSeekModels.BUILTIN_FALLBACK_IDS,
        )
    }

    @Test
    fun legacyFlashIds_areRecognizedOnlyByExactName() {
        assertTrue(DeepSeekModels.isLegacyFlashId("deepseek-v4-flash"))
        assertTrue(DeepSeekModels.isLegacyFlashId(" deepseek-v4-flash-vision-exp "))
        assertFalse(DeepSeekModels.isLegacyFlashId("deepseek-flash"))
        assertFalse(DeepSeekModels.isLegacyFlashId("deepseek-v4-pro"))
        assertFalse(DeepSeekModels.isLegacyFlashId("deepseek-v4-flash-2"))
    }

    @Test
    fun rpMarker_coversFlashAndKeepsLegacyBehavior() {
        assertTrue(DeepSeekModels.supportsRpMarker("deepseek-flash"))
        assertTrue(DeepSeekModels.supportsRpMarker("deepseek-v4-flash"))
        assertTrue(DeepSeekModels.supportsRpMarker("deepseek-v4-pro"))
        assertFalse(DeepSeekModels.supportsRpMarker("deepseek-v4-flash-vision-exp"))
        assertFalse(DeepSeekModels.supportsRpMarker("deepseek-flash-future"))
    }

    @Test
    fun emptyCache_usesBuiltinFallbackAndKeepsUnknownSelection() {
        val directory = DeepSeekModels.directory(
            selectedModelId = "kept-old-id",
            cachedModelIds = emptyList(),
        )

        assertEquals(DeepSeekModelDirectorySource.BUILTIN_FALLBACK, directory.source)
        assertEquals(DeepSeekModels.BUILTIN_FALLBACK_IDS, directory.catalogIds)
        assertTrue(directory.selectedMissingFromCatalog)
        assertEquals("kept-old-id", directory.selectedModelId)
        assertTrue(directory.visibleModelIds.contains("kept-old-id"))
        assertFalse(directory.isFromServerCache)
    }

    @Test
    fun successfulCache_doesNotMixBuiltinFallbackOrAutoSwitchSelection() {
        val directory = DeepSeekModels.directory(
            selectedModelId = "deepseek-v4-pro",
            cachedModelIds = listOf("deepseek-v4-flash", " future-id "),
            lastRefreshedAtMillis = 99L,
        )

        assertEquals(DeepSeekModelDirectorySource.SERVER_CACHE, directory.source)
        assertEquals(listOf("deepseek-v4-flash", "future-id"), directory.catalogIds)
        assertTrue(directory.selectedMissingFromCatalog)
        assertEquals("deepseek-v4-pro", directory.selectedModelId)
        assertEquals(
            listOf("deepseek-v4-flash", "future-id", "deepseek-v4-pro"),
            directory.visibleModelIds,
        )
        assertEquals(99L, directory.lastRefreshedAtMillis)
    }

    @Test
    fun blankSelectedId_fallsBackToDefaultWithoutTreatingUnknownAsEmpty() {
        assertEquals(DeepSeekModels.DEFAULT_ID, DeepSeekModels.normalizeSelectedId("  "))
        assertEquals(DeepSeekModels.DEFAULT_ID, DeepSeekModels.normalizeSelectedId(null))
        assertEquals("future-id", DeepSeekModels.normalizeSelectedId(" future-id "))
    }
}
