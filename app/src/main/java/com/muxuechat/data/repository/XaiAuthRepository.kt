package com.muxuechat.data.repository

import android.content.Context
import com.muxuechat.data.local.EncryptedXaiCredentialStore
import com.muxuechat.data.local.XaiCredential
import com.muxuechat.data.local.XaiCredentialStore
import com.muxuechat.data.remote.AndroidRemoteClientLogger
import com.muxuechat.data.remote.RemoteClientLogger
import com.muxuechat.data.remote.XaiAuthClient
import com.muxuechat.data.remote.XaiAuthException
import com.muxuechat.data.remote.XaiDevicePollResult
import com.muxuechat.data.remote.XaiIdTokenReader
import com.muxuechat.data.remote.XaiTokenResponse
import com.muxuechat.data.remote.XaiVerificationUriPolicy
import com.muxuechat.domain.model.XaiAuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * xAI 订阅登录：设备码登录（RFC 8628）、凭据保存、按需刷新与登出。
 *
 * 并发约定：
 * - [refreshMutex] 串行所有刷新：并发调用 [getValidAccessToken] 只会触发一次网络刷新，
 *   后到者拿到的是前者刷新后的 token。
 * - [storeMutex] 只包住「检查登出世代号 + 读写存储」这一小段，不跨网络请求，
 *   所以登出不必等在途刷新结束。
 * - [generation] 在每次登出时递增。刷新或登录在发起网络请求前记下世代号，写回前在 [storeMutex]
 *   内核对；不一致说明期间发生过登出，结果直接丢弃，登录态不会「复活」。
 *
 * 刷新只在调用方需要 token 时于前台按需进行，这里没有任何后台任务或定时器（AGENT.md §2）。
 */
