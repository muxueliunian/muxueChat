package com.muxuechat.data.repository

import com.muxuechat.data.remote.XaiAuthException
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muxuechat.data.local.ChatDatabase
import com.muxuechat.data.local.ConversationContextEntity
import com.muxuechat.data.local.ConversationEntity
import com.muxuechat.data.local.MessageEntity
import com.muxuechat.data.local.MessageRpMetadataEntity
import com.muxuechat.data.local.toEntity
import com.muxuechat.data.remote.DeepSeekApiClient
import com.muxuechat.data.remote.OpenAiCompatibleClient
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.DisplaySettings
import com.muxuechat.domain.model.MessageStatus
import com.muxuechat.domain.model.RpMetric
import com.muxuechat.domain.model.RpStatusPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatRepositoryHistoryPreflightTest {
    private lateinit var database: ChatDatabase
    private lateinit var scope: CoroutineScope

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

    @Test
    fun missingDeepSeekKey_regenerationLeavesOfficialHistoryUntouched() = runBlocking {
        val dao = database.chatDao()
        seedConversationWithDerivedState()
        val beforeMessages = dao.getMessages(CONVERSATION_ID)
        val repository = repository(FakeSettingsRepository(ChatSettings(), deepSeekKey = null))

        val result = runCatching { repository.regenerateLastResponse(CONVERSATION_ID).toList() }

        assertTrue(result.isFailure)
        assertEquals(beforeMessages, dao.getMessages(CONVERSATION_ID))
        assertEquals(1, dao.observeMessageRpMetadata(CONVERSATION_ID).toListOnce().size)
        assertNotNull(dao.getRpStatusPanel(CONVERSATION_ID))
        assertEquals("原摘要", dao.getConversationContext(CONVERSATION_ID)?.summary)
    }

    @Test
    fun missingDeepSeekKey_sendKeepsUserInputAndPersistsFailedAssistant() = runBlocking {
        val dao = database.chatDao()
        val repository = repository(FakeSettingsRepository(ChatSettings(), deepSeekKey = null))

        val result = runCatching { repository.sendMessage(CONVERSATION_ID, "不能丢失的输入").toList() }

        assertTrue(result.isFailure)
        val messages = dao.getMessages(CONVERSATION_ID)
        assertEquals(2, messages.size)
        val user = messages.single { it.role == ChatRole.USER }
        val failed = messages.single { it.role == ChatRole.ASSISTANT }
        assertEquals("不能丢失的输入", user.content)
        assertEquals(MessageStatus.COMPLETED, user.status)
        assertEquals(MessageStatus.FAILED, failed.status)
        assertTrue(failed.errorMessage.orEmpty().contains("API Key"))
    }

    @Test
    fun invalidCustomProvider_editLeavesMessageAndFollowingBranchUntouched() = runBlocking {
        val dao = database.chatDao()
        seedConversationWithDerivedState()
        val beforeMessages = dao.getMessages(CONVERSATION_ID)
        val invalidProvider = CustomProvider(
            id = "custom",
            name = "测试供应商",
            baseUrl = "不是地址",
            selectedModel = "model",
        )
        val settings = ChatSettings(
            provider = ChatProviderId.CUSTOM_OPENAI,
            customProviders = listOf(invalidProvider),
            activeCustomProviderId = invalidProvider.id,
        )
        val repository = repository(FakeSettingsRepository(settings, providerKey = "placeholder"))

        val result = runCatching { repository.editUserMessageAndRegenerate(USER_ID, "改写内容").toList() }

        assertTrue(result.isFailure)
        assertEquals(beforeMessages, dao.getMessages(CONVERSATION_ID))
        assertEquals("原摘要", dao.getConversationContext(CONVERSATION_ID)?.summary)
        assertEquals("原场景", dao.getRpStatusPanel(CONVERSATION_ID)?.scene)
    }

    @Test
    fun invalidCustomProvider_sendKeepsUserInputAndPersistsSafeFailure() = runBlocking {
        val dao = database.chatDao()
        val invalidProvider = CustomProvider(
            id = "custom",
            name = "测试供应商",
            baseUrl = "不是地址",
            selectedModel = "model",
        )
        val settings = ChatSettings(
            provider = ChatProviderId.CUSTOM_OPENAI,
            customProviders = listOf(invalidProvider),
            activeCustomProviderId = invalidProvider.id,
        )
        val repository = repository(FakeSettingsRepository(settings, providerKey = "placeholder"))

        val result = runCatching { repository.sendMessage(CONVERSATION_ID, "仍需保留的输入").toList() }

        assertTrue(result.isFailure)
        val messages = dao.getMessages(CONVERSATION_ID)
        assertEquals(2, messages.size)
        val user = messages.single { it.role == ChatRole.USER }
        val failed = messages.single { it.role == ChatRole.ASSISTANT }
        assertEquals("仍需保留的输入", user.content)
        assertEquals(MessageStatus.COMPLETED, user.status)
        assertEquals(MessageStatus.FAILED, failed.status)
        assertTrue(
            failed.errorMessage.orEmpty().contains("地址") ||
                failed.errorMessage.orEmpty().contains("Base URL"),
        )
        assertTrue(failed.errorMessage.orEmpty().contains("不是地址").not())
    }

    @Test
    fun networkFailure_regenerationDiscardsStagingAndKeepsDerivedState() = runBlocking {
        val dao = database.chatDao()
        seedConversationWithDerivedState()
        val beforeMessages = dao.getMessages(CONVERSATION_ID)
        val failingClient = DeepSeekApiClient(
            OkHttpClient.Builder()
                .addInterceptor { throw IOException("synthetic network failure") }
                .build(),
        )
        val repository = repository(
            settingsRepository = FakeSettingsRepository(
                ChatSettings(streamEnabled = false),
                deepSeekKey = "placeholder",
            ),
            apiClient = failingClient,
        )

        val result = runCatching { repository.regenerateLastResponse(CONVERSATION_ID).toList() }

        assertTrue(result.isFailure)
        assertEquals(beforeMessages, dao.getMessages(CONVERSATION_ID))
        assertEquals(1, dao.observeMessageRpMetadata(CONVERSATION_ID).toListOnce().size)
        assertEquals("原场景", dao.getRpStatusPanel(CONVERSATION_ID)?.scene)
        assertEquals("原摘要", dao.getConversationContext(CONVERSATION_ID)?.summary)
    }

    @Test
    fun collectorCancellation_regenerationDiscardsStagingAndKeepsOriginalReply() = runBlocking {
        val dao = database.chatDao()
        seedConversationWithDerivedState()
        val beforeMessages = dao.getMessages(CONVERSATION_ID)
        val repository = repository(
            FakeSettingsRepository(ChatSettings(), deepSeekKey = "placeholder"),
        )

        runCatching { repository.regenerateLastResponse(CONVERSATION_ID).take(1).toList() }

        assertEquals(beforeMessages, dao.getMessages(CONVERSATION_ID))
        assertEquals("原摘要", dao.getConversationContext(CONVERSATION_ID)?.summary)
        assertEquals("原场景", dao.getRpStatusPanel(CONVERSATION_ID)?.scene)
    }

    @Test
    fun commitConflict_rollsBackAndDiscardsStagingWithoutOverwritingConcurrentEdit() = runBlocking {
        val dao = database.chatDao()
        seedConversationWithDerivedState()
        val respondingClient = DeepSeekApiClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    runBlocking {
                        val currentUser = dao.getMessage(USER_ID)!!
                        dao.updateMessage(currentUser.copy(content = "并发改写", updatedAtMillis = 50))
                    }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"choices":[{"message":{"role":"assistant","content":"新回复"}}]}"""
                                .toResponseBody("application/json".toMediaType()),
                        )
                        .build()
                }
                .build(),
        )
        val repository = repository(
            settingsRepository = FakeSettingsRepository(
                ChatSettings(streamEnabled = false),
                deepSeekKey = "placeholder",
            ),
            apiClient = respondingClient,
        )

        val result = runCatching { repository.regenerateLastResponse(CONVERSATION_ID).toList() }

        assertTrue(result.isFailure)
        val messages = dao.getMessages(CONVERSATION_ID)
        assertEquals("并发改写", messages.single { it.id == USER_ID }.content)
        assertEquals("原回复", messages.single { it.id == ASSISTANT_ID }.content)
        assertTrue(messages.none { it.id.startsWith("history-replacement:") })
        assertEquals(1, dao.observeMessageRpMetadata(CONVERSATION_ID).toListOnce().size)
        assertEquals("原场景", dao.getRpStatusPanel(CONVERSATION_ID)?.scene)
        assertEquals("原摘要", dao.getConversationContext(CONVERSATION_ID)?.summary)
    }

    private suspend fun seedConversationWithDerivedState() {
        val dao = database.chatDao()
        dao.upsertConversation(
            ConversationEntity(CONVERSATION_ID, "会话", createdAtMillis = 1, updatedAtMillis = 3),
        )
        dao.upsertMessage(message(USER_ID, ChatRole.USER, "原问题", createdAt = 1))
        dao.upsertMessage(message(ASSISTANT_ID, ChatRole.ASSISTANT, "原回复", createdAt = 2))
        dao.upsertMessageRpMetadata(
            MessageRpMetadataEntity(
                messageId = ASSISTANT_ID,
                conversationId = CONVERSATION_ID,
                optionsJson = "[]",
                innerThought = "原想法",
                metricsJson = "[]",
                relationship = "",
                scene = "",
                rawJson = "",
                createdAtMillis = 2,
                updatedAtMillis = 2,
            ),
        )
        dao.upsertRpStatusPanel(
            RpStatusPanel(
                conversationId = CONVERSATION_ID,
                metrics = listOf(RpMetric("信任", 3, 0, 10)),
                relationship = "熟识",
                scene = "原场景",
                rawJson = "{}",
                updatedAtMillis = 2,
            ).toEntity(),
        )
        dao.upsertConversationContext(
            ConversationContextEntity(CONVERSATION_ID, "原摘要", coveredMessageCount = 2, updatedAtMillis = 2),
        )
    }

    private fun repository(
        settingsRepository: SettingsRepository,
        apiClient: DeepSeekApiClient = DeepSeekApiClient(),
    ) = ChatRepositoryImpl(
        chatDao = database.chatDao(),
        settingsRepository = settingsRepository,
        apiClient = apiClient,
        openAiCompatibleClient = OpenAiCompatibleClient(),
        // 这些用例只覆盖 DeepSeek 与 API Key 型供应商；任何 xAI 取 token 都应视为未登录。
        xaiAccessTokens = object : XaiAccessTokenSource {
            override suspend fun getValidAccessToken(rejectedAccessToken: String?): String =
                throw XaiAuthException.NotSignedIn()
        },
        enrichmentScope = scope,
        clock = { 100L },
    )

    private fun message(id: String, role: ChatRole, content: String, createdAt: Long) = MessageEntity(
        id = id,
        conversationId = CONVERSATION_ID,
        role = role,
        content = content,
        reasoningContent = null,
        status = MessageStatus.COMPLETED,
        errorMessage = null,
        createdAtMillis = createdAt,
        updatedAtMillis = createdAt,
    )

    private suspend fun <T> Flow<List<T>>.toListOnce(): List<T> = first()

    private class FakeSettingsRepository(
        initialSettings: ChatSettings,
        private val deepSeekKey: String? = null,
        private val providerKey: String? = null,
    ) : SettingsRepository {
        private val settingsState = MutableStateFlow(initialSettings)
        override val settings: Flow<ChatSettings> = settingsState
        override val displaySettings: Flow<DisplaySettings> = MutableStateFlow(DisplaySettings())

        override suspend fun currentSettings(): ChatSettings = settingsState.value

        override suspend fun <T> withCurrentProviderCredentials(
            transform: (settings: ChatSettings, apiKey: String?) -> T,
        ): T {
            val current = settingsState.value.normalized()
            val apiKey = when (current.provider) {
                ChatProviderId.DEEPSEEK -> deepSeekKey
                ChatProviderId.CUSTOM_OPENAI -> providerKey
            }
            return transform(current, apiKey)
        }

        override suspend fun updateSettings(transform: (ChatSettings) -> ChatSettings) {
            settingsState.value = transform(settingsState.value)
        }

        override suspend fun updateDisplaySettings(settings: DisplaySettings) = Unit
        override suspend fun saveApiKey(apiKey: String) = Unit
        override suspend fun getApiKey(): String? = deepSeekKey
        override suspend fun clearApiKey() = Unit
        override suspend fun saveProviderKey(providerId: String, apiKey: String) = Unit
        override suspend fun getProviderKey(providerId: String): String? = providerKey
        override suspend fun clearProviderKey(providerId: String) = Unit
        override fun hasProviderKey(providerId: String): Boolean = providerKey != null
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
        const val USER_ID = "user"
        const val ASSISTANT_ID = "assistant"
    }
}
