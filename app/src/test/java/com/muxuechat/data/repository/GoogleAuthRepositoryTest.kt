package com.muxuechat.data.repository

import com.muxuechat.domain.model.GoogleAuthState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Google 登录仓库：只存「已登录」标记，token 只在内存；全部使用假的 Play 服务。 */
class GoogleAuthRepositoryTest {
    private class FakeStore(initial: Boolean = false) : GoogleSignInStateStore {
        var flag: Boolean = initial
        override fun isSignedIn() = flag
        override fun setSignedIn(value: Boolean) {
            flag = value
        }
    }

    private class FakeAuthorizer(
        var result: () -> GoogleAuthorization = { GoogleAuthorization.Granted("synthetic-token-1") },
    ) : GoogleAuthorizer {
        var authorizeCalls = 0
        val clearedTokens = mutableListOf<String>()
        var revokeCalls = 0
        var revokeFailure: Exception? = null
        var clearFailure: Exception? = null
        var fromIntentResult: GoogleAuthorization = GoogleAuthorization.Granted("synthetic-token-2")

        override suspend fun authorize(): GoogleAuthorization {
            authorizeCalls++
            return result()
        }

        override suspend fun authorizationFromIntent(data: android.content.Intent?) = fromIntentResult

        override suspend fun clearToken(accessToken: String) {
            clearedTokens += accessToken
            clearFailure?.let { throw it }
        }

        override suspend fun revokeAccess() {
            revokeCalls++
            revokeFailure?.let { throw it }
        }
    }

    private fun repository(authorizer: FakeAuthorizer, store: FakeStore) = GoogleAuthRepository(authorizer, store)

    @Test
    fun notSignedIn_neverTouchesPlayServices() = runBlocking {
        val authorizer = FakeAuthorizer()
        val repo = repository(authorizer, FakeStore(false))

        val error = runCatching { repo.acquireAccessToken() }.exceptionOrNull()

        assertTrue(error is GoogleAuthRequiredException)
        assertEquals(0, authorizer.authorizeCalls)
        assertTrue(repo.state.value is GoogleAuthState.SignedOut)
    }

    @Test
    fun signedIn_returnsTokenSilently_andKeepsStateSignedIn() = runBlocking {
        val authorizer = FakeAuthorizer()
        val repo = repository(authorizer, FakeStore(true))

        assertEquals("synthetic-token-1", repo.acquireAccessToken())
        assertEquals(GoogleAuthState.SignedIn, repo.state.value)
        assertEquals(1, authorizer.authorizeCalls)
    }

    @Test
    fun forceRefresh_dropsTheRejectedCachedTokenFirst() = runBlocking {
        var n = 0
        val authorizer = FakeAuthorizer(result = { GoogleAuthorization.Granted("synthetic-token-${++n}") })
        val repo = repository(authorizer, FakeStore(true))

        val first = repo.acquireAccessToken()
        val second = repo.acquireAccessToken(forceRefresh = true)

        assertEquals("synthetic-token-1", first)
        assertEquals("synthetic-token-2", second)
        // 让 Play 服务丢弃被拒的那一枚，否则会原样把缓存里的失效 token 再发一次。
        assertEquals(listOf("synthetic-token-1"), authorizer.clearedTokens)
    }

    @Test
    fun forceRefresh_survivesClearTokenFailure() = runBlocking {
        val authorizer = FakeAuthorizer().apply { clearFailure = IllegalStateException("boom") }
        val repo = repository(authorizer, FakeStore(true))
        repo.acquireAccessToken()

        assertEquals("synthetic-token-1", repo.acquireAccessToken(forceRefresh = true))
    }

    @Test
    fun consentRequired_marksSignedOutWithNotice_andNeverPromptsInBackground() = runBlocking {
        val store = FakeStore(true)
        val repo = repository(FakeAuthorizer(result = { GoogleAuthorization.NeedsConsent(null) }), store)

        val error = runCatching { repo.acquireAccessToken() }.exceptionOrNull()

        assertEquals("Google 授权已失效，请到设置页重新登录 Google", error?.message)
        assertFalse(store.flag)
        val state = repo.state.value as GoogleAuthState.SignedOut
        assertTrue(state.notice.orEmpty().contains("重新登录"))
    }

