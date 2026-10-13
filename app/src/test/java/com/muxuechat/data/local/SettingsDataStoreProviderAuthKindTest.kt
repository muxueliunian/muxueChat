package com.muxuechat.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.ProviderAuthKind
import com.muxuechat.domain.model.OpenAiOutputTokenField
import org.junit.Assert.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDataStoreProviderAuthKindTest {
    private val dataStore = MemoryPreferencesDataStore()
    private val store = SettingsDataStore(dataStore)

    @Test
    fun compatibilityOptionsAndDefaultProtagonist_roundTripWithoutChangingOtherSettings() = runBlocking {
        store.updateSettings { it.copy(
            systemPrompt = "保留原提示",
            defaultProtagonistProfileId = "protagonist-id",
            customProviders = listOf(CustomProvider(
                id = "p", name = "端点", baseUrl = "https://example.test/v1", selectedModel = "m",
                outputTokenField = OpenAiOutputTokenField.MAX_COMPLETION_TOKENS,
                conversationHeaderName = "X-Opencode-Session",
            )),
        ) }
        val saved = store.settings.first()
        assertEquals("protagonist-id", saved.defaultProtagonistProfileId)
        assertEquals("x-opencode-session", saved.customProviders.single().conversationHeaderName)
        assertEquals(OpenAiOutputTokenField.MAX_COMPLETION_TOKENS, saved.customProviders.single().outputTokenField)
        store.updateSettings { it.copy(defaultProtagonistProfileId = null) }
        assertNull(store.settings.first().defaultProtagonistProfileId)
        assertEquals("保留原提示", store.settings.first().systemPrompt)
    }

    @Test
    fun unknownBudgetFieldAndReservedHeader_fallBackSafely() = runBlocking {
        dataStore.edit { prefs ->
            prefs[CUSTOM_PROVIDERS] = """[{"id":"p","name":"endpoint","baseUrl":"https://example.test/v1","outputTokenField":"unknown","conversationHeaderName":"Authorization"}]"""
        }
        val provider = store.settings.first().customProviders.single()
        assertEquals(OpenAiOutputTokenField.MAX_TOKENS, provider.outputTokenField)
        assertEquals("", provider.conversationHeaderName)
    }

    @Test
    fun legacyJsonWithoutAuthKind_readsAsApiKey() = runBlocking {
        dataStore.edit { prefs ->
            prefs[CUSTOM_PROVIDERS] = """[{"id":"p1","name":"Old","baseUrl":"http://10.0.0.2:8317/v1","selectedModel":"m"}]"""
        }

        val provider = store.settings.first().customProviders.single()

        assertEquals(ProviderAuthKind.API_KEY, provider.authKind)
        assertEquals("http://10.0.0.2:8317/v1", provider.baseUrl)
    }

    @Test
    fun unknownAuthKind_fallsBackToApiKey() = runBlocking {
        dataStore.edit { prefs ->
            prefs[CUSTOM_PROVIDERS] = """[{"id":"p1","name":"X","baseUrl":"https://a.example/v1","authKind":"future_kind"}]"""
        }

        assertEquals(ProviderAuthKind.API_KEY, store.settings.first().customProviders.single().authKind)
    }

    @Test
    fun apiKeyProvider_isStoredWithoutAuthKindField_soExistingDataIsByteIdentical() = runBlocking {
        store.updateSettings {
            it.copy(
                provider = ChatProviderId.CUSTOM_OPENAI,
                customProviders = listOf(CustomProvider(id = "p1", name = "Old", baseUrl = "https://a.example/v1", selectedModel = "m")),
            )
        }

        val raw = dataStore.data.first()[CUSTOM_PROVIDERS].orEmpty()
        assertFalse(raw, raw.contains("authKind"))
    }

    @Test
    fun xaiProvider_roundTripsAndStaysPinned() = runBlocking {
        store.updateSettings {
            it.copy(
                provider = ChatProviderId.CUSTOM_OPENAI,
                customProviders = listOf(
                    CustomProvider(
                        id = "x1",
                        name = CustomProvider.XAI_DEFAULT_NAME,
                        baseUrl = "http://evil.example/v1",
                        selectedModel = "grok-4",
                        authKind = ProviderAuthKind.XAI_OAUTH,
                    ),
                ),
            )
        }

        val raw = dataStore.data.first()[CUSTOM_PROVIDERS].orEmpty()
        assertTrue(raw, raw.contains("\"authKind\":\"xai_oauth\""))
        assertFalse(raw, raw.contains("evil.example"))
        val provider = store.settings.first().customProviders.single()
        assertEquals(ProviderAuthKind.XAI_OAUTH, provider.authKind)
        assertEquals(CustomProvider.XAI_SUBSCRIPTION_BASE_URL, provider.normalized().baseUrl)
    }

    private companion object {
        val CUSTOM_PROVIDERS = stringPreferencesKey("custom_providers")
    }
}
