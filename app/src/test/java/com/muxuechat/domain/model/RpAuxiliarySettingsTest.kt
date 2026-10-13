package com.muxuechat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RP 辅助模型的归属规则：模型名只在它所属的供应商下才生效。 */
class RpAuxiliarySettingsTest {
    private val vertex = CustomProvider(
        id = "p-google",
        name = CustomProvider.GOOGLE_DEFAULT_NAME,
        baseUrl = "",
        models = listOf(CustomProvider.GOOGLE_DEFAULT_MODEL),
        selectedModel = CustomProvider.GOOGLE_DEFAULT_MODEL,
        authKind = ProviderAuthKind.GOOGLE_OAUTH,
        googleProjectId = "my-project-123456",
    )

    private fun vertexSettings(route: RpAuxiliaryProvider = RpAuxiliaryProvider.FOLLOW_ACTIVE) = ChatSettings(
        provider = ChatProviderId.CUSTOM_OPENAI,
        customProviders = listOf(vertex),
        activeCustomProviderId = vertex.id,
        rpAuxiliaryProvider = route,
    )

    @Test
    fun defaults_followTheActiveProvider_likeBefore() {
        val settings = ChatSettings()

        assertEquals(RpAuxiliaryProvider.FOLLOW_ACTIVE, settings.rpAuxiliaryProvider)
        assertFalse(settings.rpAuxiliaryDivertsToDeepSeek)
        assertEquals(settings.activeModel, settings.effectiveRpAuxiliaryModel)
    }

    @Test
    fun divert_onlyWhenMainIsNotDeepSeek() {
        assertTrue(vertexSettings(RpAuxiliaryProvider.DEEPSEEK).rpAuxiliaryDivertsToDeepSeek)
        assertFalse(vertexSettings(RpAuxiliaryProvider.FOLLOW_ACTIVE).rpAuxiliaryDivertsToDeepSeek)
        // 正文本来就是 DeepSeek：不存在改道。
        assertFalse(ChatSettings(rpAuxiliaryProvider = RpAuxiliaryProvider.DEEPSEEK).rpAuxiliaryDivertsToDeepSeek)
    }

    @Test
    fun divertedWithoutOverride_usesTheStandardFlashName_notTheMainModel() {
        val settings = vertexSettings(RpAuxiliaryProvider.DEEPSEEK)

        assertEquals(DeepSeekModels.FLASH, settings.effectiveRpAuxiliaryModel)
    }

    @Test
    fun legacyUnscopedOverride_isIgnored_soADeepSeekNameNeverReachesVertex() {
        // 升级前保存的值没有归属。正文是 Vertex 时，它绝不能再被当作 Vertex 的模型名发出去。
        val settings = vertexSettings().copy(rpAuxiliaryModel = "deepseek-v4-flash")

        assertNull(settings.rpAuxiliaryModelOverride)
        assertTrue(settings.rpAuxiliaryModelIsStale)
        assertEquals(CustomProvider.GOOGLE_DEFAULT_MODEL, settings.effectiveRpAuxiliaryModel)
    }

    @Test
    fun overrideSavedUnderOneProvider_doesNotApplyAfterSwitchingMainProvider() {
        val onDeepSeek = ChatSettings(model = "deepseek-v4-pro").withRpAuxiliaryModel("deepseek-flash")
        assertEquals("deepseek-flash", onDeepSeek.rpAuxiliaryModelOverride)

        // 同一份偏好，正文切到 Vertex（辅助仍跟随正文）：归属对不上，不再生效。
        val switched = onDeepSeek.copy(
            provider = ChatProviderId.CUSTOM_OPENAI,
            customProviders = listOf(vertex),
            activeCustomProviderId = vertex.id,
        )
        assertNull(switched.rpAuxiliaryModelOverride)
        assertEquals(CustomProvider.GOOGLE_DEFAULT_MODEL, switched.effectiveRpAuxiliaryModel)

        // 改成固定走 DeepSeek 后，归属重新对上，原来的 DeepSeek 模型名恢复生效。
        val diverted = switched.copy(rpAuxiliaryProvider = RpAuxiliaryProvider.DEEPSEEK)
        assertEquals("deepseek-flash", diverted.effectiveRpAuxiliaryModel)
    }

    @Test
    fun withRpAuxiliaryModel_recordsWhoOwnsIt_andClearingForgetsIt() {
        val vertexOwned = vertexSettings().withRpAuxiliaryModel("  google/gemini-3.8-flash  ")
        assertEquals("google/gemini-3.8-flash", vertexOwned.rpAuxiliaryModel)
        assertEquals(vertex.id, vertexOwned.rpAuxiliaryModelScope)

        val diverted = vertexSettings(RpAuxiliaryProvider.DEEPSEEK).withRpAuxiliaryModel("deepseek-flash")
        assertEquals(ChatProviderId.DEEPSEEK.storageValue, diverted.rpAuxiliaryModelScope)

        val cleared = diverted.withRpAuxiliaryModel("   ")
        assertEquals("", cleared.rpAuxiliaryModel)
        assertEquals("", cleared.rpAuxiliaryModelScope)
        assertFalse(cleared.rpAuxiliaryModelIsStale)
    }

    @Test
    fun providerStorage_unknownValueFallsBackToFollow() {
        assertEquals(RpAuxiliaryProvider.FOLLOW_ACTIVE, RpAuxiliaryProvider.fromStorage(null))
        assertEquals(RpAuxiliaryProvider.FOLLOW_ACTIVE, RpAuxiliaryProvider.fromStorage("nope"))
        assertEquals(RpAuxiliaryProvider.DEEPSEEK, RpAuxiliaryProvider.fromStorage("deepseek"))
    }
}
