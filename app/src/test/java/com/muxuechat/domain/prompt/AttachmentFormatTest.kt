package com.muxuechat.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentFormatTest {

    @Test
    fun withoutAttachment_returnsTrimmedUserTextOnly() {
        assertEquals(
            "帮我建个角色卡",
            AttachmentFormat.composeUserMessage("  帮我建个角色卡  ", null, null),
        )
        assertEquals(
            "只有正文",
            AttachmentFormat.composeUserMessage("只有正文", "a.txt", ""),
        )
        assertEquals(
            "只有正文",
            AttachmentFormat.composeUserMessage("只有正文", "", "有内容"),
        )
    }

    /** 分隔符只负责分段：用户正文在前，附件正文完整地夹在 BEGIN/END 之间。 */
    @Test
    fun withAttachment_wrapsInDelimiters() {
        val composed = AttachmentFormat.composeUserMessage(
            userText = "根据这个建角色卡",
            attachmentName = "小说片段.txt",
            attachmentText = "林澈站在雨里。",
        )

        assertTrue(composed.startsWith("根据这个建角色卡"))
        assertTrue(composed.contains("小说片段.txt"))
        assertTrue(composed.contains(AttachmentFormat.BEGIN))
        assertTrue(composed.contains("林澈站在雨里。"))
        assertTrue(composed.trimEnd().endsWith(AttachmentFormat.END))
        assertTrue(composed.indexOf("根据这个建角色卡") < composed.indexOf(AttachmentFormat.BEGIN))
    }

    @Test
    fun attachmentWithoutUserText_stillProducesValidMessage() {
        val composed = AttachmentFormat.composeUserMessage("", "资料.md", "内容")

        assertTrue(composed.contains(AttachmentFormat.BEGIN))
        assertTrue(composed.contains("内容"))
        assertTrue(composed.isNotBlank())
    }

    @Test
    fun reportsAttachmentLength() {
        val text = "字".repeat(1234)
        val composed = AttachmentFormat.composeUserMessage("看看", "长文.txt", text)

        assertTrue(composed.contains("1234 字"))
    }

    /** 附件正文原样保留，不做任何改写或过滤。 */
    @Test
    fun attachmentTextIsPassedThroughVerbatim() {
        val text = "第一行\n第二行\n\n第四行"
        val composed = AttachmentFormat.composeUserMessage("看看", "多行.txt", text)

        val begin = composed.indexOf(AttachmentFormat.BEGIN)
        val end = composed.indexOf(AttachmentFormat.END)
        val payloadIndex = composed.indexOf(text)

        assertTrue("附件正文必须落在分隔符之间", payloadIndex in (begin + 1) until end)
    }
}
