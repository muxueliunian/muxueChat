package com.muxuechat.data.remote

/**
 * xAI 登录与凭据刷新的用户可读错误。
 *
 * [message] 可直接展示给用户，保证不含任何 token、`device_code`、`user_code` 或响应正文。
 * [keepsCredential] 为 false 的只有 [AuthorizationRevoked]：只有确认授权失效才允许清除本机凭据，
 * 网络、5xx、429 与其他 4xx 一律保留凭据。
 */
sealed class XaiAuthException(
    message: String,
    val keepsCredential: Boolean = true,
) : Exception(message) {
    class Network : XaiAuthException("无法连接 xAI 认证服务，请检查网络后重试")

    class Server(code: Int) : XaiAuthException("xAI 认证服务暂时不可用（HTTP $code），请稍后重试")

    class RateLimited : XaiAuthException("xAI 认证请求过于频繁，请稍后重试")

    class Rejected(code: Int, oauthError: String?) : XaiAuthException(
        "xAI 认证请求被拒绝（HTTP $code${oauthError?.let { "：$it" }.orEmpty()}）",
    )

    class InvalidResponse : XaiAuthException("xAI 认证服务返回了无法识别的内容")

    class AuthorizationRevoked : XaiAuthException("xAI 授权已失效，请重新登录", keepsCredential = false)

    class NotSignedIn : XaiAuthException("请先登录 xAI")

    class DeviceCodeExpired : XaiAuthException("验证码已过期，请重新登录")

    class AccessDenied : XaiAuthException("已在浏览器中拒绝授权，登录已取消")

    class LoginTimedOut : XaiAuthException("登录超时，请重新发起")

    class UnsafeVerificationUri : XaiAuthException("xAI 返回的验证地址不在可信域名内，已中止登录")

    class LoginAlreadyRunning : XaiAuthException("已有一次登录正在进行")
}
