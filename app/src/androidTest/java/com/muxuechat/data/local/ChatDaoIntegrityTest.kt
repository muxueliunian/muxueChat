package com.muxuechat.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muxuechat.data.repository.RpRepositoryImpl
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.ConversationRpSettings
import com.muxuechat.domain.model.MessageStatus
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RoleCardDraft
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
class ChatDaoIntegrityTest {
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
    fun upsert_existingParents_preservesAllForeignKeyChildren() = runBlocking {
        val conversation = conversation("conversation-a")
        val message = message("message-a", conversation.id)
        val roleCard = roleCard("role-a")
        val worldSetting = worldSetting("world-a")
        val writingStyle = writingStyle("style-a")

        dao.upsertConversation(conversation)
        dao.upsertMessage(message)
        dao.upsertRoleCard(roleCard)
        dao.upsertWorldSetting(worldSetting)
        dao.upsertWritingStyleProfile(writingStyle)
        dao.upsertConversationRpSettings(
            rpSettings(
                conversationId = conversation.id,
                roleCardId = roleCard.id,
                worldSettingId = worldSetting.id,
                writingStyleId = writingStyle.id,
            ),
        )
        dao.upsertRpStatusPanel(statusPanel(conversation.id))
        dao.upsertConversationContext(
            ConversationContextEntity(
                conversationId = conversation.id,
                summary = "摘要",
                coveredMessageCount = 1,
                updatedAtMillis = 10,
            ),
        )
        dao.upsertMessageRpMetadata(messageMetadata(message.id, conversation.id))

        dao.upsertConversation(conversation.copy(title = "更新标题", updatedAtMillis = 20))
        dao.upsertMessage(message.copy(content = "更新正文", updatedAtMillis = 20))
        dao.upsertRoleCard(roleCard.copy(name = "更新角色", updatedAtMillis = 20))
        dao.upsertWorldSetting(worldSetting.copy(title = "更新世界", updatedAtMillis = 20))
        dao.upsertWritingStyleProfile(writingStyle.copy(name = "更新文风", updatedAtMillis = 20))

        val savedSettings = dao.getConversationRpSettings(conversation.id)
        assertEquals(roleCard.id, savedSettings?.roleCardId)
        assertEquals(worldSetting.id, savedSettings?.worldSettingId)
        assertEquals(writingStyle.id, savedSettings?.writingStyleId)
        assertEquals("更新正文", dao.getMessage(message.id)?.content)
        assertNotNull(dao.getRpStatusPanel(conversation.id))
        assertNotNull(dao.getConversationContext(conversation.id))
        assertEquals(message.id, dao.observeMessageRpMetadata(conversation.id).first().single().messageId)

        assertEquals(1, dao.observeConversations().first().size)
        assertEquals(1, dao.getMessages(conversation.id).size)
        assertEquals(1, dao.observeRoleCards().first().size)
        assertEquals(1, dao.observeWorldSettings().first().size)
        assertEquals(1, dao.observeWritingStyleProfiles().first().size)
    }

    @Test
    fun deleteRoleCard_resetsOnlyBoundConversationsAndKeepsHistory() = runBlocking {
        val firstConversation = conversation("conversation-a")
        val secondConversation = conversation("conversation-b")
        val firstRoleCard = roleCard("role-a")
        val secondRoleCard = roleCard("role-b")
        val historicalMessage = message("message-a", firstConversation.id)

        dao.upsertConversation(firstConversation)
        dao.upsertConversation(secondConversation)
        dao.upsertMessage(historicalMessage)
        dao.upsertMessageRpMetadata(messageMetadata(historicalMessage.id, firstConversation.id))
        dao.upsertRoleCard(firstRoleCard)
        dao.upsertRoleCard(secondRoleCard)
        dao.upsertConversationRpSettings(rpSettings(firstConversation.id, firstRoleCard.id))
        dao.upsertConversationRpSettings(rpSettings(secondConversation.id, secondRoleCard.id))
        dao.upsertRpStatusPanel(statusPanel(firstConversation.id))
        dao.upsertRpStatusPanel(statusPanel(secondConversation.id))

        dao.deleteRoleCardAndResetBindings(firstRoleCard.id, updatedAtMillis = 50)

        assertNull(dao.getRoleCard(firstRoleCard.id))
        val resetSettings = dao.getConversationRpSettings(firstConversation.id)
        assertFalse(resetSettings!!.isRpEnabled)
        assertNull(resetSettings.roleCardId)
        assertEquals(50L, resetSettings.updatedAtMillis)
        assertNull(dao.getRpStatusPanel(firstConversation.id))
        assertNotNull(dao.getMessage(historicalMessage.id))
        assertEquals(1, dao.observeMessageRpMetadata(firstConversation.id).first().size)
        assertEquals(50L, dao.getConversation(firstConversation.id)?.updatedAtMillis)

        val unaffectedSettings = dao.getConversationRpSettings(secondConversation.id)
        assertTrue(unaffectedSettings!!.isRpEnabled)
        assertEquals(secondRoleCard.id, unaffectedSettings.roleCardId)
        assertNotNull(dao.getRoleCard(secondRoleCard.id))
        assertNotNull(dao.getRpStatusPanel(secondConversation.id))
        assertEquals(10L, dao.getConversation(secondConversation.id)?.updatedAtMillis)
    }

