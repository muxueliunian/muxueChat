package com.muxuechat.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muxuechat.data.local.ChatDatabase
import com.muxuechat.data.local.ConversationEntity
import com.muxuechat.data.local.MessageEntity
import com.muxuechat.data.local.RpMigrationV10
import com.muxuechat.data.local.MessageRpMetadataEntity
import com.muxuechat.data.local.ProtagonistProfileEntity
import com.muxuechat.data.local.SupportingCharacterEntity
import com.muxuechat.data.local.toEntity
import com.muxuechat.data.remote.DeepSeekApiClient
import com.muxuechat.data.remote.OpenAiCompatibleClient
import com.muxuechat.data.remote.XaiAuthException
import com.muxuechat.data.remote.XaiResponsesTransport
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.ConversationRpSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.DisplaySettings
import com.muxuechat.domain.model.MessageStatus
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RpEntry
import com.muxuechat.domain.model.RpMetric
import com.muxuechat.domain.model.RpEntryOwner
import com.muxuechat.domain.model.RpMetricDefinition
import com.muxuechat.domain.model.RpStatusPanel
import com.muxuechat.domain.model.toNumericEntry
import com.muxuechat.domain.rp.RpEffectiveDefinitions
import com.muxuechat.domain.rp.RpEntryCodec
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RP 状态从正文提交到后台补写的端到端接线（RP_ACTOR_PLAN 第 5 节）。
 *
 * DAO 用例都是手工传入期望面板；这里验证 Repository 传的期望值确实对得上库里的面板，
 * 否则正常流程下补写永远写不进面板，而 DAO 用例全部照样通过。
 */
@RunWith(AndroidJUnit4::class)
class ChatRepositoryRpStateTest {
    private lateinit var database: ChatDatabase
    private lateinit var scope: CoroutineScope
    private val dao get() = database.chatDao()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    /** 正文带状态块、补写元数据也带状态：补写结果必须叠加在正文结果之上写进面板。 */
    @Test
    fun enrichmentAfterAppliedCompletion_updatesPanel() = runBlocking {
        seedRpConversation(withMetrics = true)
        dao.upsertRpStatusPanel(panel(metrics = listOf(RpMetric("角色::信任", 4, 0, 10)), relationship = "熟识"))
        val calls = AtomicInteger()
        val repository = repository(
            Interceptor { chain ->
                val body = if (calls.getAndIncrement() == 0) {
                    """正文<muxue_state>{"metrics":[{"name":"角色::信任","value":6}],"relationship":"熟识","scene":"吧台"}</muxue_state>"""
                } else {
                    """{"inner_thought":"想法","options":["甲","乙","丙"],"state":{"metrics":[{"name":"角色::信任","value":8}],"relationship":"","scene":"雨巷"}}"""
                }
                respond(chain, body)
            },
        )

        repository.sendMessage(CONVERSATION_ID, "新一句").toList()
        awaitEnrichment()

        val panel = dao.getRpStatusPanel(CONVERSATION_ID)!!.toDomain()
        assertEquals(listOf(RpMetric("角色::信任", 8, 0, 10)), panel.metrics)
        assertEquals("雨巷", panel.scene)
        assertEquals("熟识", panel.relationship)
    }

    /**
     * 生成期间面板被改过：补写的状态注定被丢弃，因此不得再发兜底请求，
     * 也不得提示「未更新状态栏」；剧情建议照常保存。
     */
    @Test
    fun panelChangedDuringGeneration_skipsFallbackRequest() = runBlocking {
        seedRpConversation(withMetrics = true)
        dao.upsertRpStatusPanel(panel(metrics = listOf(RpMetric("角色::信任", 4, 0, 10))))
        val editedPanel = panel(metrics = listOf(RpMetric("角色::信任", 9, 0, 10)), scene = "生成期间修正", time = 50)
        val calls = AtomicInteger()
        val repository = repository(
            Interceptor { chain ->
                val body = if (calls.getAndIncrement() == 0) {
                    runBlocking { dao.upsertRpStatusPanel(editedPanel) }
                    "正文没有状态块"
                } else {
                    """{"inner_thought":"想法","options":["甲","乙","丙"]}"""
                }
                respond(chain, body)
            },
        )

        repository.sendMessage(CONVERSATION_ID, "新一句").toList()
        awaitEnrichment()

        assertEquals(2, calls.get())
        assertEquals(editedPanel, dao.getRpStatusPanel(CONVERSATION_ID))
        assertNull(latestAssistant().errorMessage)
    }

