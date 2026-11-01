package com.muxuechat.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64

/**
 * 图片请求的 DTO 序列化与流式请求体（MULTIMODAL_PLAN 3.4 / 第 5 节）。
 *
 * 最重要的约束：**纯文本请求的序列化结果必须与引入图片前逐字节一致**。这里把改动前的消息 DTO
 * 原样复制为 [LegacyDeepSeekMessage] / [LegacyOpenAiMessage]，用客户端同款 Json 配置分别序列化后逐字节比较。
 */
class ImagePayloadTest {
    /** 与 DeepSeekApiClient / OpenAiCompatibleClient 的 defaultJson 相同。 */
    private val clientJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** 与 XaiResponsesTransport.defaultJson 相同（多了 explicitNulls = false）。 */
    private val xaiJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val tempDir: File = Files.createTempDirectory("muxuechat-image-payload").toFile()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ------------------------------------------------------------------ DTO：纯文本字节不变

    @Test
    fun plainTextDeepSeekMessages_serializeByteForByteLikeLegacyDto() {
        val toolCall = DeepSeekToolCallDto(
            id = "call-1",
            function = DeepSeekFunctionCallDto(name = "create_role_card", arguments = """{"name":"林"}"""),
        )
        val current = listOf(
            DeepSeekMessageDto(role = "system", content = "你是助手"),
            DeepSeekMessageDto(role = "user", content = "你好\n\"引号\"与 emoji 🙂"),
            DeepSeekMessageDto(role = "assistant", content = null, toolCalls = listOf(toolCall)),
            DeepSeekMessageDto(role = "tool", content = "草稿已创建", toolCallId = "call-1"),
            DeepSeekMessageDto(role = "user", content = ""),
        )
        val legacy = listOf(
            LegacyDeepSeekMessage(role = "system", content = "你是助手"),
            LegacyDeepSeekMessage(role = "user", content = "你好\n\"引号\"与 emoji 🙂"),
            LegacyDeepSeekMessage(role = "assistant", content = null, toolCalls = listOf(toolCall)),
            LegacyDeepSeekMessage(role = "tool", content = "草稿已创建", toolCallId = "call-1"),
            LegacyDeepSeekMessage(role = "user", content = ""),
        )

        val currentJson = clientJson.encodeToString(ListSerializer(DeepSeekMessageDto.serializer()), current)
        assertEquals(clientJson.encodeToString(ListSerializer(LegacyDeepSeekMessage.serializer()), legacy), currentJson)
        assertTrue(currentJson.contains("""{"role":"system","content":"你是助手"}"""))
    }

    @Test
    fun plainTextOpenAiMessages_serializeByteForByteLikeLegacyDto_inBothJsonConfigs() {
        val toolCall = OpenAiCompatibleToolCallDto(
            id = "call-1",
            function = OpenAiCompatibleFunctionCallDto(name = "create_role_card", arguments = "{}"),
        )
        val current = listOf(
            OpenAiCompatibleMessageDto(role = "user", content = "你好"),
            OpenAiCompatibleMessageDto(role = "assistant", content = null, toolCalls = listOf(toolCall)),
            OpenAiCompatibleMessageDto(role = "tool", content = "结果", toolCallId = "call-1"),
        )
        val legacy = listOf(
            LegacyOpenAiMessage(role = "user", content = "你好"),
            LegacyOpenAiMessage(role = "assistant", content = null, toolCalls = listOf(toolCall)),
            LegacyOpenAiMessage(role = "tool", content = "结果", toolCallId = "call-1"),
        )
        listOf(clientJson, xaiJson).forEach { json ->
            assertEquals(
                json.encodeToString(ListSerializer(LegacyOpenAiMessage.serializer()), legacy),
                json.encodeToString(ListSerializer(OpenAiCompatibleMessageDto.serializer()), current),
            )
        }
    }

    @Test
    fun plainTextRequest_usesPlainStringBodyIdenticalToEncodedJson() {
        val request = OpenAiCompatibleChatRequest(
            model = "fixture-model",
            messages = listOf(OpenAiCompatibleMessageDto(role = "user", content = "你好")),
            stream = true,
        )
        val encoded = clientJson.encodeToString(request)

        val body = ImagePayloadRequestBody.create(encoded, request.imagePayloads(), mediaType)

        assertFalse(body is ImagePayloadRequestBody)
        assertEquals(encoded, Buffer().also(body::writeTo).readUtf8())
        assertEquals(encoded.toByteArray().size.toLong(), body.contentLength())
    }

