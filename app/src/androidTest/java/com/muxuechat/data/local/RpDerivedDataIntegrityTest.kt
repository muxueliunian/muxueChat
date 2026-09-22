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
import com.muxuechat.domain.model.toNumericEntry
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
            expectedStatusPanel = null,
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
            expectedStatusPanel = null,
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
            expectedStatusPanel = null,
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
            expectedStatusPanel = null,
            rpStatusPanel = localPanel,
            rpMetadataSnapshot = localSnapshot,
            tokenUsage = null,
            updatedAtMillis = 20,
        )

        assertEquals(RpStateCommitResult.APPLIED, rpSnapshotCurrent)
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
            expectedStatusPanel = currentPanel,
            rpStatusPanel = null,
            rpMetadataSnapshot = currentSnapshot,
            tokenUsage = null,
            updatedAtMillis = 20,
        )

        assertEquals(RpStateCommitResult.APPLIED, rpSnapshotCurrent)
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
            expectedStatusPanel = null,
            rpStatusPanel = panel("旧角色迟到状态", 30),
            rpMetadataSnapshot = oldSnapshot,
            tokenUsage = null,
            updatedAtMillis = 30,
        )

        assertEquals(RpStateCommitResult.CONFIGURATION_CHANGED, rpSnapshotCurrent)
        assertEquals(MessageStatus.COMPLETED, dao.getMessage(completed.id)?.status)
        assertEquals("新角色当前状态", dao.getRpStatusPanel(CONVERSATION_ID)?.scene)
        assertTrue(dao.observeMessageRpMetadata(CONVERSATION_ID).first().isEmpty())
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

        assertEquals(RpStateCommitResult.CONFIGURATION_CHANGED, rpSnapshotCurrent)
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

    // ---------- 面板提交守卫（RP_ACTOR_PLAN 第 5 节） ----------

    /**
     * 补写期间面板被连续改了两次（模拟 RP-3 的手动修正），迟到的补写不得把面板写回旧轮结果；
     * 剧情建议照常保存，而这条回复的历史快照要记录库里的真实面板，否则回滚会撤销那两次修改。
     */
    @Test
    fun lateEnrichment_afterPanelEditedTwice_keepsEditsAndSnapshotsThem() = runBlocking {
        val (rpSettings, roleCard) = seedRpConversation()
        val assistant = message("assistant", ChatRole.ASSISTANT, "正文", 10)
        dao.upsertMessage(assistant)
        val capturedPanel = panel("补写开始时", 10)
        dao.upsertRpStatusPanel(capturedPanel)
        dao.upsertRpStatusPanel(panel("第一次修正", 20))
        dao.upsertRpStatusPanel(panel("第二次修正", 21))

        val committed = dao.commitRpEnrichment(
            expectedMessage = assistant,
            expectedRpSettings = rpSettings,
            expectedRoleCard = roleCard,
            expectedStatusPanel = capturedPanel,
            metadata = metadata(assistant.id, "迟到内心", "迟到面板", 30),
            statusPanel = panel("迟到面板", 30),
            missingStateMessage = null,
            updatedAtMillis = 30,
        )

        assertTrue(committed)
        assertEquals("第二次修正", dao.getRpStatusPanel(CONVERSATION_ID)?.scene)
        val saved = dao.observeMessageRpMetadata(CONVERSATION_ID).first().single()
        assertEquals("迟到内心", saved.innerThought)
        assertEquals("[\"继续\"]", saved.optionsJson)
        assertEquals("第二次修正", saved.scene)
    }

    /**
     * 用户在补写期间就发了下一句：下一轮已经按补写前的面板冻结输入，旧补写不得再动面板，
     * 否则下一轮提交会因「面板变了」而丢掉它自己的状态更新。
     */
    @Test
    fun enrichment_afterNextUserMessage_onlySavesOwnSnapshot() = runBlocking {
        val (rpSettings, roleCard) = seedRpConversation()
        val assistant = message("assistant", ChatRole.ASSISTANT, "正文", 10)
        dao.upsertMessage(assistant)
        val currentPanel = panel("当前", 10)
        dao.upsertRpStatusPanel(currentPanel)
        dao.upsertMessage(message("next-user", ChatRole.USER, "下一句", 20))

        val committed = dao.commitRpEnrichment(
            expectedMessage = assistant,
            expectedRpSettings = rpSettings,
            expectedRoleCard = roleCard,
            expectedStatusPanel = currentPanel,
            metadata = metadata(assistant.id, "内心", "补写结果", 30),
            statusPanel = panel("补写结果", 30),
            missingStateMessage = null,
            updatedAtMillis = 30,
        )

        assertTrue(committed)
        assertEquals(currentPanel, dao.getRpStatusPanel(CONVERSATION_ID))
        assertEquals("补写结果", dao.observeMessageRpMetadata(CONVERSATION_ID).first().single().scene)
    }

    /**
     * 面板被修正后用户又发了下一句、下一句还没完成：本条已不是最新对话消息，
     * 但它之后的真实状态仍是修正后的面板，快照必须记它，否则从这里回滚会撤销那次修正。
     */
    @Test
    fun lateEnrichment_panelEditedThenNextUserMessage_snapshotsEditedPanel() = runBlocking {
        val (rpSettings, roleCard) = seedRpConversation()
        val assistant = message("assistant", ChatRole.ASSISTANT, "正文", 10)
        dao.upsertMessage(assistant)
        val capturedPanel = panel("补写开始时", 10)
        dao.upsertRpStatusPanel(capturedPanel)
        val editedPanel = panel("手动修正", 15)
        dao.upsertRpStatusPanel(editedPanel)
        dao.upsertMessage(message("next-user", ChatRole.USER, "下一句", 20))

        val committed = dao.commitRpEnrichment(
            expectedMessage = assistant,
            expectedRpSettings = rpSettings,
            expectedRoleCard = roleCard,
            expectedStatusPanel = capturedPanel,
            metadata = metadata(assistant.id, "迟到内心", "迟到面板", 30),
            statusPanel = panel("迟到面板", 30),
            missingStateMessage = null,
            updatedAtMillis = 30,
        )

        assertTrue(committed)
        assertEquals(editedPanel, dao.getRpStatusPanel(CONVERSATION_ID))
        val saved = dao.observeMessageRpMetadata(CONVERSATION_ID).first().single()
        assertEquals("迟到内心", saved.innerThought)
        assertEquals("手动修正", saved.scene)
    }

    /**
     * 下一轮回复已先于本条补写完成并写了面板：面板变化来自后一轮，
     * 不能记进本条快照，否则从本条回滚会回到后一轮之后的状态。
     */
    @Test
    fun lateEnrichment_afterNextReplyCommitted_keepsOwnSnapshot() = runBlocking {
        val (rpSettings, roleCard) = seedRpConversation()
        val assistant = message("assistant", ChatRole.ASSISTANT, "正文", 10)
        dao.upsertMessage(assistant)
        val capturedPanel = panel("补写开始时", 10)
        dao.upsertRpStatusPanel(capturedPanel)
        dao.upsertMessage(message("next-user", ChatRole.USER, "下一句", 20))
        dao.upsertMessage(message("next-assistant", ChatRole.ASSISTANT, "下一轮", 25))
        dao.upsertRpStatusPanel(panel("下一轮状态", 25))

        val committed = dao.commitRpEnrichment(
            expectedMessage = assistant,
            expectedRpSettings = rpSettings,
            expectedRoleCard = roleCard,
            expectedStatusPanel = capturedPanel,
            metadata = metadata(assistant.id, "迟到内心", "本条补写状态", 30),
            statusPanel = panel("本条补写状态", 30),
            missingStateMessage = null,
            updatedAtMillis = 30,
        )

        assertTrue(committed)
        assertEquals("下一轮状态", dao.getRpStatusPanel(CONVERSATION_ID)?.scene)
        assertEquals(
            "本条补写状态",
            dao.observeMessageRpMetadata(CONVERSATION_ID).first().single { it.messageId == assistant.id }.scene,
        )
    }

    @Test
    fun normalCompletion_afterPanelChanged_commitsBodyButKeepsPanel() = runBlocking {
        val (rpSettings, roleCard) = seedRpConversation()
        val capturedPanel = panel("请求开始时", 10)
        dao.upsertRpStatusPanel(capturedPanel)
        val streaming = message("assistant", ChatRole.ASSISTANT, "正文", 10).copy(
            status = MessageStatus.STREAMING,
        )
        dao.upsertMessage(streaming)
        dao.upsertRpStatusPanel(panel("生成期间修正", 15))

        val completed = streaming.copy(status = MessageStatus.COMPLETED, updatedAtMillis = 20)
        val result = dao.completeAssistantAndApplyRpState(
            completedAssistant = completed,
            expectedRpSettings = rpSettings,
            expectedRoleCard = roleCard,
            expectedStatusPanel = capturedPanel,
            rpStatusPanel = panel("旧正文的状态", 20),
            rpMetadataSnapshot = metadata(completed.id, "", "旧正文的状态", 20).copy(optionsJson = "[]"),
            tokenUsage = null,
            updatedAtMillis = 20,
        )

        assertEquals(RpStateCommitResult.STATUS_PANEL_CHANGED, result)
        assertEquals(MessageStatus.COMPLETED, dao.getMessage(completed.id)?.status)
        assertEquals("生成期间修正", dao.getRpStatusPanel(CONVERSATION_ID)?.scene)
        assertEquals("生成期间修正", dao.observeMessageRpMetadata(CONVERSATION_ID).first().single().scene)
    }

    @Test
    fun historyReplacement_afterPanelChanged_switchesBodyButKeepsPanel() = runBlocking {
        val (rpSettings, roleCard) = seedRpConversation()
        val oldAssistant = message("assistant-old", ChatRole.ASSISTANT, "旧正文", 10)
        dao.upsertMessage(oldAssistant)
        val capturedPanel = panel("请求开始时", 10)
        dao.upsertRpStatusPanel(capturedPanel)
        val expectedMessages = dao.getMessages(CONVERSATION_ID)
        val staging = message("history-replacement:new", ChatRole.ASSISTANT, "新正文", 20).copy(
            status = MessageStatus.STREAMING,
        )
        dao.upsertMessage(staging)
        dao.upsertRpStatusPanel(panel("生成期间修正", 15))

        val completed = staging.copy(status = MessageStatus.COMPLETED, updatedAtMillis = 30)
        val result = dao.commitHistoryReplacement(
            conversationId = CONVERSATION_ID,
            expectedMessages = expectedMessages,
            obsoleteMessageIds = listOf(oldAssistant.id),
            editedUserMessage = null,
            completedAssistant = completed,
            rpStatusPanel = panel("回滚后的新状态", 30),
            updatedAtMillis = 30,
            expectedRpSettings = rpSettings,
            expectedRoleCard = roleCard,
            expectedStatusPanel = capturedPanel,
            rpMetadataSnapshot = metadata(completed.id, "", "回滚后的新状态", 30).copy(optionsJson = "[]"),
        )

        assertEquals(RpStateCommitResult.STATUS_PANEL_CHANGED, result)
        assertNull(dao.getMessage(oldAssistant.id))
        assertEquals("生成期间修正", dao.getRpStatusPanel(CONVERSATION_ID)?.scene)
        // 新回复的历史快照记录库里的真实面板，否则从它回滚会撤销那次修正。
        assertEquals("生成期间修正", dao.observeMessageRpMetadata(CONVERSATION_ID).first().single().scene)
    }

    /**
     * 下一轮已经失败：它不会再提交，补写不必为它让路。否则用户接着直接发新消息时，
     * 新一轮取的是补写前的面板，这次补写的状态就悄悄丢了。
     */
    @Test
    fun enrichment_afterNextReplyFailed_stillUpdatesPanel() = runBlocking {
        val (rpSettings, roleCard) = seedRpConversation()
        val assistant = message("assistant", ChatRole.ASSISTANT, "正文", 10)
        dao.upsertMessage(assistant)
        val currentPanel = panel("当前", 10)
        dao.upsertRpStatusPanel(currentPanel)
        dao.upsertMessage(message("next-user", ChatRole.USER, "下一句", 20))
        dao.upsertMessage(
            message("next-assistant", ChatRole.ASSISTANT, "", 21).copy(status = MessageStatus.FAILED),
        )

        val committed = dao.commitRpEnrichment(
            expectedMessage = assistant,
            expectedRpSettings = rpSettings,
            expectedRoleCard = roleCard,
            expectedStatusPanel = currentPanel,
            metadata = metadata(assistant.id, "内心", "补写结果", 30),
            statusPanel = panel("补写结果", 30),
            missingStateMessage = null,
            updatedAtMillis = 30,
        )

        assertTrue(committed)
        assertEquals("补写结果", dao.getRpStatusPanel(CONVERSATION_ID)?.scene)
    }

    /** 失败之后用户又发了一句、还没回复：新一轮已冻结面板，补写仍要让路。 */
    @Test
    fun enrichment_afterFailedReplyThenNewUserMessage_onlySavesOwnSnapshot() = runBlocking {
        val (rpSettings, roleCard) = seedRpConversation()
        val assistant = message("assistant", ChatRole.ASSISTANT, "正文", 10)
        dao.upsertMessage(assistant)
        val currentPanel = panel("当前", 10)
        dao.upsertRpStatusPanel(currentPanel)
        dao.upsertMessage(message("next-user", ChatRole.USER, "下一句", 20))
        dao.upsertMessage(
            message("next-assistant", ChatRole.ASSISTANT, "", 21).copy(status = MessageStatus.CANCELLED),
        )
        dao.upsertMessage(message("another-user", ChatRole.USER, "再一句", 22))

        dao.commitRpEnrichment(
            expectedMessage = assistant,
            expectedRpSettings = rpSettings,
            expectedRoleCard = roleCard,
            expectedStatusPanel = currentPanel,
            metadata = metadata(assistant.id, "内心", "补写结果", 30),
            statusPanel = panel("补写结果", 30),
            missingStateMessage = null,
            updatedAtMillis = 30,
        )

        assertEquals(currentPanel, dao.getRpStatusPanel(CONVERSATION_ID))
        assertEquals("补写结果", dao.observeMessageRpMetadata(CONVERSATION_ID).first().single().scene)
    }

    /** 之后还有一条正在生成的回复（含历史替换的临时行）：同样要让路。 */
    @Test
    fun enrichment_withLaterReplyStreaming_doesNotTouchPanel() = runBlocking {
        val (rpSettings, roleCard) = seedRpConversation()
        val assistant = message("assistant", ChatRole.ASSISTANT, "正文", 10)
        dao.upsertMessage(assistant)
        val currentPanel = panel("当前", 10)
        dao.upsertRpStatusPanel(currentPanel)
        dao.upsertMessage(
            message("history-replacement:later", ChatRole.ASSISTANT, "", 20)
                .copy(status = MessageStatus.STREAMING),
        )

        dao.commitRpEnrichment(
            expectedMessage = assistant,
            expectedRpSettings = rpSettings,
            expectedRoleCard = roleCard,
            expectedStatusPanel = currentPanel,
            metadata = metadata(assistant.id, "内心", "补写结果", 30),
            statusPanel = panel("补写结果", 30),
            missingStateMessage = null,
            updatedAtMillis = 30,
        )

        assertEquals(currentPanel, dao.getRpStatusPanel(CONVERSATION_ID))
    }

    /** RP 暂停时重新生成没有 RP 状态可写，面板必须原样保留，而不是被当成「清空」。 */
    @Test
    fun historyReplacement_withoutRpState_keepsPausedPanel() = runBlocking {
        val (rpSettings, _) = seedRpConversation()
        val paused = rpSettings.copy(isRpEnabled = false, updatedAtMillis = 5)
        dao.upsertConversationRpSettings(paused)
        val pausedPanel = panel("暂停前", 5)
        dao.upsertRpStatusPanel(pausedPanel)
        val oldAssistant = message("assistant-old", ChatRole.ASSISTANT, "旧正文", 10)
        dao.upsertMessage(oldAssistant)
        val expectedMessages = dao.getMessages(CONVERSATION_ID)
        val staging = message("history-replacement:new", ChatRole.ASSISTANT, "新正文", 20).copy(
            status = MessageStatus.STREAMING,
        )
        dao.upsertMessage(staging)

        val result = dao.commitHistoryReplacement(
            conversationId = CONVERSATION_ID,
            expectedMessages = expectedMessages,
            obsoleteMessageIds = listOf(oldAssistant.id),
            editedUserMessage = null,
            completedAssistant = staging.copy(status = MessageStatus.COMPLETED, updatedAtMillis = 30),
            rpStatusPanel = null,
            updatedAtMillis = 30,
            expectedRpSettings = paused,
            expectedRoleCard = null,
            expectedStatusPanel = pausedPanel,
        )

        assertEquals(RpStateCommitResult.APPLIED, result)
        assertEquals(pausedPanel, dao.getRpStatusPanel(CONVERSATION_ID))
    }

    @Test
    fun assistantEdit_withoutRollbackPanel_keepsPausedPanel() = runBlocking {
        val (rpSettings, _) = seedRpConversation()
        val paused = rpSettings.copy(isRpEnabled = false, updatedAtMillis = 5)
        dao.upsertConversationRpSettings(paused)
        val pausedPanel = panel("暂停前", 5)
        dao.upsertRpStatusPanel(pausedPanel)
        val assistant = message("assistant", ChatRole.ASSISTANT, "待编辑", 10)
        dao.upsertMessage(assistant)

        val committed = dao.editAssistantAndInvalidateDerivedData(
            expectedMessages = dao.getMessages(CONVERSATION_ID),
            editedMessage = assistant.copy(content = "人工改写", updatedAtMillis = 20),
            expectedRpSettings = paused,
            expectedRoleCard = null,
            affectedAssistantMessageIds = listOf(assistant.id),
            downstreamCompletedAssistantIds = emptyList(),
            rollbackStatusPanel = null,
            downstreamNotice = "派生信息已失效",
            updatedAtMillis = 20,
        )

        assertTrue(committed)
        assertEquals(pausedPanel, dao.getRpStatusPanel(CONVERSATION_ID))
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
            entries = listOf(RpMetricDefinition("trust", "信任", minValue = 0, maxValue = 10).toNumericEntry()),
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
