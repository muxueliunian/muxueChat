package com.muxuechat.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Google Vertex 链路的脱敏真实抓包夹具（`src/test/resources/vertex/`）回归。
 *
 * 夹具中的 thought_signature 已替换为 `TEST_SIG_n`；这里只断言协议形状与钉死主机行为，
 * 不引入任何真实 token、密钥或项目 ID。
 */
class GoogleVertexFixtureTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("vertex/$name")) {
            "缺少测试夹具：$name"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun nonStreamRequest() = OpenAiCompatibleChatRequest(
        model = "google/gemini-4-argon",
        messages = listOf(OpenAiCompatibleMessageDto(role = "user", content = "东京和大阪的天气如何？")),
        stream = false,
    )

    private fun parallelToolCalls(): List<OpenAiCompatibleToolCallDto> {
        val response = json.decodeFromString<OpenAiCompatibleChatChunkDto>(
            fixture("nonstream_toolcall_parallel.json"),
        )
        return checkNotNull(response.choices.single().message?.toolCalls)
    }

    /** 与生产 pinnedTo 默认值一致：禁止重定向，响应由 interceptor 合成。 */
    private fun cannedClient(code: Int, body: String): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("synthetic")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    /** 任何请求到达网络层都视为失败：用于证明钉死主机在发出请求前就已拒绝。 */
    private fun forbiddenClient(requestCount: AtomicInteger): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor {
            requestCount.incrementAndGet()
            fail("不应发出请求")
            throw IllegalStateException("unreachable")
        }
        .build()

    // ------------------------------------------------------------------ 流式

    @Test
    fun streamToolCall_accumulatesOneCallWithOpaqueExtraContentAndReportsFinishAndUsage() {
        val parser = OpenAiCompatibleStreamParser()
        val accumulator = OpenAiCompatibleToolCallAccumulator()
        val events = fixture("stream_toolcall.sse").lines()
            .mapNotNull { parser.parseLine(it, TEST_TOKEN) }
        val deltas = events.filterIsInstance<OpenAiCompatibleStreamEvent.Delta>()

        deltas.forEach { accumulator.accept(it.toolCalls) }
        val call = accumulator.build().single()

        assertEquals("get_weather", call.function?.name)
        val arguments = json.parseToJsonElement(checkNotNull(call.function?.arguments)) as JsonObject
        assertEquals("东京", arguments["city"]?.jsonPrimitive?.content)
        val extraContent = checkNotNull(call.extraContent)
        assertTrue(json.encodeToString(extraContent).contains("TEST_SIG_1"))

        assertEquals(listOf("tool_calls"), deltas.mapNotNull { it.finishReason })
        val usage = deltas.mapNotNull { it.usage }.single()
        assertEquals(87, usage.promptTokens)
        assertEquals(22, usage.completionTokens)
        assertTrue(events.last() is OpenAiCompatibleStreamEvent.Done)
    }

    @Test
    fun streamText_parsesEveryLineAndReportsStopAndFinalUsage() {
        val parser = OpenAiCompatibleStreamParser()
        val events = fixture("stream_text_final.sse").lines()
            .mapNotNull { parser.parseLine(it, TEST_TOKEN) }
        val deltas = events.filterIsInstance<OpenAiCompatibleStreamEvent.Delta>()

        assertEquals("2", deltas.mapNotNull { it.content }.joinToString(""))
        assertEquals(listOf("stop"), deltas.mapNotNull { it.finishReason })
        val usage = deltas.mapNotNull { it.usage }.single()
        assertEquals(39, usage.promptTokens)
        assertEquals(5, usage.completionTokens)
        assertTrue(events.last() is OpenAiCompatibleStreamEvent.Done)
    }

    // ------------------------------------------------------------------ 非流式与回放

    @Test
    fun nonStreamParallelToolCalls_validateAndKeepExtraContentOnlyWhereServerSentIt() {
        val calls = parallelToolCalls()
        assertEquals(2, calls.size)

        val validated = OpenAiCompatibleToolCallValidator.validate(calls)

        assertEquals(2, validated.size)
        assertNotNull(validated[0].extraContent)
        assertNull(validated[1].extraContent)
    }

    @Test
    fun replayEncoding_putsExtraContentBackOnlyInsideTheFirstToolCall() {
        val calls = parallelToolCalls()
        val encoded = json.encodeToString(replayRequest(calls))

        assertEquals(1, Regex("\"extra_content\"").findAll(encoded).count())
        val firstCallStart = encoded.indexOf("\"id\":\"call_630067\"")
        val secondCallStart = encoded.indexOf("\"id\":\"call_630068\"")
        assertTrue(firstCallStart >= 0 && secondCallStart > firstCallStart)
        val firstCallSlice = encoded.substring(firstCallStart, secondCallStart)
        assertTrue(firstCallSlice.contains("\"extra_content\""))
        assertTrue(firstCallSlice.contains("TEST_SIG_1"))
    }

