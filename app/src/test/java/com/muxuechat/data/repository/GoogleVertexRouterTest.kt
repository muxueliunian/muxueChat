package com.muxuechat.data.repository

import com.muxuechat.data.remote.OpenAiCompatibleChatRequest
import com.muxuechat.data.remote.OpenAiCompatibleClient
import com.muxuechat.data.remote.OpenAiCompatibleMessageDto
import com.muxuechat.data.remote.OpenAiCompatibleStreamEvent
import com.muxuechat.data.remote.RecordingAuthLogger
import com.muxuechat.data.remote.SafeUserFacingError
import com.muxuechat.data.remote.XaiResponsesTransport
import com.muxuechat.domain.model.CustomProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Google Vertex 路由：钉死主机实例 + 401 后强制刷新并重试一次。
 * 全部使用合成 token 与合成响应，不访问真实服务。
 */
class GoogleVertexRouterTest {
    private val sentAuthorizations: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private val sentUrls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private class FakeGoogleTokens(
        private val refreshed: String = NEW_TOKEN,
        private val refreshFailure: Exception? = null,
    ) : GoogleAccessTokenSource {
        val forceRefreshCalls = AtomicInteger()

        override suspend fun acquireAccessToken(forceRefresh: Boolean): String {
            if (forceRefresh) {
                forceRefreshCalls.incrementAndGet()
                refreshFailure?.let { throw it }
            }
            return refreshed
        }
    }

