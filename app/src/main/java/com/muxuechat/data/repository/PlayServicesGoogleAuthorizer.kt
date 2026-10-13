package com.muxuechat.data.repository

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 生产实现：Google Identity Services `AuthorizationClient`。
 *
 * 只申请 `cloud-platform`，不请求离线访问，因此没有 refresh token / serverAuthCode。
 * Android 类型 OAuth 客户端由「包名 + 签名 SHA-1」识别，代码里没有 client ID。
 */
internal class PlayServicesGoogleAuthorizer(context: Context) : GoogleAuthorizer {
    private val client = Identity.getAuthorizationClient(context.applicationContext)

    private val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(CLOUD_PLATFORM_SCOPE)))
        .build()

    override suspend fun authorize(): GoogleAuthorization = client.authorize(request).await().toDomain()

    override suspend fun authorizationFromIntent(data: Intent?): GoogleAuthorization =
        client.getAuthorizationResultFromIntent(data).toDomain()

    override suspend fun clearToken(accessToken: String) {
        client.clearToken(ClearTokenRequest.builder().setToken(accessToken).build()).await()
    }

    override suspend fun revokeAccess() {
        val result = client.authorize(request).await()
        // 撤销需要知道是哪个账号；还没授权（需要同意）说明没有可撤销的东西。
        val account = result.toGoogleSignInAccount()?.account ?: return
        client.revokeAccess(
            RevokeAccessRequest.builder()
                .setAccount(account)
                .setScopes(listOf(Scope(CLOUD_PLATFORM_SCOPE)))
                .build(),
        ).await()
    }

    private fun AuthorizationResult.toDomain(): GoogleAuthorization {
        if (hasResolution()) return GoogleAuthorization.NeedsConsent(pendingIntent)
        val token = accessToken
        check(!token.isNullOrBlank()) { "Google 授权结果没有 access token" }
        return GoogleAuthorization.Granted(token)
    }

    private companion object {
        const val CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform"
    }
}

/** 不引入 kotlinx-coroutines-play-services，自己桥接 Task。 */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> continuation.resume(value) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
