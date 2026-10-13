package com.muxuechat.data.remote

import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.OpenAiOutputTokenField
import com.muxuechat.domain.model.ProviderAuthKind
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
    fun configuredCompletionBudget_isExclusiveForEveryPurpose() {
        val configured = settings.copy(customProviders = settings.customProviders.map {
            it.copy(outputTokenField = OpenAiOutputTokenField.MAX_COMPLETION_TOKENS)
        })
        OpenAiCompatibleRequestFactory.Purpose.entries.forEach { purpose ->
            val legacy = OpenAiCompatibleRequestFactory.build(settings, "model", messages, purpose)
            val request = OpenAiCompatibleRequestFactory.build(configured, "model", messages, purpose)
            assertNull(request.maxTokens)
            assertEquals(legacy.maxTokens, request.maxCompletionTokens)
            val encoded = json.encodeToString(request)
            assertTrue(encoded.contains("\"max_completion_tokens\":"))
            assertFalse(encoded.contains("\"max_tokens\":"))
        }
    }

    @Test
    fun xaiKeepsAdapterBudgetAndOmitsUnsupportedSampling() {
        val configured = settings.copy(customProviders = settings.customProviders.map {
            it.copy(authKind = ProviderAuthKind.XAI_OAUTH,
                outputTokenField = OpenAiOutputTokenField.MAX_COMPLETION_TOKENS)
        })
        val request = OpenAiCompatibleRequestFactory.build(
            configured, "grok", messages, OpenAiCompatibleRequestFactory.Purpose.CHAT,
        )
        assertEquals(settings.maxTokens, request.maxTokens)
        assertNull(request.maxCompletionTokens)
        assertNull(request.temperature)
        assertNull(request.topP)
    }

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

    /**
     * `stream_options` 默认必须完全不出现在请求体里。
     *
     * 它是可选字段，只支持普通聊天的旧反代收到未知字段可能直接 400——
     * 为了拿用量而让所有存量供应商冒这个风险是不划算的。
     */
    @Test
    fun streamOptions_areOmittedByDefault() {
        val request = OpenAiCompatibleRequestFactory.build(
            settings = settings,
            model = "grok-4.5",
            messages = messages,
            purpose = OpenAiCompatibleRequestFactory.Purpose.CHAT,
        )

        assertNull(request.streamOptions)
        assertFalse(json.encodeToString(request).contains("stream_options"))
    }

    @Test
    fun streamOptions_areSentOnlyWhenExplicitlyEnabled() {
        val request = OpenAiCompatibleRequestFactory.build(
            settings = settings,
            model = "grok-4.5",
            messages = messages,
            purpose = OpenAiCompatibleRequestFactory.Purpose.CHAT,
            includeStreamUsage = true,
        )

        assertTrue(request.stream)
        assertEquals(true, request.streamOptions?.includeUsage)
        assertTrue(json.encodeToString(request).contains("\"include_usage\":true"))
    }

    /** 非流式响应的 usage 本来就在响应体里，多发这个字段只会增加被拒的风险。 */
    @Test
    fun streamOptions_areOmittedForNonStreamingRequests() {
        val request = OpenAiCompatibleRequestFactory.build(
            settings = settings.copy(streamEnabled = false),
            model = "grok-4.5",
            messages = messages,
            purpose = OpenAiCompatibleRequestFactory.Purpose.CHAT,
            includeStreamUsage = true,
        )

        assertFalse(request.stream)
        assertNull(request.streamOptions)
    }

    /** 内部结构化请求一律不带该字段。 */
    @Test
    fun streamOptions_areNeverSentForInternalPurposes() {
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
                includeStreamUsage = true,
            )
            assertNull(purpose.name, request.streamOptions)
        }
    }

    /**
     * 摘要请求的输出上限必须和上下文规划器为它预留的空间是同一个数字。
     * 两边各写一份迟早漂移，届时规划器会以为还装得下，而服务端已经拒绝了。
     */
    @Test
    fun summaryMaxTokens_matchesThePlannerReservation() {
        assertEquals(
            com.muxuechat.domain.context.ChatContextPlanner.SUMMARY_OUTPUT_RESERVE_TOKENS,
            OpenAiCompatibleRequestFactory.CONTEXT_SUMMARY_MAX_TOKENS,
        )
        assertEquals(
            com.muxuechat.domain.context.ChatContextPlanner.SUMMARY_OUTPUT_RESERVE_TOKENS,
            DeepSeekRequestFactory.CONTEXT_SUMMARY_MAX_TOKENS,
        )
    }

    @Test
    fun providerSamplingSwitches_canOmitTemperatureAndTopPIndependently() {
        val provider = settings.customProviders.single()
        val withoutTemperature = settings.copy(
            customProviders = listOf(provider.copy(sendTemperature = false, sendTopP = true)),
        )
        val withoutTopP = settings.copy(
            customProviders = listOf(provider.copy(sendTemperature = true, sendTopP = false)),
        )

        val temperatureDisabled = OpenAiCompatibleRequestFactory.build(
            settings = withoutTemperature,
            model = "grok-4.5",
            messages = messages,
            purpose = OpenAiCompatibleRequestFactory.Purpose.CHAT,
        )
        val topPDisabled = OpenAiCompatibleRequestFactory.build(
            settings = withoutTopP,
            model = "grok-4.5",
            messages = messages,
            purpose = OpenAiCompatibleRequestFactory.Purpose.RP_METADATA,
        )

        assertNull(temperatureDisabled.temperature)
        assertEquals(0.8, temperatureDisabled.topP!!, 0.0001)
        assertFalse(json.encodeToString(temperatureDisabled).contains("\"temperature\""))
        assertNull(topPDisabled.topP)
        assertEquals(0.35, topPDisabled.temperature!!, 0.0001)
        assertFalse(json.encodeToString(topPDisabled).contains("\"top_p\""))
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
