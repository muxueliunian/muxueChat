package com.muxuechat.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XaiAuthClientTest {
    @Test
    fun deviceCodeRequest_isFormEncodedToPinnedEndpointWithClientIdAndScope() = runBlocking {
        val server = FakeXaiAuthServer { FakeAuthReply.Http(200, XaiFixtures.deviceCodeBody(interval = null)) }

        val deviceCode = server.client().requestDeviceCode()

        val request = server.requests.single()
        assertEquals("https://auth.x.ai/oauth2/device/code", request.url)
        assertTrue(request.contentType.orEmpty().startsWith("application/x-www-form-urlencoded"))
        assertEquals(
            mapOf(
                "client_id" to "b1a00492-073a-47ea-816f-4c329264a828",
                "scope" to "openid profile email offline_access grok-cli:access " +
                    "api:access conversations:read conversations:write",
            ),
            request.form,
        )
        assertEquals(XaiFixtures.DEVICE_CODE, deviceCode.deviceCode)
        assertEquals(XaiAuthClient.DEFAULT_POLL_INTERVAL_SECONDS, deviceCode.intervalSeconds)
    }

    @Test
    fun devicePoll_usesDeviceGrantAndMapsRfc8628Errors() = runBlocking {
        val server = FakeXaiAuthServer()
        val client = server.client()

        server.handler = { FakeAuthReply.Http(400, XaiFixtures.oauthError("authorization_pending")) }
        assertEquals(XaiDevicePollResult.Pending, client.pollDeviceToken(XaiFixtures.DEVICE_CODE))
        assertEquals(
            mapOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "device_code" to XaiFixtures.DEVICE_CODE,
                "client_id" to XaiAuthClient.CLIENT_ID,
            ),
            server.requests.last().form,
        )

        server.handler = { FakeAuthReply.Http(400, XaiFixtures.oauthError("slow_down")) }
        assertEquals(XaiDevicePollResult.SlowDown, client.pollDeviceToken(XaiFixtures.DEVICE_CODE))

        server.handler = { FakeAuthReply.Http(429, "{}") }
        assertEquals(XaiDevicePollResult.SlowDown, client.pollDeviceToken(XaiFixtures.DEVICE_CODE))

        server.handler = { FakeAuthReply.Http(503, "{}") }
        assertEquals(XaiDevicePollResult.Transient, client.pollDeviceToken(XaiFixtures.DEVICE_CODE))

        server.handler = { FakeAuthReply.NetworkFailure }
        assertEquals(XaiDevicePollResult.Transient, client.pollDeviceToken(XaiFixtures.DEVICE_CODE))

        server.handler = { FakeAuthReply.Http(400, XaiFixtures.oauthError("expired_token")) }
        assertTrue(runCatching { client.pollDeviceToken(XaiFixtures.DEVICE_CODE) }.exceptionOrNull() is XaiAuthException.DeviceCodeExpired)

        server.handler = { FakeAuthReply.Http(400, XaiFixtures.oauthError("access_denied")) }
        assertTrue(runCatching { client.pollDeviceToken(XaiFixtures.DEVICE_CODE) }.exceptionOrNull() is XaiAuthException.AccessDenied)

        server.handler = { FakeAuthReply.Http(200, XaiFixtures.tokenBody()) }
        val granted = client.pollDeviceToken(XaiFixtures.DEVICE_CODE) as XaiDevicePollResult.Granted
        assertEquals(XaiFixtures.ACCESS_TOKEN, granted.tokens.accessToken)
    }

    @Test
    fun refresh_mapsFailureTypes() = runBlocking {
        val server = FakeXaiAuthServer()
        val client = server.client()

        fun refreshError() = runCatching { runBlocking { client.refresh(XaiFixtures.REFRESH_TOKEN) } }.exceptionOrNull()

        server.handler = { FakeAuthReply.Http(400, XaiFixtures.oauthError("invalid_grant")) }
        assertTrue(refreshError() is XaiAuthException.AuthorizationRevoked)

        server.handler = { FakeAuthReply.Http(400, XaiFixtures.oauthError("invalid_request")) }
        val rejected = refreshError()
        assertTrue(rejected is XaiAuthException.Rejected)
        assertTrue(rejected!!.message!!.contains("invalid_request"))

        server.handler = { FakeAuthReply.Http(429, "{}") }
        assertTrue(refreshError() is XaiAuthException.RateLimited)

        server.handler = { FakeAuthReply.Http(502, "{}") }
        assertTrue(refreshError() is XaiAuthException.Server)

        server.handler = { FakeAuthReply.NetworkFailure }
        assertTrue(refreshError() is XaiAuthException.Network)

        // 认证端点返回重定向时不跟随，按拒绝处理，凭据不会被带往别处。
        server.handler = { FakeAuthReply.Http(302, "") }
        assertTrue(refreshError() is XaiAuthException.Rejected)

        server.handler = { FakeAuthReply.Http(200, """{"access_token":${'"'}${XaiFixtures.REFRESH_TOKEN}""") }
        val malformed = refreshError()
        assertTrue(malformed is XaiAuthException.InvalidResponse)
        assertNull(malformed?.cause)

        server.handler = { FakeAuthReply.Http(200, XaiFixtures.tokenBody(refreshToken = null)) }
        assertNull(client.refresh(XaiFixtures.REFRESH_TOKEN).refreshToken)
        assertEquals("refresh_token", server.requests.last().form["grant_type"])
    }

    @Test
    fun errorsAndLogs_neverEchoSecretsOrErrorDescriptions() = runBlocking {
        val server = FakeXaiAuthServer()
        val client = server.client()
        val messages = mutableListOf<String>()

        server.handler = { FakeAuthReply.Http(400, XaiFixtures.oauthError("invalid_grant")) }
        messages += runCatching { client.refresh(XaiFixtures.REFRESH_TOKEN) }.exceptionOrNull()?.message.orEmpty()
        server.handler = { FakeAuthReply.Http(401, XaiFixtures.oauthError("Not A Code ${XaiFixtures.ACCESS_TOKEN}")) }
        messages += runCatching { client.refresh(XaiFixtures.REFRESH_TOKEN) }.exceptionOrNull()?.message.orEmpty()
        server.handler = { FakeAuthReply.Http(200, XaiFixtures.deviceCodeBody()) }
        messages += client.requestDeviceCode().toString()
        server.handler = { FakeAuthReply.Http(200, XaiFixtures.tokenBody()) }
        messages += client.refresh(XaiFixtures.REFRESH_TOKEN).toString()

        val everything = (messages + server.logger.messages).joinToString("\n")
        XaiFixtures.SECRETS.forEach { assertFalse("leaked $it", everything.contains(it)) }
        assertFalse(everything.contains("details mention"))
    }

    @Test
    fun defaultClient_doesNotFollowRedirects() {
        val client = XaiAuthClient.defaultOkHttpClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test
    fun verificationUriPolicy_acceptsOnlyTrustedHttpsOriginsWithoutDeviceCode() {
        val code = XaiFixtures.DEVICE_CODE
        assertTrue(XaiVerificationUriPolicy.isTrusted("https://auth.x.ai/device", code))
        assertTrue(XaiVerificationUriPolicy.isTrusted("https://accounts.x.ai/device?user_code=WXYZ-2468", code))

        listOf(
            "http://auth.x.ai/device",
            "https://evil.example/device",
            "https://auth.x.ai.evil.example/device",
            "https://evilauth.x.ai/device",
            "https://auth.x.ai:8443/device",
            "https://user@auth.x.ai/device",
            "https://auth.x.ai/device?device_code=abc",
            "https://auth.x.ai/device?c=$code",
            "not a uri",
        ).forEach { assertFalse(it, XaiVerificationUriPolicy.isTrusted(it, code)) }
    }

    @Test
    fun verificationUriPolicy_prefersCompleteUriAndFallsBackOrRejects() {
        fun deviceCode(uri: String, complete: String?) = XaiDeviceCode(
            deviceCode = XaiFixtures.DEVICE_CODE,
            userCode = XaiFixtures.USER_CODE,
            verificationUri = uri,
            verificationUriComplete = complete,
            expiresInSeconds = 600,
            intervalSeconds = 5,
        )

        assertEquals(
            "https://accounts.x.ai/device?user_code=X",
            XaiVerificationUriPolicy.select(deviceCode("https://accounts.x.ai/device", "https://accounts.x.ai/device?user_code=X")),
        )
        assertEquals(
            "https://accounts.x.ai/device",
            XaiVerificationUriPolicy.select(deviceCode("https://accounts.x.ai/device", "https://evil.example/device")),
        )
        assertTrue(
            runCatching { XaiVerificationUriPolicy.select(deviceCode("https://evil.example/", null)) }
                .exceptionOrNull() is XaiAuthException.UnsafeVerificationUri,
        )
    }

    @Test
    fun idTokenReader_prefersEmailThenSubAndToleratesGarbage() {
        assertEquals("reader@example.com", XaiIdTokenReader.account(XaiFixtures.ID_TOKEN))
        assertEquals("user-9", XaiIdTokenReader.account(XaiFixtures.jwt("""{"sub":"user-9"}""")))
        assertNull(XaiIdTokenReader.account(null))
        assertNull(XaiIdTokenReader.account("not-a-jwt"))
        assertNull(XaiIdTokenReader.account("a.!!!.c"))
    }
}
