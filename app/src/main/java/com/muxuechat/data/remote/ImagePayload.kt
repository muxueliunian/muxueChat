package com.muxuechat.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import java.io.File
import java.io.OutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 随请求发送的一张图片（MULTIMODAL_PLAN 3.4）。
 *
 * 序列化时图片位置只写 [placeholder]，请求 JSON 因此只有文字、体积很小；真实字节由
 * [ImagePayloadRequestBody] 写请求体时边读 [file] 边 base64 编码写出，内存里不会同时存在
 * 原始字节、base64 字符串与整份 JSON 几份副本。
 *
 * 占位符每张图随机生成，用户正文不可能事先包含它；写出前仍会断言每个占位符恰好出现一次。
 * 文件与占位符都不得进入日志。
 */
class ImagePayload(
    val file: File,
    val mimeType: String,
) {
    val placeholder: String = "muxue-image-${UUID.randomUUID()}"
}

/** 带 HTTP 状态码的请求错误。上层只按状态码追加通用提示，不认识任何厂商异常类型（AGENT.md §2）。 */
internal interface HttpStatusError {
    val httpStatus: Int?
}

/** 沿 cause 链找 HTTP 状态码；协程恢复堆栈时会把原异常挂在 cause 上，因此必须沿链查找。 */
internal fun Throwable.httpStatusOrNull(): Int? {
    var current: Throwable? = this
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        (current as? HttpStatusError)?.httpStatus?.let { return it }
        current = current.cause
    }
    return null
}

/**
 * 两条协议共用的 content 形状：没有图片时保持 JSON 字符串（只支持纯文本的旧反代不受影响，
 * 请求字节与引入图片前完全一致）；有图片时改为标准 content part 数组。
 * 纯图片消息（D6）不生成空的 text 块。
 */
internal object MessageContentJson {
    fun encode(text: String?, images: List<ImagePayload>): JsonElement? {
        if (images.isEmpty()) return text?.let(::JsonPrimitive)
        return buildJsonArray {
            text?.takeIf { it.isNotBlank() }?.let { value ->
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", value)
                    },
                )
            }
            images.forEach { image ->
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", image.placeholder) }
                    },
                )
            }
        }
    }

    /** 只用于测试与诊断时把请求读回来：字符串原样，数组只取 text 块。图片无法还原，读回后为空。 */
    fun decodeText(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonPrimitive -> element.contentOrNull
        is JsonArray -> element.mapNotNull { part ->
            (part as? JsonObject)
                ?.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
                ?.let { (it["text"] as? JsonPrimitive)?.contentOrNull }
        }.joinToString("\n")
        else -> null
    }
}

/**
 * 含图片请求的请求体：JSON 文字段原样写出，占位符处写 `data:<mime>;base64,` 再流式编码文件。
 *
 * base64 字符集不含需要 JSON 转义的字符，因此直接写进字符串值内部仍是合法 JSON。
 * 长度按文件大小精确算出，不走分块传输（个别反代不接受 chunked 请求）。
 * 每次 [writeTo] 都重新打开文件：OkHttp 重试与 401 后的重发都会再写一遍请求体。
 */
internal class ImagePayloadRequestBody private constructor(
    private val segments: List<Segment>,
    private val mediaType: MediaType,
) : RequestBody() {
    private sealed interface Segment {
        val byteCount: Long

        class Text(val bytes: ByteString) : Segment {
            override val byteCount: Long get() = bytes.size.toLong()
        }

        class Image(val payload: ImagePayload, val prefix: ByteString, fileLength: Long) : Segment {
            override val byteCount: Long = prefix.size + base64Length(fileLength)
        }
    }

    private val totalLength = segments.sumOf { it.byteCount }

    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = totalLength

    override fun writeTo(sink: BufferedSink) {
        segments.forEach { segment ->
            when (segment) {
                is Segment.Text -> sink.write(segment.bytes)
                is Segment.Image -> {
                    sink.write(segment.prefix)
                    val target = NonClosingSinkStream(sink)
                    Base64.getEncoder().wrap(target).use { encoder ->
                        segment.payload.file.inputStream().use { input -> input.copyTo(encoder, COPY_BUFFER_BYTES) }
                    }
                }
            }
        }
    }

    /** Base64 编码流关闭时才写出末尾填充；它关闭的是这层包装，不能连带关掉 OkHttp 的 sink。 */
    private class NonClosingSinkStream(private val sink: BufferedSink) : OutputStream() {
        override fun write(b: Int) {
            sink.writeByte(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            sink.write(b, off, len)
        }

        override fun close() = Unit
    }

    companion object {
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /**
         * 没有图片时退回普通字符串请求体，纯文本请求与引入图片前逐字节一致。
         * 占位符缺失或重复属于映射错误，在发请求前就失败，不发出残缺请求。
         */
        fun create(json: String, payloads: List<ImagePayload>, mediaType: MediaType): RequestBody {
            if (payloads.isEmpty()) return json.toRequestBody(mediaType)
            val located = payloads.map { payload ->
                val first = json.indexOf(payload.placeholder)
                check(first >= 0 && json.indexOf(payload.placeholder, first + 1) < 0) { "图片请求映射异常" }
                first to payload
            }.sortedBy { it.first }

            val segments = mutableListOf<Segment>()
            var cursor = 0
            located.forEach { (index, payload) ->
                if (index > cursor) segments += Segment.Text(json.substring(cursor, index).encodeUtf8())
                segments += Segment.Image(
                    payload = payload,
                    prefix = "data:${payload.mimeType};base64,".encodeUtf8(),
                    fileLength = payload.file.length(),
                )
                cursor = index + payload.placeholder.length
            }
            if (cursor < json.length) segments += Segment.Text(json.substring(cursor).encodeUtf8())
            return ImagePayloadRequestBody(segments, mediaType)
        }

        private fun base64Length(byteCount: Long): Long = (byteCount + 2) / 3 * 4
    }
}

/**
 * 含图片的请求单独放宽读超时（MULTIMODAL_PLAN 3.4）：上游要先收完并处理图片，首个字节可能晚于 60 秒。
 * `writeTimeout` 限的是两次写之间的间隔而不是上传总时长，保持不变。`newBuilder` 共享连接池与调度器。
 */
internal fun OkHttpClient.withImageReadTimeout(): OkHttpClient =
    newBuilder().readTimeout(IMAGE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

internal const val IMAGE_READ_TIMEOUT_SECONDS = 180L
