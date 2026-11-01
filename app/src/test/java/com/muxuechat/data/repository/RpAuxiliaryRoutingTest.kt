package com.muxuechat.data.repository

import com.muxuechat.data.local.ChatDao
import com.muxuechat.data.local.ConversationRpSettingsEntity
import com.muxuechat.data.local.MessageAttachmentEntity
import com.muxuechat.data.local.MessageEntity
import com.muxuechat.data.local.RoleCardEntity
import com.muxuechat.data.local.RpTurnConfigurationSnapshot
import com.muxuechat.data.remote.DeepSeekApiClient
import com.muxuechat.data.remote.OpenAiCompatibleClient
import com.muxuechat.data.remote.RemoteClientLogger
import com.muxuechat.data.remote.XaiResponsesTransport
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.DeepSeekModels
import com.muxuechat.domain.model.MessageStatus
import com.muxuechat.domain.model.ProviderAuthKind
import com.muxuechat.domain.model.RpAuxiliaryProvider
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RP 辅助请求（重试剧情推进建议）的供应商路由。
 *
 * 全部使用合成 Key / 合成 token / 合成响应，不访问真实服务。两个 OkHttp 实例分别代表
 * DeepSeek 与 Vertex，借此断言「请求到底发给了谁、带着谁的凭据」。
 */
class RpAuxiliaryRoutingTest {
    private class RecordedRequest(val url: String, val authorization: String?, val body: String)

    private class Wire {
        val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