    @Test
    fun replayEncoding_withoutExtraContentDoesNotChangeRequestBytes() {
        val callsWithoutExtra = parallelToolCalls().map { it.copy(extraContent = null) }

        val encoded = json.encodeToString(replayRequest(callsWithoutExtra))

        assertFalse(encoded.contains("extra_content"))
    }

    private fun replayRequest(calls: List<OpenAiCompatibleToolCallDto>) = OpenAiCompatibleChatRequest(
        model = "google/gemini-4-argon",
        messages = listOf(
            OpenAiCompatibleMessageDto(role = "user", content = "东京和大阪的天气如何？"),
            OpenAiCompatibleMessageDto(role = "assistant", toolCalls = calls),
            OpenAiCompatibleMessageDto(role = "tool", content = "{\"weather\":\"晴\"}", toolCallId = calls[0].id),
            OpenAiCompatibleMessageDto(role = "tool", content = "{\"weather\":\"雨\"}", toolCallId = calls[1].id),
        ),
        stream = false,
    )

    // ------------------------------------------------------------------ 钉死主机

    @Test
    fun pinnedClient_rejectsNonPinnedEndpointsBeforeAnyRequestIsSent() {
        val nonPinnedBaseUrls = listOf(
            "http://aiplatform.googleapis.com/v1/projects/test-project/locations/global/endpoints/openapi",
            "https://evil.example.com/v1/projects/test-project/locations/global/endpoints/openapi",
            "https://aiplatform.googleapis.com:8443/v1/projects/test-project/locations/global/endpoints/openapi",
        )

        nonPinnedBaseUrls.forEach { baseUrl ->
            val requestCount = AtomicInteger(0)
            val client = OpenAiCompatibleClient.pinnedTo(PINNED_HOST, forbiddenClient(requestCount))

            assertThrows("应拒绝 $baseUrl", IllegalArgumentException::class.java) {
                runBlocking {
                    client.completeChat(apiKey = TEST_API_KEY, baseUrl = baseUrl, requestDto = nonStreamRequest())
                }
            }
            assertEquals("拒绝前不应发出任何请求：$baseUrl", 0, requestCount.get())
        }
    }

    @Test
    fun pinnedClient_maps401ArrayErrorToUnauthorizedWithoutApiKeyHint() {
        val client = OpenAiCompatibleClient.pinnedTo(
            PINNED_HOST,
            cannedClient(401, fixture("error_401_array.json")),
        )

        val error = assertThrows(OpenAiCompatibleUnauthorizedException::class.java) {
            runBlocking {
                client.completeChat(apiKey = TEST_API_KEY, baseUrl = PINNED_BASE_URL, requestDto = nonStreamRequest())
            }
        }

        val message = error.message.orEmpty()
        assertTrue(message, message.contains("HTTP 401"))
        assertTrue(message, message.contains("Request had invalid authentication credentials"))
        assertFalse(message, message.contains("请检查 API Key"))
    }

    @Test
    fun pinnedClient_maps400MissingSignatureArrayErrorWithoutUnauthorizedType() {
        val client = OpenAiCompatibleClient.pinnedTo(
            PINNED_HOST,
            cannedClient(400, fixture("error_400_missing_signature.json")),
        )

        val error = assertThrows(IOException::class.java) {
            runBlocking {
                client.completeChat(apiKey = TEST_API_KEY, baseUrl = PINNED_BASE_URL, requestDto = nonStreamRequest())
            }
        }

        assertFalse(error is OpenAiCompatibleUnauthorizedException)
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("thought_signature"))
    }

    @Test
    fun ordinaryClient_maps401ArrayErrorToUnauthorizedWithLegacyPrefix() {
        val client = OpenAiCompatibleClient(
            okHttpClient = cannedClient(401, fixture("error_401_array.json")),
            logger = SilentLogger,
        )

        val error = assertThrows(OpenAiCompatibleUnauthorizedException::class.java) {
            runBlocking {
                client.completeChat(
                    apiKey = TEST_API_KEY,
                    baseUrl = ORDINARY_BASE_URL,
                    requestDto = nonStreamRequest(),
                )
            }
        }

        assertTrue(error.message.orEmpty(), error.message.orEmpty().startsWith("供应商鉴权失败"))
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("HTTP 401"))
    }

    private object SilentLogger : RemoteClientLogger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
    }

    private companion object {
        const val TEST_TOKEN = "test-token"
        const val TEST_API_KEY = "test-api-key"
        const val PINNED_HOST = "aiplatform.googleapis.com"
        const val PINNED_BASE_URL =
            "https://aiplatform.googleapis.com/v1/projects/test-project/locations/global/endpoints/openapi"
        const val ORDINARY_BASE_URL = "https://provider.example.test/v1"
    }
}
