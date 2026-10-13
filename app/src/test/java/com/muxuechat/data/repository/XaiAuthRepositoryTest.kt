package com.muxuechat.data.repository

import com.muxuechat.data.local.XaiCredential
import com.muxuechat.data.local.XaiCredentialStore
import com.muxuechat.data.remote.FakeAuthReply
import com.muxuechat.data.remote.FakeXaiAuthServer
import com.muxuechat.data.remote.InMemoryXaiCredentialStore
import com.muxuechat.data.remote.XaiAuthClient
import com.muxuechat.data.remote.XaiAuthException
import com.muxuechat.data.remote.XaiFixtures
import com.muxuechat.domain.model.XaiAuthState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class XaiAuthRepositoryTest {
    private val server = FakeXaiAuthServer()
    private val store = InMemoryXaiCredentialStore()

    private fun TestScope.repository() = XaiAuthRepository(
        client = server.client(),
        store = store,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        clock = { testScheduler.currentTime },
        logger = server.logger,
    )

    private fun signedInCredential(expiresAtMillis: Long) = XaiCredential(
        accessToken = XaiFixtures.ACCESS_TOKEN,
        refreshToken = XaiFixtures.REFRESH_TOKEN,
        idToken = XaiFixtures.ID_TOKEN,
        expiresAtMillis = expiresAtMillis,
        account = "reader@example.com",
    )

    // ---------------------------------------------------------------- 设备码登录

    @Test
    fun deviceLogin_pollsWithIntervalAndSlowDown_thenSavesCredential() = runTest {
        val pollTimes = Collections.synchronizedList(mutableListOf<Long>())
        val pollReplies = ArrayDeque(
            listOf(
                FakeAuthReply.Http(400, XaiFixtures.oauthError("authorization_pending")),
                FakeAuthReply.Http(400, XaiFixtures.oauthError("slow_down")),
                FakeAuthReply.NetworkFailure,
                FakeAuthReply.Http(200, XaiFixtures.tokenBody()),
            ),
        )
        server.handler = { request ->
            if (request.url == XaiAuthClient.DEVICE_CODE_URL) {
                FakeAuthReply.Http(200, XaiFixtures.deviceCodeBody(interval = 5))
            } else {
                pollTimes += testScheduler.currentTime
                synchronized(pollReplies) { pollReplies.removeFirst() }
            }
        }
        val repository = repository()
        repository.initialize()

        val observed = mutableListOf<XaiAuthState>()
        val collector = launch { repository.state.collect { observed += it } }
        repository.signInWithDeviceCode()
        runCurrent()
        collector.cancel()

        // 5s 后首轮；slow_down 之后间隔变为 10s。
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 30_000L), pollTimes.toList())
        assertEquals(XaiAuthState.SignedIn("reader@example.com"), repository.state.value)
        assertTrue(observed.any { it is XaiAuthState.AwaitingUser && it.userCode == XaiFixtures.USER_CODE })
        val saved = store.credential!!
        assertEquals(XaiFixtures.ACCESS_TOKEN, saved.accessToken)
        assertEquals(XaiFixtures.REFRESH_TOKEN, saved.refreshToken)
        assertEquals(30_000L + 3_600_000L, saved.expiresAtMillis)
        assertFalse(store.loginMarker)
    }

    @Test
    fun deviceLogin_terminalErrorsLeaveNoCredentialAndNoHalfState() = runTest {
        listOf(
            "expired_token" to XaiAuthException.DeviceCodeExpired::class,
            "access_denied" to XaiAuthException.AccessDenied::class,
        ).forEach { (oauthError, expected) ->
            server.handler = { request ->
                if (request.url == XaiAuthClient.DEVICE_CODE_URL) {
                    FakeAuthReply.Http(200, XaiFixtures.deviceCodeBody())
                } else {
                    FakeAuthReply.Http(400, XaiFixtures.oauthError(oauthError))
                }
            }
            val repository = repository()
            repository.initialize()

            val error = runCatching { repository.signInWithDeviceCode() }.exceptionOrNull()

            assertTrue("$oauthError -> $error", expected.isInstance(error))
            assertEquals(XaiAuthState.SignedOut(), repository.state.value)
            assertNull(store.credential)
            assertFalse(store.loginMarker)
        }
    }

    @Test
    fun deviceLogin_stopsAtShorterOfExpiresInAndFifteenMinutes() = runTest {
        server.handler = { request ->
            if (request.url == XaiAuthClient.DEVICE_CODE_URL) {
                FakeAuthReply.Http(200, XaiFixtures.deviceCodeBody(expiresIn = 3_600, interval = 5))
            } else {
                FakeAuthReply.Http(400, XaiFixtures.oauthError("authorization_pending"))
            }
        }
        val repository = repository()
        repository.initialize()

        val error = runCatching { repository.signInWithDeviceCode() }.exceptionOrNull()

        assertTrue(error is XaiAuthException.LoginTimedOut)
        assertEquals(XaiAuthRepository.MAX_LOGIN_MILLIS, testScheduler.currentTime)
        assertEquals(XaiAuthState.SignedOut(), repository.state.value)
    }

    @Test
    fun deviceLogin_untrustedVerificationUriAbortsBeforePolling() = runTest {
        server.handler = { FakeAuthReply.Http(200, XaiFixtures.deviceCodeBody(verificationUri = "https://evil.example/device")) }
        val repository = repository()
        repository.initialize()

        val error = runCatching { repository.signInWithDeviceCode() }.exceptionOrNull()

        assertTrue(error is XaiAuthException.UnsafeVerificationUri)
        assertTrue(server.tokenRequests().isEmpty())
        assertFalse(store.loginMarker)
    }

    @Test
    fun deviceLogin_cancellationRestoresSignedOutAndClearsMarker() = runTest {
        server.handler = { request ->
            if (request.url == XaiAuthClient.DEVICE_CODE_URL) {
                FakeAuthReply.Http(200, XaiFixtures.deviceCodeBody())
            } else {
                FakeAuthReply.Http(400, XaiFixtures.oauthError("authorization_pending"))
            }
        }
        val repository = repository()
        repository.initialize()

        val job = launch { repository.signInWithDeviceCode() }
        testScheduler.advanceTimeBy(12_000)
        runCurrent()
        assertTrue(store.loginMarker)
        job.cancel()
        advanceUntilIdle()

        assertEquals(XaiAuthState.SignedOut(), repository.state.value)
        assertFalse(store.loginMarker)
        assertNull(store.credential)
    }

    @Test
    fun initialize_reportsLoginInterruptedByProcessDeath() = runTest {
        store.loginMarker = true
        val repository = repository()

        repository.initialize()

        assertEquals(XaiAuthState.SignedOut("上次登录未完成，请重新登录"), repository.state.value)
        assertFalse(store.loginMarker)
    }

    @Test
    fun initialize_restoresSignedInFromStore() = runTest {
        store.credential = signedInCredential(expiresAtMillis = 10_000_000)
        val repository = repository()

        repository.initialize()

        assertEquals(XaiAuthState.SignedIn("reader@example.com"), repository.state.value)
    }

    @Test
    fun initialize_failureOrCancellationCanBeRetried() = runTest {
        listOf(IllegalStateException("synthetic storage failure"), CancellationException("synthetic cancellation"))
            .forEach { failure ->
                var fail = true
                val failingStore = object : XaiCredentialStore by store {
                    override suspend fun load(): XaiCredential? {
                        if (fail) throw failure
                        return store.load()
                    }
                }
                store.credential = signedInCredential(10_000_000)
                val repository = XaiAuthRepository(
                    server.client(), failingStore, StandardTestDispatcher(testScheduler),
                    { testScheduler.currentTime }, server.logger,
                )
                val error = runCatching { repository.initialize() }.exceptionOrNull()
                // 协程栈恢复可能复制异常，比较类型和文案而不是对象身份。
                assertEquals(failure.javaClass, error?.javaClass)
                assertEquals(failure.message, error?.message)
                fail = false
                repository.initialize()
                assertEquals(XaiAuthState.SignedIn("reader@example.com"), repository.state.value)
            }
    }

    @Test
    fun leaseCannotRefreshOrReadNewAccountAfterSignOutAndLogin() = runTest {
        store.credential = signedInCredential(10_000_000)
        server.handler = { request ->
            when (request.url) {
                XaiAuthClient.REVOKE_URL -> FakeAuthReply.Http(200, "")
                XaiAuthClient.DEVICE_CODE_URL -> FakeAuthReply.Http(200, XaiFixtures.deviceCodeBody())
                else -> FakeAuthReply.Http(200, XaiFixtures.tokenBody("synthetic-other-account", "synthetic-other-refresh"))
            }
        }
        val repository = repository()
        repository.initialize()
        val oldLease = repository.acquireAccessToken()
        repository.signOut()
        repository.signInWithDeviceCode()
        val requestCount = server.requests.size

        assertTrue(runCatching { repository.refreshAccessToken(oldLease) }.exceptionOrNull() is XaiAuthException.NotSignedIn)
        assertTrue(runCatching { repository.validateSession(oldLease) }.exceptionOrNull() is XaiAuthException.NotSignedIn)
        assertEquals(requestCount, server.requests.size)
        assertEquals("synthetic-other-account", repository.acquireAccessToken().accessToken)
    }

    @Test
    fun refreshedLeaseRemainsInSameSessionAndReusesConcurrentRefresh() = runTest {
        store.credential = signedInCredential(10_000_000)
        server.handler = { FakeAuthReply.Http(200, XaiFixtures.tokenBody(XaiFixtures.NEW_ACCESS_TOKEN)) }
        val repository = repository()
        repository.initialize()
        val lease = repository.acquireAccessToken()
        val refreshed = repository.refreshAccessToken(lease)
        val reused = repository.refreshAccessToken(lease)

        assertEquals(lease.sessionGeneration, refreshed.sessionGeneration)
        assertEquals(refreshed.accessToken, reused.accessToken)
        assertEquals(1, server.tokenRequests().size)
        repository.validateSession(lease)
    }

    @Test
    fun legacyLeaseWithoutSessionIsRejectedByProductionRepository() = runTest {
        store.credential = signedInCredential(10_000_000)
        val repository = repository()
        repository.initialize()
        val unbound = XaiAccessTokenLease(XaiFixtures.ACCESS_TOKEN)
        assertTrue(runCatching { repository.validateSession(unbound) }.exceptionOrNull() is XaiAuthException.NotSignedIn)
        assertTrue(runCatching { repository.refreshAccessToken(unbound) }.exceptionOrNull() is XaiAuthException.NotSignedIn)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun sessionGuardAndSignOutAreAtomicForCatalogWriteback() = runTest {
        store.credential = signedInCredential(10_000_000)
        server.handler = { FakeAuthReply.Http(200, "") }
        val repository = repository()
        repository.initialize()
        val lease = repository.acquireAccessToken()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var wroteModels = false
        val writing = launch {
            repository.withCurrentSession(lease) {
                entered.complete(Unit)
                release.await()
                wroteModels = true
            }
        }
        entered.await()
        val logout = launch { repository.signOut() }
        runCurrent()
        assertFalse(logout.isCompleted)
        assertFalse(wroteModels)
        release.complete(Unit)
        writing.join()
        logout.join()
        assertTrue(wroteModels)
        assertEquals(XaiAuthState.SignedOut(), repository.state.value)
        assertTrue(runCatching { repository.withCurrentSession(lease) { error("must not write") } }
            .exceptionOrNull() is XaiAuthException.NotSignedIn)
    }

    @Test
    fun signOutDuringDeviceCodeRequest_doesNotRestorePreviousSignedInState() = runTest {
        store.credential = signedInCredential(10_000_000)
        val arrived = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        server.handler = { request ->
            if (request.url == XaiAuthClient.DEVICE_CODE_URL) {
                arrived.complete(Unit)
                release.await(10, TimeUnit.SECONDS)
                FakeAuthReply.Http(200, XaiFixtures.deviceCodeBody())
            } else {
                FakeAuthReply.Http(200, "")
            }
        }
        val repository = repository()
        repository.initialize()
        val login = async { runCatching { repository.signInWithDeviceCode() } }
        arrived.await()
        try {
            repository.signOut()
        } finally {
            release.countDown()
        }
        assertTrue(login.await().exceptionOrNull() is CancellationException)
        assertEquals(XaiAuthState.SignedOut(), repository.state.value)
        assertNull(store.credential)
        assertFalse(store.loginMarker)
    }

    @Test
    fun signOutStorageFailureStillInvalidatesLeaseAndCanRetryCleanup() = runTest {
        store.credential = signedInCredential(10_000_000)
        var failClear = true
        val failingStore = object : XaiCredentialStore by store {
            override suspend fun clear() {
                if (failClear) error("synthetic cleanup failure")
                store.clear()
            }
        }
        server.handler = { FakeAuthReply.Http(200, "") }
        val repository = XaiAuthRepository(
            server.client(), failingStore, StandardTestDispatcher(testScheduler),
            { testScheduler.currentTime }, server.logger,
        )
        repository.initialize()
        val lease = repository.acquireAccessToken()
        assertTrue(runCatching { repository.signOut() }.isFailure)
        repository.initialize()
        assertEquals(XaiAuthState.SignedOut(), repository.state.value)
        assertTrue(runCatching { repository.acquireAccessToken() }.exceptionOrNull() is XaiAuthException.NotSignedIn)
        assertTrue(runCatching { repository.validateSession(lease) }.exceptionOrNull() is XaiAuthException.NotSignedIn)
        failClear = false
        repository.signOut()
        assertNull(store.credential)
    }

    @Test
    fun loginMarkerCleanupFailureDoesNotPermanentlyBlockNewLogin() = runTest {
        var failCleanup = true
        val failingStore = object : XaiCredentialStore by store {
            override fun setLoginInProgress(inProgress: Boolean) {
                if (!inProgress && failCleanup) {
                    failCleanup = false
                    error("synthetic marker cleanup failure")
                }
                store.setLoginInProgress(inProgress)
            }
        }
        server.handler = { request ->
            if (request.url == XaiAuthClient.DEVICE_CODE_URL) {
                FakeAuthReply.Http(200, XaiFixtures.deviceCodeBody())
            } else {
                FakeAuthReply.Http(200, XaiFixtures.tokenBody())
            }
        }
        val repository = XaiAuthRepository(
            server.client(), failingStore, StandardTestDispatcher(testScheduler),
            { testScheduler.currentTime }, server.logger,
        )
        repository.initialize()
        assertTrue(runCatching { repository.signInWithDeviceCode() }.isFailure)
        repository.signInWithDeviceCode()
        assertTrue(repository.state.value is XaiAuthState.SignedIn)
        assertFalse(store.loginMarker)
    }

    // ---------------------------------------------------------------- 刷新

    @Test
    fun validToken_isReturnedWithoutNetwork_andRefreshStartsTwoMinutesEarly() = runTest {
        store.credential = signedInCredential(expiresAtMillis = XaiAuthRepository.REFRESH_MARGIN_MILLIS + 1)
        server.handler = { FakeAuthReply.Http(200, XaiFixtures.tokenBody(XaiFixtures.NEW_ACCESS_TOKEN)) }
        val repository = repository()
        repository.initialize()

        assertEquals(XaiFixtures.ACCESS_TOKEN, repository.getValidAccessToken())
        assertTrue(server.requests.isEmpty())

        testScheduler.advanceTimeBy(1)
        assertEquals(XaiFixtures.NEW_ACCESS_TOKEN, repository.getValidAccessToken())
        assertEquals(1, server.tokenRequests().size)
    }

    @Test
    fun refresh_rotatesRefreshTokenAtomically_orKeepsOldWhenNotReturned() = runTest {
        store.credential = signedInCredential(expiresAtMillis = 0)
        server.handler = {
            FakeAuthReply.Http(200, XaiFixtures.tokenBody(XaiFixtures.NEW_ACCESS_TOKEN, XaiFixtures.NEW_REFRESH_TOKEN, idToken = null))
        }
        val repository = repository()
        repository.initialize()

        repository.getValidAccessToken()

        assertEquals(XaiFixtures.NEW_ACCESS_TOKEN, store.credential!!.accessToken)
        assertEquals(XaiFixtures.NEW_REFRESH_TOKEN, store.credential!!.refreshToken)
        assertEquals(XaiFixtures.ID_TOKEN, store.credential!!.idToken)
        assertEquals("reader@example.com", store.credential!!.account)
        // 新 access token 与新 refresh token 在同一次保存里落盘。
        assertEquals(1, store.saveCount)

        store.credential = store.credential!!.copy(expiresAtMillis = 0)
        server.handler = { FakeAuthReply.Http(200, XaiFixtures.tokenBody("synthetic-access-token-3", refreshToken = null)) }
        repository.getValidAccessToken()
        assertEquals(XaiFixtures.NEW_REFRESH_TOKEN, store.credential!!.refreshToken)
    }

    @Test
    fun refresh_failureMatrix_onlyInvalidGrantClearsCredential() = runTest {
        data class Case(val reply: FakeAuthReply, val expected: Class<out XaiAuthException>, val clears: Boolean)
        listOf(
            Case(FakeAuthReply.NetworkFailure, XaiAuthException.Network::class.java, clears = false),
            Case(FakeAuthReply.Http(503, "{}"), XaiAuthException.Server::class.java, clears = false),
            Case(FakeAuthReply.Http(429, "{}"), XaiAuthException.RateLimited::class.java, clears = false),
            Case(FakeAuthReply.Http(400, XaiFixtures.oauthError("invalid_request")), XaiAuthException.Rejected::class.java, clears = false),
            Case(FakeAuthReply.Http(401, XaiFixtures.oauthError("invalid_client")), XaiAuthException.Rejected::class.java, clears = false),
            Case(FakeAuthReply.Http(400, XaiFixtures.oauthError("invalid_grant")), XaiAuthException.AuthorizationRevoked::class.java, clears = true),
        ).forEach { case ->
            store.credential = signedInCredential(expiresAtMillis = 0)
            server.handler = { case.reply }
            val repository = repository()
            repository.initialize()

            val error = runCatching { repository.getValidAccessToken() }.exceptionOrNull()

            assertTrue("${case.reply} -> $error", case.expected.isInstance(error))
            if (case.clears) {
                assertNull(store.credential)
                assertEquals(XaiAuthState.SignedOut("xAI 授权已失效，请重新登录"), repository.state.value)
            } else {
                assertEquals(XaiFixtures.REFRESH_TOKEN, store.credential?.refreshToken)
                assertEquals(XaiAuthState.SignedIn("reader@example.com"), repository.state.value)
            }
        }
    }

    @Test
    fun refresh_retriesServerErrorsWithBackoff() = runTest {
        store.credential = signedInCredential(expiresAtMillis = 0)
        val replies = ArrayDeque(
            listOf(
                FakeAuthReply.Http(502, "{}"),
                FakeAuthReply.Http(429, "{}"),
                FakeAuthReply.Http(200, XaiFixtures.tokenBody(XaiFixtures.NEW_ACCESS_TOKEN)),
            ),
        )
        server.handler = { synchronized(replies) { replies.removeFirst() } }
        val repository = repository()
        repository.initialize()

        assertEquals(XaiFixtures.NEW_ACCESS_TOKEN, repository.getValidAccessToken())
        assertEquals(3, server.tokenRequests().size)
        assertEquals(3_000L, testScheduler.currentTime)

        // 重试次数用尽后仍保留凭据。
        store.credential = store.credential!!.copy(expiresAtMillis = 0)
        server.handler = { FakeAuthReply.Http(500, "{}") }
        assertTrue(runCatching { repository.getValidAccessToken() }.exceptionOrNull() is XaiAuthException.Server)
        assertEquals(3 + 1 + XaiAuthRepository.REFRESH_BACKOFF_MILLIS.size, server.tokenRequests().size)
        assertEquals(XaiFixtures.NEW_ACCESS_TOKEN, store.credential?.accessToken)
    }

    @Test
    fun concurrentCallers_triggerOnlyOneRefresh() = runTest {
        store.credential = signedInCredential(expiresAtMillis = 0)
        server.handler = { FakeAuthReply.Http(200, XaiFixtures.tokenBody(XaiFixtures.NEW_ACCESS_TOKEN)) }
        val repository = repository()
        repository.initialize()

        val tokens = List(5) { async { repository.getValidAccessToken() } }.awaitAll()

        assertEquals(List(5) { XaiFixtures.NEW_ACCESS_TOKEN }, tokens)
        assertEquals(1, server.tokenRequests().size)
    }

    @Test
    fun rejectedToken_forcesRefreshOnce_butStaleRejectionDoesNot() = runTest {
        store.credential = signedInCredential(expiresAtMillis = 10_000_000)
        server.handler = { FakeAuthReply.Http(200, XaiFixtures.tokenBody(XaiFixtures.NEW_ACCESS_TOKEN)) }
        val repository = repository()
        repository.initialize()

        assertEquals(XaiFixtures.NEW_ACCESS_TOKEN, repository.getValidAccessToken(rejectedAccessToken = XaiFixtures.ACCESS_TOKEN))
        // 另一个请求带着同一个旧 token 回来：已经刷新过，不再重复刷新。
        assertEquals(XaiFixtures.NEW_ACCESS_TOKEN, repository.getValidAccessToken(rejectedAccessToken = XaiFixtures.ACCESS_TOKEN))
        assertEquals(1, server.tokenRequests().size)
    }

    @Test
    fun notSignedIn_reportsLoginRequired() = runTest {
        val repository = repository()
        repository.initialize()

        val error = runCatching { repository.getValidAccessToken() }.exceptionOrNull()

        assertTrue(error is XaiAuthException.NotSignedIn)
        assertEquals("请先登录 xAI", error?.message)
    }

    // ---------------------------------------------------------------- 登出

    @Test
    fun signOutDuringInFlightRefresh_doesNotResurrectLogin() = runTest {
        store.credential = signedInCredential(expiresAtMillis = 0)
        val requestArrived = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        server.handler = { request ->
            // 登出会顺带发吊销请求，它不参与本用例要制造的「刷新在途」，必须立即返回。
            if (request.url == XaiAuthClient.REVOKE_URL) {
                FakeAuthReply.Http(200, "")
            } else {
                requestArrived.complete(Unit)
                release.await(10, TimeUnit.SECONDS)
                FakeAuthReply.Http(200, XaiFixtures.tokenBody(XaiFixtures.NEW_ACCESS_TOKEN, XaiFixtures.NEW_REFRESH_TOKEN))
            }
        }
        val repository = repository()
        repository.initialize()

        val refresh = async { runCatching { repository.getValidAccessToken() } }
        requestArrived.await()
        repository.signOut()
        release.countDown()
        val result = refresh.await()

        assertTrue(result.exceptionOrNull() is XaiAuthException.NotSignedIn)
        assertNull(store.credential)
        assertEquals(0, store.saveCount)
        assertEquals(XaiAuthState.SignedOut(), repository.state.value)
    }

    @Test
    fun signOut_clearsStore() = runTest {
        server.handler = { FakeAuthReply.Http(200, "") }
        store.credential = signedInCredential(expiresAtMillis = 10_000_000)
        val repository = repository()
        repository.initialize()

        repository.signOut()

        assertNull(store.credential)
        assertEquals(XaiAuthState.SignedOut(), repository.state.value)
    }

    @Test
    fun signOut_revokesRefreshThenAccessToken_withHintsAndClientId() = runTest {
        server.handler = { FakeAuthReply.Http(200, "") }
        store.credential = signedInCredential(expiresAtMillis = 10_000_000)
        val repository = repository()
        repository.initialize()

        repository.signOut()

        val revocations = server.revokeRequests()
        assertEquals(2, revocations.size)
        // 先 refresh_token：RFC 7009 §2.1 要求服务端连带失效同一授权下的 access token。
        assertEquals("refresh_token", revocations[0].form["token_type_hint"])
        assertEquals(XaiFixtures.REFRESH_TOKEN, revocations[0].form["token"])
        assertEquals("access_token", revocations[1].form["token_type_hint"])
        assertEquals(XaiFixtures.ACCESS_TOKEN, revocations[1].form["token"])
        revocations.forEach { assertEquals(XaiAuthClient.CLIENT_ID, it.form["client_id"]) }
    }

    @Test
    fun signOut_completesLocally_evenWhenRevocationFails() = runTest {
        val replies = listOf(FakeAuthReply.NetworkFailure, FakeAuthReply.Http(503, "{}"), FakeAuthReply.Http(400, "{}"))
        for (reply in replies) {
            val server = FakeXaiAuthServer { reply }
            val store = InMemoryXaiCredentialStore()
            store.credential = signedInCredential(expiresAtMillis = 10_000_000)
            val repository = XaiAuthRepository(
                client = server.client(),
                store = store,
                ioDispatcher = StandardTestDispatcher(testScheduler),
                clock = { testScheduler.currentTime },
                logger = server.logger,
            )
            repository.initialize()

            repository.signOut()

            assertNull("$reply 下本机凭据必须已清除", store.credential)
            assertEquals("$reply 下必须已登出", XaiAuthState.SignedOut(), repository.state.value)
            val leaked = server.logger.messages.joinToString("\n")
            XaiFixtures.SECRETS.forEach { assertFalse("leaked $it", leaked.contains(it)) }
        }
    }

    // ---------------------------------------------------------------- 日志安全

    @Test
    fun logsStateAndErrors_neverContainSecrets() = runTest {
        server.handler = { request ->
            when {
                request.url == XaiAuthClient.DEVICE_CODE_URL -> FakeAuthReply.Http(200, XaiFixtures.deviceCodeBody())
                request.form["grant_type"] == "refresh_token" -> FakeAuthReply.Http(400, XaiFixtures.oauthError("invalid_grant"))
                else -> FakeAuthReply.Http(200, XaiFixtures.tokenBody())
            }
        }
        val repository = repository()
        repository.initialize()
        repository.signInWithDeviceCode()
        store.credential = store.credential!!.copy(expiresAtMillis = 0)
        val refreshError = runCatching { repository.getValidAccessToken() }.exceptionOrNull()

        val everything = (
            server.logger.messages +
                listOf(refreshError?.message.orEmpty(), repository.state.value.toString(), signedInCredential(0).toString())
            ).joinToString("\n")
        XaiFixtures.SECRETS.forEach { assertFalse("leaked $it", everything.contains(it)) }
    }
}