    @Test
    fun deleteWorldAndWritingStyle_onlyClearsTheirOwnBindings() = runBlocking {
        val conversation = conversation("conversation-a")
        val roleCard = roleCard("role-a")
        val worldSetting = worldSetting("world-a")
        val writingStyle = writingStyle("style-a")
        dao.upsertConversation(conversation)
        dao.upsertRoleCard(roleCard)
        dao.upsertWorldSetting(worldSetting)
        dao.upsertWritingStyleProfile(writingStyle)
        dao.upsertConversationRpSettings(
            rpSettings(conversation.id, roleCard.id, worldSetting.id, writingStyle.id),
        )
        dao.upsertRpStatusPanel(statusPanel(conversation.id))

        dao.deleteWorldSetting(worldSetting.id)

        val afterWorldDelete = dao.getConversationRpSettings(conversation.id)!!
        assertNull(afterWorldDelete.worldSettingId)
        assertEquals(roleCard.id, afterWorldDelete.roleCardId)
        assertEquals(writingStyle.id, afterWorldDelete.writingStyleId)
        assertTrue(afterWorldDelete.isRpEnabled)
        assertNotNull(dao.getRpStatusPanel(conversation.id))

        dao.deleteWritingStyleProfile(writingStyle.id)

        val afterStyleDelete = dao.getConversationRpSettings(conversation.id)!!
        assertNull(afterStyleDelete.writingStyleId)
        assertEquals(roleCard.id, afterStyleDelete.roleCardId)
        assertTrue(afterStyleDelete.isRpEnabled)
        assertNotNull(dao.getRpStatusPanel(conversation.id))
    }

    @Test
    fun rpSettingsSemanticChange_sameTimestamp_invalidatesOnlyStatusSnapshot() = runBlocking {
        val conversation = conversation("conversation-a")
        val roleCard = roleCard("role-a")
        val assistant = message("message-a", conversation.id)
        dao.upsertConversation(conversation)
        dao.upsertRoleCard(roleCard)
        dao.upsertConversationRpSettings(rpSettings(conversation.id, roleCard.id))
        dao.upsertMessage(assistant)
        dao.upsertMessageRpMetadata(
            messageMetadata(assistant.id, conversation.id).copy(
                optionsJson = "[\"继续\"]",
                metricsJson = "[{\"name\":\"信任\",\"value\":4,\"min\":0,\"max\":10}]",
                relationship = "熟识",
                scene = "庭院",
                rawJson = "{}",
            ),
        )

        RpRepositoryImpl(database, dao) { 10L }.updateConversationRpSettings(
            conversationId = conversation.id,
            isRpEnabled = true,
            roleCardId = roleCard.id,
            worldSettingId = null,
            writingStyleId = null,
            protagonistName = "同毫秒修改后的主角",
            protagonistDescription = null,
            protagonistInitialState = null,
            narrativeMinChars = null,
            narrativePerspective = null,
            plotSuggestionPerspective = null,
            rpMarkerMode = null,
        )

        val saved = dao.observeMessageRpMetadata(conversation.id).first().single()
        assertEquals("想法", saved.innerThought)
        assertEquals("[\"继续\"]", saved.optionsJson)
        assertEquals("[]", saved.metricsJson)
        assertEquals("", saved.relationship)
        assertEquals("", saved.scene)
        assertEquals("", saved.rawJson)
    }

