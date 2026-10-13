package com.muxuechat.data.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

/** 仅允许安全、固定或已清理的提示进入上层；不携带原始响应或解析异常。 */
internal class OpenAiCompatibleProtocolException(message: String) :
    IOException(message), OpenAiCompatibleUserFacingError

/** HTTP 与 SSE 中通用 error envelope 共用同一清理边界。 */
internal object OpenAiCompatibleErrorDetails {
    data class Details(val code: String?, val message: String?)

    fun read(source: JsonObject, apiKey: String): Details? {
        val key = apiKey.trim()
        val code = (source["code"] as? JsonPrimitive)?.contentOrNull
            ?.trim()?.takeIf { SAFE_ERROR_CODE.matches(it) }
            ?.takeUnless { key.isNotEmpty() && it.contains(key) }
        val message = (source["message"] as? JsonPrimitive)?.contentOrNull?.let { sanitize(it, apiKey) }
        return Details(code, message).takeIf { it.code != null || it.message != null }
    }

    fun responseError(source: JsonObject?, apiKey: String, streaming: Boolean): OpenAiCompatibleProtocolException {
        val details = source?.let { read(it, apiKey) }
        return OpenAiCompatibleProtocolException(buildString {
            append(if (streaming) "供应商流式请求失败" else "供应商请求失败")
            details?.code?.let { append("（错误码 $it）") }
            details?.message?.let { append("：$it") }
        })
    }

    private fun sanitize(message: String, apiKey: String): String? {
        var sanitized = message
        apiKey.trim().takeIf { it.isNotEmpty() }?.let { sanitized = sanitized.replace(it, REDACTED) }
        sanitized = sanitized.replace(BEARER_CREDENTIAL, "Bearer $REDACTED")
            .replace(CONTROL_CHARACTERS, " ")
            .replace(REPEATED_WHITESPACE, " ").trim()
        if (sanitized.isEmpty()) return null
        return if (sanitized.length <= OpenAiCompatibleClient.MAX_ERROR_MESSAGE_CHARS) sanitized
        else sanitized.take(OpenAiCompatibleClient.MAX_ERROR_MESSAGE_CHARS) + "…"
    }

    private const val REDACTED = "<已隐藏>"
    private val SAFE_ERROR_CODE = Regex("[A-Za-z0-9._-]{1,64}")
    private val BEARER_CREDENTIAL = Regex("(?i)Bearer\\s+[^\\s,;]+")
    private val CONTROL_CHARACTERS = Regex("[\\p{Cc}\\p{Cf}]")
    private val REPEATED_WHITESPACE = Regex("\\s+")
}