        fun client(): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                requests += RecordedRequest(
                    url = request.url.toString(),
                    authorization = request.header("Authorization"),
                    body = buffer.readUtf8(),
                )
                // 400 让流程停在请求之后、任何数据库写入之前；这里只关心请求发给了谁。
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(400)
                    .message("synthetic")
                    .body("""{"error":{"message":"synthetic rejection"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    private class CountingGoogleTokens : GoogleAccessTokenSource {
        val calls = AtomicInteger()
        override suspend fun acquireAccessToken(forceRefresh: Boolean): String {
            calls.incrementAndGet()
            return GOOGLE_TOKEN
        }
    }

    private class Harness(settings: ChatSettings, deepSeekKey: String?) {
        val deepSeekWire = Wire()
        val vertexWire = Wire()
        val googleTokens = CountingGoogleTokens()

        private val dao = Proxy.newProxyInstance(
            ChatDao::class.java.classLoader,
            arrayOf(ChatDao::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getMessages" -> listOf(
                    message("user-1", ChatRole.USER, "我推开了门", 1),
                    message("assistant-1", ChatRole.ASSISTANT, "门后是一条安静的走廊。", 2),
                )
                "getMessageRpMetadataForMessage" -> null
                // 纯文本会话：RP 辅助请求的「最近用户发言」不带图片占位。
                "getAttachments" -> emptyList<MessageAttachmentEntity>()
                "getRpTurnConfigurationSnapshot" -> RpTurnConfigurationSnapshot(
                    settings = rpSettings,
                    roleCard = roleCard,
                    statusPanel = null,
                    persistedStatusPanel = null,
                    statusBeforeMetadata = null,
                )
                else -> error("不应调用 DAO 方法：${method.name}")
            }
        } as ChatDao

        private val settingsRepository = Proxy.newProxyInstance(
            SettingsRepository::class.java.classLoader,
            arrayOf(SettingsRepository::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "currentSettings" -> settings
                "getApiKey" -> deepSeekKey
                "withCurrentProviderCredentials" -> {
                    @Suppress("UNCHECKED_CAST")
                    val transform = arguments!![0] as (ChatSettings, String?) -> Any?
                    // 只有正文是 DeepSeek 时才会给出 Key；Vertex 正文取不到任何 Keystore 里的 Key。
                    transform(settings.normalized(), deepSeekKey.takeIf { settings.provider == ChatProviderId.DEEPSEEK })
                }
                else -> error("不应调用设置方法：${method.name}")
            }
        } as SettingsRepository

        val repository = ChatRepositoryImpl(
            chatDao = dao,
            settingsRepository = settingsRepository,
            apiClient = DeepSeekApiClient(deepSeekWire.client(), NO_OP_LOGGER),
            openAiCompatibleClient = OpenAiCompatibleClient(),
            xaiResponsesTransport = XaiResponsesTransport(),
            xaiAccessTokens = object : XaiAccessTokenSource {
                override suspend fun getValidAccessToken(rejectedAccessToken: String?): String =
                    error("RP 辅助请求不应读取 xAI 凭据")
            },
            googleAccessTokens = googleTokens,
            googleVertexClient = OpenAiCompatibleClient.pinnedTo(CustomProvider.GOOGLE_VERTEX_HOST, vertexWire.client()),
        )

        suspend fun retry(): Throwable? = runCatching { repository.retryLatestRpOptions(CONVERSATION_ID).toList() }
            .exceptionOrNull()
    }

    private fun vertexSettings(
        route: RpAuxiliaryProvider,
        auxiliaryModel: String = "",
        auxiliaryScope: String = "",
    ) = ChatSettings(
        provider = ChatProviderId.CUSTOM_OPENAI,
        customProviders = listOf(googleProvider),
        activeCustomProviderId = googleProvider.id,
        rpAuxiliaryProvider = route,
        rpAuxiliaryModel = auxiliaryModel,
        rpAuxiliaryModelScope = auxiliaryScope,
    )

    @Test
    fun vertexMain_deepSeekRoute_retryGoesToDeepSeekWithDeepSeekKeyAndFlash() = runBlocking {
        val harness = Harness(vertexSettings(RpAuxiliaryProvider.DEEPSEEK), deepSeekKey = DEEPSEEK_KEY)

        val error = harness.retry()

        val sent = harness.deepSeekWire.requests.single()
        assertTrue(sent.url, sent.url.startsWith("https://api.deepseek.com/"))
        assertEquals("Bearer $DEEPSEEK_KEY", sent.authorization)
        assertTrue(sent.body, sent.body.contains("\"model\":\"${DeepSeekModels.FLASH}\""))
        // 辅助请求必须关闭思考：既慢又贵，还容易夹带解释文字。
        assertTrue(sent.body, sent.body.contains("\"type\":\"disabled\""))
        // Vertex 的客户端、Google token 一次都不能碰：登录失效也不该拦住这次重试。
        assertTrue(harness.vertexWire.requests.isEmpty())
        assertEquals(0, harness.googleTokens.calls.get())
        assertFalse(sent.body.contains(GOOGLE_TOKEN))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty(), error.message.orEmpty().startsWith("剧情推进建议生成失败："))
    }

    @Test
    fun vertexMain_deepSeekRoute_scopedOverrideIsUsedAsDeepSeekModel() = runBlocking {
        val settings = vertexSettings(RpAuxiliaryProvider.DEEPSEEK)
            .withRpAuxiliaryModel("deepseek-custom-flash")
        val harness = Harness(settings, deepSeekKey = DEEPSEEK_KEY)

        harness.retry()

        val sent = harness.deepSeekWire.requests.single()
        assertTrue(sent.body, sent.body.contains("\"model\":\"deepseek-custom-flash\""))
    }

    @Test
    fun vertexMain_deepSeekRoute_withoutKey_failsReadablyAndSendsNothing() = runBlocking {
        val harness = Harness(vertexSettings(RpAuxiliaryProvider.DEEPSEEK), deepSeekKey = null)

        val error = harness.retry()

        assertTrue(error is DeepSeekAuxiliaryKeyMissingException)
        assertTrue(error!!.message.orEmpty().contains("DeepSeek API Key"))
        assertTrue(harness.deepSeekWire.requests.isEmpty())
        assertTrue(harness.vertexWire.requests.isEmpty())
        assertEquals(0, harness.googleTokens.calls.get())
    }

    @Test
    fun vertexMain_followRoute_legacyDeepSeekOverrideIsNeverSentToVertex() = runBlocking {
        // 复现原 bug：旧版本存下的 DeepSeek 模型名（没有归属），正文是 Vertex。
        val harness = Harness(
            vertexSettings(RpAuxiliaryProvider.FOLLOW_ACTIVE, auxiliaryModel = "deepseek-v4-flash"),
            deepSeekKey = DEEPSEEK_KEY,
        )

        harness.retry()

        val sent = harness.vertexWire.requests.single()
        assertTrue(sent.body, sent.body.contains("\"model\":\"${CustomProvider.GOOGLE_DEFAULT_MODEL}\""))
        assertFalse(sent.body, sent.body.contains("deepseek"))
        assertTrue(harness.deepSeekWire.requests.isEmpty())
        assertEquals(1, harness.googleTokens.calls.get())
    }

    @Test
    fun vertexMain_followRoute_overrideScopedToVertexIsUsed() = runBlocking {
        val settings = vertexSettings(RpAuxiliaryProvider.FOLLOW_ACTIVE)
            .withRpAuxiliaryModel("google/gemini-3.8-flash")
        val harness = Harness(settings, deepSeekKey = DEEPSEEK_KEY)

        harness.retry()

        val sent = harness.vertexWire.requests.single()
        assertTrue(sent.body, sent.body.contains("\"model\":\"google/gemini-3.8-flash\""))
        assertTrue(harness.deepSeekWire.requests.isEmpty())
    }

    @Test
    fun deepSeekMain_followRoute_behavesAsBefore() = runBlocking {
        val settings = ChatSettings(
            provider = ChatProviderId.DEEPSEEK,
            model = "deepseek-v4-pro",
        ).withRpAuxiliaryModel("deepseek-flash")
        val harness = Harness(settings, deepSeekKey = DEEPSEEK_KEY)

        harness.retry()

        val sent = harness.deepSeekWire.requests.single()
        assertEquals("Bearer $DEEPSEEK_KEY", sent.authorization)
        assertTrue(sent.body, sent.body.contains("\"model\":\"deepseek-flash\""))
        assertTrue(harness.vertexWire.requests.isEmpty())
    }

    private companion object {
        const val CONVERSATION_ID = "conversation"
        const val DEEPSEEK_KEY = "synthetic-deepseek-key"
        const val GOOGLE_TOKEN = "synthetic-google-token"

        val googleProvider = CustomProvider(
            id = "p-google",
            name = CustomProvider.GOOGLE_DEFAULT_NAME,
            baseUrl = "",
            models = listOf(CustomProvider.GOOGLE_DEFAULT_MODEL),
            selectedModel = CustomProvider.GOOGLE_DEFAULT_MODEL,
            authKind = ProviderAuthKind.GOOGLE_OAUTH,
            googleProjectId = "my-project-123456",
        )

        val roleCard = RoleCardEntity(
            id = "role", name = "角色", description = "", persona = "沉默的守门人",
            speakingStyle = "", userIdentity = "", boundaries = "", metricDefinitionsJson = "[]",
            createdAtMillis = 0L, updatedAtMillis = 0L,
        )

        val rpSettings = ConversationRpSettingsEntity(
            conversationId = CONVERSATION_ID, isRpEnabled = true, roleCardId = "role", worldSettingId = null,
            protagonistName = "", protagonistDescription = "", protagonistInitialState = "",
            narrativeMinChars = 0, narrativePerspective = "", plotSuggestionPerspective = "",
            rpMarkerMode = "", updatedAtMillis = 0L,
        )

        fun message(id: String, role: ChatRole, content: String, createdAt: Long) = MessageEntity(
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

        val NO_OP_LOGGER = object : RemoteClientLogger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
        }
    }
}
