package com.muxuechat.data.remote

import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.ReasoningEffort
import com.muxuechat.domain.model.ThinkingMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekRequestFactoryTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val messages = listOf(DeepSeekMessageDto(role = "user", content = "你好"))

    /**
     * DeepSeek 的 thinking 缺省值是 enabled。所以"关闭思考模式"必须显式发送 disabled，
     * 只是省略字段的话服务端仍然会思考——这正是修复前的行为。
     */
    @Test
    fun thinkingDisabled_sendsExplicitDisabledInsteadOfOmittingField() {
        val request = DeepSeekRequestFactory.build(
            settings = ChatSettings(thinkingMode = ThinkingMode.DISABLED),
            messages = messages,
            purpose = DeepSeekRequestFactory.Purpose.CHAT,
        )

        assertEquals("disabled", request.thinking?.type)
        assertTrue(json.encodeToString(request).contains("\"thinking\":{\"type\":\"disabled\"}"))
    }

    @Test
    fun thinkingDisabled_omitsReasoningEffortButKeepsSampling() {
        val request = DeepSeekRequestFactory.build(
            settings = ChatSettings(
                thinkingMode = ThinkingMode.DISABLED,
                temperature = 1.2,
                topP = 0.8,
            ),
            messages = messages,
            purpose = DeepSeekRequestFactory.Purpose.CHAT,
        )

        assertNull(request.thinking?.reasoningEffort)
        assertEquals(1.2, request.temperature!!, 0.0001)
        assertEquals(0.8, request.topP!!, 0.0001)
    }

    /** 思考开启时 temperature/top_p 会被服务端忽略，不应发送以免造成"设置生效"的错觉。 */
    @Test
    fun thinkingEnabled_dropsIneffectiveSamplingParameters() {
        val request = DeepSeekRequestFactory.build(
            settings = ChatSettings(
                thinkingMode = ThinkingMode.ENABLED,
                reasoningEffort = ReasoningEffort.MAX,
                temperature = 1.5,
                topP = 0.5,
            ),
            messages = messages,
            purpose = DeepSeekRequestFactory.Purpose.CHAT,
        )

        assertEquals("enabled", request.thinking?.type)
        assertEquals("max", request.thinking?.reasoningEffort)
        assertNull(request.temperature)
        assertNull(request.topP)

        val encoded = json.encodeToString(request)
        assertFalse(encoded.contains("temperature"))
        assertFalse(encoded.contains("top_p"))
    }

    /** presence/frequency penalty 已被 DeepSeek 废弃且永不生效，已整体移除，请求体里不应再出现。 */
    @Test
    fun deprecatedPenalties_neverAppearInEncodedBody() {
        listOf(ThinkingMode.ENABLED, ThinkingMode.DISABLED).forEach { mode ->
            val request = DeepSeekRequestFactory.build(
                settings = ChatSettings(thinkingMode = mode),
                messages = messages,
                purpose = DeepSeekRequestFactory.Purpose.CHAT,
            )

            val encoded = json.encodeToString(request)
            assertFalse("mode=$mode", encoded.contains("presence_penalty"))
            assertFalse("mode=$mode", encoded.contains("frequency_penalty"))
        }
    }

    @Test
    fun maxTokens_isClampedIntoValidRange() {
        val tooLarge = DeepSeekRequestFactory.build(
            settings = ChatSettings(maxTokens = 999_999_999),
            messages = messages,
            purpose = DeepSeekRequestFactory.Purpose.CHAT,
        )
        assertEquals(ChatSettings.MAX_MAX_TOKENS, tooLarge.maxTokens)

        val tooSmall = DeepSeekRequestFactory.build(
            settings = ChatSettings(maxTokens = 0),
            messages = messages,
            purpose = DeepSeekRequestFactory.Purpose.CHAT,
        )
        assertEquals(ChatSettings.MIN_MAX_TOKENS, tooSmall.maxTokens)
    }

    /** 结构化请求必须有独立预算，不能被普通聊天的 maxTokens / 思考开关污染。 */
    @Test
    fun structuredPurposes_useIndependentBudgetAndDisableThinking() {
        val chatSettings = ChatSettings(
            maxTokens = 32_000,
            thinkingMode = ThinkingMode.ENABLED,
            streamEnabled = true,
        )

        val metadata = DeepSeekRequestFactory.build(
            settings = chatSettings,
            messages = messages,
            purpose = DeepSeekRequestFactory.Purpose.RP_METADATA,
        )
        assertEquals(DeepSeekRequestFactory.RP_METADATA_MAX_TOKENS, metadata.maxTokens)
        assertEquals("disabled", metadata.thinking?.type)
        assertFalse(metadata.stream)

        val stateUpdate = DeepSeekRequestFactory.build(
            settings = chatSettings,
            messages = messages,
            purpose = DeepSeekRequestFactory.Purpose.RP_STATE_UPDATE,
        )
        assertEquals(DeepSeekRequestFactory.RP_STATE_UPDATE_MAX_TOKENS, stateUpdate.maxTokens)
        assertFalse(stateUpdate.stream)

        val generation = DeepSeekRequestFactory.build(
            settings = chatSettings,
            messages = messages,
            purpose = DeepSeekRequestFactory.Purpose.SETTINGS_GENERATION,
        )
        assertEquals(DeepSeekRequestFactory.SETTINGS_GENERATION_MAX_TOKENS, generation.maxTokens)
        assertFalse(generation.stream)
    }

    @Test
    fun chatPurpose_keepsStreamFlagInEncodedBody() {
        val request = DeepSeekRequestFactory.build(
            settings = ChatSettings(streamEnabled = true),
            messages = messages,
            purpose = DeepSeekRequestFactory.Purpose.CHAT,
        )

        assertTrue(request.stream)
        assertTrue(json.encodeToString(request).contains("\"stream\":true"))
    }
}
