package com.muxuechat.domain.model

/**
 * xAI 订阅登录的对外状态。
 *
 * 只描述「是否已登录、登录进行到哪一步」，绝不携带 access / refresh token 或 `device_code`。
 * [AwaitingUser.userCode] 是用户需要在浏览器里输入的验证码，只用于界面展示，不得写入日志。
 */
sealed interface XaiAuthState {
    /** 正在从本机加密存储读取登录状态。 */
    data object Loading : XaiAuthState

    /**
     * 未登录。
     *
     * [notice] 说明为什么回到未登录（上次登录被中断、授权失效、本机密钥失效等），没有特别原因时为 null。
     */
    data class SignedOut(val notice: String? = null) : XaiAuthState

    /** 正在向 xAI 申请验证码，尚未拿到可展示的 user code。 */
    data object RequestingCode : XaiAuthState

    /** 设备码已签发，等待用户在浏览器里完成授权。 */
    data class AwaitingUser(
        val userCode: String,
        val verificationUri: String,
        val expiresAtMillis: Long,
    ) : XaiAuthState

    /** 已登录。[account] 取自 id_token 的 email（缺失时退回 sub），仅供展示。 */
    data class SignedIn(val account: String?) : XaiAuthState
}
