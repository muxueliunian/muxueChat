package com.muxuechat.data.repository

import com.muxuechat.data.remote.OpenAiCompatibleChatRequest
import com.muxuechat.data.remote.OpenAiCompatibleClient
import com.muxuechat.data.remote.OpenAiCompatibleMessageDto
import com.muxuechat.data.remote.OpenAiCompatibleStreamEvent
import com.muxuechat.data.remote.RecordingAuthLogger
import com.muxuechat.data.remote.XaiAuthException
import com.muxuechat.data.remote.XaiResponsesTransport
import com.muxuechat.domain.model.CustomProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * 传输路由器：按凭据类型分派，并在 access token 被上游拒绝时刷新重试一次。
 */
class ChatTransportRouterTest {
    private val sentAuthorizations: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    /** 前 [unauthorizedTimes] 次返回 401，之后返回一条正常的流。 */
    private fun transport(unauthorizedTimes: Int, onRequest: (Int) -> Unit = {}): XaiResponsesTransport {
        var seen = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request: Request = chain.request()
                sentAuthorizations += request.header("Authorization")
                val requestIndex = seen++
                val unauthorized = requestIndex < unauthorizedTimes
                onRequest(requestIndex)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (unauthorized) 401 else 200)
                    .message("synthetic")
                    .body(
                        (if (unauthorized) "" else OK_SSE)
                            .toResponseBody("text/event-stream".toMediaType()),
                    )
                    .build()
            }
            .build()
        return XaiResponsesTransport(client, RecordingAuthLogger())
    }

    private class FakeTokens(private val refreshed: String) : XaiAccessTokenSource {
        val rejected: MutableList<String?> = Collections.synchronizedList(mutableListOf())

        override suspend fun getValidAccessToken(rejectedAccessToken: String?): String {
            rejected += rejectedAccessToken
            return refreshed
        }
    }

    private fun router(unauthorizedTimes: Int, tokens: XaiAccessTokenSource, onRequest: (Int) -> Unit = {}) = ChatTransportRouter(
        openAiCompatibleClient = OpenAiCompatibleClient(),
        xaiResponsesTransport = transport(unauthorizedTimes, onRequest),
        xaiAccessTokens = tokens,
    )

    private fun request() = OpenAiCompatibleChatRequest(
        model = "grok-4.7",
        messages = listOf(OpenAiCompatibleMessageDto(role = "user", content = "你好")),
        stream = true,
    )

    @Test
    fun rejectedToken_isRefreshedOnceAndRetried() = runBlocking {
        val tokens = FakeTokens(refreshed = NEW_TOKEN)

        val events = router(unauthorizedTimes = 1, tokens = tokens)
            .streamChat(ProviderCredential.OAuthBearer(OLD_TOKEN), BASE_URL, request())
            .toList()

        // 带着被拒的那个 token 去强制刷新，而不是听任过期时间判断「还没到期，不用刷」。
        assertEquals(listOf(OLD_TOKEN), tokens.rejected)
        assertEquals(listOf("Bearer $OLD_TOKEN", "Bearer $NEW_TOKEN"), sentAuthorizations)
        assertEquals(
            "你好呀",
            events.filterIsInstance<OpenAiCompatibleStreamEvent.Delta>()
                .mapNotNull { it.content }
                .joinToString(""),
        )
    }

    @Test
    fun secondRejectionIsNotRetriedAgain() = runBlocking {
        val tokens = FakeTokens(refreshed = NEW_TOKEN)

        val error = runCatching {
            router(unauthorizedTimes = 2, tokens = tokens)
                .streamChat(ProviderCredential.OAuthBearer(OLD_TOKEN), BASE_URL, request())
                .toList()
        }.exceptionOrNull()

        // 只重试一次：再失败就是真的需要重新登录，不该无限刷新。
        assertTrue(error is XaiResponsesTransport.XaiUnauthorizedException)
        assertEquals(1, tokens.rejected.size)
        assertEquals(2, sentAuthorizations.size)
    }

    @Test
    fun completeChat_alsoRefreshesOnce() = runBlocking {
        val tokens = FakeTokens(refreshed = NEW_TOKEN)

        val chunk = router(unauthorizedTimes = 1, tokens = tokens)
            .completeChat(ProviderCredential.OAuthBearer(OLD_TOKEN), BASE_URL, request())

        // RP 补写、状态兜底、摘要与设定生成都走这条路；正文结束后才发出的补写最容易撞上过期。
        assertEquals("你好呀", chunk.choices.single().message?.content)
        assertEquals(listOf(OLD_TOKEN), tokens.rejected)
    }

    private class SessionTokens : XaiAccessTokenSource {
        @Volatile var generation = 1L
        var legacyCalls = 0
        override suspend fun getValidAccessToken(rejectedAccessToken: String?): String {
            legacyCalls++
            return NEW_TOKEN
        }
        override suspend fun validateSession(lease: XaiAccessTokenLease) {
            if (lease.sessionGeneration != generation) throw XaiAuthException.NotSignedIn()
        }
        override suspend fun refreshAccessToken(lease: XaiAccessTokenLease): XaiAccessTokenLease {
            validateSession(lease)
            return XaiAccessTokenLease(NEW_TOKEN, generation)
        }
    }

    @Test
    fun frozenSessionCannotSendAfterSwitchingAccounts() = runBlocking {
        val tokens = SessionTokens().apply { generation = 2L }
        val router = router(0, tokens)
        val old = ProviderCredential.OAuthBearer(OLD_TOKEN, 1L)
        assertTrue(runCatching { router.streamChat(old, BASE_URL, request()).toList() }.exceptionOrNull() is XaiAuthException.NotSignedIn)
        assertTrue(runCatching { router.completeChat(old, BASE_URL, request()) }.exceptionOrNull() is XaiAuthException.NotSignedIn)
        assertTrue(sentAuthorizations.isEmpty())
    }

    @Test
    fun unauthorizedAfterAccountSwitchDoesNotSendMessageWithNewAccount() = runBlocking {
        listOf(true, false).forEach { streaming ->
            sentAuthorizations.clear()
            val tokens = SessionTokens()
            val router = router(1, tokens) { tokens.generation = 2L }
            val old = ProviderCredential.OAuthBearer(OLD_TOKEN, 1L)
            val error = runCatching {
                if (streaming) router.streamChat(old, BASE_URL, request()).toList()
                else router.completeChat(old, BASE_URL, request())
            }.exceptionOrNull()
            assertTrue(error is XaiAuthException.NotSignedIn)
            assertEquals(listOf("Bearer $OLD_TOKEN"), sentAuthorizations)
            assertEquals(0, tokens.legacyCalls)
        }
    }

    @Test
    fun completedOldAccountResponseIsRejectedBeforeDelivery() = runBlocking {
        val tokens = SessionTokens()
        val router = router(0, tokens) { tokens.generation = 2L }
        val error = runCatching {
            router.completeChat(ProviderCredential.OAuthBearer(OLD_TOKEN, 1L), BASE_URL, request())
        }.exceptionOrNull()
        assertTrue(error is XaiAuthException.NotSignedIn)
        assertEquals(listOf("Bearer $OLD_TOKEN"), sentAuthorizations)
    }

    private companion object {
        const val OLD_TOKEN = "synthetic-old-token"
        const val NEW_TOKEN = "synthetic-new-token"
        val BASE_URL = CustomProvider.XAI_SUBSCRIPTION_BASE_URL

        val OK_SSE = """
            data: {"type":"response.output_item.added","output_index":1,"item":{"id":"msg_1","type":"message"}}
            data: {"type":"response.output_text.delta","output_index":1,"delta":"你好呀"}
            data: {"type":"response.output_item.done","output_index":1}
            data: {"type":"response.completed","response":{"status":"completed"}}
        """.trimIndent()
    }
}
