package com.muxuechat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProviderAuthKindTest {
    @Test
    fun xaiProvider_baseUrlIsAlwaysPinnedToSubscriptionHttpsEndpoint() {
        listOf("", "http://192.168.1.2/v1", "https://evil.example/v1", "not a url").forEach { input ->
            val provider = CustomProvider(
                id = "x",
                name = "xAI",
                baseUrl = input,
                authKind = ProviderAuthKind.XAI_OAUTH,
            ).normalized()

            assertEquals(input, "https://cli-chat-proxy.grok.com/v1", provider.baseUrl)
            assertFalse(CustomProvider.isCleartext(provider.baseUrl))
        }
    }

    @Test
    fun apiKeyProvider_normalizationIsUnchanged() {
        val provider = CustomProvider(id = "p", name = " Local ", baseUrl = " http://10.0.0.2:8317/v1/ ").normalized()

        assertEquals(ProviderAuthKind.API_KEY, provider.authKind)
        assertEquals("http://10.0.0.2:8317/v1", provider.baseUrl)
        assertEquals("Local", provider.name)
        assertFalse(provider.usesXaiOAuth)
    }

    @Test
    fun xaiProvider_isConfiguredOnceModelIsChosen() {
        val provider = CustomProvider(id = "x", name = "xAI", baseUrl = "", selectedModel = "grok-4", authKind = ProviderAuthKind.XAI_OAUTH)
            .normalized()

        assertTrue(provider.isConfigured)
        assertTrue(provider.usesXaiOAuth)
    }

    @Test
    fun storageValues_roundTrip() {
        ProviderAuthKind.entries.forEach { assertEquals(it, ProviderAuthKind.fromStorage(it.storageValue)) }
        assertEquals(ProviderAuthKind.API_KEY, ProviderAuthKind.fromStorage(null))
    }
}
