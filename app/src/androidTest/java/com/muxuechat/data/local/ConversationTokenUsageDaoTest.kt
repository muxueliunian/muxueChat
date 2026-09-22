package com.muxuechat.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.MessageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「上次 API 实际用量」的持久化契约。
 *
 * 关键不变量：用量与 assistant 完成状态必须一起提交；上游没给用量时保留上一份有效快照；
 * 清空会话要删掉它，但编辑历史不能删——那是一次真实发生过的请求。
 */
@RunWith(AndroidJUnit4::class)
class ConversationTokenUsageDaoTest {
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

    // ---------- 基础读写 ----------

    @Test
    fun upsert_replacesPreviousSnapshot() = runBlocking {
        seedConversation()

        dao.upsertConversationTokenUsage(usage(promptTokens = 100, capturedAtMillis = 1))
        dao.upsertConversationTokenUsage(usage(promptTokens = 200, capturedAtMillis = 2))

        val stored = dao.getConversationTokenUsage(CONVERSATION_ID)
        assertEquals(200, stored?.promptTokens)
        assertEquals(2L, stored?.capturedAtMillis)
    }

    /** 三个计数列可空：上游只给 total 时也必须能存下来。 */
    @Test
    fun upsert_acceptsNullCounters() = runBlocking {
        seedConversation()

        dao.upsertConversationTokenUsage(
            usage(promptTokens = null, completionTokens = null, totalTokens = 120),
        )

        val stored = dao.getConversationTokenUsage(CONVERSATION_ID)
        assertNull(stored?.promptTokens)
        assertEquals(120, stored?.totalTokens)
    }

    @Test
    fun observe_emitsLatestSnapshot() = runBlocking {
        seedConversation()
        assertNull(dao.observeConversationTokenUsage(CONVERSATION_ID).first())

        dao.upsertConversationTokenUsage(usage(promptTokens = 42))

        assertEquals(42, dao.observeConversationTokenUsage(CONVERSATION_ID).first()?.promptTokens)
    }

    // ---------- 生命周期 ----------

    @Test
    fun deletingConversation_cascadesUsageAway() = runBlocking {
        seedConversation()
        dao.upsertConversationTokenUsage(usage())

        dao.deleteConversation(CONVERSATION_ID)

        assertNull(dao.getConversationTokenUsage(CONVERSATION_ID))
    }

    /** 历史被清空后这份用量已经没有可解释的对象，留着只会误导。 */
    @Test
    fun clearingConversation_alsoDropsUsage() = runBlocking {
        seedConversation()
        dao.upsertConversationTokenUsage(usage())
        dao.upsertConversationContext(
            ConversationContextEntity(CONVERSATION_ID, "摘要", 1, 10),
        )

        dao.clearConversationAndDerivedData(CONVERSATION_ID, updatedAtMillis = 30)

        assertNull(dao.getConversationTokenUsage(CONVERSATION_ID))
        assertNull(dao.getConversationContext(CONVERSATION_ID))
    }

    // ---------- 与完成事务的原子性 ----------

    @Test
    fun completingAssistant_commitsUsageInTheSameTransaction() = runBlocking {
        seedConversation()
        val streaming = message("assistant-1", ChatRole.ASSISTANT, MessageStatus.STREAMING)
        dao.upsertMessage(streaming)

        val committed = dao.completeAssistantAndApplyRpState(
            completedAssistant = streaming.copy(
                content = "回复",
                status = MessageStatus.COMPLETED,
                updatedAtMillis = 20,
            ),
            expectedRpSettings = null,
            expectedRoleCard = null,
            expectedStatusPanel = null,
            rpStatusPanel = null,
            rpMetadataSnapshot = null,
            tokenUsage = usage(promptTokens = 13_102, completionTokens = 816, totalTokens = 13_918),
            updatedAtMillis = 20,
        )

        assertEquals(RpStateCommitResult.APPLIED, committed)
        assertEquals(MessageStatus.COMPLETED, dao.getMessage("assistant-1")?.status)
        assertEquals(13_102, dao.getConversationTokenUsage(CONVERSATION_ID)?.promptTokens)
    }

