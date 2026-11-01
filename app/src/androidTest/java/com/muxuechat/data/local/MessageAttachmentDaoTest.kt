package com.muxuechat.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.MessageStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 附件表（v11）的 DAO 回归（MULTIMODAL_PLAN 3.1 / 5）。
 *
 * 重点防止以后有人把 `upsertMessage` / `updateMessage` 改回 `INSERT OR REPLACE`：
 * 那会先删后插，经外键级联把用户消息的图片行悄悄删掉。
 */
@RunWith(AndroidJUnit4::class)
class MessageAttachmentDaoTest {
    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.chatDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsertAndUpdateMessage_keepAttachments() = runBlocking {
        dao.upsertConversation(ConversationEntity(CONVERSATION_ID, "会话", 1, 1))
        val user = message("user", ChatRole.USER, "看看这张图", 2)
        dao.insertUserMessageWithAttachments(user, listOf(attachment("a1", user.id, 0), attachment("a2", user.id, 1)), 2)

        dao.upsertMessage(user.copy(content = "改过一次", updatedAtMillis = 3))
        dao.updateMessage(user.copy(content = "改过两次", updatedAtMillis = 4))

        assertEquals(listOf("a1", "a2"), dao.getAttachmentsForMessage(user.id).map { it.id })
        assertEquals(setOf("a1", "a2"), dao.getExistingAttachmentIds(listOf("a1", "a2", "missing")).toSet())
    }

    @Test
    fun deletingMessageOrClearingConversation_cascadesAttachmentRows() = runBlocking {
        dao.upsertConversation(ConversationEntity(CONVERSATION_ID, "会话", 1, 1))
        val first = message("user-1", ChatRole.USER, "", 2)
        val second = message("user-2", ChatRole.USER, "第二条", 3)
        dao.insertUserMessageWithAttachments(first, listOf(attachment("a1", first.id, 0)), 2)
        dao.insertUserMessageWithAttachments(second, listOf(attachment("a2", second.id, 0)), 3)

        dao.deleteMessage(first.id)
        assertEquals(listOf("a2"), dao.getAttachments(CONVERSATION_ID).map { it.id })

        dao.clearConversationAndDerivedData(CONVERSATION_ID, 5)
        assertTrue(dao.getAttachments(CONVERSATION_ID).isEmpty())
        assertTrue(dao.getAllAttachmentPaths().isEmpty())
    }

    @Test
    fun commitHistoryReplacement_switchesAttachmentsAtomically() = runBlocking {
        dao.upsertConversation(ConversationEntity(CONVERSATION_ID, "会话", 1, 1))
        val user = message("user", ChatRole.USER, "原文", 2)
        val oldReply = message("reply", ChatRole.ASSISTANT, "旧回复", 3)
        val kept = attachment("kept", user.id, 0)
        val removed = attachment("removed", user.id, 1)
        dao.insertUserMessageWithAttachments(user, listOf(kept, removed), 2)
        dao.upsertMessage(oldReply)
        val expectedMessages = dao.getMessages(CONVERSATION_ID)
        val expectedAttachments = dao.getAttachments(CONVERSATION_ID)

        val newReply = message("history-replacement:new", ChatRole.ASSISTANT, "新回复", 4)
        dao.upsertMessage(newReply.copy(status = MessageStatus.STREAMING))
        val added = attachment("added", user.id, 2)
        dao.commitHistoryReplacement(
            conversationId = CONVERSATION_ID,
            expectedMessages = expectedMessages,
            obsoleteMessageIds = listOf(oldReply.id),
            editedUserMessage = user.copy(content = "改过的原文", updatedAtMillis = 4),
            completedAssistant = newReply,
            rpStatusPanel = null,
            updatedAtMillis = 4,
            expectedAttachments = expectedAttachments,
            removedAttachmentIds = listOf(removed.id),
            addedAttachments = listOf(added),
        )

        assertEquals(listOf("kept", "added"), dao.getAttachmentsForMessage(user.id).map { it.id })
        assertEquals(setOf("kept", "added"), dao.getExistingAttachmentIds(listOf("kept", "removed", "added")).toSet())
    }

    @Test
    fun commitHistoryReplacement_rejectsWhenAttachmentsChangedDuringGeneration() = runBlocking {
        dao.upsertConversation(ConversationEntity(CONVERSATION_ID, "会话", 1, 1))
        val user = message("user", ChatRole.USER, "原文", 2)
        val oldReply = message("reply", ChatRole.ASSISTANT, "旧回复", 3)
        dao.insertUserMessageWithAttachments(user, listOf(attachment("a1", user.id, 0)), 2)
        dao.upsertMessage(oldReply)
        val expectedMessages = dao.getMessages(CONVERSATION_ID)
        val staleAttachments = dao.getAttachments(CONVERSATION_ID)
        // 生成期间另一处把图片换掉了。
        dao.deleteAttachmentsByIds(listOf("a1"))

        val newReply = message("history-replacement:new", ChatRole.ASSISTANT, "新回复", 4)
        val failure = runCatching {
            dao.commitHistoryReplacement(
                conversationId = CONVERSATION_ID,
                expectedMessages = expectedMessages,
                obsoleteMessageIds = listOf(oldReply.id),
                editedUserMessage = null,
                completedAssistant = newReply,
                rpStatusPanel = null,
                updatedAtMillis = 4,
                expectedAttachments = staleAttachments,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        // 事务整体回滚：旧回复仍在，新回复没有落库。
        assertEquals(listOf("user", "reply"), dao.getMessages(CONVERSATION_ID).map { it.id })
    }

    private fun message(id: String, role: ChatRole, content: String, at: Long) = MessageEntity(
        id = id,
        conversationId = CONVERSATION_ID,
        role = role,
        content = content,
        reasoningContent = null,
        status = MessageStatus.COMPLETED,
        errorMessage = null,
        createdAtMillis = at,
        updatedAtMillis = at,
    )

    private fun attachment(id: String, messageId: String, orderIndex: Int) = MessageAttachmentEntity(
        id = id,
        messageId = messageId,
        conversationId = CONVERSATION_ID,
        kind = MessageAttachmentEntity.KIND_IMAGE,
        mimeType = "image/jpeg",
        relativePath = "chat_images/$id.jpg",
        byteSize = 100,
        width = 10,
        height = 10,
        sha256 = "fixture",
        orderIndex = orderIndex,
        createdAtMillis = 1,
    )

    private companion object {
        const val CONVERSATION_ID = "conversation"
    }
}
