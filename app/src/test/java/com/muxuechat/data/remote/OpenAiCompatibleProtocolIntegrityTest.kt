package com.muxuechat.data.remote

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleProtocolIntegrityTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun targetServicePathPrefixes_preserveChatAndModelEndpointsWithoutNetwork() = runBlocking {
        listOf(
            "https://api.commandcode.ai/provider/v1" to "/provider/v1",
            "https://opencode.ai/zen/go/v1" to "/zen/go/v1",
        ).forEach { (base, prefix) ->
            val chatRequests = mutableListOf<Request>()
            client("""{"choices":[{"message":{"content":"模拟回复"},"finish_reason":"stop"}]}""", observed = chatRequests)
                .completeChat(KEY, base, request(stream = false))
            assertEquals("$prefix/chat/completions", chatRequests.single().url.encodedPath)
            assertEquals("POST", chatRequests.single().method)
            val catalogRequests = mutableListOf<Request>()
            val ids = client("""{"data":[{"id":"sample-model"}]}""", observed = catalogRequests).listModels(KEY, base)
            assertEquals(listOf("sample-model"), ids)
            assertEquals("$prefix/models", catalogRequests.single().url.encodedPath)
            assertEquals("GET", catalogRequests.single().method)
            assertEquals("muxueChat/1.0", catalogRequests.single().header("User-Agent"))
        }
    }

    @Test
    fun refusalAndEveryFinishReason_surviveOrdinaryAndStreamingResponses() = runBlocking {
        val body = """{"choices":[{"message":{"content":null,"refusal":"无法回答"},"finish_reason":"stop"}]}"""
        val response = client(body).completeChat(KEY, BASE, request(stream = false))
        assertNull(response.choices.single().message?.content)
        assertEquals("无法回答", response.choices.single().message?.refusal)
        assertEquals("stop", response.choices.single().finishReason)
        val parser = OpenAiCompatibleStreamParser()
        listOf("stop", "length", "content_filter", "tool_calls", "future_reason").forEach { reason ->
            val delta = parser.parseLine(
                """data: {"choices":[{"delta":{"refusal":"无法回答"},"finish_reason":"$reason"}]}""",
            ) as OpenAiCompatibleStreamEvent.Delta
            assertEquals("无法回答", delta.refusal)
            assertNull(delta.content)
            assertEquals(reason, delta.finishReason)
        }
    }

    @Test
    fun usageOnlyChunk_isStillAvailableAfterTheFinishChunk() = runBlocking {
        val events = client(
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"total_tokens\":10}}\n\n" +
                "data: [DONE]\n\n",
        ).streamChat(KEY, BASE, request()).toList()
        assertEquals(3, events.size)
        assertEquals("stop", (events[0] as OpenAiCompatibleStreamEvent.Delta).finishReason)
        assertEquals(10, (events[1] as OpenAiCompatibleStreamEvent.Delta).usage?.totalTokens)
        assertEquals(OpenAiCompatibleStreamEvent.Done, events.last())
    }

    @Test
    fun errorEnvelope_preventsDoneAndCleansCredentialsInBothResponseModes() = runBlocking {
        val errorBody = """{"error":{"code":"$KEY","message":"拒绝 $KEY Bearer another-secret \u0001 ${"长".repeat(400)}"},"debug":"private-payload"}"""
        val logger = RecordingLogger()
        val events = mutableListOf<OpenAiCompatibleStreamEvent>()
        val streamingError = runCatching {
            client("data: {\"choices\":[{\"delta\":{\"content\":\"部分\"}}]}\n\n" +
                "data: $errorBody\n\ndata: [DONE]\n\n", logger)
                .streamChat(KEY, BASE, request()).collect { events += it }
        }.exceptionOrNull()
        assertTrue(events.none { it == OpenAiCompatibleStreamEvent.Done })
        val ordinaryError = runCatching {
            client(errorBody, logger).completeChat(KEY, BASE, request(stream = false))
        }.exceptionOrNull()
        listOf(streamingError, ordinaryError).forEach { error ->
            assertTrue(error is OpenAiCompatibleProtocolException)
            assertTrue(error is OpenAiCompatibleUserFacingError)
            assertTrue(error?.message.orEmpty().contains("拒绝"))
            assertTrue(error?.message.orEmpty().contains("<已隐藏>"))
            assertTrue(error?.message.orEmpty().length < 340)
            // Flow 跨协程恢复栈时可能复制异常，并把同一份安全异常挂作 cause。
            // 真正的约束是整个链均为已清理协议异常，不能带原始解析异常或敏感正文。
            val chain = generateSequence(error) { it.cause }.toList()
            assertTrue(chain.all { it is OpenAiCompatibleProtocolException })
            val shown = chain.joinToString() + logger.messages.joinToString()
            listOf(KEY, "another-secret", "private-payload", "\u0001").forEach { assertFalse(shown.contains(it)) }
        }
    }

    @Test
    fun malformedErrorEnvelope_failsWithoutShowingUntrustedFields() {
        val error = runCatching {
            OpenAiCompatibleStreamParser().parseLine("""data: {"error":"private-payload"}""", KEY)
        }.exceptionOrNull()
        assertEquals("供应商流式请求失败", error?.message)
        assertFalse(error.toString().contains("private-payload"))
    }

    @Test
    fun toolValidation_rejectsTruncatedArgumentsDuplicateIdsAndMissingNamesWithoutEcho() {
        val valid = call("one", "{}")
        assertEquals(listOf(valid), OpenAiCompatibleToolCallValidator.validate(listOf(valid)))
        listOf(
            listOf(call("one", "{\"private-payload\":")),
            listOf(call("one", "")),
            listOf(call("one", "[]")),
            listOf(valid, valid),
            listOf(valid.copy(function = valid.function?.copy(name = null))),
            listOf(valid.copy(type = "custom")),
        ).forEach { calls ->
            val error = runCatching { OpenAiCompatibleToolCallValidator.validate(calls) }.exceptionOrNull()
            assertTrue(error is OpenAiCompatibleProtocolException)
            assertNull(error?.cause)
            assertFalse(error.toString().contains("private-payload"))
        }
        val accumulator = OpenAiCompatibleToolCallAccumulator()
        accumulator.accept(listOf(valid.copy(index = 0)))
        val conflicting = runCatching { accumulator.accept(listOf(call("different", "").copy(index = 0))) }
        assertTrue(conflicting.exceptionOrNull() is OpenAiCompatibleProtocolException)
    }

    @Test
    fun outputBudgetFields_encodeTheSelectedNameAndOmitTheOther() {
        val modern = json.parseToJsonElement(json.encodeToString(request().copy(maxCompletionTokens = 123))).jsonObject
        assertEquals(123, modern.getValue("max_completion_tokens").jsonPrimitive.int)
        assertFalse(modern.containsKey("max_tokens"))
        val legacy = json.parseToJsonElement(json.encodeToString(request().copy(maxTokens = 456))).jsonObject
        assertEquals(456, legacy.getValue("max_tokens").jsonPrimitive.int)
        assertFalse(legacy.containsKey("max_completion_tokens"))
    }

    @Test
    fun conversationHeader_usesOwnUserAgentAndCannotReplaceAuthentication() = runBlocking {
        val observed = mutableListOf<Request>()
        val client = client("""{"choices":[{"message":{"content":"好"},"finish_reason":"stop"}]}""", observed = observed)
        client.completeChat(KEY, BASE, request(stream = false), "x-opencode-session", "conversation-123")
        assertEquals("muxueChat/1.0", observed.single().header("User-Agent"))
        assertEquals("conversation-123", observed.single().header("x-opencode-session"))
        assertEquals("Bearer $KEY", observed.single().header("Authorization"))
        val streamingRequests = mutableListOf<Request>()
        val streamingClient = client("data: [DONE]\n\n", observed = streamingRequests)
        repeat(2) {
            streamingClient.streamChat(KEY, BASE, request(), "x-opencode-session", "conversation-123").toList()
        }
        assertEquals(2, streamingRequests.size)
        streamingRequests.forEach {
            assertEquals("conversation-123", it.header("x-opencode-session"))
            assertEquals("muxueChat/1.0", it.header("User-Agent"))
        }
        val default = client.buildRequest(KEY, BASE, request(), true)
        assertNull(default.header("x-opencode-session"))
        listOf("Authorization", "User-Agent", "x-session\r\nAuthorization", "x-${"a".repeat(64)}").forEach { header ->
            val error = runCatching { client.buildRequest(KEY, BASE, request(), true, header, "conversation-123") }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertFalse(error.toString().contains(header))
        }
        val invalidId = runCatching {
            client.buildRequest(KEY, BASE, request(), true, "x-session", "secret\r\nInjected")
        }.exceptionOrNull()
        assertTrue(invalidId is IllegalArgumentException)
        assertFalse(invalidId.toString().contains("Injected"))
    }

    private fun call(id: String, arguments: String) = OpenAiCompatibleToolCallDto(
        id = id, function = OpenAiCompatibleFunctionCallDto(name = "create_role_card", arguments = arguments),
    )

    private fun request(stream: Boolean = true) = OpenAiCompatibleChatRequest(
        model = "test-model", messages = listOf(OpenAiCompatibleMessageDto("user", "你好")), stream = stream,
    )

    private fun client(body: String, logger: RecordingLogger = RecordingLogger(), observed: MutableList<Request>? = null) =
        OpenAiCompatibleClient(
            OkHttpClient.Builder().addInterceptor { chain ->
                observed?.add(chain.request())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("text/event-stream".toMediaType())).build()
            }.build(), logger,
        )

    private class RecordingLogger : RemoteClientLogger {
        val messages = mutableListOf<String>()
        override fun debug(message: String) { messages += message }
        override fun info(message: String) { messages += message }
        override fun warn(message: String) { messages += message }
    }

    companion object {
        private const val KEY = "synthetic-test-key"
        private const val BASE = "https://example.test/v1"
    }
}