    /**
     * RP-2b：正文状态块的数值一个都没对上（缺前缀），但给了关系与场景。
     * 关系与场景照样落面板，数值保持当前值；数值没更新，所以仍发兜底请求——兜底也失败时，
     * 关系与场景仍是正文给的新值，并提示「未更新状态栏」。
     */
    @Test
    fun contentStateWithOnlyStoryMatched_keepsNewStoryWhenFallbackFails() = runBlocking {
        seedRpConversation(withMetrics = true)
        dao.upsertRpStatusPanel(panel(metrics = listOf(RpMetric("角色::信任", 4, 0, 10)), relationship = "熟识", scene = "吧台"))
        val calls = AtomicInteger()
        val repository = repository(
            Interceptor { chain ->
                val body = when (calls.getAndIncrement()) {
                    0 -> """正文<muxue_state>{"metrics":[{"name":"信任","value":9}],"relationship":"亲近","scene":"雨巷"}</muxue_state>"""
                    1 -> """{"inner_thought":"想法","options":["甲","乙","丙"]}"""
                    else -> "兜底也给不出状态"
                }
                respond(chain, body)
            },
        )

        repository.sendMessage(CONVERSATION_ID, "新一句").toList()
        awaitEnrichment()

        assertEquals(3, calls.get())
        val panel = dao.getRpStatusPanel(CONVERSATION_ID)!!.toDomain()
        assertEquals(listOf(RpMetric("角色::信任", 4, 0, 10)), panel.metrics)
        assertEquals("亲近", panel.relationship)
        assertEquals("雨巷", panel.scene)
        assertEquals("本次回复未更新状态栏，已保留上一次状态。", latestAssistant().errorMessage)
    }

    /** 同上，但兜底成功：数值来自兜底，关系与场景以正文给的新值为基准，兜底留空时不回退。 */
    @Test
    fun contentStateWithOnlyStoryMatched_fallbackUpdatesMetricsOnTopOfNewStory() = runBlocking {
        seedRpConversation(withMetrics = true)
        dao.upsertRpStatusPanel(panel(metrics = listOf(RpMetric("角色::信任", 4, 0, 10)), relationship = "熟识", scene = "吧台"))
        val calls = AtomicInteger()
        val repository = repository(
            Interceptor { chain ->
                val body = when (calls.getAndIncrement()) {
                    0 -> """正文<muxue_state>{"metrics":[{"name":"信任","value":9}],"relationship":"亲近","scene":"雨巷"}</muxue_state>"""
                    1 -> """{"inner_thought":"想法","options":["甲","乙","丙"]}"""
                    else -> """{"metrics":[{"name":"角色::信任","value":7}],"relationship":"","scene":""}"""
                }
                respond(chain, body)
            },
        )

        repository.sendMessage(CONVERSATION_ID, "新一句").toList()
        awaitEnrichment()

        assertEquals(3, calls.get())
        val panel = dao.getRpStatusPanel(CONVERSATION_ID)!!.toDomain()
        assertEquals(listOf(RpMetric("角色::信任", 7, 0, 10)), panel.metrics)
        assertEquals("亲近", panel.relationship)
        assertEquals("雨巷", panel.scene)
        assertNull(latestAssistant().errorMessage)
    }

    /** RP-2e：会话勾选的配角只进正文请求，元数据补写请求里没有。数据直接用 DAO 插（RP-3 才有 UI）。 */
    @Test
    fun supportingCharacters_goIntoMainRequestOnly() = runBlocking {
        seedRpConversation(withMetrics = true)
        dao.insertSupportingCharacter(
            SupportingCharacterEntity(
                id = "support",
                name = "配角老周",
                description = "咖啡店的熟客",
                entriesJson = "[]",
                createdAtMillis = 1,
                updatedAtMillis = 1,
            ),
        )
        dao.replaceConversationSupportingCharacters(CONVERSATION_ID, listOf("support"))
        val bodies = mutableListOf<String>()
        val repository = repository(
            Interceptor { chain ->
                val body = synchronized(bodies) {
                    bodies += Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                    if (bodies.size == 1) {
                        """正文<muxue_state>{"metrics":[{"name":"角色::信任","value":6}],"relationship":"","scene":""}</muxue_state>"""
                    } else {
                        """{"inner_thought":"想法","options":["甲","乙","丙"]}"""
                    }
                }
                respond(chain, body)
            },
        )

        repository.sendMessage(CONVERSATION_ID, "新一句").toList()
        awaitEnrichment()

        assertEquals(2, bodies.size)
        assertTrue(bodies[0].contains("配角老周"))
        assertFalse(bodies[1].contains("配角老周"))
    }