    // ------------------------------------------------------------------ DTO：带图时输出数组

    @Test
    fun imageMessage_serializesAsStandardContentPartArray() {
        val first = payload("first", byteArrayOf(1, 2, 3))
        val second = payload("second", byteArrayOf(4))
        val message = DeepSeekMessageDto(role = "user", content = "看看这两张", images = listOf(first, second))

        val element = clientJson.parseToJsonElement(clientJson.encodeToString(DeepSeekMessageDto.serializer(), message))
        val parts = element.jsonObject["content"]!!.jsonArray

        assertEquals(3, parts.size)
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("看看这两张", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        listOf(first, second).forEachIndexed { index, image ->
            val part = parts[index + 1].jsonObject
            // type 在 encodeDefaults = false 下也必须出现。
            assertEquals("image_url", part["type"]!!.jsonPrimitive.content)
            assertEquals(image.placeholder, part["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
            // 不发 detail 等可选字段（MULTIMODAL_PLAN 第 2 节）。
            assertNull(part["image_url"]!!.jsonObject["detail"])
        }
    }

    @Test
    fun imageOnlyMessage_hasNoEmptyTextPart_inBothProtocols() {
        val image = payload("only", byteArrayOf(9, 9))
        val deepSeek = clientJson.parseToJsonElement(
            clientJson.encodeToString(DeepSeekMessageDto.serializer(), DeepSeekMessageDto("user", "", images = listOf(image))),
        ).jsonObject["content"] as JsonArray
        val openAi = clientJson.parseToJsonElement(
            clientJson.encodeToString(
                OpenAiCompatibleMessageDto.serializer(),
                OpenAiCompatibleMessageDto("user", "  ", images = listOf(image)),
            ),
        ).jsonObject["content"] as JsonArray

        listOf(deepSeek, openAi).forEach { parts ->
            assertEquals(1, parts.size)
            assertEquals("image_url", parts.single().jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    // ------------------------------------------------------------------ 流式请求体

    @Test
    fun contentLength_matchesActuallyWrittenBytes_acrossBase64Paddings() {
        // 0～3 字节覆盖 base64 的三种补齐；64 KiB + 1 跨过复制缓冲区边界。
        val sizes = listOf(0, 1, 2, 3, 1000, 64 * 1024 + 1)
        val payloads = sizes.mapIndexed { index, size -> payload("p$index", ByteArray(size) { (it * 31 + index).toByte() }) }
        val request = requestWith(payloads)

        val body = ImagePayloadRequestBody.create(clientJson.encodeToString(request), request.imagePayloads(), mediaType)
        val written = Buffer().also(body::writeTo).readByteArray()

        assertTrue(body is ImagePayloadRequestBody)
        assertEquals(written.size.toLong(), body.contentLength())
    }

    @Test
    fun streamedBody_isByteForByteEqualToOneShotEncoding() {
        val payloads = listOf(
            payload("a", ByteArray(5000) { it.toByte() }),
            payload("b", byteArrayOf(-1, 0, 1)),
        )
        val request = requestWith(payloads)
        val encoded = clientJson.encodeToString(request)

        val body = ImagePayloadRequestBody.create(encoded, request.imagePayloads(), mediaType)

        assertArrayEquals(oneShot(encoded, payloads).toByteArray(), Buffer().also(body::writeTo).readByteArray())
        // 写出的仍是合法 JSON，data URL 落在 image_url.url 里。
        val parsed = clientJson.parseToJsonElement(oneShot(encoded, payloads))
        val url = parsed.jsonObject["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[1]
            .jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertEquals("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(ByteArray(5000) { it.toByte() }), url)
    }

    @Test
    fun deepSeekRequest_streamedBodyEqualsOneShotEncoding() {
        val payloads = listOf(payload("ds", ByteArray(777) { (it * 7).toByte() }))
        val request = DeepSeekChatRequest(
            model = "deepseek-flash",
            messages = listOf(
                DeepSeekMessageDto(role = "system", content = "系统"),
                DeepSeekMessageDto(role = "user", content = "看图", images = payloads),
            ),
            stream = true,
        )
        val encoded = clientJson.encodeToString(request)

        val body = ImagePayloadRequestBody.create(encoded, request.imagePayloads(), mediaType)

        assertArrayEquals(oneShot(encoded, payloads).toByteArray(), Buffer().also(body::writeTo).readByteArray())
    }

    @Test
    fun writeTo_canRunRepeatedly_forRetries() {
        val payloads = listOf(payload("retry", ByteArray(4096) { (it % 251).toByte() }))
        val request = requestWith(payloads)
        val body = ImagePayloadRequestBody.create(clientJson.encodeToString(request), request.imagePayloads(), mediaType)

        val first = Buffer().also(body::writeTo).readByteArray()
        val second = Buffer().also(body::writeTo).readByteArray()

        assertArrayEquals(first, second)
        assertEquals(first.size.toLong(), body.contentLength())
    }

    @Test
    fun missingOrDuplicatedPlaceholder_failsBeforeAnyRequestIsBuilt() {
        val image = payload("dup", byteArrayOf(1))
        val missing = runCatching { ImagePayloadRequestBody.create("""{"a":"b"}""", listOf(image), mediaType) }
        val duplicated = runCatching {
            ImagePayloadRequestBody.create("""["${image.placeholder}","${image.placeholder}"]""", listOf(image), mediaType)
        }

        assertTrue(missing.exceptionOrNull() is IllegalStateException)
        assertTrue(duplicated.exceptionOrNull() is IllegalStateException)
        // 异常文案不带占位符、路径或任何图片内容。
        listOf(missing, duplicated).forEach { result ->
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertFalse(message.contains(image.placeholder))
            assertFalse(message.contains(tempDir.path))
        }
    }

    @Test
    fun deletedFile_failsWritingInsteadOfSendingTruncatedImage() {
        val image = payload("gone", byteArrayOf(1, 2, 3))
        val request = requestWith(listOf(image))
        val body = ImagePayloadRequestBody.create(clientJson.encodeToString(request), request.imagePayloads(), mediaType)
        image.file.delete()

        assertTrue(runCatching { body.writeTo(Buffer()) }.exceptionOrNull() is IOException)
    }

    // ------------------------------------------------------------------ 错误状态码

    @Test
    fun httpStatusOrNull_walksCauseChain() {
        val status = object : IOException("HTTP 413"), HttpStatusError {
            override val httpStatus: Int = 413
        }
        assertEquals(413, IllegalStateException("wrapped", RuntimeException(status)).httpStatusOrNull())
        assertNull(IllegalStateException("plain").httpStatusOrNull())
    }

    // ------------------------------------------------------------------ helpers

    private fun payload(name: String, bytes: ByteArray): ImagePayload {
        val file = File(tempDir, "$name.jpg").apply { writeBytes(bytes) }
        return ImagePayload(file = file, mimeType = "image/jpeg")
    }

    private fun requestWith(payloads: List<ImagePayload>) = OpenAiCompatibleChatRequest(
        model = "fixture-model",
        messages = listOf(OpenAiCompatibleMessageDto(role = "user", content = "看图", images = payloads)),
        stream = false,
    )

    /** 一次性编码：把整张图读进内存、拼成 data URL 后替换占位符。流式请求体必须与它逐字节相同。 */
    private fun oneShot(json: String, payloads: List<ImagePayload>): String = payloads.fold(json) { acc, image ->
        acc.replace(
            image.placeholder,
            "data:${image.mimeType};base64," + Base64.getEncoder().encodeToString(image.file.readBytes()),
        )
    }
}

/** 引入图片前的 DeepSeekMessageDto，原样复制（字段与顺序不得改动）。 */
@Serializable
private data class LegacyDeepSeekMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<DeepSeekToolCallDto>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

/** 引入图片前的 OpenAiCompatibleMessageDto，原样复制（字段与顺序不得改动）。 */
@Serializable
private data class LegacyOpenAiMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiCompatibleToolCallDto>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)
