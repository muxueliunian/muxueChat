package com.muxuechat.data.repository

import com.muxuechat.data.local.MessageAttachmentEntity
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

        val history = HistoryReplacementHistory.beforeAssistant(messages, "a2").messages

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

        val history = HistoryReplacementHistory.throughEditedUser(messages, "u2", "新内容").messages

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

        val history = HistoryReplacementHistory.throughEditedUser(messages, "u2", "已编辑").messages

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

    @Test
    fun `unified mapping attaches available images and replaces missing ones with counted placeholder`() {
        val messages = listOf(
            message("u1", ChatRole.USER, "两张图", createdAt = 1),
            message("a1", ChatRole.ASSISTANT, "看到了", createdAt = 2),
            message("u2", ChatRole.USER, "", createdAt = 3),
        )
        val attachments = mapOf(
            // 故意乱序：映射按 orderIndex 排。
            "u1" to listOf(attachment("img-b", "u1", 1, bytes = 20), attachment("img-a", "u1", 0, bytes = 10)),
            "u2" to listOf(attachment("img-lost", "u2", 0)),
        )

        val mapped = HistoryReplacementHistory.toPromptHistory(
            messages = messages,
            attachmentsByMessageId = attachments,
            isAvailable = { it.id != "img-lost" },
        )

        assertEquals(1, mapped.missingImageCount)
        assertEquals(listOf("img-a", "img-b"), mapped.messages[0].images.map { it.attachmentId })
        assertEquals(listOf(10L, 20L), mapped.messages[0].images.map { it.byteSize })
        // 纯图片消息（D6）不被过滤；丢失的图片换成占位，计入 missingImageCount。
        assertEquals("[图片文件已丢失]", mapped.messages[2].content)
        assertEquals(emptyList<Any>(), mapped.messages[2].images)
    }

    @Test
    fun `assistant messages never carry images and blank messages without images are dropped`() {
        val messages = listOf(
            message("u1", ChatRole.USER, "问", createdAt = 1),
            message("a1", ChatRole.ASSISTANT, "答", createdAt = 2),
            message("u2", ChatRole.USER, "   ", createdAt = 3),
        )

        val mapped = HistoryReplacementHistory.toPromptHistory(
            messages = messages,
            // 即使库里出现挂在 assistant 上的附件行，也绝不发给 assistant 角色（上游会 400）。
            attachmentsByMessageId = mapOf("a1" to listOf(attachment("odd", "a1", 0))),
        )

        assertEquals(listOf("u1", "a1").size, mapped.messages.size)
        assertEquals(emptyList<Any>(), mapped.messages[1].images)
        assertEquals(0, mapped.missingImageCount)
    }

    @Test
    fun `regeneration and edit use the same image mapping as sending`() {
        val messages = listOf(
            message("u1", ChatRole.USER, "第一问", createdAt = 1),
            message("a1", ChatRole.ASSISTANT, "第一答", createdAt = 2),
            message("u2", ChatRole.USER, "第二问", createdAt = 3),
            message("a2", ChatRole.ASSISTANT, "第二答", createdAt = 4),
        )
        val attachments = mapOf(
            "u1" to listOf(attachment("history", "u1", 0)),
            "u2" to listOf(attachment("old", "u2", 0)),
        )

        val regenerate = HistoryReplacementHistory.beforeAssistant(messages, "a2", attachments)
        // 编辑时调用方已把被编辑消息的那一项换成编辑后的图片集合。
        val edited = HistoryReplacementHistory.throughEditedUser(
            messages = messages,
            userMessageId = "u2",
            editedContent = "",
            attachmentsByMessageId = attachments + ("u2" to listOf(attachment("new", "u2", 1))),
        )

        // 重新生成仍带着历史图片，不会静默丢失。
        assertEquals(listOf(listOf("history"), emptyList(), listOf("old")), regenerate.messages.map { m -> m.images.map { it.attachmentId } })
        assertEquals(listOf(listOf("history"), emptyList(), listOf("new")), edited.messages.map { m -> m.images.map { it.attachmentId } })
        assertEquals("", edited.messages.last().content)
    }

    private fun attachment(id: String, messageId: String, orderIndex: Int, bytes: Long = 100) = MessageAttachmentEntity(
        id = id,
        messageId = messageId,
        conversationId = "conversation",
        kind = MessageAttachmentEntity.KIND_IMAGE,
        mimeType = "image/jpeg",
        relativePath = "chat_images/$id.jpg",
        byteSize = bytes,
        width = 10,
        height = 10,
        sha256 = "fixture",
        orderIndex = orderIndex,
        createdAtMillis = 1,
    )

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
