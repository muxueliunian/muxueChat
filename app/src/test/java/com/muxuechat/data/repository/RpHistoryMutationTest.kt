package com.muxuechat.data.repository

import com.muxuechat.data.local.MessageEntity
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RpHistoryMutationTest {
    @Test
    fun `fixed turn selects user before its assistant instead of newest user`() {
        val messages = listOf(
            message("u1", ChatRole.USER, "第一轮用户"),
            message("a1", ChatRole.ASSISTANT, "第一轮回复"),
            message("u2", ChatRole.USER, "第二轮用户"),
            message("a2", ChatRole.ASSISTANT, "第二轮回复"),
        )

        assertEquals(
            "第一轮用户",
            RpHistoryMutation.lastCompletedUserContentBefore(messages, "a1"),
        )
        assertEquals(
            "第二轮用户",
            RpHistoryMutation.lastCompletedUserContentBefore(messages, "a2"),
        )
    }

    @Test
    fun `failed and blank user rows do not become enrichment context`() {
        val messages = listOf(
            message("u1", ChatRole.USER, "有效输入"),
            message("u2", ChatRole.USER, "失败输入", MessageStatus.FAILED),
            message("u3", ChatRole.USER, "  "),
            message("a1", ChatRole.ASSISTANT, "回复"),
        )

        assertEquals(
            "有效输入",
            RpHistoryMutation.lastCompletedUserContentBefore(messages, "a1"),
        )
    }

    private fun message(
        id: String,
        role: ChatRole,
        content: String,
        status: MessageStatus = MessageStatus.COMPLETED,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        role = role,
        content = content,
        reasoningContent = null,
        status = status,
        errorMessage = null,
        createdAtMillis = 1,
        updatedAtMillis = 1,
    )
}
