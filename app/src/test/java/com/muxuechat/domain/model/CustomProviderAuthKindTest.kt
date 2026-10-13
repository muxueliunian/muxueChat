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
    fun googleProvider_baseUrlIsDerivedFromProjectIdAndPinned() {
        listOf("", "http://192.168.1.2/v1", "https://evil.example/v1", "not a url").forEach { input ->
            val provider = CustomProvider(
                id = "g",
                name = "Google",
                baseUrl = input,
                selectedModel = "google/gemini-4-argon",
                authKind = ProviderAuthKind.GOOGLE_OAUTH,
                googleProjectId = " my-project-123456 ",
                conversationHeaderName = "x-session",
            ).normalized()

            assertEquals(
                input,
                "https://aiplatform.googleapis.com/v1/projects/my-project-123456/locations/global/endpoints/openapi",
                provider.baseUrl,
            )
            assertEquals("my-project-123456", provider.googleProjectId)
            // OAuth 供应商不接收用户配置的会话标识头。
            assertEquals("", provider.conversationHeaderName)
            assertTrue(provider.usesGoogleOAuth && provider.usesOAuth && !provider.usesXaiOAuth)
            assertTrue(provider.isConfigured)
            assertFalse(CustomProvider.isCleartext(provider.baseUrl))
        }
    }

    @Test
    fun googleProvider_invalidProjectIdZeroesAddressSoItIsNotConfigured() {
        listOf("", "ab", "My-Project-1", "-bad-start-1", "bad-end-1-", "has space 123", "a/b/c/d/e/f", "x".repeat(31)).forEach { id ->
            val provider = CustomProvider(
                id = "g",
                name = "Google",
                baseUrl = "",
                selectedModel = "google/gemini-4-argon",
                authKind = ProviderAuthKind.GOOGLE_OAUTH,
                googleProjectId = id,
            ).normalized()

            assertEquals(id, "", provider.baseUrl)
            assertFalse(id, provider.isConfigured)
        }
        assertEquals("", CustomProvider.googleBaseUrl("evil.example/../x"))
    }

    @Test
    fun nonGoogleProvider_dropsStrayProjectId() {
        val provider = CustomProvider(
            id = "p", name = "n", baseUrl = "https://a.example/v1", selectedModel = "m",
            googleProjectId = "my-project-123456",
        ).normalized()

        assertEquals("", provider.googleProjectId)
        assertEquals("https://a.example/v1", provider.baseUrl)
    }

    @Test
    fun storageValues_roundTrip() {
        ProviderAuthKind.entries.forEach { assertEquals(it, ProviderAuthKind.fromStorage(it.storageValue)) }
        assertEquals(ProviderAuthKind.API_KEY, ProviderAuthKind.fromStorage(null))
    }
}
