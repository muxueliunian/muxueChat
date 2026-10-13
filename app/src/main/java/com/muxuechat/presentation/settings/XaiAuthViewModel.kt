package com.muxuechat.presentation.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.muxuechat.data.remote.XaiAuthException
import com.muxuechat.data.repository.XaiAuthRepository
import com.muxuechat.domain.model.XaiAuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class XaiAuthUiState(
    val auth: XaiAuthState = XaiAuthState.Loading,
    /** 最近一次操作的结果或错误，已保证不含任何凭据。 */
    val message: String? = null,
)

/**
 * 设置页「xAI 订阅登录」分组的 ViewModel。
 *
 * 与 [com.muxuechat.presentation.chat.ChatViewModel] 分开：本模块此刻有意不接入聊天流程，
 * 独立的 ViewModel 让它可以单独验收，也不继续加长 ChatViewModel。
 * 挂在 Activity 上，离开设置页时登录轮询不会被打断。
 */
class XaiAuthViewModel(
    private val repository: XaiAuthRepository,
) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)
    private var loginJob: Job? = null

    val uiState: StateFlow<XaiAuthUiState> = combine(repository.state, message) { auth, text ->
        XaiAuthUiState(auth = auth, message = text)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), XaiAuthUiState())

    init {
        viewModelScope.launch {
            try {
                repository.initialize()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message.value = "读取 xAI 登录状态失败，请重新登录重试"
            }
        }
    }

    fun startLogin() {
        if (loginJob?.isCompleted == false) return
        message.value = null
        loginJob = viewModelScope.launch {
            try {
                repository.signInWithDeviceCode()
                message.value = "已登录 xAI"
            } catch (error: CancellationException) {
                throw error
            } catch (error: XaiAuthException) {
                message.value = error.message
            } catch (error: Exception) {
                message.value = "xAI 登录失败，请重试"
            }
        }
    }

    fun cancelLogin() {
        val cancelledJob = loginJob ?: return
        cancelledJob.cancel()
        viewModelScope.launch {
            cancelledJob.join()
            if (loginJob === cancelledJob) {
                loginJob = null
                message.value = "已取消登录"
            }
        }
    }

    fun signOut() {
        loginJob?.cancel()
        viewModelScope.launch {
            try {
                repository.signOut()
                message.value = "已退出 xAI 登录"
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message.value = "本机登录已停用，凭据清理失败，请重试退出登录"
            }
        }
    }

    fun reportBrowserUnavailable() {
        message.value = "没有找到可以打开验证页面的浏览器"
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[APPLICATION_KEY]) as Application
                return XaiAuthViewModel(XaiAuthRepository.getInstance(application)) as T
            }
        }
    }
}
