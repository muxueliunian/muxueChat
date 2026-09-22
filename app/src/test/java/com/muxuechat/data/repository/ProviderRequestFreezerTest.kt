package com.muxuechat.data.repository

import com.muxuechat.data.local.XaiCredential
import com.muxuechat.data.remote.FakeAuthReply
import com.muxuechat.data.remote.FakeXaiAuthServer
import com.muxuechat.data.remote.InMemoryXaiCredentialStore
import com.muxuechat.data.remote.XaiAuthException
import com.muxuechat.data.remote.XaiFixtures
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.DisplaySettings
import com.muxuechat.domain.model.ProviderAuthKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRequestFreezerTest {
    private val apiKeyProvider = CustomProvider(
        id = "p-api",
        name = "OpenRouter",
        baseUrl = "http://192.168.1.20:8317/v1/",
        selectedModel = "gpt-x",
        reasoningEffort = " high ",
        streamUsageEnabled = true,
    )
    private val xaiProvider = CustomProvider(
        id = "p-xai",
        name = CustomProvider.XAI_DEFAULT_NAME,
        // 即使存储里被写进了别的地址，也必须被钉死回订阅地址。
        baseUrl = "http://evil.example/v1",
        selectedModel = "grok-4",
        authKind = ProviderAuthKind.XAI_OAUTH,
    )

    private fun deepSeekSettings() = ChatSettings(provider = ChatProviderId.DEEPSEEK, model = "deepseek-v4-pro")

    private fun customSettings(active: CustomProvider) = ChatSettings(
        provider = ChatProviderId.CUSTOM_OPENAI,
        customProviders = listOf(apiKeyProvider, xaiProvider),
        activeCustomProviderId = active.id,
    )

    // ---------------------------------------------------------------- 现有两类供应商：行为不变

    @Test
    fun deepSeek_snapshotMatchesPreviousFieldsAndNeverTouchesXai() = runBlocking {
        val tokens = RecordingTokenSource()
        val freezer = ProviderRequestFreezer(FakeSettingsRepository(deepSeekSettings(), "sk-deepseek"), tokens)
        var selected: ChatProviderId? = null

        val snapshot = freezer.freeze { selected = it }

        val normalized = deepSeekSettings().normalized()
        assertEquals(ChatProviderId.DEEPSEEK, selected)
        assertEquals(ChatProviderId.DEEPSEEK, snapshot.provider)
        assertEquals("sk-deepseek", snapshot.apiKey)
        assertTrue(snapshot.credential is ProviderCredential.ApiKey)
        assertEquals(normalized.activeBaseUrl, snapshot.baseUrl)
        assertEquals(normalized.activeModel, snapshot.model)
        assertNull(snapshot.reasoningEffort)
        assertFalse(snapshot.includeStreamUsage)
        assertEquals(normalized, snapshot.settings)
        assertEquals(0, tokens.calls)
    }

    @Test
    fun deepSeek_missingKeyKeepsOriginalMessage() = runBlocking {
        val freezer = ProviderRequestFreezer(FakeSettingsRepository(deepSeekSettings(), apiKey = null), RecordingTokenSource())

        val error = runCatching { freezer.freeze() }.exceptionOrNull()

        assertEquals("请先在设置中保存 DeepSeek API Key", error?.message)
    }

    @Test
    fun apiKeyProvider_snapshotMatchesPreviousFieldsAndNeverTouchesXai() = runBlocking {
        val tokens = RecordingTokenSource()
        val freezer = ProviderRequestFreezer(FakeSettingsRepository(customSettings(apiKeyProvider), "sk-custom"), tokens)

        val snapshot = freezer.freeze()

        assertEquals(ChatProviderId.CUSTOM_OPENAI, snapshot.provider)
        assertEquals("sk-custom", snapshot.apiKey)
        assertEquals("http://192.168.1.20:8317/v1", snapshot.baseUrl)
        assertEquals("gpt-x", snapshot.model)
        assertEquals("high", snapshot.reasoningEffort)
        assertTrue(snapshot.includeStreamUsage)
        assertEquals(0, tokens.calls)
    }

    @Test
    fun apiKeyProvider_errorsKeepOriginalMessages() = runBlocking {
        val missingKey = runCatching {
            ProviderRequestFreezer(FakeSettingsRepository(customSettings(apiKeyProvider), null), RecordingTokenSource()).freeze()
        }.exceptionOrNull()
        assertEquals("请先在设置中保存「OpenRouter」的 API Key", missingKey?.message)

        val unconfigured = customSettings(apiKeyProvider).copy(
            customProviders = listOf(apiKeyProvider.copy(selectedModel = "", models = emptyList())),
        )
        val notConfigured = runCatching {
            ProviderRequestFreezer(FakeSettingsRepository(unconfigured, "sk-custom"), RecordingTokenSource()).freeze()
        }.exceptionOrNull()
        assertEquals("请先在设置中配置「OpenRouter」的地址和模型", notConfigured?.message)

        val noProvider = runCatching {
            ProviderRequestFreezer(
                FakeSettingsRepository(ChatSettings(provider = ChatProviderId.CUSTOM_OPENAI), "sk-custom"),
                RecordingTokenSource(),
            ).freeze()
        }.exceptionOrNull()
        assertEquals("请先在设置中添加一个供应商", noProvider?.message)
    }

    // ---------------------------------------------------------------- xAI OAuth 供应商

    @Test
    fun xaiProvider_notSignedIn_reportsLoginRequired_evenWhenAnApiKeyIsStored() = runBlocking {
        val tokens = RecordingTokenSource(failure = XaiAuthException.NotSignedIn())
        // 同 id 下即使 Keystore 里残留了一个 Key，也绝不能被拿来冒充登录态。
        val freezer = ProviderRequestFreezer(FakeSettingsRepository(customSettings(xaiProvider), "sk-leftover"), tokens)

        val error = runCatching { freezer.freeze() }.exceptionOrNull()

        assertTrue(error is XaiAuthException.NotSignedIn)
        assertEquals("请先登录 xAI", error?.message)
        assertEquals(1, tokens.calls)
    }

    @Test
    fun xaiProvider_signedIn_snapshotHoldsBearerTokenAndPinnedAddress() = runBlocking {
        val tokens = RecordingTokenSource(token = XaiFixtures.ACCESS_TOKEN)
        val freezer = ProviderRequestFreezer(FakeSettingsRepository(customSettings(xaiProvider), "sk-leftover"), tokens)

        val snapshot = freezer.freeze()

        val credential = snapshot.credential
        assertTrue(credential is ProviderCredential.OAuthBearer)
        assertEquals(XaiFixtures.ACCESS_TOKEN, (credential as ProviderCredential.OAuthBearer).accessToken)
        assertEquals("https://cli-chat-proxy.grok.com/v1", snapshot.baseUrl)
        assertEquals("grok-4", snapshot.model)
        assertEquals(ChatProviderId.CUSTOM_OPENAI, snapshot.provider)
        assertEquals(listOf<String?>(null), tokens.rejectedTokens)
    }

    @Test
    fun xaiSnapshot_isNeverUsableAsApiKey() = runBlocking {
        val snapshot = ProviderRequestFreezer(
            FakeSettingsRepository(customSettings(xaiProvider), "sk-leftover"),
            RecordingTokenSource(token = XaiFixtures.ACCESS_TOKEN),
        ).freeze()

        val error = runCatching { snapshot.apiKey }.exceptionOrNull()

        assertTrue(error is XaiTransportUnavailableException)
        assertFalse(error!!.message.orEmpty().contains(XaiFixtures.ACCESS_TOKEN))
        assertFalse(snapshot.credential.toString().contains(XaiFixtures.ACCESS_TOKEN))
        assertFalse(snapshot.toString().contains(XaiFixtures.ACCESS_TOKEN))
    }

    @Test
    fun xaiProvider_withoutModel_failsLocallyWithoutFetchingToken() = runBlocking {
        val tokens = RecordingTokenSource(token = XaiFixtures.ACCESS_TOKEN)
        val settings = customSettings(xaiProvider).copy(
            customProviders = listOf(xaiProvider.copy(selectedModel = "", models = emptyList())),
        )

        val error = runCatching { ProviderRequestFreezer(FakeSettingsRepository(settings, null), tokens).freeze() }
            .exceptionOrNull()

        // 不能复用 API Key 型那句「地址和模型」：xAI 的地址钉死且不可编辑，
        // 而按 Provider 收敛的兜底句还会提到 Base URL 与 API Key，界面上都没有。
        assertEquals("请先为「xAI 订阅」填写模型名（当前版本需手动填写）。", error?.message)
        assertTrue(error is SafeUserFacingError)
        assertEquals(0, tokens.calls)
    }

    @Test
    fun xaiProvider_freezeTriggersOnDemandRefreshThroughAuthRepository() = runTest {
        val server = FakeXaiAuthServer {
            FakeAuthReply.Http(200, XaiFixtures.tokenBody(XaiFixtures.NEW_ACCESS_TOKEN, XaiFixtures.NEW_REFRESH_TOKEN))
        }
        val store = InMemoryXaiCredentialStore(
            credential = XaiCredential(
                accessToken = XaiFixtures.ACCESS_TOKEN,
                refreshToken = XaiFixtures.REFRESH_TOKEN,
                expiresAtMillis = 0,
            ),
        )
        val auth = XaiAuthRepository(
            client = server.client(),
            store = store,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
            logger = server.logger,
        )
        auth.initialize()

        val snapshot = ProviderRequestFreezer(FakeSettingsRepository(customSettings(xaiProvider), null), auth).freeze()

        assertEquals(
            XaiFixtures.NEW_ACCESS_TOKEN,
            (snapshot.credential as ProviderCredential.OAuthBearer).accessToken,
        )
        assertEquals(1, server.tokenRequests().size)
        assertEquals("refresh_token", server.tokenRequests().single().form["grant_type"])
        assertEquals(XaiFixtures.NEW_REFRESH_TOKEN, store.credential?.refreshToken)
    }

    @Test
    fun xaiProvider_revokedAuthorizationSurfacesReadableErrorWithoutSecrets() = runTest {
        val server = FakeXaiAuthServer { FakeAuthReply.Http(400, XaiFixtures.oauthError("invalid_grant")) }
        val store = InMemoryXaiCredentialStore(
            credential = XaiCredential(
                accessToken = XaiFixtures.ACCESS_TOKEN,
                refreshToken = XaiFixtures.REFRESH_TOKEN,
                expiresAtMillis = 0,
            ),
        )
        val auth = XaiAuthRepository(
            client = server.client(),
            store = store,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
            logger = server.logger,
        )
        auth.initialize()

        val error = runCatching {
            ProviderRequestFreezer(FakeSettingsRepository(customSettings(xaiProvider), null), auth).freeze()
        }.exceptionOrNull()

        assertTrue(error is XaiAuthException.AuthorizationRevoked)
        assertNull(store.credential)
        val everything = (server.logger.messages + error?.message.orEmpty()).joinToString("\n")
        XaiFixtures.SECRETS.forEach { assertFalse("leaked $it", everything.contains(it)) }
    }

    // ---------------------------------------------------------------- 测试替身

    private class RecordingTokenSource(
        private val token: String = "unused",
        private val failure: Exception? = null,
    ) : XaiAccessTokenSource {
        var calls = 0
            private set
        val rejectedTokens = mutableListOf<String?>()

        override suspend fun getValidAccessToken(rejectedAccessToken: String?): String {
            calls++
            rejectedTokens += rejectedAccessToken
            failure?.let { throw it }
            return token
        }
    }

    /** 只实现冻结路径需要的方法；其余方法被调用即说明冻结器越界。 */
    private class FakeSettingsRepository(
        private val current: ChatSettings,
        private val apiKey: String?,
    ) : SettingsRepository {
        override val settings: Flow<ChatSettings> = flowOf(current)
        override val displaySettings: Flow<DisplaySettings> = flowOf(DisplaySettings())

        override suspend fun currentSettings(): ChatSettings = current

        override suspend fun <T> withCurrentProviderCredentials(
            transform: (settings: ChatSettings, apiKey: String?) -> T,
        ): T = transform(current.normalized(), apiKey)

        override suspend fun updateSettings(transform: (ChatSettings) -> ChatSettings) = unexpected()
        override suspend fun updateDisplaySettings(settings: DisplaySettings) = unexpected()
        override suspend fun saveApiKey(apiKey: String) = unexpected()
        override suspend fun getApiKey(): String? = unexpected()
        override suspend fun clearApiKey() = unexpected()
        override suspend fun saveProviderKey(providerId: String, apiKey: String) = unexpected()
        override suspend fun getProviderKey(providerId: String): String? = unexpected()
        override suspend fun clearProviderKey(providerId: String) = unexpected()
        override fun hasProviderKey(providerId: String): Boolean = unexpected()
        override suspend fun upsertCustomProvider(provider: CustomProvider) = unexpected()
        override suspend fun updateCustomProvider(
            providerId: String,
            transform: (CustomProvider) -> CustomProvider,
        ) = unexpected()
        override suspend fun deleteCustomProvider(providerId: String) = unexpected()
        override suspend fun selectCustomProviderModel(providerId: String, model: String) = unexpected()
        override suspend fun refreshProviderModels(providerId: String): List<String> = unexpected()
        override suspend fun refreshDeepSeekModels(): List<String> = unexpected()
        override suspend fun migrateLegacyProviderIfNeeded() = unexpected()

        private fun unexpected(): Nothing = throw AssertionError("freezer must not call this")
    }
}
