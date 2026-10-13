package com.muxuechat.domain.rp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RpStateBlockFormatTest {
    @Test
    fun stripForDisplay_removesCompleteBlock() {
        val content = "正文结尾。\n\n<muxue_state>{\"metrics\":[]}</muxue_state>"

        assertEquals("正文结尾。", RpStateBlockFormat.stripForDisplay(content, isStreaming = false))
    }

    @Test
    fun stripForDisplay_hidesUnclosedBlockWhileStreaming() {
        val content = "正文结尾。\n<muxue_state>{\"metrics\":[{\"name\":\"角色::好"

        assertEquals("正文结尾。", RpStateBlockFormat.stripForDisplay(content, isStreaming = true))
    }

    @Test
    fun stripForDisplay_hidesHalfWrittenOpeningTagOnlyWhileStreaming() {
        val content = "正文结尾。\n<muxue_st"

        assertEquals("正文结尾。", RpStateBlockFormat.stripForDisplay(content, isStreaming = true))
        assertEquals(content, RpStateBlockFormat.stripForDisplay(content, isStreaming = false))
    }

    @Test
    fun stripForDisplay_keepsLoneAngleBracket() {
        val content = "a <"

        assertEquals("a <", RpStateBlockFormat.stripForDisplay(content, isStreaming = true))
    }

    @Test
    fun stripForDisplay_dropsFenceThatOnlyWrappedStateBlock() {
        val complete = "正文。\n```json\n<muxue_state>{}</muxue_state>\n```"
        val streaming = "正文。\n```json\n<muxue_state>{\"metr"

        assertEquals("正文。", RpStateBlockFormat.stripForDisplay(complete, isStreaming = false))
        assertEquals("正文。", RpStateBlockFormat.stripForDisplay(streaming, isStreaming = true))
    }

    @Test
    fun stripForDisplay_keepsRealCodeBlock() {
        val content = "示例：\n```kotlin\nval a = 1\n```"

        assertEquals(content, RpStateBlockFormat.stripForDisplay(content, isStreaming = false))
    }

    @Test
    fun example_usesInstructionsInsteadOfCopyableValues() {
        val example = RpStateBlockFormat.stateBlockExample(definitions = emptyList())

        assertFalse(example.contains("\"当前关系\""))
        assertEquals("", RpStateBlockFormat.meaningfulText(RpStateBlockFormat.RELATIONSHIP_HINT))
        assertEquals("初识", RpStateBlockFormat.meaningfulText(" 初识 "))
    }
}
