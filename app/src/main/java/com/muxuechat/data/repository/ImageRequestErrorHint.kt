package com.muxuechat.data.repository

import com.muxuechat.data.remote.httpStatusOrNull

/**
 * 本轮请求带了图片、上游又以 400 / 413 拒绝时追加的一句提示（D8、MULTIMODAL_PLAN 3.4）。
 *
 * 判断只依据「请求是否含图片」与通用的 HTTP 状态码（[httpStatusOrNull]），不匹配错误文案、
 * 不识别任何厂商或模型的异常类型，三条传输一致；400 无法断言就是图片导致，所以只说「如果」。
 */
internal object ImageRequestErrorHint {
    const val IMAGE_REJECTED =
        "如果问题与图片有关，可能是当前模型或服务不接受图片输入，可切换到支持图片的模型或新开会话。"
    const val PAYLOAD_TOO_LARGE =
        "请求体过大：历史图片每轮都会重发，请减少本条图片或新开会话。"

    fun append(message: String, error: Throwable, requestHadImages: Boolean): String {
        if (!requestHadImages) return message
        val hint = when (error.httpStatusOrNull()) {
            400 -> IMAGE_REJECTED
            413 -> PAYLOAD_TOO_LARGE
            else -> return message
        }
        return if (message.isBlank()) hint else "${message.trimEnd()}\n$hint"
    }
}