    /** 角色卡没有状态条时，重新生成也要回滚关系与场景，不能留着被作废那条回复写下的值。 */
    @Test
    fun regenerateWithCardWithoutMetrics_rollsBackRelationshipAndScene() = runBlocking {
        seedRpConversation(withMetrics = false)
        dao.upsertMessage(message("user-1", ChatRole.USER, "问", 10))
        dao.upsertMessage(message("assistant-1", ChatRole.ASSISTANT, "旧回复", 11))
        dao.upsertRpStatusPanel(panel(metrics = emptyList(), relationship = "旧回复写下的关系", scene = "旧场景"))
        val calls = AtomicInteger()
        val repository = repository(
            Interceptor { chain ->
                val body = if (calls.getAndIncrement() == 0) {
                    "新回复"
                } else {
                    """{"inner_thought":"想法","options":["甲","乙","丙"]}"""
                }
                respond(chain, body)
            },
        )

        repository.regenerateLastResponse(CONVERSATION_ID).toList()
        awaitEnrichment()

        val panel = dao.getRpStatusPanel(CONVERSATION_ID)!!.toDomain()
        assertEquals("", panel.relationship)
        assertEquals("", panel.scene)
    }

    /**
     * RP-1 最关键的验收：**升级后立即重新生成，仍能回滚到正确的旧值**。
     *
     * 数据用迁移自己的转换函数种成「刚从 v9 升上来」的形状。若迁移写进库的状态名
     * 与运行时从快照取的 wireName 对不上，回滚会找不到切点值而落回初始值 2。
     */
    @Test
    fun regenerateRightAfterUpgrade_rollsBackToMigratedSnapshotValue() = runBlocking {
        val v9Definitions = """[{"id":"trust","name":"信任","minValue":0,"maxValue":10,"initialValue":2}]"""
        val entriesJson = RpMigrationV10.metricDefinitionsToEntriesJson(v9Definitions).json
        dao.upsertConversation(ConversationEntity(CONVERSATION_ID, "会话", 1, 1))
        dao.upsertRoleCard(
            RoleCard(
                id = ROLE_CARD_ID,
                name = "角色",
                description = "",
                persona = "",
                speakingStyle = "",
                userIdentity = "",
                openingMessage = "",
                boundaries = "",
                entries = RpEntryCodec.decodeEntries(entriesJson).entries,
                createdAtMillis = 1,
                updatedAtMillis = 1,
            ).toEntity(),
        )
        dao.upsertConversationRpSettings(
            ConversationRpSettings(
                conversationId = CONVERSATION_ID,
                isRpEnabled = true,
                roleCardId = ROLE_CARD_ID,
                metricSnapshot = RpEntryCodec.decodeSnapshot(RpMigrationV10.roleCardSnapshotJson(entriesJson)).entries,
                updatedAtMillis = 1,
            ).toEntity(),
        )
        dao.upsertMessage(message("user-1", ChatRole.USER, "问一", 10))
        dao.upsertMessage(message("assistant-1", ChatRole.ASSISTANT, "第一轮", 11))
        dao.upsertMessageRpMetadata(
            MessageRpMetadataEntity(
                messageId = "assistant-1",
                conversationId = CONVERSATION_ID,
                optionsJson = "[]",
                innerThought = "",
                metricsJson = RpMigrationV10.prefixLegacyMetricNames(
                    """[{"name":"信任","value":5,"min":0,"max":10}]""",
                ),
                relationship = "熟识",
                scene = "吧台",
                rawJson = "{}",
                createdAtMillis = 11,
                updatedAtMillis = 11,
            ),
        )
        dao.upsertMessage(message("user-2", ChatRole.USER, "问二", 12))
        dao.upsertMessage(message("assistant-2", ChatRole.ASSISTANT, "第二轮", 13))
        dao.upsertRpStatusPanel(
            RpStatusPanel(
                conversationId = CONVERSATION_ID,
                metrics = listOf(RpMetric(RpMigrationV10.legacyWireName("信任"), 9, 0, 10)),
                relationship = "亲近",
                scene = "雨巷",
                rawJson = "",
                updatedAtMillis = 13,
            ).toEntity(),
        )
        val calls = AtomicInteger()
        val repository = repository(
            Interceptor { chain ->
                // 新回复不带状态块；补写与兜底都给不出状态，面板应停在回滚后的值。
                val body = if (calls.getAndIncrement() == 0) {
                    "新的第二轮"
                } else {
                    """{"inner_thought":"想法","options":["甲","乙","丙"]}"""
                }
                respond(chain, body)
            },
        )

        repository.regenerateLastResponse(CONVERSATION_ID).toList()
        awaitEnrichment()

        val panel = dao.getRpStatusPanel(CONVERSATION_ID)!!.toDomain()
        assertEquals(listOf(RpMetric("角色::信任", 5, 0, 10)), panel.metrics)
        assertEquals("熟识", panel.relationship)
        assertEquals("吧台", panel.scene)
    }