    /** 按顺序返回给定的 (状态码, 响应体)；超出后重复最后一项。 */
    private fun router(
        vararg responses: Pair<Int, String>,
        tokens: GoogleAccessTokenSource,
        streaming: Boolean = false,
    ): ChatTransportRouter {
        var seen = 0
        val okHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                sentAuthorizations += request.header("Authorization")
                sentUrls += request.url.toString()
                val (code, body) = responses[minOf(seen++, responses.lastIndex)]
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("synthetic")
                    .body(
                        body.toResponseBody(
                            (if (streaming && code == 200) "text/event-stream" else "application/json").toMediaType(),
                        ),
                    )
                    .build()
            }
            .build()
        return ChatTransportRouter(
            // 普通客户端若被误用会去访问真实网络；本测试中它不应被调用。
            openAiCompatibleClient = OpenAiCompatibleClient(),
            xaiResponsesTransport = XaiResponsesTransport(OkHttpClient(), RecordingAuthLogger()),
            xaiAccessTokens = object : XaiAccessTokenSource {
                override suspend fun getValidAccessToken(rejectedAccessToken: String?): String =
                    error("Google 路径不应触碰 xAI 令牌")
            },
            googleVertexClient = OpenAiCompatibleClient.pinnedTo(CustomProvider.GOOGLE_VERTEX_HOST, okHttp),
            googleAccessTokens = tokens,
        )
    }

    private fun request(stream: Boolean) = OpenAiCompatibleChatRequest(
        model = CustomProvider.GOOGLE_DEFAULT_MODEL,
        messages = listOf(OpenAiCompatibleMessageDto(role = "user", content = "1+1=?")),
        stream = stream,
    )

    @Test
    fun completeChat_rejectedToken_isForceRefreshedOnceAndRetried() = runBlocking {
        val tokens = FakeGoogleTokens()

        val chunk = router(401 to ERROR_401, 200 to OK_JSON, tokens = tokens)
            .completeChat(ProviderCredential.GoogleBearer(OLD_TOKEN), BASE_URL, request(stream = false))

        assertEquals("2", chunk.choices.single().message?.content)
        assertEquals(1, tokens.forceRefreshCalls.get())
        assertEquals(listOf("Bearer $OLD_TOKEN", "Bearer $NEW_TOKEN"), sentAuthorizations)
        // 只发往钉死地址，且 URL 正是 {base}/chat/completions。
        assertEquals(listOf("$BASE_URL/chat/completions", "$BASE_URL/chat/completions"), sentUrls)
    }

    @Test
    fun streamChat_rejectedBeforeAnyContent_isRetriedOnce() = runBlocking {
        val tokens = FakeGoogleTokens()

        val events = router(401 to ERROR_401, 200 to OK_SSE, tokens = tokens, streaming = true)
            .streamChat(ProviderCredential.GoogleBearer(OLD_TOKEN), BASE_URL, request(stream = true))
            .toList()

        assertEquals(
            "2",
            events.filterIsInstance<OpenAiCompatibleStreamEvent.Delta>().mapNotNull { it.content }.joinToString(""),
        )
        assertEquals(1, tokens.forceRefreshCalls.get())
        assertEquals(listOf("Bearer $OLD_TOKEN", "Bearer $NEW_TOKEN"), sentAuthorizations)
    }

    @Test
    fun secondRejection_isNotRetriedAgain_andReportsReloginWithSafeMessage() = runBlocking {
        listOf(true, false).forEach { streaming ->
            sentAuthorizations.clear()
            val tokens = FakeGoogleTokens()
            val router = router(401 to ERROR_401, tokens = tokens, streaming = streaming)

            val error = runCatching {
                if (streaming) {
                    router.streamChat(ProviderCredential.GoogleBearer(OLD_TOKEN), BASE_URL, request(stream = true)).toList()
                } else {
                    router.completeChat(ProviderCredential.GoogleBearer(OLD_TOKEN), BASE_URL, request(stream = false))
                }
            }.exceptionOrNull()

            // 只重试一次：再失败就是真的需要重新登录，不该无限刷新；文案不再提「API Key」。
            assertTrue("streaming=$streaming", error is GoogleAuthRequiredException)
            assertTrue(error is SafeUserFacingError)
            assertTrue(error!!.message.orEmpty().contains("重新登录"))
            assertFalse(error.message.orEmpty().contains("API Key"))
            assertFalse(error.message.orEmpty().contains(OLD_TOKEN))
            assertEquals(1, tokens.forceRefreshCalls.get())
            assertEquals(2, sentAuthorizations.size)
        }
    }

    @Test
    fun nonAuthErrors_passThroughWithoutRefresh() = runBlocking {
        val tokens = FakeGoogleTokens()

        val error = runCatching {
            router(400 to ERROR_400, tokens = tokens)
                .completeChat(ProviderCredential.GoogleBearer(OLD_TOKEN), BASE_URL, request(stream = false))
        }.exceptionOrNull()

        assertFalse(error is GoogleAuthRequiredException)
        assertTrue(error!!.message.orEmpty().contains("HTTP 400"))
        assertEquals(0, tokens.forceRefreshCalls.get())
        assertEquals(1, sentAuthorizations.size)
    }

    @Test
    fun refreshFailure_isPropagatedAndNothingIsResent() = runBlocking {
        val tokens = FakeGoogleTokens(refreshFailure = GoogleAuthRequiredException.reauthorize())

        val error = runCatching {
            router(401 to ERROR_401, tokens = tokens)
                .completeChat(ProviderCredential.GoogleBearer(OLD_TOKEN), BASE_URL, request(stream = false))
        }.exceptionOrNull()

        assertEquals("Google 授权已失效，请到设置页重新登录 Google", error?.message)
        assertEquals(1, sentAuthorizations.size)
    }

    @Test
    fun pinnedClient_neverFollowsRedirectsByDefault() {
        val client = OpenAiCompatibleClient.pinnedOkHttpClient()

        // 跟随 3xx 会让 Authorization 头跟着去别的主机。
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    private companion object {
        const val OLD_TOKEN = "synthetic-old-google-token"
        const val NEW_TOKEN = "synthetic-new-google-token"
        val BASE_URL = CustomProvider.googleBaseUrl("my-project-123456")

        const val ERROR_401 =
            """[{"error":{"code":401,"message":"Request had invalid authentication credentials.","status":"UNAUTHENTICATED"}}]"""
        const val ERROR_400 =
            """[{"error":{"code":400,"message":"Bad request shape.","status":"INVALID_ARGUMENT"}}]"""
        const val OK_JSON =
            """{"choices":[{"finish_reason":"stop","index":0,"message":{"role":"assistant","content":"2"}}],"usage":{"prompt_tokens":39,"completion_tokens":5,"total_tokens":44}}"""
        val OK_SSE = """
            data: {"choices":[{"delta":{"content":"2","role":"assistant"},"index":0}]}

            data: {"choices":[{"delta":{"content":""},"finish_reason":"stop","index":0}],"usage":{"prompt_tokens":39,"completion_tokens":5,"total_tokens":44}}

            data: [DONE]

        """.trimIndent()
    }
}
