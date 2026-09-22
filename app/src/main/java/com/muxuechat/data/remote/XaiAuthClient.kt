package com.muxuechat.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** `POST /oauth2/device/code` 的结果。[toString] 不输出 `device_code` 与 `user_code`。 */
data class XaiDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
) {
    override fun toString(): String =
        "XaiDeviceCode(expiresInSeconds=$expiresInSeconds, intervalSeconds=$intervalSeconds)"
}

/** token 端点成功响应。[toString] 不输出任何 token。 */
data class XaiTokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresInSeconds: Long?,
) {
    override fun toString(): String =
        "XaiTokenResponse(expiresInSeconds=$expiresInSeconds, hasRefreshToken=${refreshToken != null})"
}

/** 设备码轮询一次的结果（RFC 8628 §3.5）。终止性错误以 [XaiAuthException] 抛出。 */
sealed interface XaiDevicePollResult {
    data object Pending : XaiDevicePollResult

    /** 服务端要求放慢（`slow_down` 或 429），轮询间隔需要加 5 秒。 */
    data object SlowDown : XaiDevicePollResult

    /** 网络错误或 5xx：本次作废，按原间隔继续轮询直到总时限。 */
    data object Transient : XaiDevicePollResult

    data class Granted(val tokens: XaiTokenResponse) : XaiDevicePollResult
}

/**
 * xAI OAuth（标准 OIDC）认证端点客户端。
 *
 * 所有地址钉死在 `https://auth.x.ai`，不接受外部传入；OkHttp 关闭重定向跟随，
 * 服务端返回的任何 3xx 都按错误处理，凭据不会被带往别的主机。
 * 请求与响应正文一律不写日志，只记录端点名、HTTP 状态码与 OAuth 错误码。
 */
