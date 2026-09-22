package com.muxuechat.data.repository

import com.muxuechat.data.local.DeepSeekCredentialStore
import com.muxuechat.data.local.LegacyCliProxyCredentialStore
import com.muxuechat.data.local.MemoryPreferencesDataStore
import com.muxuechat.data.local.ProviderCredentialStore
import com.muxuechat.data.local.SettingsDataStore
import com.muxuechat.data.remote.DeepSeekApiClient
import com.muxuechat.data.remote.OpenAiCompatibleClient
import com.muxuechat.data.remote.RemoteClientLogger
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.DeepSeekModels
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsRepositoryModelRefreshTest {
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var deepSeekKeys: MemoryDeepSeekKeys
    private lateinit var providerKeys: MemoryProviderKeys

    @Before
    fun setUp() {
        settingsDataStore = SettingsDataStore(MemoryPreferencesDataStore())
        deepSeekKeys = MemoryDeepSeekKeys("deepseek-key")
        providerKeys = MemoryProviderKeys()
    }

    @Test
    fun deepSeekRefresh_cachesTrimmedDistinctIdsAndKeepsCurrentSelection() = runBlocking {
        seedDeepSeek(model = DeepSeekModels.V4_PRO)
        val repository = repository(
            deepSeekClient = deepSeekClient(
                """{"data":[
                    {"id":" deepseek-v4-flash "},
                    {"id":"deepseek-v4-flash"},
                    {"id":"future-id"},
                    {"id":"  "}
                ]}""",
            ),
        )

        val models = repository.refreshDeepSeekModels()
        val settings = settingsDataStore.settings.first()

        assertEquals(listOf("deepseek-v4-flash", "future-id"), models)
        assertEquals(listOf("deepseek-v4-flash", "future-id"), settings.deepSeekCachedModelIds)
        assertEquals(DeepSeekModels.V4_PRO, settings.model)
        assertTrue(settings.deepSeekModelsUpdatedAtMillis > 0L)
        assertTrue(settings.deepSeekModelDirectory.selectedMissingFromCatalog)
    }

    @Test
    fun deepSeekRefresh_failureEmptyOrInvalid_keepsOldCacheAndSelection() = runBlocking {
        seedDeepSeek(
            model = "kept-selection",
            cached = listOf("cached-a", "cached-b"),
            updatedAt = 11L,
        )

        listOf(
            500 to """{"data":[{"id":"should-not-cache"}]}""",
            200 to """{"data":[]}""",
            200 to "{not-json",
        ).forEach { (code, body) ->
            val repository = repository(deepSeekClient = deepSeekClient(body, statusCode = code))
            val error = runCatching { repository.refreshDeepSeekModels() }.exceptionOrNull()
            assertTrue("status=$code", error != null)
            val settings = settingsDataStore.settings.first()
            assertEquals(listOf("cached-a", "cached-b"), settings.deepSeekCachedModelIds)
            assertEquals("kept-selection", settings.model)
            assertEquals(11L, settings.deepSeekModelsUpdatedAtMillis)
        }
    }

    @Test
    fun deepSeekRefresh_staleSnapshot_doesNotWriteOldResultAfterKeyOrUrlChange() = runBlocking {
        seedDeepSeek(model = DeepSeekModels.FLASH, cached = listOf("old-cache"))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val repository = repository(
            deepSeekClient = DeepSeekApiClient(
                delayedClient(started, release, SUCCESS_BODY),
                NO_OP_LOGGER,
            ),
        )

        val refresh = async(Dispatchers.IO) { runCatching { repository.refreshDeepSeekModels() } }
        withTimeout(5_000) {
            while (started.count > 0L) delay(1)
        }
        repository.saveApiKey("new-key")
        repository.updateSettings { it.copy(baseUrl = "https://api.deepseek.com/v2") }
        release.countDown()

        val result = refresh.await()
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("已变化"))
        val settings = settingsDataStore.settings.first()
        assertEquals(listOf("old-cache"), settings.deepSeekCachedModelIds)
        assertEquals("https://api.deepseek.com/v2", settings.baseUrl)
        assertEquals("new-key", deepSeekKeys.value)
        assertEquals(DeepSeekModels.FLASH, settings.model)
    }

    @Test
    fun customProviderRefresh_stillReplacesModelsAndFallsBackSelectedWhenMissing() = runBlocking {
        val provider = CustomProvider(
            id = "p1",
            name = "自建",
            baseUrl = "https://example.test/v1",
            models = listOf("old-a", "old-b"),
            selectedModel = "old-b",
        )
        settingsDataStore.updateSettings {
            ChatSettings(
                provider = ChatProviderId.CUSTOM_OPENAI,
                customProviders = listOf(provider),
                activeCustomProviderId = "p1",
            )
        }
        providerKeys.saveApiKey("p1", "provider-key")
        val repository = repository(
            openAiClient = OpenAiCompatibleClient(
                clientReturning("""{"data":[{"id":"new-a"},{"id":"new-c"}]}"""),
                NO_OP_LOGGER,
            ),
        )

        val models = repository.refreshProviderModels("p1")
        val updated = settingsDataStore.settings.first().customProviders.single()

        assertEquals(listOf("new-a", "new-c"), models)
        assertEquals(listOf("new-a", "new-c"), updated.models)
        assertEquals("new-a", updated.selectedModel)
    }

    private suspend fun seedDeepSeek(
        model: String,
        cached: List<String> = emptyList(),
        updatedAt: Long = 0L,
    ) {
        settingsDataStore.updateSettings {
            ChatSettings(
                model = model,
                deepSeekCachedModelIds = cached,
                deepSeekModelsUpdatedAtMillis = updatedAt,
            )
        }
    }

    private fun repository(
        deepSeekClient: DeepSeekApiClient = DeepSeekApiClient(clientReturning(SUCCESS_BODY), NO_OP_LOGGER),
        openAiClient: OpenAiCompatibleClient = OpenAiCompatibleClient(clientReturning(SUCCESS_BODY), NO_OP_LOGGER),
    ) = SettingsRepositoryImpl(
        settingsDataStore = settingsDataStore,
        apiKeyStore = deepSeekKeys,
        providerKeyStore = providerKeys,
        openAiCompatibleClient = openAiClient,
        deepSeekApiClient = deepSeekClient,
        legacyCliProxyApiKeyStore = UnusedLegacyKeys(),
    )

    private fun deepSeekClient(body: String, statusCode: Int = 200) =
        DeepSeekApiClient(clientReturning(body, statusCode), NO_OP_LOGGER)

    private fun clientReturning(body: String, statusCode: Int = 200): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message("test")
                    .body(body.toResponseBody(JSON_MEDIA_TYPE))
                    .build()
            }
            .build()

    private fun delayedClient(
        started: CountDownLatch,
        release: CountDownLatch,
        body: String,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("test")
                .body(body.toResponseBody(JSON_MEDIA_TYPE))
                .build()
        }
        .build()

    private class MemoryDeepSeekKeys(initial: String? = null) : DeepSeekCredentialStore {
        @Volatile var value: String? = initial
        override suspend fun getApiKey(): String? = value
        override suspend fun saveApiKey(apiKey: String) {
            value = apiKey
        }
        override suspend fun clearApiKey() {
            value = null
        }
    }

    private class MemoryProviderKeys : ProviderCredentialStore {
        private val keys = ConcurrentHashMap<String, String>()
        override suspend fun getApiKey(providerId: String): String? = keys[providerId]
        override suspend fun saveApiKey(providerId: String, apiKey: String) {
            keys[providerId] = apiKey
        }
        override suspend fun clearApiKey(providerId: String) {
            keys.remove(providerId)
        }
        override fun hasApiKey(providerId: String): Boolean = keys.containsKey(providerId)
    }

    private class UnusedLegacyKeys : LegacyCliProxyCredentialStore {
        override suspend fun getApiKey(): String? = error("legacy unused")
        override suspend fun saveApiKey(apiKey: String) = error("legacy unused")
        override suspend fun clearApiKey() = error("legacy unused")
    }

    private companion object {
        const val SUCCESS_BODY =
            """{"data":[{"id":"deepseek-v4-flash"},{"id":"deepseek-v4-pro"}]}"""
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val NO_OP_LOGGER = object : RemoteClientLogger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
        }
    }
}
