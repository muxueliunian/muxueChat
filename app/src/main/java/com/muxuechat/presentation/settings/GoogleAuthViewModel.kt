package com.muxuechat.presentation.settings

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.google.android.gms.common.api.ApiException
import com.muxuechat.data.repository.GoogleAuthRepository
import com.muxuechat.data.repository.GoogleAuthorization
import com.muxuechat.domain.model.GoogleAuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GoogleAuthUiState(
    val auth: GoogleAuthState = GoogleAuthState.SignedOut(),
    val busy: Boolean = false,
    /** 最近一次操作的结果或错误，已保证不含任何凭据。 */
    val message: String? = null,
    /** 待 UI 拉起的 Google 同意页；UI 拉起后立刻调用 [GoogleAuthViewModel.consentLaunched] 清掉。 */
    val pendingConsent: IntentSender? = null,
)

/**
 * 设置页「Google Vertex 登录」分组的 ViewModel。挂在 Activity 上，与聊天链路共用
 * [GoogleAuthRepository] 单例。同意页的拉起必须在前台由用户点击触发，这里不做任何后台登录。
 */
class GoogleAuthViewModel(
    private val repository: GoogleAuthRepository,
) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    private val pendingConsent = MutableStateFlow<IntentSender?>(null)

    val uiState: StateFlow<GoogleAuthUiState> = combine(
        repository.state, busy, message, pendingConsent,
    ) { auth, isBusy, text, consent ->
        GoogleAuthUiState(auth = auth, busy = isBusy, message = text, pendingConsent = consent)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoogleAuthUiState())

    fun startLogin() {
        if (busy.value) return
        message.value = null
        busy.value = true
        viewModelScope.launch {
            try {
                when (val result = repository.beginSignIn()) {
                    is GoogleAuthorization.Granted -> message.value = "已登录 Google"
                    is GoogleAuthorization.NeedsConsent -> {
                        val sender = result.pendingIntent?.intentSender
                        if (sender == null) {
                            message.value = "无法打开 Google 授权页，请重试"
                        } else {
                            pendingConsent.value = sender
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message.value = describeFailure(error)
            } finally {
                busy.value = false
            }
        }
    }

    fun consentLaunched() {
        pendingConsent.value = null
    }

    fun onConsentResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) {
            message.value = "已取消 Google 登录"
            return
        }
        busy.value = true
        viewModelScope.launch {
            try {
                repository.completeSignIn(data)
                message.value = "已登录 Google"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message.value = describeFailure(error)
            } finally {
                busy.value = false
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            try {
                repository.signOut()
                message.value = "已退出 Google 登录"
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message.value = "本机已退出 Google 登录，但撤销授权失败，可到 Google 账号的「第三方访问权限」页手动移除"
            }
        }
    }

    /** 只输出状态码与固定文案，绝不拼接 Play 服务异常的原文（可能含账号信息）。 */
    private fun describeFailure(error: Exception): String = when {
        error is ApiException && error.statusCode == DEVELOPER_ERROR ->
            "Google 登录失败（代码 10）：OAuth 客户端配置不匹配，请检查 GCP 里 Android 客户端的包名 com.muxuechat 与签名 SHA-1"
        error is ApiException ->
            "Google 登录失败（代码 ${error.statusCode}）：请确认手机已安装 Google Play 服务并登录了 Google 账号"
        else -> "Google 登录失败（${error.javaClass.simpleName}），请重试"
    }

    companion object {
        private const val DEVELOPER_ERROR = 10

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[APPLICATION_KEY]) as Application
                return GoogleAuthViewModel(GoogleAuthRepository.getInstance(application)) as T
            }
        }
    }
}
