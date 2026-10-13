package com.muxuechat.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muxuechat.data.local.ChatDao
import com.muxuechat.data.local.ChatDatabase
import com.muxuechat.data.local.ConversationEntity
import com.muxuechat.data.local.MessageEntity
import com.muxuechat.data.local.MessageRpMetadataEntity
import com.muxuechat.data.local.toEntity
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.MessageStatus
import com.muxuechat.domain.model.ProtagonistDraft
import com.muxuechat.domain.model.ProtagonistProfile
import com.muxuechat.domain.model.ProtagonistProfileDraft
import com.muxuechat.domain.model.RoleCardDraft
import com.muxuechat.domain.model.RpEntry
import com.muxuechat.domain.model.RpEntryOwner
import com.muxuechat.domain.model.RpMetric
import com.muxuechat.domain.model.RpMetricDefinition
import com.muxuechat.domain.model.RpStatusPanel
import com.muxuechat.domain.model.WorldSettingDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 会话绑定变化时，定义快照、状态面板与历史快照的转换规则（RP_ACTOR_PLAN 第 4 节）。
 *
 * 库里与状态块里的名字都是带归属前缀的 wireName（「角色::好感度」）。
 */
@RunWith(AndroidJUnit4::class)
class RpBindingTransitionTest {
    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatDao
    private lateinit var repository: RpRepositoryImpl
    private var now = 100L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.chatDao()
        repository = RpRepositoryImpl(database, dao, clock = { now++ })
    }

    @After
    fun tearDown() = database.close()

    // ---------- 角色卡组 ----------

    /** 两张卡都有「好感度」不代表是同一个东西：换卡从新卡的初始值开始，关系与场景一并清空。 */
    @Test
    fun switchingRoleCard_startsFromNewCardInitialValues() = runBlocking {
        seedConversation()
        val cardA = saveCard("A", initialValue = 10)
        val cardB = saveCard("B", initialValue = 30)
        bind(cardA)
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), scene = "A 的场景")

        bind(cardB)

        val panel = panel()
        assertEquals(listOf(RpMetric(ROLE_WIRE, 30, 0, 100)), panel?.metrics)
        assertEquals("", panel?.scene)
    }

    /** 换卡时旧卡的历史快照作废，但内心独白与剧情建议仍可展示，不能跟着删。 */
    @Test
    fun switchingRoleCard_clearsOldCardStateButKeepsNarrativeInHistory() = runBlocking {
        seedConversation()
        val cardA = saveCard("A", initialValue = 10)
        val cardB = saveCard("B", initialValue = 30)
        bind(cardA)
        seedAssistantWithSnapshot("""[{"name":"$ROLE_WIRE","value":50,"min":0,"max":100}]""")

        bind(cardB)

        val row = dao.observeMessageRpMetadata(CONVERSATION_ID).first().single()
        assertEquals("[]", row.metricsJson)
        assertEquals("", row.scene)
        assertEquals("仍可展示的内心", row.innerThought)
        assertEquals("[\"继续\"]", row.optionsJson)
    }

    /** 暂停不等于重置：面板与历史快照原样保留，重新开启后接着用。 */
    @Test
    fun pausingAndResumingRp_keepsPanelAndSnapshots() = runBlocking {
        seedConversation()
        val card = saveCard("A", initialValue = 10)
        bind(card)
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), scene = "暂停前")
        val snapshot = """[{"name":"$ROLE_WIRE","value":50,"min":0,"max":100}]"""
        seedAssistantWithSnapshot(snapshot)

        bind(card, isRpEnabled = false)
        assertEquals(77, panel()?.metrics?.single()?.value)

        bind(card, isRpEnabled = true)
        assertEquals(77, panel()?.metrics?.single()?.value)
        assertEquals("暂停前", panel()?.scene)
        assertEquals(snapshot, dao.observeMessageRpMetadata(CONVERSATION_ID).first().single().metricsJson)
    }

    /** 暂停中换卡：旧卡的数值不得被带进新卡。 */
    @Test
    fun switchingRoleCardWhilePaused_doesNotCarryOldValues() = runBlocking {
        seedConversation()
        val cardA = saveCard("A", initialValue = 10)
        val cardB = saveCard("B", initialValue = 30)
        bind(cardA)
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), scene = "A 的场景")

        bind(cardA, isRpEnabled = false)
        bind(cardB, isRpEnabled = false)
        bind(cardB, isRpEnabled = true)

        assertEquals(listOf(RpMetric(ROLE_WIRE, 30, 0, 100)), panel()?.metrics)
    }

    /** 与数值无关的设置不动面板：写一次就会刷新时间戳，让在途请求的提交守卫误判面板被改过。 */
    @Test
    fun savingUnrelatedSettings_leavesPanelRowUntouched() = runBlocking {
        seedConversation()
        val card = saveCard("A", initialValue = 10)
        bind(card)
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), scene = "场景")
        val before = dao.getRpStatusPanel(CONVERSATION_ID)

        repository.updateConversationRpSettings(
            conversationId = CONVERSATION_ID,
            isRpEnabled = true,
            roleCardId = card,
            worldSettingId = null,
            writingStyleId = null,
            protagonistProfileId = null,
            narrativeMinChars = 300,
            narrativePerspective = null,
            plotSuggestionPerspective = null,
            rpMarkerMode = null,
        )

        assertEquals(before, dao.getRpStatusPanel(CONVERSATION_ID))
    }

    /**
     * 角色卡没有状态条时，面板里是模型自报的状态条。只保存世界观（不换卡）不得把它们清掉。
     */
    @Test
    fun bindingGeneratedWorldSetting_keepsSelfDescribedMetricsOfCardWithoutDefinitions() = runBlocking {
        seedConversation()
        val card = saveCard("A", initialValue = 10, withMetrics = false)
        bind(card)
        setPanel(RpMetric("模型自报", 77, 0, 100), scene = "场景")
        val before = dao.getRpStatusPanel(CONVERSATION_ID)

        repository.saveGeneratedSettings(
            roleCard = null,
            worldSetting = WorldSettingDraft(title = "新世界观"),
            writingStyle = null,
            bindToConversationId = CONVERSATION_ID,
        )

        assertEquals(before, dao.getRpStatusPanel(CONVERSATION_ID))
    }

    // ---------- 定义快照：改档案不影响已绑定的会话（R6） ----------

    @Test
    fun bindingRoleCard_snapshotsItsNumericEntries() = runBlocking {
        seedConversation()
        val card = saveCard("A", initialValue = 10)

        bind(card)

        val item = settings().metricSnapshot.single()
        assertEquals(METRIC_ID, item.entryId)
        assertEquals(ROLE_WIRE, item.wireName)
        assertEquals(METRIC_NAME, item.displayName)
    }

    @Test
    fun editingRoleCardMetrics_doesNotTouchBoundConversation() = runBlocking {
        seedConversation()
        val card = saveCard("A", initialValue = 10)
        bind(card)
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), scene = "场景")
        val snapshot = """[{"name":"$ROLE_WIRE","value":50,"min":0,"max":100}]"""
        seedAssistantWithSnapshot(snapshot)
        val panelBefore = dao.getRpStatusPanel(CONVERSATION_ID)
        val settingsBefore = dao.getConversationRpSettings(CONVERSATION_ID)

        repository.saveRoleCard(card, cardDraft("A", metricName = "亲密度", initialValue = 10))

        assertEquals(settingsBefore, dao.getConversationRpSettings(CONVERSATION_ID))
        assertEquals(panelBefore, dao.getRpStatusPanel(CONVERSATION_ID))
        assertEquals(snapshot, dao.observeMessageRpMetadata(CONVERSATION_ID).first().single().metricsJson)
    }

    /** 显式同步：按 entryId 找回原值，改名不归零；面板与历史快照都迁移。 */
    @Test
    fun syncAfterRename_keepsValuesByIdentity() = runBlocking {
        seedConversation()
        val card = saveCard("A", initialValue = 10)
        bind(card)
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), scene = "场景")
        seedAssistantWithSnapshot("""[{"name":"$ROLE_WIRE","value":50,"min":0,"max":100}]""")
        repository.saveRoleCard(card, cardDraft("A", metricName = "亲密度", initialValue = 10))

        assertTrue(repository.syncMetricDefinitionsFromProfiles(CONVERSATION_ID))

        val renamed = "角色::亲密度"
        assertEquals(renamed, settings().metricSnapshot.single().wireName)
        assertEquals(listOf(RpMetric(renamed, 77, 0, 100)), panel()?.metrics)
        assertEquals("场景", panel()?.scene)
        val history = dao.observeMessageRpMetadata(CONVERSATION_ID).first().single()
        assertEquals(listOf(RpMetric(renamed, 50, 0, 100)), history.toDomain().statusPanelSnapshot?.metrics)
    }

    @Test
    fun syncWhilePaused_alignsPanelToo() = runBlocking {
        seedConversation()
        val card = saveCard("A", initialValue = 10)
        bind(card)
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), scene = "场景")
        bind(card, isRpEnabled = false)
        repository.saveRoleCard(card, cardDraft("A", metricName = "亲密度", initialValue = 10))

        repository.syncMetricDefinitionsFromProfiles(CONVERSATION_ID)

        assertEquals(listOf(RpMetric("角色::亲密度", 77, 0, 100)), panel()?.metrics)
    }

    @Test
    fun syncWithoutChanges_writesNothing() = runBlocking {
        seedConversation()
        val card = saveCard("A", initialValue = 10)
        bind(card)
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), scene = "场景")
        val settingsBefore = dao.getConversationRpSettings(CONVERSATION_ID)
        // 只改简介，数值定义没变。
        repository.saveRoleCard(card, cardDraft("A", description = "改过的简介", initialValue = 10))

        assertFalse(repository.syncMetricDefinitionsFromProfiles(CONVERSATION_ID))
        assertEquals(settingsBefore, dao.getConversationRpSettings(CONVERSATION_ID))
    }

    @Test
    fun syncRoleOnly_doesNotRefreshFrozenProtagonistDefinitionOrValues() = runBlocking {
        seedConversation()
        val card = saveCard("A", initialValue = 10)
        val profile = saveProfile("主角", staminaInitial = 20)
        bind(card, protagonist = profile)
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), RpMetric(PROTAGONIST_WIRE, 80, 0, 100), scene = "场景")
        seedAssistantWithSnapshot("""[{"name":"$ROLE_WIRE","value":50,"min":0,"max":100},{"name":"$PROTAGONIST_WIRE","value":60,"min":0,"max":100}]""")
        val frozen = settings().metricSnapshot.single { it.ownerType == RpEntryOwner.PROTAGONIST }
        val oldProfile = dao.getProtagonistProfile(profile)!!.toDomain()
        repository.saveProtagonistProfile(profile, ProtagonistProfileDraft(
            name = oldProfile.name,
            entries = oldProfile.entries.map { if (it is RpEntry.Numeric) it.copy(maxValue = 30) else it },
        ))
        repository.saveRoleCard(card, cardDraft("A", metricName = "亲密度", initialValue = 10))

        assertTrue(repository.syncMetricDefinitionsFromProfiles(CONVERSATION_ID, setOf(RpEntryOwner.ROLE_CARD)))

        assertEquals(frozen, settings().metricSnapshot.single { it.ownerType == RpEntryOwner.PROTAGONIST })
        assertEquals(RpMetric(PROTAGONIST_WIRE, 80, 0, 100), panel()!!.metrics.single { it.name == PROTAGONIST_WIRE })
        val history = dao.getMessageRpMetadataForMessage("assistant")!!.toDomain().statusPanelSnapshot!!
        assertEquals(RpMetric(PROTAGONIST_WIRE, 60, 0, 100), history.metrics.single { it.name == PROTAGONIST_WIRE })
        assertEquals(50, history.metrics.single { it.name == "角色::亲密度" }.value)
    }

    @Test
    fun manualCorrection_changesOnlyTargetAndCreatesLatestAssistantCheckpoint() = runBlocking {
        seedConversation()
        val card = saveCard("A", initialValue = 10)
        val profile = saveProfile("主角", staminaInitial = 20)
        bind(card, protagonist = profile)
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), RpMetric(PROTAGONIST_WIRE, 80, 0, 100), scene = "场景")
        seedAssistantWithSnapshot("""[{"name":"$ROLE_WIRE","value":50,"min":0,"max":100}]""")
        val before = dao.getMessageRpMetadataForMessage("assistant")!!
        val definition = settings().metricSnapshot.single { it.ownerType == RpEntryOwner.ROLE_CARD }

        val corrected = repository.updateMetricValue(CONVERSATION_ID, definition, 3)

        assertEquals(listOf(RpMetric(ROLE_WIRE, 3, 0, 100), RpMetric(PROTAGONIST_WIRE, 80, 0, 100)), corrected.metrics)
        assertEquals("场景", corrected.scene)
        val checkpoint = dao.getMessageRpMetadataForMessage("assistant")!!
        assertEquals(corrected.metrics, checkpoint.toDomain().statusPanelSnapshot!!.metrics)
        assertEquals(before.innerThought, checkpoint.innerThought)
        assertEquals(before.optionsJson, checkpoint.optionsJson)
    }

    @Test
    fun manualCorrection_withoutMetadata_createsCheckpointAndLeavesOlderHistoryUntouched() = runBlocking {
        seedConversation()
        val card = saveCard("A", initialValue = 10)
        bind(card)
        seedAssistantWithSnapshot("""[{"name":"$ROLE_WIRE","value":50,"min":0,"max":100}]""")
        val older = dao.getMessageRpMetadataForMessage("assistant")!!
        val latest = dao.getMessage("assistant")!!.copy(id = "latest-assistant", createdAtMillis = 20, updatedAtMillis = 20)
        dao.upsertMessage(latest)
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), scene = "场景")

        repository.updateMetricValue(CONVERSATION_ID, settings().metricSnapshot.single(), 3)

        assertEquals(older, dao.getMessageRpMetadataForMessage("assistant"))
        val checkpoint = dao.getMessageRpMetadataForMessage(latest.id)!!
        assertEquals(3, checkpoint.toDomain().statusPanelSnapshot!!.metrics.single().value)
        assertEquals("[]", checkpoint.optionsJson)
        assertEquals("", checkpoint.innerThought)
    }

    @Test
    fun manualCorrection_rejectsStaleDefinitionAndOutOfRangeWithoutPartialWrites() = runBlocking {
        seedConversation()
        val card = saveCard("A", initialValue = 10)
        bind(card)
        val stale = settings().metricSnapshot.single()
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), scene = "场景")
        val originalPanel = dao.getRpStatusPanel(CONVERSATION_ID)
        assertTrue(runCatching { repository.updateMetricValue(CONVERSATION_ID, stale, 101) }.isFailure)
        assertEquals(originalPanel, dao.getRpStatusPanel(CONVERSATION_ID))
        repository.saveRoleCard(card, cardDraft("A", metricName = "亲密度", initialValue = 10))
        repository.syncMetricDefinitionsFromProfiles(CONVERSATION_ID, setOf(RpEntryOwner.ROLE_CARD))
        val syncedPanel = dao.getRpStatusPanel(CONVERSATION_ID)
        assertTrue(runCatching { repository.updateMetricValue(CONVERSATION_ID, stale, 3) }.isFailure)
        assertEquals(syncedPanel, dao.getRpStatusPanel(CONVERSATION_ID))
        assertTrue(dao.getMessageRpMetadata(CONVERSATION_ID).isEmpty())
    }

    @Test
    fun lateEnrichment_afterCorrectionAndNextReply_keepsOwnCorrectedCheckpoint() = runBlocking {
        assertLateEnrichmentProtection(captureAfterCorrection = false)
    }

    @Test
    fun lateEnrichment_correctionBeforeSchedulingSnapshotAndThenNextReply_keepsCorrection() = runBlocking {
        assertLateEnrichmentProtection(captureAfterCorrection = true)
    }

    private suspend fun assertLateEnrichmentProtection(captureAfterCorrection: Boolean) {
        seedConversation()
        val card = saveCard("A", initialValue = 10)
        bind(card)
        seedAssistantWithSnapshot("""[{"name":"$ROLE_WIRE","value":50,"min":0,"max":100}]""")
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), scene = "场景")
        val expectedMessage = dao.getMessage("assistant")!!
        val expectedSettings = dao.getConversationRpSettings(CONVERSATION_ID)
        val expectedRole = dao.getRoleCard(card)
        val expectedPanel = dao.getRpStatusPanel(CONVERSATION_ID)
        val originalMetadata = dao.getMessageRpMetadataForMessage("assistant")!!
        repository.updateMetricValue(CONVERSATION_ID, settings().metricSnapshot.single(), 3)
        val expectedMetadata = if (captureAfterCorrection) dao.getMessageRpMetadataForMessage("assistant")!! else originalMetadata
        dao.upsertMessage(expectedMessage.copy(id = "next-assistant", createdAtMillis = 30, updatedAtMillis = 30))
        setPanel(RpMetric(ROLE_WIRE, 90, 0, 100), scene = "下一轮场景")
        val newerPanel = dao.getRpStatusPanel(CONVERSATION_ID)

        assertTrue(dao.commitRpEnrichment(
            expectedMessage = expectedMessage,
            expectedRpSettings = expectedSettings,
            expectedRoleCard = expectedRole,
            expectedStatusPanel = expectedPanel,
            metadata = originalMetadata.copy(innerThought = "迟到内心", scene = "旧补写场景", updatedAtMillis = 50),
            statusPanel = expectedPanel,
            missingStateMessage = null,
            updatedAtMillis = 50,
            expectedMetadata = expectedMetadata,
        ))

        assertEquals(newerPanel, dao.getRpStatusPanel(CONVERSATION_ID))
        val checkpoint = dao.getMessageRpMetadataForMessage("assistant")!!
        assertEquals(3, checkpoint.toDomain().statusPanelSnapshot!!.metrics.single().value)
        assertEquals("场景", checkpoint.scene)
        assertEquals("迟到内心", checkpoint.innerThought)
    }

    // ---------- 主角组 ----------

    /** 换主角只重置主角组：角色组数值、关系与场景都不动。 */
    @Test
    fun switchingProtagonist_resetsOnlyProtagonistGroup() = runBlocking {
        seedConversation()
        val card = saveCard("A", initialValue = 10)
        val first = saveProfile("沈昭", staminaInitial = 60)
        val second = saveProfile("陆行", staminaInitial = 90)
        bind(card, protagonist = first)
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), RpMetric(PROTAGONIST_WIRE, 20, 0, 100), scene = "场景")

        bind(card, protagonist = second)

        val panel = panel()
        assertEquals(
            listOf(RpMetric(ROLE_WIRE, 77, 0, 100), RpMetric(PROTAGONIST_WIRE, 90, 0, 100)),
            panel?.metrics,
        )
        assertEquals("场景", panel?.scene)
    }

    /**
     * 删除角色卡只清角色组，**不得连带清主角组**（§4）。
     * 还绑着主角时 RP 保持开启（RP-2f：主角单独也能驱动状态链路）。
     */
    @Test
    fun deletingRoleCard_keepsProtagonistGroup() = runBlocking {
        seedConversation()
        val card = saveCard("A", initialValue = 10)
        val profile = saveProfile("沈昭", staminaInitial = 60)
        bind(card, protagonist = profile)
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), RpMetric(PROTAGONIST_WIRE, 20, 0, 100), scene = "场景")

        repository.deleteRoleCard(card)

        val settings = settings()
        assertNull(settings.roleCardId)
        assertTrue(settings.isRpEnabled)
        assertEquals(profile, settings.protagonistProfileId)
        assertEquals(listOf(PROTAGONIST_WIRE), settings.metricSnapshot.map { it.wireName })
        assertEquals(listOf(RpMetric(PROTAGONIST_WIRE, 20, 0, 100)), panel()?.metrics)
        assertEquals("", panel()?.scene)
    }

    @Test
    fun deletingRoleCardWithoutProtagonist_removesPanelButKeepsHistoryRows() = runBlocking {
        seedConversation()
        val card = saveCard("A", initialValue = 10)
        bind(card)
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), scene = "场景")
        seedAssistantWithSnapshot("""[{"name":"$ROLE_WIRE","value":50,"min":0,"max":100}]""")

        repository.deleteRoleCard(card)

        assertNull(dao.getRpStatusPanel(CONVERSATION_ID))
        assertNull(dao.getRoleCard(card))
        assertEquals(1, dao.observeMessageRpMetadata(CONVERSATION_ID).first().size)
    }

    @Test
    fun deletingProtagonistProfile_keepsRoleGroupAndStory() = runBlocking {
        seedConversation()
        val card = saveCard("A", initialValue = 10)
        val profile = saveProfile("沈昭", staminaInitial = 60)
        bind(card, protagonist = profile)
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), RpMetric(PROTAGONIST_WIRE, 20, 0, 100), scene = "场景")

        repository.deleteProtagonistProfile(profile)

        val settings = settings()
        assertNull(settings.protagonistProfileId)
        assertTrue(settings.isRpEnabled)
        assertEquals(listOf(RpMetric(ROLE_WIRE, 77, 0, 100)), panel()?.metrics)
        assertEquals("场景", panel()?.scene)
    }

    // ---------- 设定生成工具 ----------

    /**
     * RP-2g（§11.2）：工具草稿里的旧 metric 定义保存为 `RpEntry.Numeric`，id 不变；
     * 绑定时按它拍快照，wireName 带「角色::」前缀，面板从初始值开始。
     */
    @Test
    fun generatedRoleCardMetrics_areSavedAsNumericEntriesAndSnapshotted() = runBlocking {
        seedConversation()

        val saved = repository.saveGeneratedSettings(
            roleCard = cardDraft("生成的角色", initialValue = 30),
            worldSetting = null,
            writingStyle = null,
            bindToConversationId = CONVERSATION_ID,
        )

        val card = dao.getRoleCard(saved.roleCard!!.id)!!.toDomain()
        assertEquals(listOf(RpEntry.Numeric(METRIC_ID, METRIC_NAME, 0, 100, 30)), card.entries)
        val snapshot = settings().metricSnapshot.single()
        assertEquals(METRIC_ID, snapshot.entryId)
        assertEquals(ROLE_WIRE, snapshot.wireName)
        assertEquals(listOf(RpMetric(ROLE_WIRE, 30, 0, 100)), panel()?.metrics)
    }

    /** 工具建的主角成为独立档案并绑定；旧三字段不再写入，角色卡绑定与面板不动。 */
    @Test
    fun savingProtagonistDraft_createsProfileAndBindsIt() = runBlocking {
        seedConversation()
        val card = saveCard("A", initialValue = 10)
        bind(card)
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), scene = "场景")
        val before = dao.getRpStatusPanel(CONVERSATION_ID)

        val saved = repository.saveGeneratedSettings(
            roleCard = null,
            worldSetting = null,
            writingStyle = null,
            protagonist = ProtagonistDraft(
                name = "沈昭",
                description = "外地来的记者",
                initialState = "刚下火车",
            ),
            bindToConversationId = CONVERSATION_ID,
        )

        val profile = saved.protagonistProfile!!
        assertEquals("沈昭", profile.name)
        assertEquals("外地来的记者", profile.description)
        assertEquals(listOf("刚下火车"), profile.textEntries.map { it.value })
        val settings = settings()
        assertEquals(profile.id, settings.protagonistProfileId)
        assertEquals(card, settings.roleCardId)
        assertEquals("", settings.protagonistName)
        assertEquals(before, dao.getRpStatusPanel(CONVERSATION_ID))
    }

    /** 没有会话就没有落点：必须整笔失败，不能留下孤儿档案。 */
    @Test
    fun savingProtagonistWithoutConversation_fails() = runBlocking {
        seedConversation()

        val failure = runCatching {
            repository.saveGeneratedSettings(
                roleCard = null,
                worldSetting = null,
                writingStyle = null,
                protagonist = ProtagonistDraft(name = "沈昭"),
                bindToConversationId = null,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(dao.observeProtagonistProfiles().first().isEmpty())
    }

    // ---------- 词条校验（§3.3） ----------

    @Test
    fun numericEntryNameWithSeparator_isRejected() = runBlocking {
        val failure = runCatching {
            repository.saveRoleCard(null, cardDraft("A", metricName = "主角::体力", initialValue = 10))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun duplicateNumericEntryNames_areRejected() = runBlocking {
        val failure = runCatching {
            repository.saveProtagonistProfile(
                null,
                ProtagonistProfileDraft(
                    name = "沈昭",
                    entries = listOf(RpEntry.Numeric("", "体力"), RpEntry.Numeric("", "体力")),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    /** 文本词条不进状态命名空间，名字不受分隔符限制。 */
    @Test
    fun textEntryNameMayContainSeparator() = runBlocking {
        val saved = repository.saveProtagonistProfile(
            null,
            ProtagonistProfileDraft(name = "沈昭", entries = listOf(RpEntry.Text("", "备注::一", "值"))),
        )

        assertEquals("备注::一", saved.textEntries.single().name)
    }

    // ---------- 主角档案另存与共用数（RP-3b） ----------

    /**
     * 另存为本会话专用：本会话改绑新档案，主角数值按 entryId 保留、新增的条用初始值；
     * 原档案与另一个会话都不动。这不是换主角，不得重置主角组（§4）。
     */
    @Test
    fun forkingProtagonist_rebindsOnlyThisConversationAndKeepsValues() = runBlocking {
        seedConversation()
        dao.upsertConversation(ConversationEntity(OTHER_CONVERSATION_ID, "另一个会话", 1, 1))
        val source = saveProfile("沈昭", staminaInitial = 60)
        bind(roleCardId = null, protagonist = source)
        bindOther(source)
        setPanel(RpMetric(PROTAGONIST_WIRE, 20, 0, 100), scene = "场景")

        val forked = repository.forkProtagonistProfileForConversation(
            CONVERSATION_ID,
            source,
            ProtagonistProfileDraft(
                name = "沈昭（本会话）",
                entries = listOf(
                    RpEntry.Numeric("stamina-沈昭", STAMINA, 0, 100, 60),
                    RpEntry.Numeric("mood", "心情", 0, 100, 50),
                ),
            ),
        )

        assertTrue(forked.id != source)
        assertEquals(forked.id, settings().protagonistProfileId)
        assertEquals(source, dao.getConversationRpSettings(OTHER_CONVERSATION_ID)!!.protagonistProfileId)
        assertEquals("沈昭", dao.getProtagonistProfile(source)!!.name)
        val panel = panel()
        assertEquals(
            listOf(RpMetric(PROTAGONIST_WIRE, 20, 0, 100), RpMetric("主角::心情", 50, 0, 100)),
            panel?.metrics,
        )
        assertEquals("场景", panel?.scene)
    }

    /** 编辑界面过期（本会话已不再绑源档案）时拒绝另存，也不留下新档案。 */
    @Test
    fun forkingProtagonist_whenNoLongerBound_failsWithoutCreatingProfile() = runBlocking {
        seedConversation()
        val bound = saveProfile("沈昭", staminaInitial = 60)
        val other = saveProfile("陆行", staminaInitial = 90)
        bind(roleCardId = null, protagonist = bound)

        val failure = runCatching {
            repository.forkProtagonistProfileForConversation(
                CONVERSATION_ID,
                other,
                ProtagonistProfileDraft(name = "陆行（副本）"),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(2, dao.observeProtagonistProfiles().first().size)
        assertEquals(bound, settings().protagonistProfileId)
    }

    /** 共用数包含暂停 RP 的会话（绑定还在）；没人用的档案不在表里。 */
    @Test
    fun protagonistUsage_countsBoundConversationsIncludingPaused() = runBlocking {
        seedConversation()
        dao.upsertConversation(ConversationEntity(OTHER_CONVERSATION_ID, "另一个会话", 1, 1))
        val shared = saveProfile("沈昭", staminaInitial = 60)
        val unused = saveProfile("陆行", staminaInitial = 90)
        bind(roleCardId = null, isRpEnabled = false, protagonist = shared)
        bindOther(shared)

        val usage = repository.observeProtagonistProfileUsage().first()

        assertEquals(2, usage[shared])
        assertFalse(unused in usage)
    }

    /** 名字允许为空（用户 2026-09-23 选定），但整份档案不能什么都没有。 */
    @Test
    fun protagonistProfile_allowsBlankNameButNotEmptyProfile() = runBlocking {
        val nameless = repository.saveProtagonistProfile(
            null,
            ProtagonistProfileDraft(name = " ", description = "外地来的记者"),
        )
        assertEquals("", nameless.name)

        val failure = runCatching {
            repository.saveProtagonistProfile(
                null,
                ProtagonistProfileDraft(name = "", entries = listOf(RpEntry.Text("", "备注", " "))),
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    // ---------- 配角 ----------

    @Test
    fun supportingCharacters_keepOrderAndIgnoreDuplicates() = runBlocking {
        seedConversation()
        val a = repository.saveSupportingCharacter(null, supporting("甲")).id
        val b = repository.saveSupportingCharacter(null, supporting("乙")).id

        repository.setConversationSupportingCharacters(CONVERSATION_ID, listOf(b, a, b, "已删除的配角"))

        val names = repository.observeConversationSupportingCharacters(CONVERSATION_ID).first().map { it.name }
        assertEquals(listOf("乙", "甲"), names)
    }

    @Test
    fun deletingSupportingCharacter_unlinksItFromConversations() = runBlocking {
        seedConversation()
        val a = repository.saveSupportingCharacter(null, supporting("甲")).id
        repository.setConversationSupportingCharacters(CONVERSATION_ID, listOf(a))

        repository.deleteSupportingCharacter(a)

        assertTrue(repository.observeConversationSupportingCharacters(CONVERSATION_ID).first().isEmpty())
    }

    /** 配角共用数按勾选的会话计；删除配角后关联随外键一起消失，共用数归零。 */
    @Test
    fun supportingUsage_countsSelectingConversations() = runBlocking {
        seedConversation()
        dao.upsertConversation(ConversationEntity(OTHER_CONVERSATION_ID, "另一个会话", 1, 1))
        val shared = repository.saveSupportingCharacter(null, supporting("老周")).id
        val single = repository.saveSupportingCharacter(null, supporting("阿绿")).id
        repository.setConversationSupportingCharacters(CONVERSATION_ID, listOf(shared, single))
        repository.setConversationSupportingCharacters(OTHER_CONVERSATION_ID, listOf(shared))

        assertEquals(mapOf(shared to 2, single to 1), repository.observeSupportingCharacterUsage().first())

        repository.deleteSupportingCharacter(shared)
        assertEquals(mapOf(single to 1), repository.observeSupportingCharacterUsage().first())
    }

    // ---------- 设定工具：删除与配角草稿 ----------

    /** 一次删多类：各走自己的转换规则（删主角只清主角组），已不存在的项跳过并如实计数。 */
    @Test
    fun deleteSettings_deletesEachKindAndSkipsMissing() = runBlocking {
        seedConversation()
        val card = saveCard("A", initialValue = 10)
        val profile = saveProfile("沈昭", staminaInitial = 60)
        val character = repository.saveSupportingCharacter(null, supporting("老周")).id
        bind(card, protagonist = profile)
        repository.setConversationSupportingCharacters(CONVERSATION_ID, listOf(character))
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), RpMetric(PROTAGONIST_WIRE, 20, 0, 100), scene = "场景")

        val deleted = repository.deleteSettings(
            listOf(
                com.muxuechat.domain.model.RpSettingRef(com.muxuechat.domain.model.RpSettingKind.PROTAGONIST, profile),
                com.muxuechat.domain.model.RpSettingRef(com.muxuechat.domain.model.RpSettingKind.SUPPORTING_CHARACTER, character),
                com.muxuechat.domain.model.RpSettingRef(com.muxuechat.domain.model.RpSettingKind.WORLD_SETTING, "missing"),
            ),
        )

        assertEquals(2, deleted)
        assertNull(dao.getProtagonistProfile(profile))
        assertNull(dao.getSupportingCharacter(character))
        assertNull(settings().protagonistProfileId)
        assertEquals(card, settings().roleCardId)
        assertEquals(listOf(RpMetric(ROLE_WIRE, 77, 0, 100)), panel()?.metrics)
        assertTrue(dao.getConversationSupportingCharacters(CONVERSATION_ID).isEmpty())
    }

    /** 工具生成的配角：勾选时追加到已选末尾，不打乱已有顺序。 */
    @Test
    fun savingGeneratedSupportingCharacter_appendsToSelection() = runBlocking {
        seedConversation()
        val existing = repository.saveSupportingCharacter(null, supporting("阿绿")).id
        repository.setConversationSupportingCharacters(CONVERSATION_ID, listOf(existing))

        val saved = repository.saveGeneratedSettings(
            roleCard = null,
            worldSetting = null,
            writingStyle = null,
            supportingCharacter = supporting("老周"),
            bindToConversationId = CONVERSATION_ID,
        )

        assertTrue(saved.boundToConversation)
        assertEquals(
            listOf(existing, saved.supportingCharacter!!.id),
            dao.getConversationSupportingCharacters(CONVERSATION_ID).map { it.id },
        )
    }

    // ---------- 辅助 ----------

    // ---------- AI 修改请求（update_rp_setting） ----------

    /** 状态条定义被 AI 修改：绑定它的会话回到新初始值，历史快照里的旧值移除。 */
    @Test
    fun applyingUpdate_resetsChangedMetricToNewInitialValue() = runBlocking {
        seedConversation()
        val cardId = saveCard("A", initialValue = 10)
        bind(cardId)
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), scene = "场景")
        seedAssistantWithSnapshot("""[{"name":"$ROLE_WIRE","value":50,"min":0,"max":100}]""")
        val card = dao.getRoleCard(cardId)!!.toDomain()

        val reset = repository.applySettingsUpdate(
            target = updateTarget(cardId, card.updatedAtMillis),
            roleCard = cardDraft("A", initialValue = 30),
            worldSetting = null,
            writingStyle = null,
            protagonistProfile = null,
            supportingCharacter = null,
        )

        assertEquals(1, reset)
        assertEquals(listOf(RpMetric(ROLE_WIRE, 30, 0, 100)), panel()?.metrics)
        assertEquals("场景", panel()?.scene)
        assertEquals(30, settings().metricSnapshot.single().initialValue)
        assertEquals("[]", dao.observeMessageRpMetadata(CONVERSATION_ID).first().single().metricsJson)
    }

    /** 只改文字不动数值；生成请求后设定又被改过时拒绝覆盖。 */
    @Test
    fun applyingUpdate_textOnlyKeepsValuesAndStaleRequestIsRejected() = runBlocking {
        seedConversation()
        val cardId = saveCard("A", initialValue = 10)
        bind(cardId)
        setPanel(RpMetric(ROLE_WIRE, 77, 0, 100), scene = "")
        val base = dao.getRoleCard(cardId)!!.updatedAtMillis

        val reset = repository.applySettingsUpdate(
            target = updateTarget(cardId, base),
            roleCard = cardDraft("A", description = "新简介", initialValue = 10),
            worldSetting = null,
            writingStyle = null,
            protagonistProfile = null,
            supportingCharacter = null,
        )
        assertEquals(0, reset)
        assertEquals(77, panel()?.metrics?.single()?.value)
        assertEquals("新简介", dao.getRoleCard(cardId)!!.description)

        val stale = runCatching {
            repository.applySettingsUpdate(
                target = updateTarget(cardId, base),
                roleCard = cardDraft("A", description = "再改", initialValue = 10),
                worldSetting = null,
                writingStyle = null,
                protagonistProfile = null,
                supportingCharacter = null,
            )
        }
        assertTrue(stale.isFailure)
        assertEquals("新简介", dao.getRoleCard(cardId)!!.description)
    }

    private fun updateTarget(cardId: String, baseUpdatedAt: Long) = com.muxuechat.domain.tool.SettingsUpdateTarget(
        ref = com.muxuechat.domain.model.RpSettingRef(com.muxuechat.domain.model.RpSettingKind.ROLE_CARD, cardId),
        name = "A",
        usageCount = 1,
        baseUpdatedAtMillis = baseUpdatedAt,
        changes = listOf("简介"),
    )

    private suspend fun seedConversation() {
        dao.upsertConversation(ConversationEntity(CONVERSATION_ID, "会话", 1, 1))
    }

    private suspend fun saveCard(name: String, initialValue: Int, withMetrics: Boolean = true): String =
        repository.saveRoleCard(null, cardDraft(name, initialValue = initialValue, withMetrics = withMetrics)).id

    private fun cardDraft(
        name: String,
        metricName: String = METRIC_NAME,
        description: String = "",
        initialValue: Int,
        withMetrics: Boolean = true,
    ) = RoleCardDraft(
        name = name,
        description = description,
        metricDefinitions = if (withMetrics) {
            listOf(RpMetricDefinition(METRIC_ID, metricName, 0, 100, initialValue))
        } else {
            emptyList()
        },
    )

    private suspend fun saveProfile(name: String, staminaInitial: Int): String =
        repository.saveProtagonistProfile(
            null,
            ProtagonistProfileDraft(
                name = name,
                entries = listOf(RpEntry.Numeric("stamina-$name", STAMINA, 0, 100, staminaInitial)),
            ),
        ).id

    private fun supporting(name: String) = com.muxuechat.domain.model.SupportingCharacterDraft(
        name = name,
        entries = listOf(RpEntry.Text("", "身份", "邻居")),
    )

    private suspend fun bind(roleCardId: String?, isRpEnabled: Boolean = true, protagonist: String? = null) {
        repository.updateConversationRpSettings(
            conversationId = CONVERSATION_ID,
            isRpEnabled = isRpEnabled,
            roleCardId = roleCardId,
            worldSettingId = null,
            writingStyleId = null,
            protagonistProfileId = protagonist,
            narrativeMinChars = null,
            narrativePerspective = null,
            plotSuggestionPerspective = null,
            rpMarkerMode = null,
        )
    }

    private suspend fun bindOther(protagonistProfileId: String) {
        repository.updateConversationRpSettings(
            conversationId = OTHER_CONVERSATION_ID,
            isRpEnabled = true,
            roleCardId = null,
            worldSettingId = null,
            writingStyleId = null,
            protagonistProfileId = protagonistProfileId,
        )
    }

    private suspend fun settings() = dao.getConversationRpSettings(CONVERSATION_ID)!!.toDomain()

    private suspend fun panel() = dao.getRpStatusPanel(CONVERSATION_ID)?.toDomain()

    private suspend fun setPanel(vararg metrics: RpMetric, scene: String) {
        dao.upsertRpStatusPanel(
            RpStatusPanel(
                conversationId = CONVERSATION_ID,
                metrics = metrics.toList(),
                relationship = "关系",
                scene = scene,
                rawJson = "",
                updatedAtMillis = 50,
            ).toEntity(),
        )
    }

    private suspend fun seedAssistantWithSnapshot(metricsJson: String) {
        dao.upsertMessage(
            MessageEntity(
                id = "assistant",
                conversationId = CONVERSATION_ID,
                role = ChatRole.ASSISTANT,
                content = "正文",
                reasoningContent = null,
                status = MessageStatus.COMPLETED,
                errorMessage = null,
                createdAtMillis = 10,
                updatedAtMillis = 10,
            ),
        )
        dao.upsertMessageRpMetadata(
            MessageRpMetadataEntity(
                messageId = "assistant",
                conversationId = CONVERSATION_ID,
                optionsJson = "[\"继续\"]",
                innerThought = "仍可展示的内心",
                metricsJson = metricsJson,
                relationship = "关系",
                scene = "场景",
                rawJson = "",
                createdAtMillis = 10,
                updatedAtMillis = 10,
            ),
        )
    }

    private companion object {
        const val CONVERSATION_ID = "conversation"
        const val OTHER_CONVERSATION_ID = "other-conversation"
        const val METRIC_ID = "metric-affection"
        const val METRIC_NAME = "好感度"
        const val ROLE_WIRE = "角色::好感度"
        const val STAMINA = "体力"
        const val PROTAGONIST_WIRE = "主角::体力"
    }
}