    /**
     * RP-2f 无角色卡路径：只绑了有数值的主角。正文状态块照常更新面板；
     * 元数据补写需要角色卡，因此不发；数值已更新，兜底也不发——整轮只有一次请求。
     */
    @Test
    fun protagonistOnly_contentStateUpdatesPanelWithSingleRequest() = runBlocking {
        seedProtagonistOnlyConversation()
        dao.upsertRpStatusPanel(panel(metrics = listOf(RpMetric(STAMINA_WIRE, 50, 0, 100))))
        val bodies = mutableListOf<String>()
        val repository = repository(
            Interceptor { chain ->
                synchronized(bodies) { bodies += Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8() }
                respond(
                    chain,
                    """正文<muxue_state>{"metrics":[{"name":"$STAMINA_WIRE","value":40}],"relationship":"","scene":"山路"}</muxue_state>""",
                )
            },
        )

        repository.sendMessage(CONVERSATION_ID, "继续赶路").toList()
        delay(300)

        assertEquals(1, bodies.size)
        assertTrue(bodies.single().contains(STAMINA_WIRE))
        val panel = dao.getRpStatusPanel(CONVERSATION_ID)!!.toDomain()
        assertEquals(listOf(RpMetric(STAMINA_WIRE, 40, 0, 100)), panel.metrics)
        assertEquals("山路", panel.scene)
        assertNull(latestAssistant().errorMessage)
    }

    /** 同上，但正文没给状态块：后台只跑兜底状态请求，不跑元数据补写。 */
    @Test
    fun protagonistOnly_missingStateRunsFallbackOnly() = runBlocking {
        seedProtagonistOnlyConversation()
        dao.upsertRpStatusPanel(panel(metrics = listOf(RpMetric(STAMINA_WIRE, 50, 0, 100))))
        val calls = AtomicInteger()
        val repository = repository(
            Interceptor { chain ->
                val body = if (calls.getAndIncrement() == 0) {
                    "正文没有状态块"
                } else {
                    """{"metrics":[{"name":"$STAMINA_WIRE","value":45}],"relationship":"","scene":""}"""
                }
                respond(chain, body)
            },
        )

        repository.sendMessage(CONVERSATION_ID, "继续赶路").toList()
        withTimeout(10_000) {
            while (dao.getRpStatusPanel(CONVERSATION_ID)?.toDomain()?.metrics?.singleOrNull()?.value != 45) {
                delay(20)
            }
        }

        assertEquals(2, calls.get())
        assertNull(latestAssistant().errorMessage)
    }

    private suspend fun seedProtagonistOnlyConversation() {
        dao.upsertConversation(ConversationEntity(CONVERSATION_ID, "会话", 1, 1))
        val entries = listOf(RpEntry.Numeric("stamina", "体力", 0, 100, 80))
        dao.insertProtagonistProfile(
            ProtagonistProfileEntity(
                id = PROTAGONIST_ID,
                name = "沈昭",
                description = "",
                entriesJson = RpEntryCodec.encodeEntries(entries),
                createdAtMillis = 1,
                updatedAtMillis = 1,
            ),
        )
        dao.upsertConversationRpSettings(
            ConversationRpSettings(
                conversationId = CONVERSATION_ID,
                isRpEnabled = true,
                roleCardId = null,
                protagonistProfileId = PROTAGONIST_ID,
                metricSnapshot = RpEffectiveDefinitions.snapshotOf(RpEntryOwner.PROTAGONIST, entries),
                updatedAtMillis = 1,
            ).toEntity(),
        )
    }

    private suspend fun awaitEnrichment() = withTimeout(10_000) {
        while (dao.getMessageRpMetadata(CONVERSATION_ID).none { it.innerThought == "想法" }) {
            delay(20)
        }
    }

    private suspend fun latestAssistant(): MessageEntity =
        dao.getMessages(CONVERSATION_ID).last { it.role == ChatRole.ASSISTANT }

