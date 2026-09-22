package com.muxuechat.data.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class RemoteClientCoroutineCancellationTest {
    @Test
    fun openAiCoroutineCancellation_onlyCancelsOwnedCall() = runBlocking {
        val calls = ConcurrentHashMap<String, Call>()
        val client = OpenAiCompatibleClient(blockingClient(calls), NO_OP_LOGGER)
        val first = async {
            client.completeChat(TEST_API_KEY, "$BASE_URL/first", openAiRequest())
        }
        val second = async {
            client.completeChat(TEST_API_KEY, "$BASE_URL/second", openAiRequest())
        }
        awaitCalls(calls)

        first.cancelAndJoin()

        assertTrue(calls.getValue("/first/chat/completions").isCanceled())
        assertFalse(calls.getValue("/second/chat/completions").isCanceled())
        client.cancelActiveRequest()
        second.join()
    }

    @Test
    fun deepSeekCoroutineCancellation_onlyCancelsOwnedCall() = runBlocking {
        val calls = ConcurrentHashMap<String, Call>()
        val client = DeepSeekApiClient(blockingClient(calls), NO_OP_LOGGER)
        val first = async {
            client.completeChat(TEST_API_KEY, "$BASE_URL/first", deepSeekRequest())
        }
        val second = async {
            client.completeChat(TEST_API_KEY, "$BASE_URL/second", deepSeekRequest())
        }
        awaitCalls(calls)

        first.cancelAndJoin()

        assertTrue(calls.getValue("/first/chat/completions").isCanceled())
        assertFalse(calls.getValue("/second/chat/completions").isCanceled())
        client.cancelActiveStream()
        second.join()
    }

    @Test
    fun modelListCoroutineCancellation_onlyCancelsOwnedCall() = runBlocking {
        val calls = ConcurrentHashMap<String, Call>()
        val client = OpenAiCompatibleClient(blockingClient(calls), NO_OP_LOGGER)
        val first = async {
            client.listModels(TEST_API_KEY, "$BASE_URL/first")
        }
        val second = async {
            client.listModels(TEST_API_KEY, "$BASE_URL/second")
        }
        awaitCalls(calls)

        first.cancelAndJoin()

        assertTrue(calls.getValue("/first/models").isCanceled())
        assertFalse(calls.getValue("/second/models").isCanceled())
        second.cancelAndJoin()
        assertTrue(calls.getValue("/second/models").isCanceled())
    }

    @Test
    fun deepSeekModelListCoroutineCancellation_onlyCancelsOwnedCall() = runBlocking {
        val calls = ConcurrentHashMap<String, Call>()
        val client = DeepSeekApiClient(blockingClient(calls), NO_OP_LOGGER)
        val first = async {
            client.listModels(TEST_API_KEY, "$BASE_URL/first")
        }
        val second = async {
            client.listModels(TEST_API_KEY, "$BASE_URL/second")
        }
        awaitCalls(calls)

        first.cancelAndJoin()

        assertTrue(calls.getValue("/first/models").isCanceled())
        assertFalse(calls.getValue("/second/models").isCanceled())
        second.cancelAndJoin()
        assertTrue(calls.getValue("/second/models").isCanceled())
    }

    private suspend fun awaitCalls(calls: Map<String, Call>) {
        withTimeout(5_000) {
            while (calls.size < 2) delay(1)
        }
    }

    private fun blockingClient(calls: MutableMap<String, Call>): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            calls[chain.request().url.encodedPath] = chain.call()
            while (!chain.call().isCanceled()) {
                Thread.sleep(1)
            }
            throw IOException("test cancellation")
        }
        .build()

    private fun openAiRequest() = OpenAiCompatibleChatRequest(
        model = "test-model",
        messages = listOf(OpenAiCompatibleMessageDto(role = "user", content = "test")),
        stream = false,
    )

    private fun deepSeekRequest() = DeepSeekChatRequest(
        model = "deepseek-v4-flash",
        messages = listOf(DeepSeekMessageDto(role = "user", content = "test")),
        stream = false,
    )

    private companion object {
        const val TEST_API_KEY = "test-only-key"
        const val BASE_URL = "https://example.test"
        val NO_OP_LOGGER = object : RemoteClientLogger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
        }
    }
}
