package com.muxuechat.data.repository

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class DeepSeekErrorSanitizerTest {
    @Test
    fun `known http statuses keep actionable messages without response body`() {
        assertEquals(
            "API Key 无效或未授权，请检查 DeepSeek API Key。",
            IOException("DeepSeek API returned HTTP 401").toSafeDeepSeekUserFacingMessage(),
        )
        assertEquals(
            "DeepSeek 服务暂时异常，请稍后再试。",
            IOException("DeepSeek API returned HTTP 503").toSafeDeepSeekUserFacingMessage(),
        )
    }

    @Test
    fun `network exception types map without exposing addresses`() {
        assertEquals(
            "网络不可用或无法解析 DeepSeek 服务地址。",
            UnknownHostException("secret-host.example").toSafeDeepSeekUserFacingMessage(),
        )
        assertEquals(
            "网络请求超时，请检查网络或稍后重试。",
            SocketTimeoutException("https://user:pass@example.test/token").toSafeDeepSeekUserFacingMessage(),
        )
        assertEquals(
            "DeepSeek 的 HTTPS 证书校验失败，请检查服务证书和地址。",
            SSLHandshakeException("certificate for secret-host.example").toSafeDeepSeekUserFacingMessage(),
        )
    }

    @Test
    fun `serialization and unknown errors never echo sensitive message`() {
        val secret = "response-secret-9f17 API_KEY https://user:pass@example.test?q=token"
        val serializationMessage = SerializationException(secret).toSafeDeepSeekUserFacingMessage()
        val unknownMessage = IllegalStateException(secret).toSafeDeepSeekUserFacingMessage()

        assertEquals("DeepSeek 响应格式异常，请稍后重试。", serializationMessage)
        assertEquals("生成失败，请检查 API Key、网络和模型配置。", unknownMessage)
        assertFalse(serializationMessage.contains(secret))
        assertFalse(unknownMessage.contains(secret))
    }

    @Test
    fun `untrusted message containing http text is not treated as client status`() {
        val secret = "HTTP 401 response-secret"
        val message = SerializationException(secret).toSafeDeepSeekUserFacingMessage()

        assertEquals("DeepSeek 响应格式异常，请稍后重试。", message)
        assertFalse(message.contains(secret))
    }

    @Test
    fun `known local preflight errors remain actionable`() {
        assertEquals(
            "请先在设置中保存 DeepSeek API Key",
            IllegalStateException("请先在设置中保存 DeepSeek API Key")
                .toSafeDeepSeekUserFacingMessage(),
        )
        assertEquals(
            "DeepSeek 地址无效，请在设置中填写 http:// 或 https:// 开头的 Base URL。",
            IllegalArgumentException("Provider Base URL 格式无效")
                .toSafeDeepSeekUserFacingMessage(),
        )
    }

    @Test
    fun `safe log descriptor contains no throwable message`() {
        val secret = "response-secret Authorization Bearer abc"
        val error = IOException(secret, SerializationException(secret))

        val descriptor = error.toSafeLogDescriptor()

        assertEquals("type=IOException", descriptor)
        assertFalse(descriptor.contains(secret))
    }
}
