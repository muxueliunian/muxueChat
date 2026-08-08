package com.muxuechat.data.remote

import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleRequestFactoryTest {
    private val json = Json { encodeDefaults = false }
    private val messages = listOf(OpenAiCompatibleMessageDto(role = "user", content = "你好"))
    private val settings = ChatSettings(
        provider = ChatProviderId.CUSTOM_OPENAI,
        customProviders = listOf(
            CustomProvider(
                id = "p1",
                name = "CLIProxyAPI",
                baseUrl = "https://192.168.1.20:8317/v1",
                models = listOf("grok-4.5"),
                selectedModel = "grok-4.5",
            ),
        ),
        activeCustomProviderId = "p1",
        streamEnabled = true,
        maxTokens = 4096,
        temperature = 1.1,
        topP = 0.8,
    )

    @Test
    fun chat_usesFrozenCliModelAndNormalGenerationParameters() {
        val request = OpenAiCompatibleRequestFactory.build(
            settings = settings,
            model = "grok-4.5",
            messages = messages,
            purpose = OpenAiCompatibleRequestFactory.Purpose.CHAT,
        )

        assertEquals("grok-4.5", request.model)
        assertTrue(request.stream)
        assertEquals(4096, request.maxTokens)
        assertEquals(1.1, request.temperature!!, 0.0001)
        assertEquals(0.8, request.topP!!, 0.0001)
        val encoded = json.encodeToString(request)
        assertFalse(encoded.contains("thinking"))
        assertFalse(encoded.contains("reasoning"))
        assertFalse(encoded.contains("tool"))
        assertFalse(encoded.contains("oauth"))
    }

    @Test
    fun auxiliaryPurposes_useIndependentNonStreamingBudgets() {
        val metadata = OpenAiCompatibleRequestFactory.build(
            settings = settings.copy(maxTokens = 32_000, streamEnabled = true),
            model = "grok-4.5",
            messages = messages,
            purpose = OpenAiCompatibleRequestFactory.Purpose.RP_METADATA,
        )
        val summary = OpenAiCompatibleRequestFactory.build(
            settings = settings,
            model = "grok-4.5",
            messages = messages,
            purpose = OpenAiCompatibleRequestFactory.Purpose.CONTEXT_SUMMARY,
        )

        assertFalse(metadata.stream)
        assertEquals(OpenAiCompatibleRequestFactory.RP_METADATA_MAX_TOKENS, metadata.maxTokens)
        assertEquals(0.35, metadata.temperature!!, 0.0001)
        assertEquals(1.0, metadata.topP!!, 0.0001)
        assertFalse(summary.stream)
        assertEquals(OpenAiCompatibleRequestFactory.CONTEXT_SUMMARY_MAX_TOKENS, summary.maxTokens)
        assertEquals(0.2, summary.temperature!!, 0.0001)
    }

    /** 用户没填思考等级时，请求体里绝不能出现 reasoning_effort：不支持的模型会直接 400。 */
    @Test
    fun chat_omitsReasoningEffortWhenUserLeftItBlank() {
        listOf(null, "", "   ").forEach { value ->
            val request = OpenAiCompatibleRequestFactory.build(
                settings = settings,
                model = "grok-4.5",
                messages = messages,
                purpose = OpenAiCompatibleRequestFactory.Purpose.CHAT,
                reasoningEffort = value,
            )

            assertNull(request.reasoningEffort)
            assertFalse(json.encodeToString(request).contains("reasoning_effort"))
        }
    }

    @Test
    fun chat_sendsUserProvidedReasoningEffortVerbatim() {
        // 不做枚举校验：各家档位不统一，用户填什么就发什么。
        val request = OpenAiCompatibleRequestFactory.build(
            settings = settings,
            model = "grok-4.5",
            messages = messages,
            purpose = OpenAiCompatibleRequestFactory.Purpose.CHAT,
            reasoningEffort = " xhigh ",
        )

        assertEquals("xhigh", request.reasoningEffort)
        assertTrue(json.encodeToString(request).contains("\"reasoning_effort\":\"xhigh\""))
    }

    @Test
    fun chat_preservesStandardToolsOnlyWhenTheyAreSupplied() {
        val tools = SettingsToolCatalog.openAiCompatibleTools()
        val request = OpenAiCompatibleRequestFactory.build(
            settings = settings,
            model = "grok-4.5",
            messages = messages,
            purpose = OpenAiCompatibleRequestFactory.Purpose.CHAT,
            tools = tools,
            toolChoice = SettingsToolCatalog.TOOL_CHOICE_AUTO,
        )

        assertEquals(tools, request.tools)
        assertEquals("auto", request.toolChoice)
        assertTrue(json.encodeToString(request).contains("\"tools\""))
        assertTrue(json.encodeToString(request).contains("\"tool_choice\":\"auto\""))
    }

    /** RP 补写与摘要是短结构化输出，让模型在这些请求上思考只会拖慢并烧 token。 */
    @Test
    fun auxiliaryPurposes_neverSendReasoningEffort() {
        listOf(
            OpenAiCompatibleRequestFactory.Purpose.RP_METADATA,
            OpenAiCompatibleRequestFactory.Purpose.RP_STATE_UPDATE,
            OpenAiCompatibleRequestFactory.Purpose.CONTEXT_SUMMARY,
        ).forEach { purpose ->
            val request = OpenAiCompatibleRequestFactory.build(
                settings = settings,
                model = "grok-4.5",
                messages = messages,
                purpose = purpose,
                reasoningEffort = "high",
            )

            assertNull(request.reasoningEffort)
        }
    }

    @Test
    fun auxiliaryPurposes_stripToolsEvenWhenCallerSuppliesThem() {
        val tools = SettingsToolCatalog.openAiCompatibleTools()

        listOf(
            OpenAiCompatibleRequestFactory.Purpose.RP_METADATA,
            OpenAiCompatibleRequestFactory.Purpose.RP_STATE_UPDATE,
            OpenAiCompatibleRequestFactory.Purpose.CONTEXT_SUMMARY,
        ).forEach { purpose ->
            val request = OpenAiCompatibleRequestFactory.build(
                settings = settings,
                model = "grok-4.5",
                messages = messages,
                purpose = purpose,
                tools = tools,
                toolChoice = SettingsToolCatalog.TOOL_CHOICE_AUTO,
            )

            assertNull(request.tools)
            assertNull(request.toolChoice)
            assertFalse(json.encodeToString(request).contains("\"tools\""))
        }
    }
}
