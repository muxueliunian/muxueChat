package com.muxuechat.data.repository

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.muxuechat.domain.model.GoogleAuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一次授权请求的结果。access token 只在内存里短暂存在，[toString] 不展开它。
 */
sealed interface GoogleAuthorization {
    class Granted(val accessToken: String) : GoogleAuthorization {
        override fun toString(): String = "GoogleAuthorization.Granted"
    }

    /**
     * 需要用户在前台确认。生产实现的 [pendingIntent] 恒非 null；允许为 null 仅为让 JVM 测试
     * 不必构造 Android 的 PendingIntent。
     */
    class NeedsConsent(val pendingIntent: PendingIntent?) : GoogleAuthorization {
        override fun toString(): String = "GoogleAuthorization.NeedsConsent"
    }
}

/** Play 服务 `AuthorizationClient` 的最小封装，藏在接口后面以便 JVM 测试用假实现替换。 */
interface GoogleAuthorizer {
    /** 静默授权：已授权过则直接返回 [GoogleAuthorization.Granted]，否则 [GoogleAuthorization.NeedsConsent]。 */
    suspend fun authorize(): GoogleAuthorization

    /** 解析前台同意页返回的结果。 */
    suspend fun authorizationFromIntent(data: Intent?): GoogleAuthorization

    /** 丢弃 Play 服务缓存的这枚 token，使下一次 [authorize] 取到新的。 */
    suspend fun clearToken(accessToken: String)

    /** 撤销本应用对当前账号的授权。 */
    suspend fun revokeAccess()
}

/** 「已登录」标记。它不是凭据：没有它就不会向 Play 服务索取 token。 */
interface GoogleSignInStateStore {
    fun isSignedIn(): Boolean

    fun setSignedIn(value: Boolean)
}

private class PreferencesGoogleSignInStateStore(context: Context) : GoogleSignInStateStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isSignedIn(): Boolean = prefs.getBoolean(KEY_SIGNED_IN, false)

    override fun setSignedIn(value: Boolean) {
        prefs.edit().putBoolean(KEY_SIGNED_IN, value).apply()
    }

    private companion object {
        const val PREFS_NAME = "muxuechat_google_auth_state"
        const val KEY_SIGNED_IN = "signed_in"
    }
}

/**
 * Google Vertex 登录（AGENT.md §2「Google Vertex 登录」例外）。
 *
 * **不保存任何 token**：每次冻结请求时静默向 Play 服务索取；需要重新同意时抛
 * [GoogleAuthRequiredException]，绝不在后台弹窗、不引入后台任务或定时器。
 * 内存里只留最近一枚 token，仅用于 401 后让 Play 服务丢弃缓存的那一枚。
 */
class GoogleAuthRepository internal constructor(
    private val authorizer: GoogleAuthorizer,
    private val stateStore: GoogleSignInStateStore,
) : GoogleAccessTokenSource {
    private val mutableState = MutableStateFlow<GoogleAuthState>(
        if (stateStore.isSignedIn()) GoogleAuthState.SignedIn else GoogleAuthState.SignedOut(),
    )
    val state: StateFlow<GoogleAuthState> = mutableState.asStateFlow()

    @Volatile
    private var lastAccessToken: String? = null

    override suspend fun acquireAccessToken(forceRefresh: Boolean): String {
        if (!stateStore.isSignedIn()) throw GoogleAuthRequiredException.notSignedIn()
        if (forceRefresh) {
            lastAccessToken?.let { rejected -> bestEffort { authorizer.clearToken(rejected) } }
        }
        val result = try {
            authorizer.authorize()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 只给类型名：Play 服务异常文案不保证不含账号信息。
            throw GoogleAuthRequiredException(
                "无法从 Google Play 服务获取授权（${error.javaClass.simpleName}），请检查 Play 服务与网络",
            )
        }
        return when (result) {
            is GoogleAuthorization.Granted -> {
                // 期间用户退出登录：不得把刚取到的 token 交出去。
                if (!stateStore.isSignedIn()) throw GoogleAuthRequiredException.notSignedIn()
                lastAccessToken = result.accessToken
                result.accessToken
            }

            is GoogleAuthorization.NeedsConsent -> {
                markSignedOut("Google 授权已失效，请重新登录")
                throw GoogleAuthRequiredException.reauthorize()
            }
        }
    }

    /** 点「登录」：已授权过则直接成功，否则返回需要前台确认的结果，由 UI 拉起同意页。 */
    suspend fun beginSignIn(): GoogleAuthorization {
        val result = authorizer.authorize()
        if (result is GoogleAuthorization.Granted) markSignedIn(result)
        return result
    }

    /** 同意页返回后调用；用户取消或失败会抛异常，状态保持未登录。 */
    suspend fun completeSignIn(data: Intent?) {
        val result = authorizer.authorizationFromIntent(data)
        check(result is GoogleAuthorization.Granted) { "Google 授权未完成" }
        markSignedIn(result)
    }

    /** 先停用本机登录态，再尽力撤销授权；撤销失败不阻止本机退出。 */
    suspend fun signOut() {
        lastAccessToken = null
        stateStore.setSignedIn(false)
        mutableState.value = GoogleAuthState.SignedOut()
        bestEffort { authorizer.revokeAccess() }
    }

    private fun markSignedIn(granted: GoogleAuthorization.Granted) {
        lastAccessToken = granted.accessToken
        stateStore.setSignedIn(true)
        mutableState.value = GoogleAuthState.SignedIn
    }

    private fun markSignedOut(notice: String) {
        lastAccessToken = null
        stateStore.setSignedIn(false)
        mutableState.value = GoogleAuthState.SignedOut(notice)
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // 尽力而为：失败只意味着缓存/授权没被清掉，下一次 authorize 仍会得到正确结果。
        }
    }

    companion object {
        @Volatile
        private var instance: GoogleAuthRepository? = null

        /** 登录状态是进程级单例：设置页与聊天链路必须看到同一个。 */
        fun getInstance(context: Context): GoogleAuthRepository =
            instance ?: synchronized(this) {
                instance ?: GoogleAuthRepository(
                    PlayServicesGoogleAuthorizer(context.applicationContext),
                    PreferencesGoogleSignInStateStore(context.applicationContext),
                ).also { instance = it }
            }
    }
}
