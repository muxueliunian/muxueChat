package com.muxuechat.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

class RemoteClientLifecycleTest {
    @Test
    fun deepSeekBaseUrl_preservesPathAndRejectsUnsafeStructureWithoutEchoingInput() {
        assertEquals(
            "http://127.0.0.1:8317/v1",
            DeepSeekApiClient.normalizeBaseUrl(" http://127.0.0.1:8317/v1/ "),
        )
        assertEquals(
            DeepSeekApiClient.DEFAULT_BASE_URL,
            DeepSeekApiClient.normalizeBaseUrl("  "),
        )

        val unsafeUrls = listOf(
            "ftp://example.test/v1",
            "https://name:secret@example.test/v1",
            "https://example.test/v1?key=secret",
            "https://example.test/v1#fragment",
            "https://example.test:70000/v1",
        )
        unsafeUrls.forEach { unsafeUrl ->
            val error = runCatching { DeepSeekApiClient.normalizeBaseUrl(unsafeUrl) }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message?.contains(unsafeUrl) != true)
        }
    }

    @Test
    fun openAiCompleteChat_releasesSensitiveCallOnEveryExitPath() = runBlocking {
        val scenarios = listOf(
            Scenario.Failure(SocketTimeoutException("test timeout")),
            Scenario.Response(code = 503, body = "{}"),
            Scenario.Response(code = 200, body = "not-json"),
            Scenario.Response(code = 200, body = VALID_OPEN_AI_RESPONSE),
        )

        scenarios.forEach { scenario ->
            val observedCall = AtomicReference<Call>()
            val client = OpenAiCompatibleClient(clientFor(scenario, observedCall), NO_OP_LOGGER)
            val result = runCatching {
                client.completeChat(TEST_API_KEY, TEST_BASE_URL, openAiRequest(stream = false))
            }

            assertTrue("测试请求必须实际进入 OkHttp 执行链", observedCall.get() != null)
            val expectsSuccess = scenario is Scenario.Response &&
                scenario.code == 200 &&
                scenario.body == VALID_OPEN_AI_RESPONSE
            assertEquals(expectsSuccess, result.isSuccess)
            assertEquals(
                "完成、HTTP 错误、解析错误和 execute 异常都必须释放 Call",
                0,
                client.activeCallCount(),
            )
        }
    }

    @Test
    fun openAiCompleteChat_activeCancellationReleasesCall() = runBlocking {
        val startedCall = AtomicReference<Call>()
        val client = OpenAiCompatibleClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    startedCall.set(chain.call())
                    while (!chain.call().isCanceled()) {
                        Thread.sleep(1)
                    }
                    throw IOException("test cancellation")
                }
                .build(),
            NO_OP_LOGGER,
        )

        val result = async(Dispatchers.IO) {
            runCatching {
                client.completeChat(TEST_API_KEY, TEST_BASE_URL, openAiRequest(stream = false))
            }
        }
        withTimeout(5_000) {
            while (startedCall.get() == null) delay(1)
        }

        client.cancelActiveRequest()

        assertTrue(result.await().exceptionOrNull() is CancellationException)
        assertEquals(0, client.activeCallCount())
    }

    @Test
    fun openAiStream_slowConsumerReceivesAllEventsInOrder() = runBlocking {
        val expectedContent = (0 until EVENT_COUNT).map { "c$it" }
        val expectedReasoning = (0 until EVENT_COUNT).map { "r$it" }
        val body = openAiSseBody(expectedContent, expectedReasoning)
        val client = OpenAiCompatibleClient(clientFor(Scenario.Response(200, body)), NO_OP_LOGGER)
        var receivedEventCount = 0

        val events = withTimeout(15_000) {
            client.streamChat(TEST_API_KEY, TEST_BASE_URL, openAiRequest(stream = true))
                .onEach {
                    receivedEventCount++
                    if (receivedEventCount % SLOW_CONSUMER_BATCH_SIZE == 0) delay(1)
                }
                .toList()
        }
        val deltas = events.filterIsInstance<OpenAiCompatibleStreamEvent.Delta>()

        assertEquals(EVENT_COUNT + 1, events.size)
        assertEquals(expectedContent, deltas.map { it.content })
        assertEquals(expectedReasoning, deltas.map { it.reasoningContent })
        assertEquals(OpenAiCompatibleStreamEvent.Done, events.last())
        assertEquals(0, client.activeCallCount())
    }

    @Test
    fun deepSeekStream_slowConsumerReceivesAllEventsInOrder() = runBlocking {
        val expectedContent = (0 until EVENT_COUNT).map { "c$it" }
        val expectedReasoning = (0 until EVENT_COUNT).map { "r$it" }
        val body = deepSeekSseBody(expectedContent, expectedReasoning)
        val client = DeepSeekApiClient(clientFor(Scenario.Response(200, body)), NO_OP_LOGGER)
        var receivedEventCount = 0

        val events = withTimeout(15_000) {
            client.streamChat(TEST_API_KEY, TEST_BASE_URL, deepSeekRequest())
                .onEach {
                    receivedEventCount++
                    if (receivedEventCount % SLOW_CONSUMER_BATCH_SIZE == 0) delay(1)
                }
                .toList()
        }
        val deltas = events.filterIsInstance<DeepSeekStreamEvent.Delta>()

        assertEquals(EVENT_COUNT + 1, events.size)
        assertEquals(expectedContent, deltas.map { it.content })
        assertEquals(expectedReasoning, deltas.map { it.reasoningContent })
        assertEquals(DeepSeekStreamEvent.Done, events.last())
        assertEquals(0, client.activeCallCount())
    }

    @Test
    fun streamCollectionCancellationCancelsBothProviderCalls() = runBlocking {
        val openAiCall = AtomicReference<Call>()
        val deepSeekCall = AtomicReference<Call>()
        val content = (0 until EVENT_COUNT).map { "c$it" }
        val reasoning = (0 until EVENT_COUNT).map { "r$it" }
        val openAiClient = OpenAiCompatibleClient(
            clientFor(Scenario.Response(200, openAiSseBody(content, reasoning)), openAiCall),
            NO_OP_LOGGER,
        )
        val deepSeekClient = DeepSeekApiClient(
            clientFor(Scenario.Response(200, deepSeekSseBody(content, reasoning)), deepSeekCall),
            NO_OP_LOGGER,
        )

        openAiClient.streamChat(TEST_API_KEY, TEST_BASE_URL, openAiRequest(stream = true))
            .take(1)
            .toList()
        deepSeekClient.streamChat(TEST_API_KEY, TEST_BASE_URL, deepSeekRequest())
            .take(1)
            .toList()

        withTimeout(5_000) {
            while (openAiCall.get()?.isCanceled() != true || deepSeekCall.get()?.isCanceled() != true) {
                delay(1)
            }
        }
        assertEquals(0, openAiClient.activeCallCount())
        assertEquals(0, deepSeekClient.activeCallCount())
    }

    private fun clientFor(scenario: Scenario, observedCall: AtomicReference<Call>? = null): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    observedCall?.set(chain.call())
                    when (scenario) {
                        is Scenario.Failure -> throw scenario.error
                        is Scenario.Response -> Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(scenario.code)
                            .message("test response")
                            .body(scenario.body.toResponseBody(EVENT_STREAM_MEDIA_TYPE))
                            .build()
                    }
                },
            )
            .build()

    private fun openAiRequest(stream: Boolean) = OpenAiCompatibleChatRequest(
        model = "test-model",
        messages = listOf(OpenAiCompatibleMessageDto(role = "user", content = "test message")),
        stream = stream,
    )

    private fun deepSeekRequest() = DeepSeekChatRequest(
        model = "deepseek-v4-flash",
        messages = listOf(DeepSeekMessageDto(role = "user", content = "test message")),
        stream = true,
    )

    private fun openAiSseBody(content: List<String>, reasoning: List<String>): String = buildString {
        content.zip(reasoning).forEach { (contentDelta, reasoningDelta) ->
            append("data: {\"choices\":[{\"delta\":{\"content\":\"")
            append(contentDelta)
            append("\",\"reasoning_content\":\"")
            append(reasoningDelta)
            append("\"}}]}\n\n")
        }
        append("data: [DONE]\n\n")
    }

    private fun deepSeekSseBody(content: List<String>, reasoning: List<String>): String = buildString {
        content.zip(reasoning).forEach { (contentDelta, reasoningDelta) ->
            append("data: {\"choices\":[{\"delta\":{\"content\":\"")
            append(contentDelta)
            append("\",\"reasoning_content\":\"")
            append(reasoningDelta)
            append("\"}}]}\n\n")
        }
        append("data: [DONE]\n\n")
    }

    @Suppress("UNCHECKED_CAST")
    private fun OpenAiCompatibleClient.activeCallCount(): Int =
        activeCallCount(this, OpenAiCompatibleClient::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun DeepSeekApiClient.activeCallCount(): Int =
        activeCallCount(this, DeepSeekApiClient::class.java)

    private fun activeCallCount(instance: Any, type: Class<*>): Int {
        val field = type.getDeclaredField("activeCalls").apply { isAccessible = true }
        return (field.get(instance) as Set<*>).size
    }

    private sealed interface Scenario {
        data class Failure(val error: IOException) : Scenario
        data class Response(val code: Int, val body: String) : Scenario
    }

    private companion object {
        const val EVENT_COUNT = 1_024
        const val SLOW_CONSUMER_BATCH_SIZE = 16
        const val TEST_API_KEY = "test-only-key"
        const val TEST_BASE_URL = "https://example.test/v1"
        const val VALID_OPEN_AI_RESPONSE =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}"
        val EVENT_STREAM_MEDIA_TYPE = "text/event-stream; charset=utf-8".toMediaType()
        val NO_OP_LOGGER = object : RemoteClientLogger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
        }
    }
}
