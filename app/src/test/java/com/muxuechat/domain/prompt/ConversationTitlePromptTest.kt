package com.muxuechat.domain.prompt

import com.muxuechat.domain.model.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTitlePromptTest {

    @Test
    fun emptyConversationBuildsNothing() {
        assertNull(ConversationTitlePrompt.build(emptyList()))
        assertNull(ConversationTitlePrompt.build(listOf(ConversationTitlePrompt.Line(ChatRole.USER, "  "))))
    }

    @Test
    fun transcriptGoesIntoSingleUserMessageWithHeadAndTail() {
        val lines = (1..12).map { index ->
            ConversationTitlePrompt.Line(if (index % 2 == 1) ChatRole.USER else ChatRole.ASSISTANT, "第${index}条")
        }
        val messages = ConversationTitlePrompt.build(lines)!!

        assertEquals(listOf("system", "user"), messages.map { it.role })
        val transcript = messages[1].content
        assertTrue(transcript.contains("用户：第1条"))
        assertTrue(transcript.contains("助手：第4条"))
        assertFalse(transcript.contains("第5条"))
        assertTrue(transcript.contains("中间省略 4 条"))
        assertTrue(transcript.contains("助手：第12条"))
    }

    @Test
    fun longMessagesAreTruncated() {
        val long = "字".repeat(ConversationTitlePrompt.MAX_CHARS_PER_MESSAGE + 50)
        val content = ConversationTitlePrompt.build(listOf(ConversationTitlePrompt.Line(ChatRole.USER, long)))!![1].content
        assertFalse(content.contains(long))
        assertTrue(content.contains("字".repeat(ConversationTitlePrompt.MAX_CHARS_PER_MESSAGE) + "…"))
    }

    @Test
    fun cleanStripsDecorations() {
        assertEquals("咖啡店的雨夜", ConversationTitlePrompt.clean("标题：「咖啡店的雨夜」。\n解释一下……", 80))
        assertEquals("Kotlin 协程入门", ConversationTitlePrompt.clean("\n  **Kotlin 协程入门**  ", 80))
        assertEquals("学习计划", ConversationTitlePrompt.clean("<think>先想想\n再想想</think>\n学习计划", 80))
        assertEquals("abc", ConversationTitlePrompt.clean("abcdef", 3))
        assertNull(ConversationTitlePrompt.clean(" \n 「」 ", 80))
    }
}