class XaiAuthRepository internal constructor(
    private val client: XaiAuthClient,
    private val store: XaiCredentialStore,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val logger: RemoteClientLogger,
) : XaiAccessTokenSource {
    private val _state = MutableStateFlow<XaiAuthState>(XaiAuthState.Loading)
    val state: StateFlow<XaiAuthState> = _state.asStateFlow()

    private val refreshMutex = Mutex()
    private val storeMutex = Mutex()
    private var generation = 0L
    private val loginRunning = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)

    /** 读取本机登录状态。重复调用无副作用；进程内首次调用时处理上次被中断的登录。 */
    suspend fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        storeMutex.withLock {
            val credential = io { store.load() }
            val interrupted = io { store.isLoginInProgress() } && !loginRunning.get()
            if (interrupted) io { store.setLoginInProgress(false) }
            _state.value = when {
                credential != null -> XaiAuthState.SignedIn(credential.account)
                interrupted -> XaiAuthState.SignedOut("上次登录未完成，请重新登录")
                else -> XaiAuthState.SignedOut()
            }
        }
    }

    /**
     * 完整走一遍设备码登录，直到成功、失败或调用方取消。
     *
     * 期间通过 [state] 发布 [XaiAuthState.AwaitingUser] 供界面展示验证码。
     * 失败时抛出 [XaiAuthException]，状态回到登录前；取消时同样回到登录前，不留半截状态。
     */
    suspend fun signInWithDeviceCode() {
        if (!loginRunning.compareAndSet(false, true)) throw XaiAuthException.LoginAlreadyRunning()
        val previousState = _state.value
        val loginGeneration = storeMutex.withLock { generation }
        try {
            io { store.setLoginInProgress(true) }
            _state.value = XaiAuthState.RequestingCode

            val deviceCode = client.requestDeviceCode()
            val verificationUri = XaiVerificationUriPolicy.select(deviceCode)
            val deadline = clock() + minOf(deviceCode.expiresInSeconds * 1_000, MAX_LOGIN_MILLIS)
            _state.value = XaiAuthState.AwaitingUser(deviceCode.userCode, verificationUri, deadline)
            logger.info("xAI device login awaiting user")

            var intervalMillis = deviceCode.intervalSeconds * 1_000
            while (true) {
                delay(intervalMillis)
                if (clock() >= deadline) throw XaiAuthException.LoginTimedOut()
                when (val result = client.pollDeviceToken(deviceCode.deviceCode)) {
                    XaiDevicePollResult.Pending, XaiDevicePollResult.Transient -> Unit
                    XaiDevicePollResult.SlowDown -> intervalMillis += SLOW_DOWN_STEP_MILLIS
                    is XaiDevicePollResult.Granted -> {
                        val credential = result.tokens.toCredential(previous = null)
                        storeMutex.withLock {
                            // 登录期间发生过登出（理论上界面不会同时提供两个入口），以登出为准。
                            if (generation != loginGeneration) throw CancellationException("登出后丢弃登录结果")
                            io { store.save(credential) }
                            _state.value = XaiAuthState.SignedIn(credential.account)
                        }
                        logger.info("xAI device login completed")
                        return
                    }
                }
            }
        } catch (error: Throwable) {
            if (_state.value !is XaiAuthState.SignedIn) {
                _state.value = when (previousState) {
                    XaiAuthState.Loading, XaiAuthState.RequestingCode, is XaiAuthState.AwaitingUser ->
                        XaiAuthState.SignedOut()
                    else -> previousState
                }
            }
            if (error is XaiAuthException) logger.warn("xAI device login failed: ${error.javaClass.simpleName}")
            throw error
        } finally {
            withContext(NonCancellable) { io { store.setLoginInProgress(false) } }
            loginRunning.set(false)
        }
    }

    /**
     * 返回一个当前有效的 access token，必要时先刷新（到期前 2 分钟即刷新）。
     *
     * [rejectedAccessToken] 用于「请求收到 401 后刷新并重试一次」：如果它仍是本机保存的那一个，
     * 就无视过期时间强制刷新；如果别的调用已经刷新过，直接返回新 token，不重复刷新。
     *
     * 失败时抛出 [XaiAuthException]：只有 [XaiAuthException.AuthorizationRevoked] 会清除凭据。
     */
    override suspend fun getValidAccessToken(rejectedAccessToken: String?): String = refreshMutex.withLock {
        val (credential, refreshGeneration) = storeMutex.withLock { io { store.load() } to generation }
        if (credential == null) {
            syncSignedOutState()
            throw XaiAuthException.NotSignedIn()
        }
        val forced = rejectedAccessToken != null && rejectedAccessToken == credential.accessToken
        if (!forced && credential.expiresAtMillis - REFRESH_MARGIN_MILLIS > clock()) {
            return@withLock credential.accessToken
        }

        val refreshToken = credential.refreshToken ?: run {
            revoke(refreshGeneration)
            throw XaiAuthException.AuthorizationRevoked()
        }
        val tokens = try {
            refreshWithBackoff(refreshToken)
        } catch (error: XaiAuthException) {
            if (!error.keepsCredential) revoke(refreshGeneration)
            throw error
        }

        val refreshed = tokens.toCredential(previous = credential)
        storeMutex.withLock {
            // 刷新请求在途时用户登出了：新 token 直接丢弃，不写回。
            if (generation != refreshGeneration) throw XaiAuthException.NotSignedIn()
            io { store.save(refreshed) }
            _state.value = XaiAuthState.SignedIn(refreshed.account)
        }
        refreshed.accessToken
    }

    /**
     * 登出：先删除本机密文与 Keystore alias，再尽力而为地调用服务端吊销端点（RFC 7009）。
     *
     * 顺序与容错是刻意的：
     * - **本机登出无条件先完成**。用户点了登出就必须立刻不再是登录态，
     *   不能因为断网或服务端故障而卡住或失败。
     * - 吊销在 [storeMutex] 之外执行，不阻塞其它调用方，失败只记录错误类型。
     * - 先递增 [generation]，因此吊销期间任何在途刷新都无法把凭据写回。
     * - 先吊销 refresh token：RFC 7009 §2.1 规定服务端 SHOULD 连带失效同一授权下的
     *   access token。随后再吊销 access token 作为兜底——若服务端已连带失效，
     *   这次请求按 §2.2 同样返回 200，无副作用。
     */
    suspend fun signOut() {
        val revoked = storeMutex.withLock {
            generation++
            val current = io { store.load() }
            io { store.clear() }
            _state.value = XaiAuthState.SignedOut()
            current
        }
        logger.info("xAI signed out")
        revoked?.let { revokeBestEffort(it) }
    }

    /** 吊销失败不影响已经完成的本机登出，只记录错误类型。 */
    private suspend fun revokeBestEffort(credential: XaiCredential) {
        val targets = listOfNotNull(
            credential.refreshToken?.let { it to "refresh_token" },
            credential.accessToken.takeIf { it.isNotBlank() }?.let { it to "access_token" },
        )
        for ((token, hint) in targets) {
            try {
                client.revoke(token, hint)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.warn("xAI $hint revocation failed: ${error.javaClass.simpleName}")
            }
        }
    }

    /** 5xx 与 429 按退避重试；网络错误、`invalid_grant` 与其他 4xx 直接交给调用方。 */
    private suspend fun refreshWithBackoff(refreshToken: String): XaiTokenResponse {
        var attempt = 0
        while (true) {
            try {
                return client.refresh(refreshToken)
            } catch (error: XaiAuthException) {
                val retryable = error is XaiAuthException.Server || error is XaiAuthException.RateLimited
                if (!retryable || attempt >= REFRESH_BACKOFF_MILLIS.size) throw error
                delay(REFRESH_BACKOFF_MILLIS[attempt])
                attempt++
            }
        }
    }

    private suspend fun revoke(expectedGeneration: Long) {
        storeMutex.withLock {
            if (generation != expectedGeneration) return
            generation++
            io { store.clear() }
            _state.value = XaiAuthState.SignedOut("xAI 授权已失效，请重新登录")
        }
        logger.warn("xAI authorization revoked; credential cleared")
    }

    private fun syncSignedOutState() {
        if (_state.value is XaiAuthState.SignedIn) {
            _state.value = XaiAuthState.SignedOut("本机保存的 xAI 登录已失效，请重新登录")
        }
    }

    /** 服务端轮换 refresh token 时用新的；没返回就沿用旧的。id_token 同理。 */
    private fun XaiTokenResponse.toCredential(previous: XaiCredential?): XaiCredential {
        val newIdToken = idToken ?: previous?.idToken
        return XaiCredential(
            accessToken = accessToken,
            refreshToken = refreshToken ?: previous?.refreshToken,
            idToken = newIdToken,
            expiresAtMillis = clock() + (expiresInSeconds ?: DEFAULT_TOKEN_LIFETIME_SECONDS) * 1_000,
            account = XaiIdTokenReader.account(newIdToken) ?: previous?.account,
        )
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(ioDispatcher) { block() }

    companion object {
        private const val TAG = "muxueChat.XaiAuth"

        /** 设备码登录的总时长上限；服务端给的 expires_in 更短时以服务端为准。 */
        internal const val MAX_LOGIN_MILLIS = 15 * 60 * 1_000L
        internal const val SLOW_DOWN_STEP_MILLIS = 5_000L
        internal const val REFRESH_MARGIN_MILLIS = 2 * 60 * 1_000L
        internal val REFRESH_BACKOFF_MILLIS = longArrayOf(1_000L, 2_000L)

        /** 服务端未给 expires_in 时的保守假设；真实失效时由 401 → 强制刷新兜底。 */
        private const val DEFAULT_TOKEN_LIFETIME_SECONDS = 3_600L

        @Volatile
        private var instance: XaiAuthRepository? = null

        /**
         * 进程内唯一实例。刷新的串行化依赖同一把锁，后续接入聊天链路时必须与设置页共用这一个实例。
         */
        fun getInstance(context: Context): XaiAuthRepository =
            instance ?: synchronized(this) {
                instance ?: XaiAuthRepository(
                    client = XaiAuthClient(),
                    store = EncryptedXaiCredentialStore(context.applicationContext),
                    ioDispatcher = Dispatchers.IO,
                    clock = System::currentTimeMillis,
                    logger = AndroidRemoteClientLogger(TAG),
                ).also { instance = it }
            }
    }
}