class XaiAuthClient internal constructor(
    private val okHttpClient: OkHttpClient,
    private val logger: RemoteClientLogger,
) {
    constructor() : this(defaultOkHttpClient(), AndroidRemoteClientLogger(TAG))

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun requestDeviceCode(): XaiDeviceCode {
        logger.info("Requesting xAI device code")
        val body = when (val outcome = postForm(DEVICE_CODE_URL, mapOf("client_id" to CLIENT_ID, "scope" to SCOPE))) {
            is FormOutcome.Success -> outcome.body
            is FormOutcome.Failure -> throw outcome.toGenericException()
        }
        val dto = decode(body, DeviceCodeDto.serializer())
        if (dto.deviceCode.isBlank() || dto.userCode.isBlank() || dto.verificationUri.isBlank() || dto.expiresIn <= 0) {
            throw XaiAuthException.InvalidResponse()
        }
        return XaiDeviceCode(
            deviceCode = dto.deviceCode,
            userCode = dto.userCode,
            verificationUri = dto.verificationUri,
            verificationUriComplete = dto.verificationUriComplete?.takeIf { it.isNotBlank() },
            expiresInSeconds = dto.expiresIn,
            intervalSeconds = dto.interval?.takeIf { it > 0 } ?: DEFAULT_POLL_INTERVAL_SECONDS,
        )
    }

    suspend fun pollDeviceToken(deviceCode: String): XaiDevicePollResult {
        val outcome = try {
            postForm(
                TOKEN_URL,
                mapOf(
                    "grant_type" to DEVICE_CODE_GRANT,
                    "device_code" to deviceCode,
                    "client_id" to CLIENT_ID,
                ),
            )
        } catch (_: XaiAuthException.Network) {
            return XaiDevicePollResult.Transient
        }
        return when (outcome) {
            is FormOutcome.Success -> XaiDevicePollResult.Granted(parseTokens(outcome.body))
            is FormOutcome.Failure -> when {
                outcome.oauthError == "authorization_pending" -> XaiDevicePollResult.Pending
                outcome.oauthError == "slow_down" || outcome.code == 429 -> XaiDevicePollResult.SlowDown
                outcome.oauthError == "expired_token" -> throw XaiAuthException.DeviceCodeExpired()
                outcome.oauthError == "access_denied" -> throw XaiAuthException.AccessDenied()
                outcome.code >= 500 -> XaiDevicePollResult.Transient
                else -> throw outcome.toGenericException()
            }
        }
    }

    suspend fun refresh(refreshToken: String): XaiTokenResponse {
        logger.info("Refreshing xAI access token")
        return when (
            val outcome = postForm(
                TOKEN_URL,
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id" to CLIENT_ID,
                ),
            )
        ) {
            is FormOutcome.Success -> parseTokens(outcome.body)
            is FormOutcome.Failure -> if (outcome.oauthError == "invalid_grant" && outcome.code in 400..499) {
                throw XaiAuthException.AuthorizationRevoked()
            } else {
                throw outcome.toGenericException()
            }
        }
    }

    /**
     * 吊销一个 token（RFC 7009）。`token_type_hint` 取 `refresh_token` 或 `access_token`。
     *
     * RFC 7009 §2.2 规定：吊销成功、以及客户端提交了无效 token，服务端都返回 200。
     * 因此这里只把网络失败与非 2xx 当作失败抛出，调用方可按「尽力而为」处理。
     */
    suspend fun revoke(token: String, tokenTypeHint: String) {
        logger.info("Revoking xAI $tokenTypeHint")
        val outcome = postForm(
            REVOKE_URL,
            mapOf(
                "token" to token,
                "token_type_hint" to tokenTypeHint,
                "client_id" to CLIENT_ID,
            ),
        )
        if (outcome is FormOutcome.Failure) throw outcome.toGenericException()
    }

    private fun parseTokens(body: String): XaiTokenResponse {
        val dto = decode(body, TokenDto.serializer())
        if (dto.accessToken.isBlank()) throw XaiAuthException.InvalidResponse()
        return XaiTokenResponse(
            accessToken = dto.accessToken,
            refreshToken = dto.refreshToken?.takeIf { it.isNotBlank() },
            idToken = dto.idToken?.takeIf { it.isNotBlank() },
            expiresInSeconds = dto.expiresIn?.takeIf { it > 0 },
        )
    }

    private fun <T> decode(body: String, deserializer: kotlinx.serialization.DeserializationStrategy<T>): T =
        try {
            json.decodeFromString(deserializer, body)
        } catch (_: Exception) {
            // 解析异常的 message 可能引用响应片段（其中就有 token），因此不保留 cause。
            logger.warn("xAI auth response could not be parsed")
            throw XaiAuthException.InvalidResponse()
        }

    private suspend fun postForm(url: String, fields: Map<String, String>): FormOutcome {
        val form = FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(form)
            .build()
        val endpoint = request.url.encodedPath

        val response = try {
            okHttpClient.newCall(request).await()
        } catch (error: IOException) {
            logger.warn("xAI auth request to $endpoint failed: ${error.javaClass.simpleName}")
            throw XaiAuthException.Network()
        }

        return response.use {
            val text = try {
                it.readBodyWithinLimit()
            } catch (error: IOException) {
                logger.warn("xAI auth response from $endpoint could not be read: ${error.javaClass.simpleName}")
                throw XaiAuthException.Network()
            }
            if (it.isSuccessful) {
                FormOutcome.Success(text)
            } else {
                val oauthError = parseOAuthError(text)
                // 轮询中的 authorization_pending 每隔几秒就出现一次，不写日志。
                if (oauthError != "authorization_pending") {
                    logger.warn("xAI auth $endpoint HTTP ${it.code}${oauthError?.let { code -> " ($code)" }.orEmpty()}")
                }
                FormOutcome.Failure(it.code, oauthError)
            }
        }
    }

    /** 只取 `error` 字段，且只接受形如 `invalid_grant` 的短标识；`error_description` 一律忽略。 */
    private fun parseOAuthError(body: String): String? {
        val error = runCatching {
            (json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonPrimitive
        }.getOrNull()?.contentOrNull ?: return null
        return error.takeIf { OAUTH_ERROR_PATTERN.matches(it) }
    }

    private fun Response.readBodyWithinLimit(): String {
        val source = body.source()
        if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("xAI auth response too large")
        return source.buffer.readUtf8()
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    private sealed interface FormOutcome {
        data class Success(val body: String) : FormOutcome {
            override fun toString(): String = "Success"
        }

        data class Failure(val code: Int, val oauthError: String?) : FormOutcome
    }

    private fun FormOutcome.Failure.toGenericException(): XaiAuthException = when {
        code == 429 -> XaiAuthException.RateLimited()
        code >= 500 -> XaiAuthException.Server(code)
        else -> XaiAuthException.Rejected(code, oauthError)
    }

    @Serializable
    private data class DeviceCodeDto(
        @SerialName("device_code") val deviceCode: String = "",
        @SerialName("user_code") val userCode: String = "",
        @SerialName("verification_uri") val verificationUri: String = "",
        @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
        @SerialName("expires_in") val expiresIn: Long = 0,
        val interval: Long? = null,
    )

    @Serializable
    private data class TokenDto(
        @SerialName("access_token") val accessToken: String = "",
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("id_token") val idToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
    )

    companion object {
        private const val TAG = "muxueChat.XaiAuth"

        const val AUTH_ORIGIN = "https://auth.x.ai"
        internal const val DEVICE_CODE_URL = "$AUTH_ORIGIN/oauth2/device/code"
        internal const val TOKEN_URL = "$AUTH_ORIGIN/oauth2/token"
        internal const val REVOKE_URL = "$AUTH_ORIGIN/oauth2/revoke"

        /** Grok CLI 的公开 client（public client，无 secret）。复用它的风险已由用户确认承担。 */
        internal const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        internal const val SCOPE = "openid profile email offline_access grok-cli:access " +
            "api:access conversations:read conversations:write"
        internal const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

        /** RFC 8628 §3.2：服务端未给 interval 时客户端默认 5 秒。 */
        internal const val DEFAULT_POLL_INTERVAL_SECONDS = 5L
        private const val MAX_RESPONSE_BYTES = 64L * 1024
        private val OAUTH_ERROR_PATTERN = Regex("[a-z_]{1,40}")

        internal fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/**
 * 校验设备码响应里的验证地址，返回可以交给浏览器打开的那一个。
 *
 * 只接受 `https://auth.x.ai` 与 `https://accounts.x.ai` 两个 origin；地址里出现 `device_code`
 * （无论是参数名还是本次的实际值）都拒绝——它只该在 App 与 token 端点之间往返，不能进浏览器。
 * 优先使用 `verification_uri_complete`（已带上 user code，省去手输），不合格时退回 `verification_uri`。
 */
internal object XaiVerificationUriPolicy {
    private val TRUSTED_HOSTS = setOf("auth.x.ai", "accounts.x.ai")

    fun select(deviceCode: XaiDeviceCode): String =
        listOfNotNull(deviceCode.verificationUriComplete, deviceCode.verificationUri)
            .firstOrNull { isTrusted(it, deviceCode.deviceCode) }
            ?: throw XaiAuthException.UnsafeVerificationUri()

    fun isTrusted(uri: String, deviceCode: String): Boolean {
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return false
        return parsed.scheme == "https" &&
            parsed.host?.lowercase() in TRUSTED_HOSTS &&
            parsed.port == -1 &&
            parsed.rawUserInfo == null &&
            !uri.contains("device_code", ignoreCase = true) &&
            !uri.contains(deviceCode)
    }
}

/**
 * 从 id_token 取一个可展示的账号名（email，缺失时退回 sub）。
 *
 * id_token 在这里只用于展示，**不参与任何安全决策**，因此不验签、不引入 JWT/JWKS 依赖。
 */
internal object XaiIdTokenReader {
    private const val MAX_ACCOUNT_LENGTH = 200
    private val json = Json { ignoreUnknownKeys = true }

    fun account(idToken: String?): String? {
        val payload = idToken?.split('.')?.getOrNull(1) ?: return null
        val claims = runCatching {
            val decoded = Base64.getUrlDecoder().decode(payload.trimEnd('='))
            json.parseToJsonElement(decoded.toString(Charsets.UTF_8)) as? JsonObject
        }.getOrNull() ?: return null

        fun claim(name: String) = (claims[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return (claim("email") ?: claim("sub"))?.take(MAX_ACCOUNT_LENGTH)
    }
}
