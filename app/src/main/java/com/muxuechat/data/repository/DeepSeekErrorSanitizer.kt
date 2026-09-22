package com.muxuechat.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 把 DeepSeek 链路的底层异常收敛成固定文案。
 *
 * 这里刻意不把未知异常的 [Throwable.message] 交给 UI 或日志。解析器异常可能包含响应片段，
 * URL/网络异常也可能携带用户填写的完整地址；两者都不适合成为持久化的消息错误文本。
 */
internal fun Throwable.toSafeDeepSeekUserFacingMessage(): String {
    val causes = causeChain()
    val httpStatus = causes.firstNotNullOfOrNull(::safeDeepSeekHttpStatus)

    return when {
        httpStatus == 401 -> "API Key 无效或未授权，请检查 DeepSeek API Key。"
        httpStatus == 402 -> "DeepSeek 账户余额不足或账单不可用。"
        httpStatus == 403 -> "DeepSeek 拒绝访问，请检查账户权限和 API Key。"
        httpStatus == 404 -> "DeepSeek 接口或模型不存在，请检查 Base URL 和模型设置。"
        httpStatus == 408 -> "网络请求超时，请检查网络或稍后重试。"
        httpStatus == 429 -> "请求过于频繁或触发限流，请稍后再试。"
        httpStatus != null && httpStatus in 500..599 -> "DeepSeek 服务暂时异常，请稍后再试。"
        httpStatus != null && httpStatus in 400..499 -> "DeepSeek 请求被拒绝，请检查模型和参数配置。"
        causes.any { it is CancellationException } -> "生成已取消。"
        causes.any { it is UnknownHostException } -> "网络不可用或无法解析 DeepSeek 服务地址。"
        causes.any { it is SocketTimeoutException } -> "网络请求超时，请检查网络或稍后重试。"
        causes.any { it is SSLException } -> "DeepSeek 的 HTTPS 证书校验失败，请检查服务证书和地址。"
        causes.any { it is ConnectException } -> "无法连接 DeepSeek 服务，请检查网络和 Base URL。"
        causes.hasSafeMessage(DEEPSEEK_EARLY_STREAM_MESSAGE) ->
            "流式响应提前结束，没有收到完整回复。"
        causes.hasSafeMessage(DEEPSEEK_EMPTY_BODY_MESSAGE) ->
            "DeepSeek 返回了空响应，请稍后重试。"
        causes.hasSafeMessagePrefix(DEEPSEEK_INVALID_URL_PREFIX) ->
            "DeepSeek 地址无效，请在设置中填写 http:// 或 https:// 开头的 Base URL。"
        causes.hasSafeMessagePrefix(PROVIDER_INVALID_URL_PREFIX) ->
            "DeepSeek 地址无效，请在设置中填写 http:// 或 https:// 开头的 Base URL。"
        causes.hasSafeMessage(DEEPSEEK_MISSING_KEY_MESSAGE) -> DEEPSEEK_MISSING_KEY_MESSAGE
        causes.hasSafeMessage(DEEPSEEK_MISSING_MODEL_MESSAGE) -> DEEPSEEK_MISSING_MODEL_MESSAGE
        causes.hasSafeMessage(DEEPSEEK_THINKING_OPTIONS_MESSAGE) ->
            "思考模式关闭时不能发送 reasoning_effort，请更新应用后重试。"
        causes.any { it is SerializationException } ->
            "DeepSeek 响应格式异常，请稍后重试。"
        causes.any { it is IOException } ->
            "DeepSeek 网络请求失败，请检查网络或稍后重试。"
        else -> "生成失败，请检查 API Key、网络和模型配置。"
    }
}

/** 仅返回异常类型和由客户端固定格式产生的 HTTP 状态，不包含异常 message 或堆栈。 */
internal fun Throwable.toSafeLogDescriptor(): String = buildString {
    append("type=")
    append(this@toSafeLogDescriptor.javaClass.simpleName.ifBlank { "Throwable" })
    this@toSafeLogDescriptor.causeChain()
        .firstNotNullOfOrNull(::safeDeepSeekHttpStatus)
        ?.let { status ->
            append(" httpStatus=")
            append(status)
        }
}

private fun Throwable.causeChain(): List<Throwable> {
    val causes = mutableListOf<Throwable>()
    var current: Throwable? = this
    while (current != null && causes.size < MAX_CAUSE_DEPTH && current !in causes) {
        causes += current
        current = current.cause
    }
    return causes
}

private fun safeDeepSeekHttpStatus(error: Throwable): Int? {
    if (error !is IOException) return null
    val message = error.message?.trim() ?: return null
    return SAFE_DEEPSEEK_HTTP_ERROR.matchEntire(message)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}

private fun List<Throwable>.hasSafeMessage(expected: String): Boolean =
    any { error -> error.message?.trim().equals(expected, ignoreCase = true) }

private fun List<Throwable>.hasSafeMessagePrefix(expected: String): Boolean =
    any { error -> error.message?.trim()?.startsWith(expected, ignoreCase = true) == true }

private const val MAX_CAUSE_DEPTH = 8
private const val DEEPSEEK_EARLY_STREAM_MESSAGE = "DeepSeek stream ended before completion"
private const val DEEPSEEK_EMPTY_BODY_MESSAGE = "DeepSeek API returned empty body"
private const val DEEPSEEK_INVALID_URL_PREFIX = "DeepSeek 地址无效"
private const val PROVIDER_INVALID_URL_PREFIX = "Provider Base URL"
private const val DEEPSEEK_MISSING_KEY_MESSAGE = "请先在设置中保存 DeepSeek API Key"
private const val DEEPSEEK_MISSING_MODEL_MESSAGE = "请先配置要使用的模型"
private const val DEEPSEEK_THINKING_OPTIONS_MESSAGE = "thinking options type cannot be disabled"
private val SAFE_DEEPSEEK_HTTP_ERROR = Regex("(?:DeepSeek API returned )?HTTP (\\d{3})")
