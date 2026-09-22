package com.muxuechat.presentation.common

import com.muxuechat.domain.model.*
import com.muxuechat.presentation.chat.rpMarkerAvailability
import com.muxuechat.presentation.settings.sections.BaseUrlValidation
import com.muxuechat.presentation.settings.sections.validateBaseUrl
import org.junit.Assert.*
import org.junit.Test

class ModelPresentationTest {
    @Test fun largeDirectoryHasBoundedEditorWindowAndCanFindLastEntry() {
        val all = (1..2000).map { "fixture-model-$it" }
        val first = ModelListWindow.of(all, listOf("retired-selection"), "", 8)
        assertEquals(9, first.pinned.size + first.others.size)
        assertEquals(listOf("retired-selection"), first.pinned)
        assertEquals(1992, first.hiddenCount)
        val search = ModelListWindow.of(all, listOf("retired-selection"), "model-2000", 8)
        assertEquals(listOf("fixture-model-2000"), search.others)
        assertTrue(search.pinned.isEmpty())
    }

    @Test fun missingSelectionAndLegacyNameRemainVisible() {
        val id = DeepSeekModels.V4_FLASH_VISION_EXP
        val directory = DeepSeekModels.directory(id, listOf(DeepSeekModels.FLASH))
        val notes = ModelNotes.deepSeekNotes(id, directory, null).map { it.text }
        assertTrue(directory.visibleModelIds.contains(id))
        assertTrue(notes.contains(ModelNotes.LEGACY_FLASH_ID))
        assertTrue(notes.contains(ModelNotes.TEXT_ONLY_INPUT))
        // 普通 Flash 旧名不是视觉模型，不应被标注图片相关限制。
        val legacyText = ModelNotes.deepSeekCapabilityNotes(DeepSeekModels.V4_FLASH).map { it.text }
        assertEquals(listOf(ModelNotes.LEGACY_FLASH_ID), legacyText)
        assertTrue(notes.any { "仍保留当前选择" in it })
        assertTrue(notes.any { "内置已知值" in it })
        val marker = ChatSettings(model = id).rpMarkerAvailability()
        assertFalse(marker.isAvailable)
        assertFalse(marker.unavailableReason.orEmpty().contains("不是 DeepSeek V4"))
    }

    @Test fun flashIsMarkedTextOnlyUntilImageInputShips() {
        val directory = DeepSeekModels.directory(DeepSeekModels.FLASH, emptyList())
        val notes = ModelNotes.deepSeekNotes(DeepSeekModels.FLASH, directory, null).map { it.text }
        assertTrue(notes.contains(ModelNotes.TEXT_ONLY_INPUT))
        assertFalse(notes.contains(ModelNotes.LEGACY_FLASH_ID))
        assertTrue(ModelNotes.deepSeekNotes(DeepSeekModels.V4_PRO, directory, null).none { "图片" in it.text || "旧名" in it.text })
    }

    @Test fun rpMarkerHintNamesCurrentVerifiedModels() {
        val reason = ChatSettings(model = "fixture-unknown").rpMarkerAvailability().unavailableReason.orEmpty()
        assertTrue(reason.contains(DeepSeekModels.FLASH))
        assertTrue(reason.contains(DeepSeekModels.V4_PRO))
        assertFalse(reason.contains(DeepSeekModels.V4_FLASH))
    }

    @Test fun unknownNameDoesNotInventCapabilitiesAndOverrideHasPriority() {
        val id = "fixture-v4-vision-1m"
        val directory = DeepSeekModels.directory(id, listOf(id))
        val unknown = ModelNotes.deepSeekNotes(id, directory, null)
        assertEquals(1, unknown.size)
        assertTrue(unknown.single().text.contains("回退"))
        assertFalse(unknown.single().text.contains("图片"))
        val configured = ModelNotes.deepSeekNotes(id, directory, 65536)
        assertTrue(configured.single().text.contains("手动配置"))
        assertFalse(configured.single().text.contains("回退"))
    }

    @Test fun sourceAndRefreshStateAreExplicit() {
        assertTrue(describeDirectorySource(DeepSeekModels.directory("fixture", emptyList())).contains("内置兜底"))
        assertTrue(describeDirectorySource(DeepSeekModels.directory("fixture", listOf("fixture"))).contains("服务器目录缓存"))
        assertEquals("从未成功刷新", describeLastRefreshed(0))
        assertEquals("正在刷新…", describeRefreshAction(true, false))
        assertEquals("其他列表刷新中", describeRefreshAction(false, true))
    }

    @Test fun reasoningLabelsAndSamplingConditionsRespectProtocol() {
        assertEquals(setOf("轻度思考", "深度思考", "最大强度"), ReasoningEffort.entries.map { it.displayName }.toSet())
        assertFalse(ChatSettings(thinkingMode = ThinkingMode.ENABLED).samplingParametersEffective)
        assertTrue(ChatSettings(thinkingMode = ThinkingMode.DISABLED).samplingParametersEffective)
    }

    @Test fun urlValidationDistinguishesInvalidAndCleartext() {
        assertTrue(validateBaseUrl("").isError)
        assertTrue(validateBaseUrl("not-a-url").isError)
        assertTrue(validateBaseUrl("https://example.com/v1?token=fixture").isError)
        assertTrue(validateBaseUrl("http://example.com/v1") is BaseUrlValidation.Cleartext)
        assertTrue(validateBaseUrl("https://example.com/v1") is BaseUrlValidation.Secure)
    }
}
