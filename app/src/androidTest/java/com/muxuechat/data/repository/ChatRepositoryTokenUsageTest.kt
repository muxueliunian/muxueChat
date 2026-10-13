package com.muxuechat.data.repository

import com.muxuechat.data.remote.XaiAuthException
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muxuechat.data.local.ChatDatabase
import com.muxuechat.data.local.ConversationContextEntity
import com.muxuechat.data.local.ConversationEntity
import com.muxuechat.data.local.ConversationTokenUsageEntity
import com.muxuechat.data.local.MessageEntity
import com.muxuechat.data.remote.DeepSeekApiClient
import com.muxuechat.data.remote.OpenAiCompatibleClient
import com.muxuechat.data.remote.XaiResponsesTransport
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.DisplaySettings
import com.muxuechat.domain.model.MessageStatus
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 请求内容与 token 用量的端到端契约。
 *
 * 两件事必须成立：
 * 1. 真正发出去的 messages 就是 [com.muxuechat.domain.context.ContextWindowPlan] 规划的内容，
 *    不是请求层另外拼的一份。
 * 2. 只有正文请求成功完成时才写入用量；上游不给用量时保留旧快照，摘要请求不许覆盖它。
 */
@RunWith(AndroidJUnit4::class)
class ChatRepositoryTokenUsageTest {
    private lateinit var database: ChatDatabase
    private lateinit var scope: CoroutineScope
    private val json = Json { ignoreUnknownKeys = true }

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

    // ---------- 发送内容 ----------

    /** 关闭工具时，规划结果就是请求体：历史逐条按序出现，一条不多一条不少。 */
    @Test
    fun sentMessages_matchThePlannedContext() = runBlocking {
        seedHistory()
        val recorder = RequestRecorder()
        val repository = repository(
            settings = ChatSettings(streamEnabled = false, settingsToolsEnabled = false),
            interceptor = recorder.respondingWith(CHAT_RESPONSE_WITH_USAGE),
        )

        repository.sendMessage(CONVERSATION_ID, "新问题").toList()

        val sent = recorder.messages()
        assertEquals(
            listOf(
                "user" to "原问题",
                "assistant" to "原回复",
                "user" to "新问题",
            ),
            sent,
        )
        assertFalse(recorder.request().containsKey("tools"))
    }

    /** system prompt 也来自同一份规划，不再由请求层单独拼。 */
    @Test
    fun systemPrompt_isPartOfThePlannedContext() = runBlocking {
        seedHistory()
        val recorder = RequestRecorder()
        val repository = repository(
            settings = ChatSettings(
                streamEnabled = false,
                systemPrompt = "你是助手",
                settingsToolsEnabled = false,
            ),
            interceptor = recorder.respondingWith(CHAT_RESPONSE_WITH_USAGE),
        )

        repository.sendMessage(CONVERSATION_ID, "新问题").toList()

        assertEquals("system" to "你是助手", recorder.messages().first())
    }

    /** 工具开启时，说明补在同一条 system 消息里，用户提示词和历史仍完整保留。 */
    @Test
    fun settingsToolsEnabled_preservesSystemPromptAndHistory() = runBlocking {
        seedHistory()
        val recorder = RequestRecorder()
        val repository = repository(
            settings = ChatSettings(
                streamEnabled = false,
                systemPrompt = "你是助手",
                settingsToolsEnabled = true,
            ),
            interceptor = recorder.respondingWith(CHAT_RESPONSE_WITH_USAGE),
        )

        repository.sendMessage(CONVERSATION_ID, "新问题").toList()

        val sent = recorder.messages()
        assertEquals(listOf("system", "user", "assistant", "user"), sent.map { it.first })
        assertTrue(sent.first().second.startsWith("你是助手\n\n"))
        assertTrue(sent.first().second.contains("## 可用工具"))
        assertEquals(
            listOf("user" to "原问题", "assistant" to "原回复", "user" to "新问题"),
            sent.drop(1),
        )
        assertTrue(recorder.request().getValue("tools").jsonArray.isNotEmpty())
    }

    // ---------- 用量采集 ----------

