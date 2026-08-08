package com.muxuechat.data.remote

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekRequestResponseTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    @Test
    fun encodeRequest_includesStreamTrueAndMaxTokens() {
        val request = DeepSeekChatRequest(
            model = "deepseek-v4-flash",
            messages = listOf(DeepSeekMessageDto(role = "user", content = "Hello")),
            stream = true,
            maxTokens = 4096,
            thinking = DeepSeekThinkingDto(type = "enabled", reasoningEffort = "high"),
        )

        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"model\":\"deepseek-v4-flash\""))
        assertTrue(encoded.contains("\"stream\":true"))
        assertTrue(encoded.contains("\"max_tokens\":4096"))
        // reasoning_effort 必须嵌在 thinking 里，不能是顶层字段。
        assertTrue(encoded.contains("\"thinking\":{\"type\":\"enabled\",\"reasoning_effort\":\"high\"}"))
    }

    /**
     * 回归：`encodeDefaults = false` 曾把 `type":"function"` 整个省掉，
     * 服务端返回 400 `tools[0]: missing field type`。
     */
    @Test
    fun encodeRequest_alwaysIncludesToolTypeEvenWithEncodeDefaultsOff() {
        val request = DeepSeekChatRequest(
            model = "deepseek-v4-flash",
            messages = listOf(DeepSeekMessageDto(role = "user", content = "建个角色卡")),
            stream = true,
            tools = SettingsToolCatalog.tools(),
            toolChoice = SettingsToolCatalog.TOOL_CHOICE_AUTO,
        )

        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"tools\":[{\"type\":\"function\""))
        assertEquals(
            SettingsToolCatalog.tools().size,
            Regex("\"type\":\"function\"").findAll(encoded).count(),
        )
    }

    @Test
    fun encodeAssistantToolCall_includesTypeAndOmitsStreamIndex() {
        val message = DeepSeekMessageDto(
            role = "assistant",
            toolCalls = listOf(
                DeepSeekToolCallDto(
                    id = "call_1",
                    function = DeepSeekFunctionCallDto(name = "create_role_card", arguments = "{}"),
                ),
            ),
        )

        val encoded = json.encodeToString(message)

        assertTrue(encoded.contains("\"type\":\"function\""))
        assertTrue(!encoded.contains("\"index\""))
    }

    @Test
    fun decodeNonStreamResponse_readsAssistantMessageReasoningAndUsage() {
        val response = json.decodeFromString<DeepSeekChatChunkDto>(
            """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "最终回答",
                    "reasoning_content": "推理过程"
                  },
                  "finish_reason": "stop"
                }
              ],
              "usage": { "total_tokens": 24 }
            }
            """.trimIndent(),
        )

        val choice = response.choices.first()
        assertEquals("最终回答", choice.message?.content)
        assertEquals("推理过程", choice.message?.reasoningContent)
        assertEquals("stop", choice.finishReason)
        assertEquals(24, response.usage?.totalTokens)
    }
}