    @Test
    fun playServicesFailure_exposesOnlyTheExceptionTypeNotItsMessage() = runBlocking {
        val repo = repository(
            FakeAuthorizer(result = { throw IllegalStateException("secret-account@example.com 10") }),
            FakeStore(true),
        )

        val error = runCatching { repo.acquireAccessToken() }.exceptionOrNull()

        assertTrue(error is GoogleAuthRequiredException)
        assertTrue(error!!.message.orEmpty().contains("IllegalStateException"))
        assertFalse(error.message.orEmpty().contains("secret-account"))
        // 失败不等于授权被撤销：保持已登录，用户重试即可。
        assertEquals(GoogleAuthState.SignedIn, repo.state.value)
    }

    @Test
    fun signOutDuringAuthorize_doesNotHandOutTheFreshToken() = runBlocking {
        val store = FakeStore(true)
        val authorizer = FakeAuthorizer(result = {
            store.flag = false // 模拟请求在途时用户退出登录
            GoogleAuthorization.Granted("synthetic-late-token")
        })
        val repo = repository(authorizer, store)

        val error = runCatching { repo.acquireAccessToken() }.exceptionOrNull()

        assertEquals("请先在设置中登录 Google（Google Vertex 登录）", error?.message)
    }

    @Test
    fun beginSignIn_grantedMarksSignedIn_consentLeavesSignedOut() = runBlocking {
        val store = FakeStore()
        val granted = repository(FakeAuthorizer(), store)
        assertTrue(granted.beginSignIn() is GoogleAuthorization.Granted)
        assertTrue(store.flag)
        assertEquals(GoogleAuthState.SignedIn, granted.state.value)

        val store2 = FakeStore()
        val needs = repository(FakeAuthorizer(result = { GoogleAuthorization.NeedsConsent(null) }), store2)
        assertTrue(needs.beginSignIn() is GoogleAuthorization.NeedsConsent)
        assertFalse(store2.flag)
    }

    @Test
    fun completeSignIn_requiresGrantedResult() = runBlocking {
        val store = FakeStore()
        val authorizer = FakeAuthorizer()
        val repo = repository(authorizer, store)

        authorizer.fromIntentResult = GoogleAuthorization.NeedsConsent(null)
        assertTrue(runCatching { repo.completeSignIn(null) }.exceptionOrNull() is IllegalStateException)
        assertFalse(store.flag)

        authorizer.fromIntentResult = GoogleAuthorization.Granted("synthetic-token-2")
        repo.completeSignIn(null)
        assertTrue(store.flag)
    }

    @Test
    fun signOut_stopsLocalSigninFirst_andSurvivesRevokeFailure() = runBlocking {
        val store = FakeStore(true)
        val authorizer = FakeAuthorizer().apply { revokeFailure = IllegalStateException("offline") }
        val repo = repository(authorizer, store)
        repo.acquireAccessToken()

        repo.signOut()

        assertFalse(store.flag)
        assertEquals(1, authorizer.revokeCalls)
        assertTrue(repo.state.value is GoogleAuthState.SignedOut)
        // 退出后不能再取 token，也不应再向 Play 服务发起授权。
        val before = authorizer.authorizeCalls
        assertTrue(runCatching { repo.acquireAccessToken() }.exceptionOrNull() is GoogleAuthRequiredException)
        assertEquals(before, authorizer.authorizeCalls)
    }

    @Test
    fun tokensAreNeverExposedThroughToString() {
        assertFalse(GoogleAuthorization.Granted("synthetic-secret").toString().contains("synthetic-secret"))
        assertFalse(ProviderCredential.GoogleBearer("synthetic-secret").toString().contains("synthetic-secret"))
        assertNull(null)
    }
}