    @Test
    fun nonStreamingResponse_persistsUsageWithProviderAndModel() = runBlocking {
        seedHistory()
        val settings = ChatSettings(streamEnabled = false)
        val recorder = RequestRecorder()
        val repository = repository(
            settings = settings,
            interceptor = recorder.respondingWith(CHAT_RESPONSE_WITH_USAGE),
        )

        repository.sendMessage(CONVERSATION_ID, "新问题").toList()

        val usage = database.chatDao().getConversationTokenUsage(CONVERSATION_ID)
        assertNotNull(usage)
        assertEquals(13_102, usage?.promptTokens)
        assertEquals(816, usage?.completionTokens)
        assertEquals(13_918, usage?.totalTokens)
        assertEquals(ChatProviderId.DEEPSEEK.storageValue, usage?.providerKey)
        assertEquals(settings.activeModel, recorder.request().getValue("model").jsonPrimitive.content)
        assertEquals(settings.activeModel, usage?.model)
        assertEquals(950_000L, usage?.effectiveContextTokens)
    }

    @Test
    fun streamingResponse_consumesUsageFromTheFinalChunk() = runBlocking {
        seedHistory()
        val repository = repository(
            settings = ChatSettings(streamEnabled = true),
            interceptor = RequestRecorder().respondingWith(
                body = STREAM_RESPONSE_WITH_USAGE,
                contentType = "text/event-stream",
            ),
        )

        repository.sendMessage(CONVERSATION_ID, "新问题").toList()

        val usage = database.chatDao().getConversationTokenUsage(CONVERSATION_ID)
        assertEquals(555, usage?.promptTokens)
        assertEquals(66, usage?.completionTokens)
    }

    /** 上游不返回 usage 是常态：聊天要照常成功，旧快照要原样保留。 */
    @Test
    fun missingUsage_keepsPreviousSnapshotAndStillSucceeds() = runBlocking {
        seedHistory()
        database.chatDao().upsertConversationTokenUsage(previousUsage())
        val repository = repository(
            settings = ChatSettings(streamEnabled = false),
            interceptor = RequestRecorder().respondingWith(CHAT_RESPONSE_WITHOUT_USAGE),
        )

        repository.sendMessage(CONVERSATION_ID, "新问题").toList()

        val messages = database.chatDao().getMessages(CONVERSATION_ID)
        assertTrue(messages.any { it.role == ChatRole.ASSISTANT && it.status == MessageStatus.COMPLETED })
        assertEquals(999, database.chatDao().getConversationTokenUsage(CONVERSATION_ID)?.promptTokens)
    }

    @Test
    fun failedRequest_neverWritesUsage() = runBlocking {
        seedHistory()
        val repository = repository(
            settings = ChatSettings(streamEnabled = false),
            interceptor = Interceptor { throw IOException("网络中断") },
        )

        runCatching { repository.sendMessage(CONVERSATION_ID, "新问题").toList() }

        assertNull(database.chatDao().getConversationTokenUsage(CONVERSATION_ID))
    }

    /** 摘要是内部请求，它的用量不能冒充正文的「上次实际用量」。 */
    @Test
    fun summaryRequest_doesNotOverwriteChatUsage() = runBlocking {
        seedLongHistory()
        database.chatDao().upsertConversationTokenUsage(previousUsage())
        val repository = repository(
            settings = ChatSettings(streamEnabled = false),
            interceptor = RequestRecorder().respondingWith(SUMMARY_RESPONSE_WITH_USAGE),
        )

        runCatching { repository.compactContext(CONVERSATION_ID) }

        assertEquals(999, database.chatDao().getConversationTokenUsage(CONVERSATION_ID)?.promptTokens)
    }

    // ---------- 自定义供应商 ----------

    /** 默认绝不发送 `stream_options`：旧反代收到未知字段可能直接 400。 */
    @Test
    fun customProvider_doesNotSendStreamOptionsByDefault() = runBlocking {
        seedHistory()
        val recorder = RequestRecorder()
        val repository = repository(
            settings = customProviderSettings(streamUsageEnabled = false),
            openAiInterceptor = recorder.respondingWith(
                body = STREAM_RESPONSE_WITH_USAGE,
                contentType = "text/event-stream",
            ),
            providerKey = "placeholder",
        )

        repository.sendMessage(CONVERSATION_ID, "新问题").toList()

        assertFalse(recorder.lastBody().contains("stream_options"))
    }

