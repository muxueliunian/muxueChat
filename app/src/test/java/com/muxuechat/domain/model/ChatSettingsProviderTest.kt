package com.muxuechat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSettingsProviderTest {
    private fun provider(
        id: String = "p1",
        name: String = "CLIProxyAPI",
        baseUrl: String = "https://192.168.10.20:8317/v1",
        models: List<String> = listOf("xai/grok-4.5"),
        selectedModel: String = "xai/grok-4.5",
        toolCallingEnabled: Boolean = false,
    ) = CustomProvider(
        id = id,
        name = name,
        baseUrl = baseUrl,
        models = models,
        selectedModel = selectedModel,
        toolCallingEnabled = toolCallingEnabled,
    )

    @Test
    fun defaultsKeepExistingDeepSeekConfiguration() {
        val settings = ChatSettings()

        assertEquals(ChatProviderId.DEEPSEEK, settings.provider)
        assertEquals(ChatSettings.DEFAULT_BASE_URL, settings.activeBaseUrl)
        assertEquals(DeepSeekModel.V4_FLASH.apiName, settings.activeModel)
        assertTrue(settings.usesDeepSeekParameters)
        assertFalse(settings.isCustomProvider)
        assertTrue(settings.samplingParametersEffective)
    }

    @Test
    fun customProviderUsesItsOwnEndpointAndSelectedModel() {
        val settings = ChatSettings(
            provider = ChatProviderId.CUSTOM_OPENAI,
            baseUrl = "https://custom.deepseek.example",
            model = DeepSeekModel.V4_PRO,
            customProviders = listOf(provider(baseUrl = " https://192.168.10.20:8317/v1/ ")),
            activeCustomProviderId = "p1",
            thinkingMode = ThinkingMode.ENABLED,
        ).normalized()

        assertEquals("https://192.168.10.20:8317/v1", settings.activeBaseUrl)
        assertEquals("xai/grok-4.5", settings.activeModel)
        assertEquals("CLIProxyAPI", settings.activeProviderLabel)
        assertTrue(settings.isCustomProvider)
        assertFalse(settings.usesDeepSeekParameters)
        assertTrue(settings.isCustomProviderConfigured)
        // thinking 是 DeepSeek 专属协议，不能据此把自定义供应商的采样参数标为无效。
        assertTrue(settings.samplingParametersEffective)
    }

    @Test
    fun multipleProvidersSwitchIndependently() {
        val settings = ChatSettings(
            provider = ChatProviderId.CUSTOM_OPENAI,
            customProviders = listOf(
                provider(id = "p1", name = "A", baseUrl = "https://a.example/v1", models = listOf("m-a"), selectedModel = "m-a"),
                provider(id = "p2", name = "B", baseUrl = "https://b.example/v1", models = listOf("m-b"), selectedModel = "m-b"),
            ),
            activeCustomProviderId = "p2",
        ).normalized()

        assertEquals("https://b.example/v1", settings.activeBaseUrl)
        assertEquals("m-b", settings.activeModel)
        assertEquals("B", settings.activeProviderLabel)
    }

    /** 局域网自建服务常常没有证书，经用户确认允许明文 http。 */
    @Test
    fun customProviderAcceptsCleartextHttp() {
        val settings = ChatSettings(
            provider = ChatProviderId.CUSTOM_OPENAI,
            customProviders = listOf(provider(baseUrl = " http://192.168.10.20:8317/v1/ ")),
            activeCustomProviderId = "p1",
        ).normalized()

        assertEquals("http://192.168.10.20:8317/v1", settings.activeBaseUrl)
        assertTrue(settings.isCustomProviderConfigured)
        assertTrue(CustomProvider.isCleartext(settings.activeBaseUrl))
        assertFalse(CustomProvider.isCleartext("https://example.test/v1"))
    }

    @Test
    fun customProviderRejectsUnsupportedOrCredentialBearingUrls() {
        listOf(
            "ftp://192.168.10.20:8317/v1",
            "https://token@example.test:8317/v1",
            "https://example.test:8317/v1?api_key=should-not-be-stored",
            "https://example.test:8317/v1#fragment",
            "not a URL",
        ).forEach { value ->
            val settings = ChatSettings(
                provider = ChatProviderId.CUSTOM_OPENAI,
                customProviders = listOf(provider(baseUrl = value)),
                activeCustomProviderId = "p1",
            ).normalized()

            assertEquals("", settings.activeBaseUrl)
            assertFalse(settings.isCustomProviderConfigured)
        }
    }

    @Test
    fun activeProviderIdFallsBackWhenItPointsAtDeletedProvider() {
        val settings = ChatSettings(
            provider = ChatProviderId.CUSTOM_OPENAI,
            customProviders = listOf(provider(id = "p1")),
            activeCustomProviderId = "already-deleted",
        ).normalized()

        assertEquals("p1", settings.activeCustomProviderId)
        assertEquals("https://192.168.10.20:8317/v1", settings.activeBaseUrl)
    }

    @Test
    fun activeProviderIdIsNullWhenNoProvidersExist() {
        val settings = ChatSettings(
            provider = ChatProviderId.CUSTOM_OPENAI,
            activeCustomProviderId = "already-deleted",
        ).normalized()

        assertNull(settings.activeCustomProviderId)
        assertFalse(settings.isCustomProviderConfigured)
    }

    @Test
    fun customProviderToolsRequirePerProviderOptInInAdditionToGlobalSwitch() {
        val disabled = ChatSettings(
            provider = ChatProviderId.CUSTOM_OPENAI,
            customProviders = listOf(provider(toolCallingEnabled = false)),
            activeCustomProviderId = "p1",
        )
        val enabled = disabled.copy(
            customProviders = listOf(provider(toolCallingEnabled = true)),
        )

        assertFalse(disabled.settingsToolsEnabledForActiveProvider)
        assertTrue(enabled.settingsToolsEnabledForActiveProvider)
        assertFalse(enabled.copy(settingsToolsEnabled = false).settingsToolsEnabledForActiveProvider)
        assertTrue(ChatSettings().settingsToolsEnabledForActiveProvider)
    }

    @Test
    fun unknownProviderStorageValueFallsBackToDeepSeek() {
        assertEquals(ChatProviderId.DEEPSEEK, ChatProviderId.fromStorage(null))
        assertEquals(ChatProviderId.DEEPSEEK, ChatProviderId.fromStorage("future_provider"))
        assertEquals(ChatProviderId.CUSTOM_OPENAI, ChatProviderId.fromStorage("custom_openai"))
        // 一般化改造前写入的旧值必须继续落到 OpenAI 兼容链路。
        assertEquals(ChatProviderId.CUSTOM_OPENAI, ChatProviderId.fromStorage("cli_proxy_api"))
    }
}
