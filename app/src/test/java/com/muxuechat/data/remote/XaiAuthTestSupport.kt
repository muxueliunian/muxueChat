package com.muxuechat.data.remote

import com.muxuechat.data.local.XaiCredential
import com.muxuechat.data.local.XaiCredentialStore
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.Base64
import java.util.Collections

/** 一次被拦截的认证请求。[form] 是解开后的表单字段。 */
internal data class RecordedAuthRequest(
    val url: String,
    val contentType: String?,
    val form: Map<String, String>,
)

internal sealed interface FakeAuthReply {
    data class Http(val code: Int, val body: String) : FakeAuthReply
    data object NetworkFailure : FakeAuthReply
}

/**
 * 不联网的假 xAI 认证服务：拦截器直接按 [handler] 返回响应。
 * [handler] 运行在 OkHttp 的调度线程上，测试可以在里面阻塞以制造「请求在途」。
 */
internal class FakeXaiAuthServer(
    var handler: (RecordedAuthRequest) -> FakeAuthReply = { FakeAuthReply.Http(500, "{}") },
) {
    val requests: MutableList<RecordedAuthRequest> = Collections.synchronizedList(mutableListOf())
    val logger = RecordingAuthLogger()

    fun client(): XaiAuthClient = XaiAuthClient(okHttpClient(), logger)

    private fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val formBody = request.body as FormBody
            val recorded = RecordedAuthRequest(
                url = request.url.toString(),
                contentType = formBody.contentType().toString(),
                form = (0 until formBody.size).associate { formBody.name(it) to formBody.value(it) },
            )
            requests += recorded
            when (val reply = handler(recorded)) {
                FakeAuthReply.NetworkFailure -> throw IOException("synthetic network failure")
                is FakeAuthReply.Http -> Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(reply.code)
                    .message("synthetic")
                    .body(reply.body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }
        .build()

    fun tokenRequests(): List<RecordedAuthRequest> = requests.filter { it.url == XaiAuthClient.TOKEN_URL }

    fun revokeRequests(): List<RecordedAuthRequest> = requests.filter { it.url == XaiAuthClient.REVOKE_URL }
}

internal class RecordingAuthLogger : RemoteClientLogger {
    val messages: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun debug(message: String) {
        messages += message
    }

    override fun info(message: String) {
        messages += message
    }

    override fun warn(message: String) {
        messages += message
    }
}

internal class InMemoryXaiCredentialStore(
    var credential: XaiCredential? = null,
    var loginMarker: Boolean = false,
) : XaiCredentialStore {
    var saveCount = 0
        private set

    override suspend fun load(): XaiCredential? = credential

    override suspend fun save(credential: XaiCredential) {
        saveCount++
        this.credential = credential
    }

    override suspend fun clear() {
        credential = null
    }

    override fun isLoginInProgress(): Boolean = loginMarker

    override fun setLoginInProgress(inProgress: Boolean) {
        loginMarker = inProgress
    }
}

internal object XaiFixtures {
    const val DEVICE_CODE = "SYNTHETIC-DEVICE-CODE-7f3a"
    const val USER_CODE = "WXYZ-2468"
    const val ACCESS_TOKEN = "synthetic-access-token-1"
    const val REFRESH_TOKEN = "synthetic-refresh-token-1"
    const val NEW_ACCESS_TOKEN = "synthetic-access-token-2"
    const val NEW_REFRESH_TOKEN = "synthetic-refresh-token-2"

    val ID_TOKEN = jwt("""{"sub":"user-123","email":"reader@example.com"}""")

    val SECRETS = listOf(DEVICE_CODE, USER_CODE, ACCESS_TOKEN, REFRESH_TOKEN, NEW_ACCESS_TOKEN, NEW_REFRESH_TOKEN, ID_TOKEN)

    fun deviceCodeBody(
        verificationUri: String = "https://accounts.x.ai/device",
        verificationUriComplete: String? = null,
        expiresIn: Long = 600,
        interval: Long? = 5,
    ): String = buildString {
        append("""{"device_code":"$DEVICE_CODE","user_code":"$USER_CODE","verification_uri":"$verificationUri"""")
        verificationUriComplete?.let { append(""","verification_uri_complete":"$it"""") }
        append(""","expires_in":$expiresIn""")
        interval?.let { append(""","interval":$it""") }
        append("}")
    }

    fun tokenBody(
        accessToken: String = ACCESS_TOKEN,
        refreshToken: String? = REFRESH_TOKEN,
        idToken: String? = ID_TOKEN,
        expiresIn: Long = 3600,
    ): String = buildString {
        append("""{"access_token":"$accessToken","token_type":"Bearer","expires_in":$expiresIn""")
        refreshToken?.let { append(""","refresh_token":"$it"""") }
        idToken?.let { append(""","id_token":"$it"""") }
        append("}")
    }

    fun oauthError(error: String, description: String = "details mention $REFRESH_TOKEN") =
        """{"error":"$error","error_description":"$description"}"""

    fun jwt(payloadJson: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"ES256"}""".toByteArray())
        val payload = encoder.encodeToString(payloadJson.toByteArray())
        return "$header.$payload.c2lnbmF0dXJl"
    }
}
