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

    @Test
    fun `regeneration hides the replaced reply while staging streams`() {
        val messages = listOf(
            message("u1", ChatRole.USER, "问题", createdAt = 1),
            message("a1", ChatRole.ASSISTANT, "旧回复", createdAt = 2),
            message(
                id = "history-replacement:new",
                role = ChatRole.ASSISTANT,
                content = "新回复进行中",
                createdAt = 3,
                status = MessageStatus.STREAMING,
            ),
        )

        val visible = HistoryReplacementHistory.visibleDuringReplacement(
            messages = messages,
            obsoleteMessageIds = listOf("a1"),
            editedUserMessage = null,
        )

        assertEquals(listOf("u1", "history-replacement:new"), visible.map { it.id })
    }

    @Test
    fun `edit replacement shows edited user content and hides old branch`() {
        val messages = listOf(
            message("u1", ChatRole.USER, "旧问题", createdAt = 1),
            message("a1", ChatRole.ASSISTANT, "旧回复", createdAt = 2),
            message("u2", ChatRole.USER, "后续问题", createdAt = 3),
            message("a2", ChatRole.ASSISTANT, "后续回复", createdAt = 4),
            message(
                id = "history-replacement:new",
                role = ChatRole.ASSISTANT,
                content = "",
                createdAt = 5,
                status = MessageStatus.PENDING,
            ),
        )

        val visible = HistoryReplacementHistory.visibleDuringReplacement(
            messages = messages,
            obsoleteMessageIds = listOf("a1", "u2", "a2"),
            editedUserMessage = messages[0].copy(content = "新问题"),
        )

        assertEquals(listOf("u1", "history-replacement:new"), visible.map { it.id })
        assertEquals("新问题", visible[0].content)
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
