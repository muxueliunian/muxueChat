package com.muxuechat.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.ConversationRpSettings
import com.muxuechat.domain.model.MessageStatus
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RpMetric
import com.muxuechat.domain.model.RpMetricDefinition
import com.muxuechat.domain.model.RpStatusPanel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RpDerivedDataIntegrityTest {
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
    fun tearDown() = database.close()

    @Test
    fun sameTimestampMessages_keepInsertionOrderForHistoryAndLatestAssistant() = runBlocking {
        seedRpConversation()
        val user = message("user", ChatRole.USER, "用户", 10)
        val firstAssistant = message("assistant-1", ChatRole.ASSISTANT, "第一条", 10)
        val secondAssistant = message("assistant-2", ChatRole.ASSISTANT, "第二条", 10)
        dao.upsertMessage(user)
        dao.upsertMessage(firstAssistant)
        dao.upsertMessage(secondAssistant)
        dao.upsertMessageRpMetadata(metadata(firstAssistant.id, "第一条内心", "第一条面板", 10))

        assertEquals(listOf("user", "assistant-1", "assistant-2"), dao.getMessages(CONVERSATION_ID).map { it.id })
        assertEquals(
            listOf("user", "assistant-1", "assistant-2"),
            dao.observeMessages(CONVERSATION_ID).first().map { it.id },
        )
        assertEquals(
            secondAssistant.id,
            dao.getLatestCompletedAssistant(CONVERSATION_ID, ChatRole.ASSISTANT, MessageStatus.COMPLETED)?.id,
        )
        assertEquals(
            firstAssistant.id,
            dao.getLatestMessageRpMetadataBeforeMessage(CONVERSATION_ID, secondAssistant.id)?.messageId,
        )
    }

    @Test
    fun olderEnrichment_savesOwnMetadataButCannotOverwriteNewerLocalPanel() = runBlocking {
        val (rpSettings, roleCard) = seedRpConversation()
        val oldAssistant = message("assistant-old", ChatRole.ASSISTANT, "旧回复", 10)
        val newAssistant = message("assistant-new", ChatRole.ASSISTANT, "新回复", 20)
        dao.upsertMessage(oldAssistant)
        dao.upsertMessage(newAssistant)
        dao.upsertRpStatusPanel(panel("新回复本地面板", 20))

        val committed = dao.commitRpEnrichment(
            expectedMessage = oldAssistant,
            expectedRpSettings = rpSettings,
            expectedRoleCard = roleCard,
            metadata = metadata(oldAssistant.id, "旧轮内心", "旧轮网络面板", 30),
            statusPanel = panel("旧轮网络面板", 30),
            missingStateMessage = null,
            updatedAtMillis = 30,
        )

        assertTrue(committed)
        assertNotNull(dao.observeMessageRpMetadata(CONVERSATION_ID).first().singleOrNull { it.messageId == oldAssistant.id })
        assertEquals("新回复本地面板", dao.getRpStatusPanel(CONVERSATION_ID)?.scene)
    }

    @Test
    fun fallbackOnlyEnrichment_persistsPanelAndMessageSnapshot() = runBlocking {
        val (rpSettings, roleCard) = seedRpConversation()
        val assistant = message("assistant", ChatRole.ASSISTANT, "正文无状态块", 10)
        dao.upsertMessage(assistant)
        val fallbackPanel = panel("兜底状态", 20)
        val fallbackSnapshot = metadata(assistant.id, "", "兜底状态", 20).copy(optionsJson = "[]")

        val committed = dao.commitRpEnrichment(
            expectedMessage = assistant,
            expectedRpSettings = rpSettings,
            expectedRoleCard = roleCard,
            metadata = fallbackSnapshot,
            statusPanel = fallbackPanel,
            missingStateMessage = null,
            updatedAtMillis = 20,
        )

        assertTrue(committed)
        assertEquals("兜底状态", dao.getRpStatusPanel(CONVERSATION_ID)?.scene)
        assertEquals(
            "兜底状态",
            dao.observeMessageRpMetadata(CONVERSATION_ID).first().single().scene,
        )
    }

    @Test
    fun enrichmentCommit_rejectsEditedMessageVersionWithoutPartialWrites() = runBlocking {
        val (rpSettings, roleCard) = seedRpConversation()
        val expected = message("assistant", ChatRole.ASSISTANT, "原正文", 10)
        dao.upsertMessage(expected)
        dao.updateMessage(expected.copy(content = "已编辑", updatedAtMillis = 20))

        val committed = dao.commitRpEnrichment(
            expectedMessage = expected,
            expectedRpSettings = rpSettings,
            expectedRoleCard = roleCard,
            metadata = metadata(expected.id, "迟到结果", "迟到面板", 30),
            statusPanel = panel("迟到面板", 30),
            missingStateMessage = null,
            updatedAtMillis = 30,
        )

        assertFalse(committed)
        assertTrue(dao.observeMessageRpMetadata(CONVERSATION_ID).first().isEmpty())
        assertNull(dao.getRpStatusPanel(CONVERSATION_ID))
    }

    @Test
    fun normalCompletion_commitsBodyPanelAndLocalSnapshotTogether() = runBlocking {
        val (rpSettings, roleCard) = seedRpConversation()
        val streaming = message("assistant", ChatRole.ASSISTANT, "正文", 10).copy(
            status = MessageStatus.STREAMING,
        )
        dao.upsertMessage(streaming)
        val completed = streaming.copy(status = MessageStatus.COMPLETED, updatedAtMillis = 20)
        val localPanel = panel("本地状态", 20)
        val localSnapshot = metadata(completed.id, "", "本地状态", 20).copy(optionsJson = "[]")

        val rpSnapshotCurrent = dao.completeAssistantAndApplyRpState(
            completedAssistant = completed,
            expectedRpSettings = rpSettings,
            expectedRoleCard = roleCard,
            rpStatusPanel = localPanel,
            rpMetadataSnapshot = localSnapshot,
            tokenUsage = null,
            updatedAtMillis = 20,
        )

        assertTrue(rpSnapshotCurrent)
        assertEquals(MessageStatus.COMPLETED, dao.getMessage(completed.id)?.status)
        assertEquals("本地状态", dao.getRpStatusPanel(CONVERSATION_ID)?.scene)
        assertEquals(
            "本地状态",
            dao.observeMessageRpMetadata(CONVERSATION_ID).first().single().scene,
        )
    }

    @Test
    fun normalCompletion_withoutLocalState_stillSnapshotsCurrentPanel() = runBlocking {
        val (rpSettings, roleCard) = seedRpConversation()
        val currentPanel = panel("沿用的当前状态", 10)
        dao.upsertRpStatusPanel(currentPanel)
        val streaming = message("assistant", ChatRole.ASSISTANT, "无状态块正文", 10).copy(
            status = MessageStatus.STREAMING,
        )
        dao.upsertMessage(streaming)
        val completed = streaming.copy(status = MessageStatus.COMPLETED, updatedAtMillis = 20)
        val currentSnapshot = metadata(completed.id, "", "沿用的当前状态", 20).copy(optionsJson = "[]")

        val rpSnapshotCurrent = dao.completeAssistantAndApplyRpState(
            completedAssistant = completed,
            expectedRpSettings = rpSettings,
            expectedRoleCard = roleCard,
            rpStatusPanel = null,
            rpMetadataSnapshot = currentSnapshot,
            tokenUsage = null,
            updatedAtMillis = 20,
        )

        assertTrue(rpSnapshotCurrent)
        assertEquals("沿用的当前状态", dao.getRpStatusPanel(CONVERSATION_ID)?.scene)
        assertEquals(
            "沿用的当前状态",
            dao.observeMessageRpMetadata(CONVERSATION_ID).first().single().scene,
        )
    }

    @Test
    fun normalCompletion_whenRoleChanges_commitsBodyButPreservesNewPanel() = runBlocking {
        val (oldSettings, oldRoleCard) = seedRpConversation()
        val streaming = message("assistant", ChatRole.ASSISTANT, "正文", 10).copy(
            status = MessageStatus.STREAMING,
        )
        dao.upsertMessage(streaming)

        val newRoleCard = oldRoleCard.copy(id = "new-role-card", name = "新角色", updatedAtMillis = 20)
        dao.upsertRoleCard(newRoleCard)
        dao.upsertConversationRpSettings(
            oldSettings.copy(roleCardId = newRoleCard.id, updatedAtMillis = 20),
        )
        dao.upsertRpStatusPanel(panel("新角色当前状态", 20))

        val completed = streaming.copy(status = MessageStatus.COMPLETED, updatedAtMillis = 30)
        val oldSnapshot = metadata(completed.id, "", "旧角色迟到状态", 30).copy(optionsJson = "[]")
        val rpSnapshotCurrent = dao.completeAssistantAndApplyRpState(
            completedAssistant = completed,
            expectedRpSettings = oldSettings,
            expectedRoleCard = oldRoleCard,
            rpStatusPanel = panel("旧角色迟到状态", 30),
            rpMetadataSnapshot = oldSnapshot,
            tokenUsage = null,
            updatedAtMillis = 30,
        )

        assertFalse(rpSnapshotCurrent)
        assertEquals(MessageStatus.COMPLETED, dao.getMessage(completed.id)?.status)
        assertEquals("新角色当前状态", dao.getRpStatusPanel(CONVERSATION_ID)?.scene)
        assertTrue(dao.observeMessageRpMetadata(CONVERSATION_ID).first().isEmpty())
    }

    @Test
    fun clearingStatusSnapshots_preservesInnerThoughtAndOptions() = runBlocking {
        seedRpConversation()
        val assistant = message("assistant", ChatRole.ASSISTANT, "正文", 10)
        dao.upsertMessage(assistant)
        dao.upsertMessageRpMetadata(metadata(assistant.id, "仍可展示的内心", "旧状态", 10))

        assertEquals(1, dao.clearMessageRpStatusSnapshots(CONVERSATION_ID, 20))

        val saved = dao.observeMessageRpMetadata(CONVERSATION_ID).first().single()
        assertEquals("仍可展示的内心", saved.innerThought)
        assertEquals("[\"继续\"]", saved.optionsJson)
        assertEquals("[]", saved.metricsJson)
        assertEquals("", saved.relationship)
        assertEquals("", saved.scene)
        assertEquals("", saved.rawJson)
    }

    @Test
    fun historyReplacement_whenRoleChanges_switchesBodyButPreservesNewPanel() = runBlocking {
        val (oldSettings, oldRoleCard) = seedRpConversation()
        val oldAssistant = message("assistant-old", ChatRole.ASSISTANT, "旧正文", 10)
        dao.upsertMessage(oldAssistant)
        val expectedMessages = dao.getMessages(CONVERSATION_ID)
        val staging = message("history-replacement:new", ChatRole.ASSISTANT, "新正文", 20).copy(
            status = MessageStatus.STREAMING,
        )
        dao.upsertMessage(staging)

        val newRoleCard = oldRoleCard.copy(id = "new-role-card", name = "新角色", updatedAtMillis = 20)
        dao.upsertRoleCard(newRoleCard)
        dao.upsertConversationRpSettings(
            oldSettings.copy(roleCardId = newRoleCard.id, updatedAtMillis = 20),
        )
        dao.upsertRpStatusPanel(panel("新角色当前状态", 20))

        val completed = staging.copy(status = MessageStatus.COMPLETED, updatedAtMillis = 30)
        val rpSnapshotCurrent = dao.commitHistoryReplacement(
            conversationId = CONVERSATION_ID,
            expectedMessages = expectedMessages,
            obsoleteMessageIds = listOf(oldAssistant.id),
            editedUserMessage = null,
            completedAssistant = completed,
            rpStatusPanel = panel("旧角色迟到状态", 30),
            updatedAtMillis = 30,
            expectedRpSettings = oldSettings,
            expectedRoleCard = oldRoleCard,
            rpMetadataSnapshot = metadata(completed.id, "", "旧角色迟到状态", 30).copy(optionsJson = "[]"),
        )

        assertFalse(rpSnapshotCurrent)
        assertNull(dao.getMessage(oldAssistant.id))
        assertEquals(MessageStatus.COMPLETED, dao.getMessage(completed.id)?.status)
        assertEquals("新角色当前状态", dao.getRpStatusPanel(CONVERSATION_ID)?.scene)
        assertTrue(dao.observeMessageRpMetadata(CONVERSATION_ID).first().isEmpty())
    }

    @Test
    fun assistantEdit_invalidatesDownstreamDerivedDataAndRollsBackPanelAtomically() = runBlocking {
        val (rpSettings, roleCard) = seedRpConversation()
        val first = message("assistant-1", ChatRole.ASSISTANT, "第一条", 10)
        val edited = message("assistant-2", ChatRole.ASSISTANT, "待编辑", 20)
        val downstream = message("assistant-3", ChatRole.ASSISTANT, "后续正文保留", 30)
        listOf(first, edited, downstream).forEach { dao.upsertMessage(it) }
        dao.upsertMessageRpMetadata(metadata(first.id, "第一条内心", "第一条面板", 10))
        dao.upsertMessageRpMetadata(metadata(edited.id, "旧内心", "旧面板", 20))
        dao.upsertMessageRpMetadata(metadata(downstream.id, "后续内心", "后续面板", 30))
        dao.upsertRpStatusPanel(panel("后续面板", 30))
        dao.upsertConversationContext(ConversationContextEntity(CONVERSATION_ID, "旧摘要", 3, 30))

        val committed = dao.editAssistantAndInvalidateDerivedData(
            expectedMessages = dao.getMessages(CONVERSATION_ID),
            editedMessage = edited.copy(content = "人工改写", updatedAtMillis = 40),
            expectedRpSettings = rpSettings,
            expectedRoleCard = roleCard,
            affectedAssistantMessageIds = listOf(edited.id, downstream.id),
            downstreamCompletedAssistantIds = listOf(downstream.id),
            rollbackStatusPanel = panel("第一条面板", 40),
            downstreamNotice = "派生信息已失效",
            updatedAtMillis = 40,
        )

        assertTrue(committed)
        assertEquals("人工改写", dao.getMessage(edited.id)?.content)
        assertEquals("后续正文保留", dao.getMessage(downstream.id)?.content)
        assertEquals("派生信息已失效", dao.getMessage(downstream.id)?.errorMessage)
        assertEquals(listOf(first.id), dao.observeMessageRpMetadata(CONVERSATION_ID).first().map { it.messageId })
        assertEquals("第一条面板", dao.getRpStatusPanel(CONVERSATION_ID)?.scene)
        assertNull(dao.getConversationContext(CONVERSATION_ID))
    }

    @Test
    fun clearConversation_removesMessagesMetadataPanelAndSummaryTogether() = runBlocking {
        seedRpConversation()
        val assistant = message("assistant", ChatRole.ASSISTANT, "正文", 10)
        dao.upsertMessage(assistant)
        dao.upsertMessageRpMetadata(metadata(assistant.id, "内心", "面板", 10))
        dao.upsertRpStatusPanel(panel("面板", 10))
        dao.upsertConversationContext(ConversationContextEntity(CONVERSATION_ID, "摘要", 1, 10))

        dao.clearConversationAndDerivedData(CONVERSATION_ID, 50)

        assertTrue(dao.getMessages(CONVERSATION_ID).isEmpty())
        assertTrue(dao.observeMessageRpMetadata(CONVERSATION_ID).first().isEmpty())
        assertNull(dao.getRpStatusPanel(CONVERSATION_ID))
        assertNull(dao.getConversationContext(CONVERSATION_ID))
        assertEquals(50L, dao.getConversation(CONVERSATION_ID)?.updatedAtMillis)
    }

    private suspend fun seedRpConversation(): Pair<ConversationRpSettingsEntity, RoleCardEntity> {
        dao.upsertConversation(ConversationEntity(CONVERSATION_ID, "会话", 1, 1))
        val roleCard = RoleCard(
            id = ROLE_CARD_ID,
            name = "角色",
            description = "",
            persona = "",
            speakingStyle = "",
            userIdentity = "",
            openingMessage = "",
            boundaries = "",
            metricDefinitions = listOf(RpMetricDefinition("trust", "信任", minValue = 0, maxValue = 10)),
            createdAtMillis = 1,
            updatedAtMillis = 1,
        ).toEntity()
        dao.upsertRoleCard(roleCard)
        val rpSettings = ConversationRpSettings(
            conversationId = CONVERSATION_ID,
            isRpEnabled = true,
            roleCardId = ROLE_CARD_ID,
            updatedAtMillis = 1,
        ).toEntity()
        dao.upsertConversationRpSettings(rpSettings)
        return rpSettings to roleCard
    }

    private fun message(id: String, role: ChatRole, content: String, time: Long) = MessageEntity(
        id = id,
        conversationId = CONVERSATION_ID,
        role = role,
        content = content,
        reasoningContent = null,
        status = MessageStatus.COMPLETED,
        errorMessage = null,
        createdAtMillis = time,
        updatedAtMillis = time,
    )

    private fun panel(scene: String, time: Long) = RpStatusPanel(
        conversationId = CONVERSATION_ID,
        metrics = listOf(RpMetric("信任", 5, 0, 10)),
        relationship = "熟识",
        scene = scene,
        rawJson = "{}",
        updatedAtMillis = time,
    ).toEntity()

    private fun metadata(messageId: String, innerThought: String, scene: String, time: Long) =
        MessageRpMetadataEntity(
            messageId = messageId,
            conversationId = CONVERSATION_ID,
            optionsJson = "[\"继续\"]",
            innerThought = innerThought,
            metricsJson = "[{\"name\":\"信任\",\"value\":5,\"min\":0,\"max\":10}]",
            relationship = "熟识",
            scene = scene,
            rawJson = "{}",
            createdAtMillis = time,
            updatedAtMillis = time,
        )

    private companion object {
        const val CONVERSATION_ID = "conversation"
        const val ROLE_CARD_ID = "role-card"
    }
}
