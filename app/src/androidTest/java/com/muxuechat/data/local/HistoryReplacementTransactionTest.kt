package com.muxuechat.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.MessageStatus
import com.muxuechat.domain.model.RpMetric
import com.muxuechat.domain.model.RpStatusPanel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryReplacementTransactionTest {
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
    fun successfulCommit_switchesHistoryRpStateAndSummaryTogether() = runBlocking {
        val conversation = conversation()
        val firstUser = message("u1", ChatRole.USER, "旧问题", 1)
        val oldAssistant = message("a1", ChatRole.ASSISTANT, "旧回复", 2)
        val laterUser = message("u2", ChatRole.USER, "旧分支后续", 3)
        dao.upsertConversation(conversation)
        listOf(firstUser, oldAssistant, laterUser).forEach { dao.upsertMessage(it) }
        dao.upsertMessageRpMetadata(metadata(oldAssistant.id))
        dao.upsertConversationContext(
            ConversationContextEntity(conversation.id, "旧摘要", coveredMessageCount = 3, updatedAtMillis = 3),
        )
        dao.upsertRpStatusPanel(panel("旧场景", updatedAt = 3).toEntity())

        val expectedMessages = dao.getMessages(conversation.id)
        val staging = message(
            id = "history-replacement:new",
            role = ChatRole.ASSISTANT,
            content = "新回复",
            createdAt = 4,
            status = MessageStatus.STREAMING,
        )
        dao.upsertMessage(staging)

        dao.commitHistoryReplacement(
            conversationId = conversation.id,
            expectedMessages = expectedMessages,
            obsoleteMessageIds = listOf(oldAssistant.id, laterUser.id),
            editedUserMessage = firstUser.copy(content = "新问题", updatedAtMillis = 5),
            completedAssistant = staging.copy(status = MessageStatus.COMPLETED, updatedAtMillis = 5),
            rpStatusPanel = panel("新场景", updatedAt = 5).toEntity(),
            updatedAtMillis = 5,
            expectedStatusPanel = panel("旧场景", updatedAt = 3).toEntity(),
        )

        assertEquals("新问题", dao.getMessage(firstUser.id)?.content)
        assertNull(dao.getMessage(oldAssistant.id))
        assertNull(dao.getMessage(laterUser.id))
        assertEquals(MessageStatus.COMPLETED, dao.getMessage(staging.id)?.status)
        assertEquals(emptyList<MessageRpMetadataEntity>(), dao.observeMessageRpMetadata(conversation.id).first())
        assertEquals("新场景", dao.getRpStatusPanel(conversation.id)?.scene)
        assertNull(dao.getConversationContext(conversation.id))
        assertEquals(5L, dao.getConversation(conversation.id)?.updatedAtMillis)
    }

    @Test
    fun commitFailure_rollsBackEveryOfficialTableChange() = runBlocking {
        val conversation = conversation()
        val user = message("u1", ChatRole.USER, "原问题", 1)
        val assistant = message("a1", ChatRole.ASSISTANT, "原回复", 2)
        dao.upsertConversation(conversation)
        dao.upsertMessage(user)
        dao.upsertMessage(assistant)
        dao.upsertConversationContext(
            ConversationContextEntity(conversation.id, "原摘要", coveredMessageCount = 2, updatedAtMillis = 2),
        )
        dao.upsertRpStatusPanel(panel("原场景", updatedAt = 2).toEntity())
        val expectedMessages = dao.getMessages(conversation.id)

        val invalidAssistant = message(
            id = "history-replacement:invalid",
            role = ChatRole.ASSISTANT,
            content = "不应提交",
            createdAt = 3,
            status = MessageStatus.COMPLETED,
        ).copy(conversationId = "missing-conversation")

        val result = runCatching {
            dao.commitHistoryReplacement(
                conversationId = conversation.id,
                expectedMessages = expectedMessages,
                obsoleteMessageIds = listOf(assistant.id),
                editedUserMessage = user.copy(content = "不应提交", updatedAtMillis = 3),
                completedAssistant = invalidAssistant,
                rpStatusPanel = null,
                updatedAtMillis = 3,
            )
        }
        assertTrue(result.isFailure)

        assertEquals("原问题", dao.getMessage(user.id)?.content)
        assertNotNull(dao.getMessage(assistant.id))
        assertEquals("原场景", dao.getRpStatusPanel(conversation.id)?.scene)
        assertEquals("原摘要", dao.getConversationContext(conversation.id)?.summary)
        assertEquals(2L, dao.getConversation(conversation.id)?.updatedAtMillis)
    }

    private fun conversation() = ConversationEntity(
        id = "conversation",
        title = "会话",
        createdAtMillis = 1,
        updatedAtMillis = 2,
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

    private fun panel(scene: String, updatedAt: Long) = RpStatusPanel(
        conversationId = "conversation",
        metrics = listOf(RpMetric("信任", 3, 0, 10)),
        relationship = "熟识",
        scene = scene,
        rawJson = "{}",
        updatedAtMillis = updatedAt,
    )

    private fun metadata(messageId: String) = MessageRpMetadataEntity(
        messageId = messageId,
        conversationId = "conversation",
        optionsJson = "[]",
        innerThought = "旧想法",
        metricsJson = "[]",
        relationship = "",
        scene = "",
        rawJson = "",
        createdAtMillis = 2,
        updatedAtMillis = 2,
    )
}
