package com.muxuechat.data.repository

import com.muxuechat.data.remote.HttpStatusError
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** 含图请求的 400 / 413 提示（D8）：只看「本轮是否带图 + HTTP 状态码」，不看文案或异常类型。 */
class ImageRequestErrorHintTest {
    private fun httpError(status: Int): Throwable = object : IOException("供应商请求失败（HTTP $status）"), HttpStatusError {
        override val httpStatus: Int = status
    }

    @Test
    fun badRequestWithImages_appendsHedgedImageHint() {
        assertEquals(
            "请求被拒绝\n${ImageRequestErrorHint.IMAGE_REJECTED}",
            ImageRequestErrorHint.append("请求被拒绝", httpError(400), requestHadImages = true),
        )
    }

    @Test
    fun payloadTooLargeWithImages_appendsPayloadHint_evenWhenWrapped() {
        val wrapped = IllegalStateException("外层", httpError(413))
        assertEquals(
            "失败\n${ImageRequestErrorHint.PAYLOAD_TOO_LARGE}",
            ImageRequestErrorHint.append("失败", wrapped, requestHadImages = true),
        )
    }

    @Test
    fun withoutImages_orOtherStatuses_messageIsUnchanged() {
        assertEquals("请求被拒绝", ImageRequestErrorHint.append("请求被拒绝", httpError(400), requestHadImages = false))
        assertEquals("限流", ImageRequestErrorHint.append("限流", httpError(429), requestHadImages = true))
        assertEquals("未授权", ImageRequestErrorHint.append("未授权", httpError(401), requestHadImages = true))
        // 文案里出现「400」也不算：只认结构化状态码。
        assertEquals("HTTP 400", ImageRequestErrorHint.append("HTTP 400", IOException("HTTP 400"), requestHadImages = true))
    }
}
