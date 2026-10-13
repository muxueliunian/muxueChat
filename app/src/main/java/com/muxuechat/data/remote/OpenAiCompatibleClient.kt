package com.muxuechat.data.remote

import com.muxuechat.domain.model.ConversationHeaderPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** 标记消息已经过客户端清理，可以由 Repository 原样展示。 */
internal interface OpenAiCompatibleUserFacingError

/**
 * HTTP 401。文案与其他鉴权失败完全一致，只是带上类型，让 OAuth 链路（Google Vertex）
 * 能在上层强制刷新 token 后重试一次；API Key 链路不受影响。
 */
internal class OpenAiCompatibleUnauthorizedException(message: String) :
    IOException(message),
    OpenAiCompatibleUserFacingError,
    ModelCatalogUserFacingError

/**
 * 所有自定义供应商共用的 OpenAI 兼容客户端。
 *
 * 它只发送标准的 `POST {base}/chat/completions` 与 `GET {base}/models`；
 * 上游服务自己的 OAuth 与账号体系完全由该服务处理，客户端不会接触、保存或
 * 透传任何 OAuth 凭据，只携带用户填写的 API Key。
 */
class OpenAiCompatibleClient private constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val parser: OpenAiCompatibleStreamParser,
    private val logger: RemoteClientLogger,
    /**
     * 非 null 时这是 OAuth 链路的**钉死主机实例**（AGENT.md §2「Google Vertex 链路」）：
     * 只允许 https 与该主机，且调用方应传入禁止重定向的 OkHttp。普通实例恒为 null，行为不变。
     */
    private val pinnedHost: String?,
) {
    constructor(
        okHttpClient: OkHttpClient = defaultOkHttpClient(),
        json: Json = defaultJson(),
        parser: OpenAiCompatibleStreamParser = OpenAiCompatibleStreamParser(json),
    ) : this(okHttpClient, json, parser, AndroidRemoteClientLogger(TAG), null)

    internal constructor(
        okHttpClient: OkHttpClient,
        logger: RemoteClientLogger,
        json: Json = defaultJson(),
        parser: OpenAiCompatibleStreamParser = OpenAiCompatibleStreamParser(json),
    ) : this(okHttpClient, json, parser, logger, null)

    /**
     * RP 正文完成后可能还会后台补写元数据；这些请求与下一条正文请求可以短暂重叠。
     * 因此必须追踪全部在途 Call，停止生成时不能只取消最后创建的一条。
     */
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val modelCatalogRequester = ModelCatalogRequester(okHttpClient, json, logger)

    fun streamChat(
        apiKey: String,
        baseUrl: String,
        requestDto: OpenAiCompatibleChatRequest,
        conversationHeaderName: String = "",
        conversationId: String? = null,
    ): Flow<OpenAiCompatibleStreamEvent> {
        require(requestDto.stream) { "供应商流式请求必须设置 stream=true" }
        val request = buildRequest(
            apiKey = apiKey,
            baseUrl = baseUrl,
            requestDto = requestDto,
            acceptsEventStream = true,
            conversationHeaderName = conversationHeaderName,
            conversationId = conversationId,
        )

        return callbackFlow {
            logger.info("Starting OpenAI-compatible stream request")
            val call = okHttpClient.newCall(request)
            activeCalls.add(call)
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (call.isCanceled()) {
                            close(CancellationException("供应商请求已取消"))
                            return
                        }

                        logger.warn("OpenAI-compatible stream request failed: ${e.javaClass.simpleName}")
                        close(toUserReadableNetworkError(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                if (!it.isSuccessful) {
                                    logger.warn("OpenAI-compatible stream HTTP ${it.code}")
                                    close(httpError(it, apiKey))
                                    return
                                }

                                val source = it.body?.source()
                                if (source == null) {
                                    close(OpenAiCompatibleResponseException("供应商返回了空响应"))
                                    return
                                }

                                var eventCount = 0L
                                while (true) {
                                    val line = readSseLineWithinLimit(source) ?: break
                                    val event = try {
                                        parser.parseLine(line, apiKey)
                                    } catch (error: Exception) {
                                        if (error is OpenAiCompatibleProtocolException) throw error
                                        logger.warn(
                                            "OpenAI-compatible stream parsing failed: ${error.javaClass.simpleName}",
                                        )
                                        throw OpenAiCompatibleResponseException("供应商返回内容无法解析")
                                    } ?: continue
                                    eventCount++
                                    val sendResult = trySendBlocking(event)
                                    if (sendResult.isFailure) {
                                        // 通道只会在下游取消或关闭后发送失败。缓冲区满时这里会等待，
                                        // 从而把背压传回 OkHttp 读取线程，不能把它误判成流结束。
                                        call.cancel()
                                        return
                                    }
                                    if (event == OpenAiCompatibleStreamEvent.Done) break
                                }
                                logger.info("OpenAI-compatible stream request finished: events=$eventCount")
                                close()
                            }
                        } catch (error: IOException) {
                            if (call.isCanceled()) {
                                close(CancellationException("供应商请求已取消"))
                            } else {
                                if (error !is OpenAiCompatibleUserFacingError) {
                                    logger.warn(
                                        "OpenAI-compatible stream read failed: ${error.javaClass.simpleName}",
                                    )
                                }
                                close(
                                    if (error is OpenAiCompatibleUserFacingError) {
                                        error
                                    } else {
                                        toUserReadableNetworkError(error)
                                    },
                                )
                            }
                        } catch (error: Exception) {
                            if (call.isCanceled()) {
                                close(CancellationException("供应商请求已取消"))
                            } else {
                                logger.warn("OpenAI-compatible stream response failed: ${error.javaClass.simpleName}")
                                close(OpenAiCompatibleResponseException("供应商返回内容无法解析"))
                            }
                        }
                    }
                })
            } catch (_: RuntimeException) {
                activeCalls.remove(call)
                call.cancel()
                close(OpenAiCompatibleResponseException("供应商请求无法启动"))
            }

            awaitClose {
                activeCalls.remove(call)
                call.cancel()
            }
        }.flowOn(Dispatchers.IO)
    }

    suspend fun completeChat(
        apiKey: String,
        baseUrl: String,
        requestDto: OpenAiCompatibleChatRequest,
        conversationHeaderName: String = "",
        conversationId: String? = null,
    ): OpenAiCompatibleChatChunkDto = suspendCancellableCoroutine { continuation ->
        require(!requestDto.stream) { "供应商非流式请求必须设置 stream=false" }
        val request = buildRequest(
            apiKey = apiKey,
            baseUrl = baseUrl,
            requestDto = requestDto,
            acceptsEventStream = false,
            conversationHeaderName = conversationHeaderName,
            conversationId = conversationId,
        )
        logger.info("Starting OpenAI-compatible non-stream request")

        val call = okHttpClient.newCall(request)
        activeCalls.add(call)
        continuation.invokeOnCancellation {
            // Job 与 Call 一一绑定；取消正文不会再通过全局 activeCalls 误伤 RP 补写。
            activeCalls.remove(call)
            call.cancel()
        }
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    activeCalls.remove(call)
                    val error = if (call.isCanceled()) {
                        CancellationException("供应商请求已取消")
                    } else {
                        toUserReadableNetworkError(e)
                    }
                    continuation.resumeWith(Result.failure(error))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) {
                                logger.warn("OpenAI-compatible non-stream HTTP ${it.code}")
                                throw httpError(it, apiKey)
                            }

                            val responseBody = readBodyWithinLimit(
                                response = it,
                                maxBytes = MAX_NON_STREAM_RESPONSE_BYTES,
                                tooLargeMessage = "供应商响应过大，已停止解析",
                            )
                            try {
                                val root = json.parseToJsonElement(responseBody)
                                if (root is JsonObject && root["error"] != null && root["error"] != JsonNull) {
                                    throw OpenAiCompatibleErrorDetails.responseError(
                                        root["error"] as? JsonObject, apiKey, streaming = false,
                                    )
                                }
                                json.decodeFromJsonElement<OpenAiCompatibleChatChunkDto>(root).also {
                                    logger.info("OpenAI-compatible non-stream request finished")
                                }
                            } catch (error: Exception) {
                                if (error is OpenAiCompatibleProtocolException) throw error
                                logger.warn(
                                    "OpenAI-compatible non-stream parsing failed: ${error.javaClass.simpleName}",
                                )
                                throw OpenAiCompatibleResponseException("供应商返回内容无法解析")
                            }
                        }
                    }
                    activeCalls.remove(call)
                    result.fold(
                        onSuccess = { value ->
                            continuation.resumeWith(Result.success(value))
                        },
                        onFailure = { error ->
                            val terminalError = when {
                                call.isCanceled() -> CancellationException("供应商请求已取消")
                                error is OpenAiCompatibleUserFacingError -> error
                                error is IOException -> toUserReadableNetworkError(error)
                                else -> OpenAiCompatibleResponseException("供应商返回内容无法解析")
                            }
                            continuation.resumeWith(Result.failure(terminalError))
                        },
                    )
                }
            })
        } catch (_: RuntimeException) {
            activeCalls.remove(call)
            call.cancel()
            continuation.resumeWith(
                Result.failure(OpenAiCompatibleResponseException("供应商请求无法启动")),
            )
        }
    }

    /**
     * 拉取供应商声明的可用模型列表。
     *
     * 这是个**可选能力**：不少 OpenAI 兼容服务并不实现 `/models`，或返回结构不同。
     * 因此这里失败就抛可读异常，由上层降级到手填，绝不能影响聊天本身。
     * 请求不进 [activeCalls]：它与生成无关，"停止生成"不应该把它一起取消。
     */
    suspend fun listModels(apiKey: String, baseUrl: String): List<String> {
        // 钉死实例的 OkHttp 与校验只覆盖聊天请求；Vertex 本就没有 /models（实测 404），这里直接拒绝。
        check(pinnedHost == null) { "该供应商不支持获取模型列表，请手动填写模型名" }
        return fetchModels(apiKey, baseUrl)
    }

    private suspend fun fetchModels(apiKey: String, baseUrl: String): List<String> =
        modelCatalogRequester.listModels(
            apiKey = apiKey,
            baseUrl = baseUrl,
            normalizeBaseUrl = { url -> normalizeBaseUrl(url) },
            copy = ModelCatalogRequester.OPENAI_COMPATIBLE_COPY,
            wrap = { message -> OpenAiCompatibleResponseException(message) },
            mapHttpError = { response, key -> httpError(response, key) },
            mapNetworkError = { error -> toUserReadableNetworkError(error) },
        )

    /** 取消当前所有进行中的流式或非流式请求。 */
    fun cancelActiveRequest() {
        activeCalls.toList().forEach { call ->
            call.cancel()
        }
    }

    /**
     * 供同包测试核对请求边界：请求体只会来自 [OpenAiCompatibleChatRequest] 定义的标准
     * OpenAI 兼容字段。生产调用统一经过这个方法，避免遗漏 HTTPS 与凭据校验。
     */
    internal fun buildRequest(
        apiKey: String,
        baseUrl: String,
        requestDto: OpenAiCompatibleChatRequest,
        acceptsEventStream: Boolean,
        conversationHeaderName: String = "",
        conversationId: String? = null,
    ): Request {
        require(requestDto.model.isNotBlank()) { "请配置供应商模型名" }
        require(requestDto.messages.isNotEmpty()) { "供应商消息不能为空" }
        val sessionHeader = conversationHeaderName.trim()
        require(sessionHeader.isEmpty() || ConversationHeaderPolicy.isValid(sessionHeader)) {
            "供应商会话请求头名称无效，必须是合法的 x- 前缀请求头"
        }
        if (sessionHeader.isNotEmpty() && conversationId != null) {
            require(conversationId.isNotBlank() && conversationId.length <= 128 &&
                conversationId.all { it in '!'..'~' }) { "供应商会话标识无效" }
        }

        val requestUrl = "${normalizeBaseUrl(baseUrl)}/chat/completions"
        pinnedHost?.let { host ->
            val url = requestUrl.toHttpUrlOrNull()
            require(url != null && url.isHttps && url.host.equals(host, ignoreCase = true) && url.port == 443) {
                "该供应商只允许 https://$host，已拒绝发送凭据"
            }
        }
        val proxyApiKey = apiKey.trim().also { key ->
            require(key.isNotEmpty()) { "请先在设置中保存供应商 API Key" }
            require(key.none { it == '\r' || it == '\n' }) { "供应商 API Key 格式无效" }
        }
        val body = json.encodeToString(requestDto).toRequestBody(JSON_MEDIA_TYPE)

        return Request.Builder()
            .url(requestUrl)
            .header("Authorization", "Bearer $proxyApiKey")
            .header("Content-Type", "application/json")
            .header("User-Agent", "muxueChat/1.0")
            .apply {
                if (sessionHeader.isNotEmpty() && conversationId != null) {
                    header(sessionHeader, conversationId)
                }
                if (acceptsEventStream) {
                    header("Accept", "text/event-stream")
                }
            }
            .post(body)
            .build()
    }

    /** 仅承载本客户端生成并已做凭据清理、长度限制的用户可见错误。 */
    private class OpenAiCompatibleResponseException(message: String) :
        IOException(message),
        OpenAiCompatibleUserFacingError,
        ModelCatalogUserFacingError

    /**
     * 读取供应商明确返回的结构化错误，而不是只保留 HTTP 状态码。
     *
     * 错误正文来自不受信任的远端：只接受 `code` / `message`，限制读取与展示长度，
     * 并清除当前 API Key、Bearer 凭据及控制字符。完整正文绝不进入日志或异常。
     */
    private fun httpError(response: Response, apiKey: String): IOException {
        val error = buildHttpError(response, apiKey)
        return if (response.code == 401) {
            OpenAiCompatibleUnauthorizedException(error.message.orEmpty())
        } else {
            error
        }
    }

    private fun buildHttpError(response: Response, apiKey: String): IOException {
        val details = readProviderErrorDetails(response, apiKey)
        if (details == null) return httpError(response.code)

        val title = when (response.code) {
            401 -> "供应商鉴权失败"
            402 -> "供应商服务余额或订阅不可用"
            403 -> "供应商拒绝访问"
            429 -> "供应商请求过于频繁"
            in 500..599 -> "供应商服务暂时不可用"
            else -> "供应商请求失败"
        }
        val status = buildString {
            append("HTTP ")
            append(response.code)
            details.code?.let {
                append("，错误码 ")
                append(it)
            }
        }
        return OpenAiCompatibleResponseException(
            buildString {
                append(title)
                append('（')
                append(status)
                append('）')
                details.message?.let {
                    append('：')
                    append(it)
                }
            },
        )
    }

    private fun readProviderErrorDetails(response: Response, apiKey: String): OpenAiCompatibleErrorDetails.Details? {
        val body = runCatching {
            readBodyWithinLimit(
                response = response,
                maxBytes = MAX_ERROR_RESPONSE_BYTES,
                tooLargeMessage = "供应商错误响应过大",
            )
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        // 结构兼容：Vertex 的错误体是单元素数组 `[{"error":{...}}]`，不兼容就会误报成 API Key 问题。
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
        val root = when (parsed) {
            is JsonObject -> parsed
            is JsonArray -> parsed.singleOrNull() as? JsonObject
            else -> null
        } ?: return null
        val source = (root["error"] as? JsonObject) ?: root
        return OpenAiCompatibleErrorDetails.read(source, apiKey)
    }

    companion object {
        internal const val MAX_SSE_LINE_BYTES = 1_048_576L
        internal const val MAX_NON_STREAM_RESPONSE_BYTES = 8_388_608L
        internal const val MAX_MODELS_RESPONSE_BYTES = ModelCatalogParser.MAX_RESPONSE_BYTES
        internal const val MAX_ERROR_RESPONSE_BYTES = 65_536L
        internal const val MAX_MODEL_COUNT = ModelCatalogParser.MAX_MODEL_COUNT
        internal const val MAX_MODEL_ID_LENGTH = ModelCatalogParser.MAX_MODEL_ID_LENGTH
        internal const val MAX_ERROR_MESSAGE_CHARS = 300
        private const val TAG = "muxueChat.OpenAiCompatible"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * OAuth 链路的钉死主机实例：强制 https 与指定主机，并且**禁止任何重定向**，
         * 避免 Authorization 头跟着 3xx 去往其他主机。
         */
        internal fun pinnedOkHttpClient(): OkHttpClient = defaultOkHttpClient().newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        internal fun pinnedTo(
            host: String,
            okHttpClient: OkHttpClient = pinnedOkHttpClient(),
        ): OpenAiCompatibleClient {
            val json = defaultJson()
            return OpenAiCompatibleClient(
                okHttpClient, json, OpenAiCompatibleStreamParser(json), AndroidRemoteClientLogger(TAG), host,
            )
        }

        private fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        /**
         * 允许 http 与 https。
         *
         * 经用户确认：局域网自建服务常常没有证书，因此不强制 HTTPS。
         * 明文 http 下 Authorization 头与聊天正文都不加密，只应用于自己可控的局域网。
         * 校验规则与 [com.muxuechat.domain.model.CustomProvider.normalizeBaseUrl] 保持一致。
         * 不允许 user-info、query 或 fragment，避免把错误填入的认证信息带入请求或日志。
         */
        internal fun normalizeBaseUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull()
            val hasValidPort = uri?.let { it.port == -1 || it.port in 1..65_535 } == true
            val hasSupportedScheme = uri != null &&
                (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true))
            val isValid = uri != null &&
                hasSupportedScheme &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                hasValidPort

            require(isValid) { "供应商地址无效：请填写 http:// 或 https:// 开头的 Base URL" }
            return trimmed
        }

        private fun httpError(code: Int): IOException = OpenAiCompatibleResponseException(
            when (code) {
                401 -> "供应商鉴权失败（401）：请检查 API Key"
                402 -> "供应商服务余额或订阅不可用（402）"
                403 -> "供应商拒绝访问（403）：请检查服务权限"
                429 -> "供应商请求过于频繁（429）：请稍后重试"
                in 500..599 -> "供应商服务暂时不可用（HTTP $code）"
                else -> "供应商请求失败（HTTP $code）"
            },
        )

        private fun toUserReadableNetworkError(error: IOException): IOException = when (error) {
            is UnknownHostException -> IOException("无法连接供应商：未找到服务器地址")
            is ConnectException -> IOException("无法连接供应商：请检查网络连接和服务状态")
            is SocketTimeoutException -> IOException("连接供应商超时，请检查网络和服务状态")
            is SSLException -> IOException("供应商的 HTTPS 证书校验失败")
            else -> IOException("供应商网络请求失败，请检查网络连接和服务状态")
        }

        private fun readSseLineWithinLimit(source: BufferedSource): String? {
            val newlineIndex = source.indexOf(
                '\n'.code.toByte(),
                0L,
                MAX_SSE_LINE_BYTES + 1L,
            )
            if (newlineIndex >= 0L) return source.readUtf8Line()
            if (source.buffer.size > MAX_SSE_LINE_BYTES) {
                throw OpenAiCompatibleResponseException("供应商流式响应单行过大，已停止解析")
            }
            return if (source.buffer.size == 0L) null else source.readUtf8()
        }

        private fun readBodyWithinLimit(
            response: Response,
            maxBytes: Long,
            tooLargeMessage: String,
        ): String {
            val body = response.body
                ?: throw OpenAiCompatibleResponseException("供应商返回了空响应")
            if (body.contentLength() > maxBytes) {
                throw OpenAiCompatibleResponseException(tooLargeMessage)
            }
            val source = body.source()
            if (source.request(maxBytes + 1L)) {
                throw OpenAiCompatibleResponseException(tooLargeMessage)
            }
            return source.readUtf8()
        }
    }
}
