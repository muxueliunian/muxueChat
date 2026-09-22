package com.muxuechat.data.repository

import com.muxuechat.data.local.MessageEntity
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryReplacementHistoryTest {
    @Test
    fun `regeneration prompt keeps history only before target assistant`() {
        val messages = listOf(
            message("u1", ChatRole.USER, "第一问", createdAt = 1),
            message("a1", ChatRole.ASSISTANT, "第一答", createdAt = 2),
            message("u2", ChatRole.USER, "第二问", createdAt = 3),
            message("a2", ChatRole.ASSISTANT, "待替换回复", createdAt = 4),
        )

        val history = HistoryReplacementHistory.beforeAssistant(messages, "a2")

        assertEquals(listOf("user", "assistant", "user"), history.map { it.role })
        assertEquals(listOf("第一问", "第一答", "第二问"), history.map { it.content })
    }

    @Test
    fun `edited user prompt uses new content without mutating original branch`() {
        val messages = listOf(
            message("u1", ChatRole.USER, "第一问", createdAt = 1),
            message("a1", ChatRole.ASSISTANT, "第一答", createdAt = 2),
            message("u2", ChatRole.USER, "旧内容", createdAt = 3),
            message("a2", ChatRole.ASSISTANT, "旧分支", createdAt = 4),
        )

        val history = HistoryReplacementHistory.throughEditedUser(messages, "u2", "新内容")

        assertEquals(listOf("第一问", "第一答", "新内容"), history.map { it.content })
        assertEquals("旧内容", messages[2].content)
        assertEquals("旧分支", messages[3].content)
    }

    @Test
    fun `replacement prompt excludes incomplete messages`() {
        val messages = listOf(
            message("u1", ChatRole.USER, "可用问题", createdAt = 1),
            message(
                id = "failed",
                role = ChatRole.ASSISTANT,
                content = "失败半成品",
                createdAt = 2,
                status = MessageStatus.FAILED,
            ),
            message("u2", ChatRole.USER, "待编辑", createdAt = 3),
        )

        val history = HistoryReplacementHistory.throughEditedUser(messages, "u2", "已编辑")

        assertEquals(listOf("可用问题", "已编辑"), history.map { it.content })
    }

    private fun message(
        id: String,
        role: ChatRole,
        content: String,
        createdAt: Long,
        status: MessageStatus = MessageStatus.COMPLETED,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        role = role,
        content = content,
        reasoningContent = null,
        status = status,
        errorMessage = null,
        createdAtMillis = createdAt,
        updatedAtMillis = createdAt,
    )
}