    @Test
    fun customProvider_sendsStreamOptionsOnceEnabled() = runBlocking {
        seedHistory()
        val recorder = RequestRecorder()
        val repository = repository(
            settings = customProviderSettings(streamUsageEnabled = true),
            openAiInterceptor = recorder.respondingWith(
                body = STREAM_RESPONSE_WITH_USAGE,
                contentType = "text/event-stream",
            ),
            providerKey = "placeholder",
        )

        repository.sendMessage(CONVERSATION_ID, "新问题").toList()

        assertTrue(recorder.lastBody().contains("\"include_usage\":true"))
        assertEquals(555, database.chatDao().getConversationTokenUsage(CONVERSATION_ID)?.promptTokens)
        // 自定义供应商的用量按供应商 id 归属，避免和 DeepSeek 的同名模型混在一起。
        assertEquals("p1", database.chatDao().getConversationTokenUsage(CONVERSATION_ID)?.providerKey)
    }

    // ---------- 上下文视窗 ----------

    /** 顶栏与面板消费的是同一个 Flow，因此它必须在没有任何交互时也能直接给出占用。 */
    @Test
    fun observeContextWindow_emitsWithoutOpeningAnyPanel() = runBlocking {
        seedHistory()
        val repository = repository(settings = ChatSettings(), interceptor = null)

        val plan = withTimeout(CONTEXT_FLOW_TIMEOUT_MILLIS) {
            repository.observeContextWindow(CONVERSATION_ID).first()
        }

        assertEquals(945_904L, plan.inputBudgetTokens)
        assertEquals(2, plan.totalHistoryMessageCount)
        assertNull(plan.overflowError)
    }

    /** 新会话可选记录缺失时，Room 也必须发出 null，不能让组合 Flow 一直等首值。 */
    @Test
    fun contextSources_emitNullWhenOptionalRowsAreMissing() = runBlocking {
        seedHistory()
        val dao = database.chatDao()

        assertNull(withTimeout(CONTEXT_FLOW_TIMEOUT_MILLIS) {
            dao.observeConversationContext(CONVERSATION_ID).first()
        })
        assertNull(withTimeout(CONTEXT_FLOW_TIMEOUT_MILLIS) {
            dao.observeConversationRpSettings(CONVERSATION_ID).first()
        })
        assertNull(withTimeout(CONTEXT_FLOW_TIMEOUT_MILLIS) {
            dao.observeRpStatusPanel(CONVERSATION_ID).first()
        })
        assertNull(withTimeout(CONTEXT_FLOW_TIMEOUT_MILLIS) {
            dao.observeConversationTokenUsage(CONVERSATION_ID).first()
        })
    }

    // ---------- 测试脚手架 ----------

    private class RequestRecorder {
        private val bodies = CopyOnWriteArrayList<String>()

        fun respondingWith(body: String, contentType: String = "application/json") = Interceptor { chain ->
            val request = chain.request()
            bodies += request.body?.let { requestBody ->
                Buffer().also { requestBody.writeTo(it) }.readUtf8()
            }.orEmpty()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody(contentType.toMediaType()))
                .build()
        }

        fun lastBody(): String = bodies.last()

        fun request(): JsonObject = Json.parseToJsonElement(lastBody()).jsonObject

