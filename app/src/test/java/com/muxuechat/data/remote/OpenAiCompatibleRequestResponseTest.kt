package com.muxuechat.data.remote

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleRequestResponseTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val request = OpenAiCompatibleChatRequest(
        model = "grok-4.5",
        messages = listOf(
            OpenAiCompatibleMessageDto(role = "system", content = "你是一个助手"),
            OpenAiCompatibleMessageDto(role = "user", content = "你好"),
        ),
        stream = true,
        maxTokens = 4096,
        temperature = 0.7,
        topP = 0.9,
    )

    @Test
    fun encodeRequest_containsOnlyOpenAiCompatibleNormalChatFields() {
        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"model\":\"grok-4.5\""))
        assertTrue(encoded.contains("\"messages\""))
        assertTrue(encoded.contains("\"stream\":true"))
        assertTrue(encoded.contains("\"max_tokens\":4096"))
        assertTrue(encoded.contains("\"temperature\":0.7"))
        assertTrue(encoded.contains("\"top_p\":0.9"))
        assertFalse(encoded.contains("thinking"))
        assertFalse(encoded.contains("reasoning"))
        assertFalse(encoded.contains("tool"))
        assertFalse(encoded.contains("oauth"))
    }

    @Test
    fun encodeRequest_withStandardToolsIncludesToolChoiceAndRequiredTypes() {
        val requestWithTools = request.copy(
            tools = SettingsToolCatalog.openAiCompatibleTools(),
            toolChoice = SettingsToolCatalog.TOOL_CHOICE_AUTO,
        )

        val encoded = json.encodeToString(requestWithTools)

        assertTrue(encoded.contains("\"tools\":[{\"type\":\"function\""))
        assertTrue(encoded.contains("\"tool_choice\":\"auto\""))
        assertTrue(encoded.contains("create_role_card"))
        assertTrue(encoded.contains("create_world_setting"))
        assertTrue(encoded.contains("create_writing_style"))
        assertEquals(
            SettingsToolCatalog.openAiCompatibleTools().size,
            Regex("\"type\":\"function\"").findAll(encoded).count(),
        )
    }

    @Test
    fun encodeToolExchange_allowsAssistantToolOnlyMessageAndReturnsToolCallId() {
        val assistant = OpenAiCompatibleMessageDto(
            role = "assistant",
            toolCalls = listOf(
                OpenAiCompatibleToolCallDto(
                    id = "call_1",
                    function = OpenAiCompatibleFunctionCallDto(
                        name = "create_role_card",
                        arguments = "{}",
                    ),
                ),
            ),
        )
        val result = OpenAiCompatibleMessageDto(
            role = "tool",
            content = "草稿已创建",
            toolCallId = "call_1",
        )

        val assistantEncoded = json.encodeToString(assistant)
        val resultEncoded = json.encodeToString(result)

        assertFalse(assistantEncoded.contains("\"content\""))
        assertTrue(assistantEncoded.contains("\"tool_calls\""))
        assertTrue(assistantEncoded.contains("\"type\":\"function\""))
        assertFalse(assistantEncoded.contains("\"index\""))
        assertTrue(resultEncoded.contains("\"tool_call_id\":\"call_1\""))
        assertTrue(resultEncoded.contains("\"content\":\"草稿已创建\""))
    }

    @Test
    fun encodeRequest_keepsStreamFalseInsteadOfOmittingIt() {
        val encoded = json.encodeToString(
            request.copy(stream = false, maxTokens = null, temperature = null, topP = null),
        )

        assertTrue(encoded.contains("\"stream\":false"))
        assertFalse(encoded.contains("max_tokens"))
        assertFalse(encoded.contains("temperature"))
        assertFalse(encoded.contains("top_p"))
    }

    @Test
    fun decodeNonStreamResponse_readsAssistantContentAndUsage() {
        val response = json.decodeFromString<OpenAiCompatibleChatChunkDto>(
            """
            {
              "choices": [
                {
                  "message": { "role": "assistant", "content": "正常回复" },
                  "finish_reason": "stop"
                }
              ],
              "usage": { "total_tokens": 24 }
            }
            """.trimIndent(),
        )

        val choice = response.choices.first()
        assertEquals("assistant", choice.message?.role)
        assertEquals("正常回复", choice.message?.content)
        assertEquals("stop", choice.finishReason)
        assertEquals(24, response.usage?.totalTokens)
    }

    @Test
    fun decodeNonStreamResponse_readsToolCallsWithoutAssistantContent() {
        val response = json.decodeFromString<OpenAiCompatibleChatChunkDto>(
            """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "tool_calls": [
                      {
                        "id": "call_1",
                        "type": "function",
                        "function": {"name": "create_world_setting", "arguments": "{\"title\":\"雨城\"}"}
                      }
                    ]
                  },
                  "finish_reason": "tool_calls"
                }
              ]
            }
            """.trimIndent(),
        )

        val choice = response.choices.first()
        val call = choice.message?.toolCalls?.single()
        assertNull(choice.message?.content)
        assertEquals("tool_calls", choice.finishReason)
        assertEquals("call_1", call?.id)
        assertEquals("create_world_setting", call?.function?.name)
        assertEquals("{\"title\":\"雨城\"}", call?.function?.arguments)
    }

    @Test
    fun buildRequest_usesHttpsChatCompletionsBearerKeyAndSseAccept() {
        val client = OpenAiCompatibleClient()

        val built = client.buildRequest(
            apiKey = "proxy-key-value",
            baseUrl = "https://192.168.1.20:8317/v1/",
            requestDto = request,
            acceptsEventStream = true,
        )

        assertEquals("https://192.168.1.20:8317/v1/chat/completions", built.url.toString())
        assertEquals("Bearer proxy-key-value", built.header("Authorization"))
        assertEquals("text/event-stream", built.header("Accept"))
        assertEquals("application/json", built.header("Content-Type")?.substringBefore(';'))
        assertEquals("POST", built.method)
        assertTrue(built.body!!.writeToString().contains("\"stream\":true"))
    }

    @Test
    fun buildRequest_rejectsUnsupportedSchemeOrCredentialBearingEndpoint() {
        val client = OpenAiCompatibleClient()

        val schemeError = runCatching {
            client.buildRequest("proxy-key", "ftp://192.168.1.20:8317/v1", request, false)
        }.exceptionOrNull()
        val userInfoError = runCatching {
            client.buildRequest("proxy-key", "https://name:secret@192.168.1.20:8317/v1", request, false)
        }.exceptionOrNull()
        val queryError = runCatching {
            client.buildRequest("proxy-key", "https://192.168.1.20:8317/v1?key=secret", request, false)
        }.exceptionOrNull()

        assertTrue(schemeError is IllegalArgumentException)
        assertTrue(userInfoError is IllegalArgumentException)
        assertTrue(queryError is IllegalArgumentException)
    }

    /** 局域网自建服务常常没有证书，经用户确认允许明文 http。 */
    @Test
    fun buildRequest_acceptsCleartextHttpEndpoint() {
        val client = OpenAiCompatibleClient()

        val built = client.buildRequest("proxy-key", "http://192.168.1.20:8317/v1", request, false)

        assertEquals("http://192.168.1.20:8317/v1/chat/completions", built.url.toString())
    }

    /** /models 各家返回的附加字段差异很大，解析只能依赖 id，其余一律忽略。 */
    @Test
    fun modelsResponse_parsesIdsAndIgnoresUnknownFields() {
        val payload = """
            {
              "object": "list",
              "data": [
                {"id": "grok-4.5", "object": "model", "owned_by": "xai", "created": 1},
                {"id": "gpt-5", "permission": [{"id": "x"}]}
              ]
            }
        """.trimIndent()

        val parsed = json.decodeFromString<OpenAiCompatibleModelsResponseDto>(payload)

        assertEquals(listOf("grok-4.5", "gpt-5"), parsed.data.map { it.id })
    }

    /** 服务只返回 `{}` 或缺少 data 时不能抛异常，上层要能据空列表提示用户手填。 */
    @Test
    fun modelsResponse_withoutDataFieldYieldsEmptyList() {
        val parsed = json.decodeFromString<OpenAiCompatibleModelsResponseDto>("""{"object":"list"}""")

        assertTrue(parsed.data.isEmpty())
    }

    private fun okhttp3.RequestBody.writeToString(): String = Buffer().use { buffer ->
        writeTo(buffer)
        buffer.readUtf8()
    }
}