    @Test
    fun editRoleCardMetrics_normalizesPanelsWithoutBreakingBindings() = runBlocking {
        val enabledConversation = conversation("conversation-a")
        val disabledConversation = conversation("conversation-b")
        val originalRoleCard = roleCard(
            id = "role-a",
            metrics = listOf(
                metricDefinition(id = "trust", name = "信任", max = 10, initial = 2),
                metricDefinition(id = "old", name = "旧指标", max = 10, initial = 1),
            ),
        )
        dao.upsertConversation(enabledConversation)
        dao.upsertConversation(disabledConversation)
        dao.upsertRoleCard(originalRoleCard)
        dao.upsertConversationRpSettings(rpSettings(enabledConversation.id, originalRoleCard.id))
        dao.upsertConversationRpSettings(
            rpSettings(disabledConversation.id, originalRoleCard.id).copy(isRpEnabled = false),
        )
        val historicalAssistant = message("message-role-edit", enabledConversation.id)
        dao.upsertMessage(historicalAssistant)
        dao.upsertMessageRpMetadata(
            messageMetadata(historicalAssistant.id, enabledConversation.id).copy(
                optionsJson = "[\"继续\"]",
                metricsJson = "[{\"name\":\"信任\",\"value\":9,\"min\":0,\"max\":10}]",
                relationship = "熟识",
                scene = "庭院",
                rawJson = "{}",
            ),
        )
        dao.upsertRpStatusPanel(
            statusPanel(
                conversationId = enabledConversation.id,
                metrics = listOf(
                    RpMetric(name = "信任", value = 9, min = 0, max = 10),
                    RpMetric(name = "旧指标", value = 7, min = 0, max = 10),
                ),
            ),
        )
        dao.upsertRpStatusPanel(statusPanel(disabledConversation.id))

        val repository = RpRepositoryImpl(database, dao) { 100L }
        val savedRoleCard = repository.saveRoleCard(
            id = originalRoleCard.id,
            draft = RoleCardDraft(
                name = "角色更新",
                metricDefinitions = listOf(
                    metricDefinition(id = "trust", name = "信任", max = 5, initial = 2),
                    metricDefinition(id = "courage", name = "勇气", max = 10, initial = 3),
                ),
            ),
        )

        assertEquals(10L, savedRoleCard.createdAtMillis)
        assertEquals(100L, savedRoleCard.updatedAtMillis)
        assertEquals(originalRoleCard.id, dao.getConversationRpSettings(enabledConversation.id)?.roleCardId)
        val normalized = dao.getRpStatusPanel(enabledConversation.id)!!.toDomain()
        assertEquals(
            listOf(
                RpMetric(name = "信任", value = 5, min = 0, max = 5),
                RpMetric(name = "勇气", value = 3, min = 0, max = 10),
            ),
            normalized.metrics,
        )
        assertEquals("熟识", normalized.relationship)
        assertEquals("庭院", normalized.scene)
        assertNull(dao.getRpStatusPanel(disabledConversation.id))
        val historicalMetadata = dao.observeMessageRpMetadata(enabledConversation.id).first().single()
        assertEquals("想法", historicalMetadata.innerThought)
        assertEquals("[\"继续\"]", historicalMetadata.optionsJson)
        assertEquals("[]", historicalMetadata.metricsJson)
        assertEquals("", historicalMetadata.relationship)
        assertEquals("", historicalMetadata.scene)
        assertEquals("", historicalMetadata.rawJson)
    }

    private fun conversation(id: String) = ConversationEntity(
        id = id,
        title = "会话",
        createdAtMillis = 10,
        updatedAtMillis = 10,
    )

    private fun message(id: String, conversationId: String) = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = ChatRole.ASSISTANT,
        content = "正文",
        reasoningContent = null,
        status = MessageStatus.COMPLETED,
        errorMessage = null,
        createdAtMillis = 10,
        updatedAtMillis = 10,
    )

    private fun roleCard(
        id: String,
        metrics: List<RpMetricDefinition> = listOf(metricDefinition()),
    ) = RoleCard(
        id = id,
        name = "角色",
        description = "",
        persona = "",
        speakingStyle = "",
        userIdentity = "",
        openingMessage = "",
        boundaries = "",
        metricDefinitions = metrics,
        createdAtMillis = 10,
        updatedAtMillis = 10,
    ).toEntity()

    private fun worldSetting(id: String) = WorldSettingEntity(
        id = id,
        title = "世界",
        content = "设定",
        createdAtMillis = 10,
        updatedAtMillis = 10,
    )

    private fun writingStyle(id: String) = WritingStyleProfileEntity(
        id = id,
        name = "文风",
        content = "简洁",
        createdAtMillis = 10,
        updatedAtMillis = 10,
    )

    private fun rpSettings(
        conversationId: String,
        roleCardId: String,
        worldSettingId: String? = null,
        writingStyleId: String? = null,
    ) = ConversationRpSettings(
        conversationId = conversationId,
        isRpEnabled = true,
        roleCardId = roleCardId,
        worldSettingId = worldSettingId,
        writingStyleId = writingStyleId,
        updatedAtMillis = 10,
    ).toEntity()

    private fun statusPanel(
        conversationId: String,
        metrics: List<RpMetric> = listOf(RpMetric(name = "信任", value = 4, min = 0, max = 10)),
    ) = RpStatusPanel(
        conversationId = conversationId,
        metrics = metrics,
        relationship = "熟识",
        scene = "庭院",
        rawJson = "{}",
        updatedAtMillis = 10,
    ).toEntity()

    private fun messageMetadata(messageId: String, conversationId: String) = MessageRpMetadataEntity(
        messageId = messageId,
        conversationId = conversationId,
        optionsJson = "[]",
        innerThought = "想法",
        metricsJson = "[]",
        relationship = "",
        scene = "",
        rawJson = "",
        createdAtMillis = 10,
        updatedAtMillis = 10,
    )

    private fun metricDefinition(
        id: String = "trust",
        name: String = "信任",
        max: Int = 10,
        initial: Int = 4,
    ) = RpMetricDefinition(
        id = id,
        name = name,
        minValue = 0,
        maxValue = max,
        initialValue = initial,
    )
}