    /** 上游没返回用量时传 null，必须保留上一份有效快照而不是写入一行空数据。 */
    @Test
    fun completingAssistantWithoutUsage_keepsPreviousSnapshot() = runBlocking {
        seedConversation()
        dao.upsertConversationTokenUsage(usage(promptTokens = 999, capturedAtMillis = 5))
        val streaming = message("assistant-2", ChatRole.ASSISTANT, MessageStatus.STREAMING)
        dao.upsertMessage(streaming)

        dao.completeAssistantAndApplyRpState(
            completedAssistant = streaming.copy(status = MessageStatus.COMPLETED, updatedAtMillis = 20),
            expectedRpSettings = null,
            expectedRoleCard = null,
            expectedStatusPanel = null,
            rpStatusPanel = null,
            rpMetadataSnapshot = null,
            tokenUsage = null,
            updatedAtMillis = 20,
        )

        assertEquals(999, dao.getConversationTokenUsage(CONVERSATION_ID)?.promptTokens)
        assertEquals(5L, dao.getConversationTokenUsage(CONVERSATION_ID)?.capturedAtMillis)
    }

    /** 生成失败时消息不会进入完成事务，用量自然也不该被写入。 */
    @Test
    fun failedGeneration_neverWritesUsage() = runBlocking {
        seedConversation()
        val streaming = message("assistant-3", ChatRole.ASSISTANT, MessageStatus.STREAMING)
        dao.upsertMessage(streaming)

        dao.upsertMessage(streaming.copy(status = MessageStatus.FAILED, errorMessage = "网络错误"))

        assertNull(dao.getConversationTokenUsage(CONVERSATION_ID))
    }

    /**
     * 编辑历史会作废摘要，但**不**作废用量——那是一次真实发生过的请求，
     * 界面会标注它属于哪个模型和时间，用户仍然应该看得到。
     */
    @Test
    fun historyReplacement_invalidatesSummaryButKeepsUsage() = runBlocking {
        seedConversation()
        val user = message("user-1", ChatRole.USER, MessageStatus.COMPLETED)
        dao.upsertMessage(user)
        dao.upsertConversationContext(ConversationContextEntity(CONVERSATION_ID, "摘要", 1, 10))
        dao.upsertConversationTokenUsage(usage(promptTokens = 777, capturedAtMillis = 7))

        val replacement = message("assistant-4", ChatRole.ASSISTANT, MessageStatus.COMPLETED)
        val committed = dao.commitHistoryReplacement(
            conversationId = CONVERSATION_ID,
            expectedMessages = listOf(user),
            obsoleteMessageIds = emptyList(),
            editedUserMessage = null,
            completedAssistant = replacement,
            rpStatusPanel = null,
            updatedAtMillis = 30,
        )

        assertEquals(RpStateCommitResult.APPLIED, committed)
        assertNull(dao.getConversationContext(CONVERSATION_ID))
        assertNotNull(dao.getConversationTokenUsage(CONVERSATION_ID))
        assertEquals(777, dao.getConversationTokenUsage(CONVERSATION_ID)?.promptTokens)
    }

    // ---------- 测试数据 ----------

    private suspend fun seedConversation() {
        dao.upsertConversation(
            ConversationEntity(
                id = CONVERSATION_ID,
                title = "会话",
                createdAtMillis = 1,
                updatedAtMillis = 1,
            ),
        )
    }

    private fun message(
        id: String,
        role: ChatRole,
        status: MessageStatus,
    ) = MessageEntity(
        id = id,
        conversationId = CONVERSATION_ID,
        role = role,
        content = if (status == MessageStatus.STREAMING) "" else "内容",
        reasoningContent = null,
        status = status,
        errorMessage = null,
        createdAtMillis = 10,
        updatedAtMillis = 10,
    )

    private fun usage(
        promptTokens: Int? = 100,
        completionTokens: Int? = 20,
        totalTokens: Int? = 120,
        capturedAtMillis: Long = 1,
    ) = ConversationTokenUsageEntity(
        conversationId = CONVERSATION_ID,
        providerKey = "DEEPSEEK",
        model = "deepseek-v4-flash",
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        effectiveContextTokens = 950_000L,
        capturedAtMillis = capturedAtMillis,
    )

    private companion object {
        const val CONVERSATION_ID = "conversation-usage"
    }
}