    /** 绑定时的定义快照由 Repository 生成；这里按同一入口拍快照，名字是带前缀的 wireName。 */
    private suspend fun seedRpConversation(withMetrics: Boolean) {
        dao.upsertConversation(ConversationEntity(CONVERSATION_ID, "会话", 1, 1))
        val entries = if (withMetrics) {
            listOf(RpMetricDefinition("trust", "信任", minValue = 0, maxValue = 10, initialValue = 2).toNumericEntry())
        } else {
            emptyList()
        }
        dao.upsertRoleCard(
            RoleCard(
                id = ROLE_CARD_ID,
                name = "角色",
                description = "",
                persona = "",
                speakingStyle = "",
                userIdentity = "",
                openingMessage = "",
                boundaries = "",
                entries = entries,
                createdAtMillis = 1,
                updatedAtMillis = 1,
            ).toEntity(),
        )
        dao.upsertConversationRpSettings(
            ConversationRpSettings(
                conversationId = CONVERSATION_ID,
                isRpEnabled = true,
                roleCardId = ROLE_CARD_ID,
                metricSnapshot = RpEffectiveDefinitions.snapshotOf(RpEntryOwner.ROLE_CARD, entries),
                updatedAtMillis = 1,
            ).toEntity(),
        )
    }

    private fun panel(
        metrics: List<RpMetric>,
        relationship: String = "",
        scene: String = "",
        time: Long = 1,
    ) = RpStatusPanel(
        conversationId = CONVERSATION_ID,
        metrics = metrics,
        relationship = relationship,
        scene = scene,
        rawJson = "",
        updatedAtMillis = time,
    ).toEntity()

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

    private fun respond(chain: Interceptor.Chain, content: String): Response {
        val body = buildJsonObject {
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            putJsonObject("message") {
                                put("role", "assistant")
                                put("content", content)
                            }
                        },
                    )
                },
            )
        }.toString()
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun repository(interceptor: Interceptor) = ChatRepositoryImpl(
        chatDao = dao,
        settingsRepository = FakeSettingsRepository(ChatSettings(streamEnabled = false)),
        apiClient = DeepSeekApiClient(OkHttpClient.Builder().addInterceptor(interceptor).build()),
        openAiCompatibleClient = OpenAiCompatibleClient(),
        xaiResponsesTransport = XaiResponsesTransport(),
        xaiAccessTokens = object : XaiAccessTokenSource {
            override suspend fun getValidAccessToken(rejectedAccessToken: String?): String =
                throw XaiAuthException.NotSignedIn()
        },
        enrichmentScope = scope,
        clock = { 100L },
    )

    private class FakeSettingsRepository(initialSettings: ChatSettings) : SettingsRepository {
        private val settingsState = MutableStateFlow(initialSettings)
        override val settings: Flow<ChatSettings> = settingsState
        override val displaySettings: Flow<DisplaySettings> = MutableStateFlow(DisplaySettings())

        override suspend fun currentSettings(): ChatSettings = settingsState.value

        override suspend fun <T> withCurrentProviderCredentials(
            transform: (settings: ChatSettings, apiKey: String?) -> T,
        ): T = transform(settingsState.value.normalized(), "placeholder")

        override suspend fun updateSettings(transform: (ChatSettings) -> ChatSettings) {
            settingsState.value = transform(settingsState.value)
        }

        override suspend fun updateDisplaySettings(settings: DisplaySettings) = Unit
        override suspend fun saveApiKey(apiKey: String) = Unit
        override suspend fun getApiKey(): String? = "placeholder"
        override suspend fun clearApiKey() = Unit
        override suspend fun saveProviderKey(providerId: String, apiKey: String) = Unit
        override suspend fun getProviderKey(providerId: String): String? = null
        override suspend fun clearProviderKey(providerId: String) = Unit
        override fun hasProviderKey(providerId: String): Boolean = false
        override suspend fun upsertCustomProvider(provider: CustomProvider) = Unit
        override suspend fun updateCustomProvider(
            providerId: String,
            transform: (CustomProvider) -> CustomProvider,
        ) = Unit
        override suspend fun deleteCustomProvider(providerId: String) = Unit
        override suspend fun selectCustomProviderModel(providerId: String, model: String) = Unit
        override suspend fun refreshProviderModels(providerId: String): List<String> = emptyList()
        override suspend fun refreshDeepSeekModels(): List<String> = emptyList()
        override suspend fun migrateLegacyProviderIfNeeded() = Unit
    }

    private companion object {
        const val CONVERSATION_ID = "conversation"
        const val ROLE_CARD_ID = "role-card"
        const val PROTAGONIST_ID = "protagonist"
        const val STAMINA_WIRE = "主角::体力"
    }
}
