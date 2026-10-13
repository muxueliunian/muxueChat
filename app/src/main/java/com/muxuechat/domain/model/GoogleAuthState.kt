package com.muxuechat.domain.model

/**
 * Google Vertex 登录的对外状态。
 *
 * 只描述「是否已登录」，绝不携带 access token（App 不保存任何 Google token，见 AGENT.md §3.2）。
 */
sealed interface GoogleAuthState {
    /** 未登录。[notice] 说明为什么回到未登录（授权失效、被撤销等），没有特别原因时为 null。 */
    data class SignedOut(val notice: String? = null) : GoogleAuthState

    data object SignedIn : GoogleAuthState
}