        /** 请求体里的 messages，按 (role, content) 展开，便于逐条比对。 */
        fun messages(): List<Pair<String, String>> {
            val root = request()
            return root.getValue("messages").jsonArray.map { element ->
                val message = element.jsonObject
                message.getValue("role").jsonPrimitive.content to
                    (message["content"]?.jsonPrimitive?.content ?: "")
            }
        }
    }

    private suspend fun seedHistory() {
        val dao = database.chatDao()
        dao.upsertConversation(ConversationEntity(CONVERSATION_ID, "会话", 1, 3))
        dao.upsertMessage(message("user-1", ChatRole.USER, "原问题", 1))
        dao.upsertMessage(message("assistant-1", ChatRole.ASSISTANT, "原回复", 2))
    }

    /** 长到足以让 planCompaction 有东西可压。 */
    private suspend fun seedLongHistory() {
        val dao = database.chatDao()
        dao.upsertConversation(ConversationEntity(CONVERSATION_ID, "会话", 1, 3))
        repeat(20) { index ->
            dao.upsertMessage(message("user-$index", ChatRole.USER, "问".repeat(4_000), index * 2L))
            dao.upsertMessage(
                message("assistant-$index", ChatRole.ASSISTANT, "答".repeat(4_000), index * 2L + 1),
            )
        }
        dao.upsertConversationContext(ConversationContextEntity(CONVERSATION_ID, "", 0, 1))
    }

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

    private fun previousUsage() = ConversationTokenUsageEntity(
        conversationId = CONVERSATION_ID,
        providerKey = ChatProviderId.DEEPSEEK.storageValue,
        model = "deepseek-v4-flash",
        promptTokens = 999,
        completionTokens = 9,
        totalTokens = 1_008,
        effectiveContextTokens = 950_000L,
        capturedAtMillis = 5,
    )

    private fun customProviderSettings(streamUsageEnabled: Boolean) = ChatSettings(
        provider = ChatProviderId.CUSTOM_OPENAI,
        streamEnabled = true,
        customProviders = listOf(
            CustomProvider(
                id = "p1",
                name = "自建",
                baseUrl = "https://example.com/v1",
                models = listOf("some-model"),
                selectedModel = "some-model",
                streamUsageEnabled = streamUsageEnabled,
            ),
        ),
        activeCustomProviderId = "p1",
    )

    private fun repository(
        settings: ChatSettings,
        interceptor: Interceptor? = null,
        openAiInterceptor: Interceptor? = null,
        providerKey: String? = null,
    ) = ChatRepositoryImpl(
        chatDao = database.chatDao(),
        settingsRepository = FakeSettingsRepository(
            initialSettings = settings,
            deepSeekKey = "placeholder",
            providerKey = providerKey,
        ),
        apiClient = interceptor?.let { DeepSeekApiClient(clientWith(it)) } ?: DeepSeekApiClient(),
        openAiCompatibleClient = openAiInterceptor
            ?.let { OpenAiCompatibleClient(clientWith(it)) }
            ?: OpenAiCompatibleClient(),
        xaiResponsesTransport = XaiResponsesTransport(),
        // 这些用例只覆盖 DeepSeek 与 API Key 型供应商；任何 xAI 取 token 都应视为未登录。
        xaiAccessTokens = object : XaiAccessTokenSource {
            override suspend fun getValidAccessToken(rejectedAccessToken: String?): String =
                throw XaiAuthException.NotSignedIn()
        },
        enrichmentScope = scope,
        clock = { 100L },
    )

    private fun clientWith(interceptor: Interceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    private class FakeSettingsRepository(
        initialSettings: ChatSettings,
        private val deepSeekKey: String?,
        private val providerKey: String?,
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
        const val CONTEXT_FLOW_TIMEOUT_MILLIS = 5_000L

        const val CHAT_RESPONSE_WITH_USAGE = """
            {"choices":[{"message":{"role":"assistant","content":"新回复"}}],
             "usage":{"prompt_tokens":13102,"completion_tokens":816,"total_tokens":13918}}
        """

        const val CHAT_RESPONSE_WITHOUT_USAGE =
            """{"choices":[{"message":{"role":"assistant","content":"新回复"}}]}"""

        const val SUMMARY_RESPONSE_WITH_USAGE = """
            {"choices":[{"message":{"role":"assistant","content":"这是一段更早剧情的摘要。"}}],
             "usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}
        """

        val STREAM_RESPONSE_WITH_USAGE = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"新回复\"}}]}\n\n")
            append(
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]," +
                    "\"usage\":{\"prompt_tokens\":555,\"completion_tokens\":66,\"total_tokens\":621}}\n\n",
            )
            append("data: [DONE]\n\n")
        }
    }
}
