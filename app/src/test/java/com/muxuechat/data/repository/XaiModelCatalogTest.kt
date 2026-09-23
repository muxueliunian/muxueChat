package com.muxuechat.data.repository

import com.muxuechat.data.remote.RemoteClientLogger
import com.muxuechat.data.remote.XaiResponsesTransport
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** 401 时带着被拒的 token 强制刷新一次再重试，与聊天请求的处理一致；只重试一次。 */
class XaiModelCatalogTest {
    private val sentTokens = CopyOnWriteArrayList<String>()

    private fun transport(vararg codes: Int): XaiResponsesTransport {
        var index = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                sentTokens += chain.request().header("Authorization").orEmpty().removePrefix("Bearer ")
                val code = codes[minOf(index++, codes.lastIndex)]
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("synthetic")
                    .body(BODY.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        return XaiResponsesTransport(client, NO_OP_LOGGER)
    }

    private class Tokens : XaiAccessTokenSource {
        val rejected = CopyOnWriteArrayList<String?>()
        private var current = "token-1"
        override suspend fun getValidAccessToken(rejectedAccessToken: String?): String {
            rejected += rejectedAccessToken
            if (rejectedAccessToken == current) current = "token-2"
            return current
        }
    }

    @Test
    fun unauthorized_refreshesWithRejectedTokenAndRetriesOnce() = runBlocking {
        val tokens = Tokens()

        val models = XaiModelCatalog(transport(401, 200), tokens).listModels()

        assertEquals(listOf("grok-4.7"), models)
        assertEquals(listOf("token-1", "token-2"), sentTokens)
        assertEquals(listOf(null, "token-1"), tokens.rejected)
    }

    @Test
    fun secondUnauthorized_isNotRetriedAgain() = runBlocking {
        val error = runCatching { XaiModelCatalog(transport(401, 401), Tokens()).listModels() }.exceptionOrNull()

        assertTrue(error is XaiResponsesTransport.XaiUnauthorizedException)
        assertEquals(2, sentTokens.size)
    }

    private companion object {
        const val BODY = """{"object":"list","data":[{"id":"grok-4.7","context_window":500000}]}"""
        val NO_OP_LOGGER = object : RemoteClientLogger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
        }
    }
}
