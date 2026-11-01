package com.muxuechat.data.remote

import com.muxuechat.domain.model.CustomProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class DeepSeekApiClient private constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val parser: DeepSeekStreamParser,
    private val logger: RemoteClientLogger,
) {
    constructor(
        okHttpClient: OkHttpClient = defaultOkHttpClient(),
        json: Json = defaultJson(),
        parser: DeepSeekStreamParser = DeepSeekStreamParser(json),
    ) : this(okHttpClient, json, parser, AndroidRemoteClientLogger(TAG))

    internal constructor(
        okHttpClient: OkHttpClient,
        logger: RemoteClientLogger,
        json: Json = defaultJson(),
        parser: DeepSeekStreamParser = DeepSeekStreamParser(json),
    ) : this(okHttpClient, json, parser, logger)

    /** 正文流与 RP 后台补写可能短暂重叠；停止时必须全部取消。 */
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val modelCatalogRequester = ModelCatalogRequester(okHttpClient, json, logger)

    /** 含图片的请求用放宽了读超时的同源 client（共享连接池）。 */
    private val imageOkHttpClient: OkHttpClient by lazy { okHttpClient.withImageReadTimeout() }

    private fun clientFor(requestDto: DeepSeekChatRequest): OkHttpClient =
        if (requestDto.imagePayloads().isEmpty()) okHttpClient else imageOkHttpClient

    fun streamChat(
        apiKey: String,
        baseUrl: String,
        requestDto: DeepSeekChatRequest,
    ): Flow<DeepSeekStreamEvent> = callbackFlow {
        require(requestDto.stream) { "DeepSeek 流式请求必须设置 stream=true" }
        val requestUrl = "${normalizeBaseUrl(baseUrl)}/chat/completions"
        val normalizedApiKey = normalizeApiKey(apiKey)
        logger.info("Starting DeepSeek stream request")
        val body = ImagePayloadRequestBody.create(json.encodeToString(requestDto), requestDto.imagePayloads(), JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(requestUrl)
            .addHeader("Authorization", "Bearer $normalizedApiKey")
            .addHeader("Accept", "text/event-stream")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val call = clientFor(requestDto).newCall(request)
        activeCalls.add(call)
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (call.isCanceled()) {
                        close(kotlinx.coroutines.CancellationException("DeepSeek 请求已取消"))
                    } else {
                        logger.warn("DeepSeek stream request failed: ${e.javaClass.simpleName}")
                        close(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) {
                                logger.warn("DeepSeek stream HTTP ${it.code}")
                                close(DeepSeekHttpException(it.code))
                                return
                            }

                            val source = it.body?.source()
                            if (source == null) {
                                close(IOException("DeepSeek API returned empty body"))
                                return
                            }

                            var eventCount = 0L
                            while (true) {
                                val line = readSseLineWithinLimit(source) ?: break
                                val event = try {
                                    parser.parseLine(line)
                                } catch (error: Exception) {
                                    logger.warn(
                                        "DeepSeek stream parsing failed: ${error.javaClass.simpleName}",
                                    )
                                    throw DeepSeekResponseException(DEEPSEEK_RESPONSE_PARSE_MESSAGE)
                                } ?: continue
                                eventCount++
                                val sendResult = trySendBlocking(event)
                                if (sendResult.isFailure) {
                                    // 缓冲区满时阻塞读取线程，把背压传回网络；只有下游关闭后才失败。
                                    call.cancel()
                                    return
                                }
                                if (event == DeepSeekStreamEvent.Done) break
                            }
                            logger.info("DeepSeek stream request finished: events=$eventCount")
                            close()
                        }
                    } catch (error: IOException) {
                        if (call.isCanceled()) {
                            close(kotlinx.coroutines.CancellationException("DeepSeek 请求已取消"))
                        } else {
                            logger.warn("DeepSeek stream read failed: ${error.javaClass.simpleName}")
                            close(error)
                        }
                    } catch (error: DeepSeekResponseException) {
                        if (call.isCanceled()) {
                            close(kotlinx.coroutines.CancellationException("DeepSeek 请求已取消"))
                        } else {
                            close(error)
                        }
                    } catch (error: Exception) {
                        if (call.isCanceled()) {
                            close(kotlinx.coroutines.CancellationException("DeepSeek 请求已取消"))
                        } else {
                            logger.warn("DeepSeek stream response failed: ${error.javaClass.simpleName}")
                            close(DeepSeekResponseException(DEEPSEEK_RESPONSE_PARSE_MESSAGE))
                        }
                    }
                }
            })
        } catch (_: RuntimeException) {
            activeCalls.remove(call)
            call.cancel()
            close(IOException("DeepSeek request could not be started"))
        }

        awaitClose {
            activeCalls.remove(call)
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun completeChat(
        apiKey: String,
        baseUrl: String,
        requestDto: DeepSeekChatRequest,
    ): DeepSeekChatChunkDto = suspendCancellableCoroutine { continuation ->
        require(!requestDto.stream) { "DeepSeek 非流式请求必须设置 stream=false" }
        val requestUrl = "${normalizeBaseUrl(baseUrl)}/chat/completions"
        val normalizedApiKey = normalizeApiKey(apiKey)
        logger.info("Starting DeepSeek non-stream request")
        val body = ImagePayloadRequestBody.create(json.encodeToString(requestDto), requestDto.imagePayloads(), JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(requestUrl)
            .addHeader("Authorization", "Bearer $normalizedApiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val call = clientFor(requestDto).newCall(request)
        activeCalls.add(call)
        continuation.invokeOnCancellation {
            // 只取消当前协程拥有的 Call；同 client 上的正文、RP 补写或模型请求互不误伤。
            activeCalls.remove(call)
            call.cancel()
        }
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    activeCalls.remove(call)
                    val error = if (call.isCanceled()) {
                        kotlinx.coroutines.CancellationException("DeepSeek 请求已取消")
                    } else {
                        e
                    }
                    continuation.resumeWith(Result.failure(error))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        Result.success(
                            response.use {
                                if (!it.isSuccessful) {
                                    logger.warn("DeepSeek non-stream HTTP ${it.code}")
                                    throw DeepSeekHttpException(it.code)
                                }

                                val responseBody = readBodyWithinLimit(it)
                                val decoded = try {
                                    json.decodeFromString<DeepSeekChatChunkDto>(responseBody)
                                } catch (error: Exception) {
                                    logger.warn(
                                        "DeepSeek non-stream parsing failed: ${error.javaClass.simpleName}",
                                    )
                                    throw DeepSeekResponseException(DEEPSEEK_RESPONSE_PARSE_MESSAGE)
                                }
                                logger.info("DeepSeek non-stream request finished")
                                decoded
                            },
                        )
                    } catch (error: IOException) {
                        Result.failure(error)
                    } catch (error: DeepSeekResponseException) {
                        Result.failure(error)
                    } catch (error: Exception) {
                        logger.warn("DeepSeek non-stream response failed: ${error.javaClass.simpleName}")
                        Result.failure(DeepSeekResponseException(DEEPSEEK_RESPONSE_PARSE_MESSAGE))
                    }
                    activeCalls.remove(call)
                    result.fold(
                        onSuccess = { value ->
                            continuation.resumeWith(Result.success(value))
                        },
                        onFailure = { error ->
                            val terminalError = if (call.isCanceled()) {
                                kotlinx.coroutines.CancellationException("DeepSeek 请求已取消")
                            } else {
                                error
                            }
                            continuation.resumeWith(Result.failure(terminalError))
                        }
                    )
                }
            })
        } catch (_: RuntimeException) {
            activeCalls.remove(call)
            call.cancel()
            continuation.resumeWith(
                Result.failure(IOException("DeepSeek request could not be started")),
            )
        }
    }

    /**
     * 拉取 DeepSeek 当前可用模型。
     *
     * 请求不进 [activeCalls]：模型目录不属于生成，停止生成不应把它一起取消。
     * 失败由调用方保留旧缓存，绝不能阻断聊天。
     */
    suspend fun listModels(apiKey: String, baseUrl: String): List<String> =
        modelCatalogRequester.listModels(
            apiKey = apiKey,
            baseUrl = baseUrl,
            normalizeBaseUrl = { url -> normalizeBaseUrl(url) },
            copy = ModelCatalogRequester.DEEPSEEK_COPY,
            wrap = { message -> ModelCatalogException(message) },
            mapHttpError = { response, _ -> deepSeekModelListHttpError(response.code) },
            mapNetworkError = { error -> toUserReadableNetworkError(error) },
        )

    /**
     * 文案保持原有的固定格式（`DeepSeekErrorSanitizer` 按它识别状态码），
     * 额外带上结构化状态码，供上层判断「含图片的 400 / 413」而不必解析文案。
     */
    private class DeepSeekHttpException(override val httpStatus: Int) :
        IOException("DeepSeek API returned HTTP $httpStatus"),
        HttpStatusError

    /** 固定文案且不携带原始响应或 cause，避免解析器把响应片段带到上层。 */
    private class DeepSeekResponseException(message: String) : SerializationException(message)

    fun cancelActiveStream() {
        activeCalls.toList().forEach { call ->
            call.cancel()
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        internal const val MAX_SSE_LINE_BYTES = 1_048_576L
        internal const val MAX_NON_STREAM_RESPONSE_BYTES = 8_388_608L
        private const val TAG = "muxueChat.DeepSeek"
        private const val DEEPSEEK_RESPONSE_PARSE_MESSAGE = "DeepSeek response could not be parsed"
        private const val DEEPSEEK_RESPONSE_TOO_LARGE_MESSAGE = "DeepSeek response exceeded safe size limit"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        private fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        /**
         * DeepSeek 允许保留自定义路径（例如 /v1），但拒绝可能携带凭据或改变请求语义的 URL 部分。
         * HTTP 仍按项目既定策略允许；错误只说明格式，不回显用户输入的完整地址。
         */
        internal fun normalizeBaseUrl(baseUrl: String): String {
            val candidate = baseUrl.trim().ifBlank { DEFAULT_BASE_URL }
            val normalized = CustomProvider.normalizeBaseUrl(candidate)
            require(normalized.isNotBlank()) {
                "DeepSeek 地址无效：请填写 http:// 或 https:// 开头的 Base URL"
            }
            return normalized
        }

        private fun normalizeApiKey(apiKey: String): String = apiKey.trim().also { key ->
            require(key.isNotEmpty()) { "请先在设置中保存 DeepSeek API Key" }
            require(key.none { it == '\r' || it == '\n' }) { "DeepSeek API Key 格式无效" }
        }

        private fun deepSeekModelListHttpError(code: Int): IOException = ModelCatalogException(
            when (code) {
                401 -> "API Key 无效或未授权，请检查 DeepSeek API Key。"
                402 -> "DeepSeek 账户余额不足或账单不可用。"
                403 -> "DeepSeek 拒绝访问，请检查账户权限和 API Key。"
                429 -> "请求过于频繁或触发限流，请稍后再试。"
                in 500..599 -> "DeepSeek 服务暂时异常，请稍后再试。"
                in 400..499 -> "DeepSeek 请求被拒绝，请检查模型和参数配置。"
                else -> "DeepSeek 请求失败（HTTP $code）"
            },
        )

        private fun toUserReadableNetworkError(error: IOException): IOException = when (error) {
            is UnknownHostException -> IOException("网络不可用或无法解析 DeepSeek 服务地址。")
            is ConnectException -> IOException("无法连接 DeepSeek 服务，请检查网络和 Base URL。")
            is SocketTimeoutException -> IOException("网络请求超时，请检查网络或稍后重试。")
            is SSLException -> IOException("DeepSeek 的 HTTPS 证书校验失败，请检查服务证书和地址。")
            else -> IOException("DeepSeek 网络请求失败，请检查网络或稍后重试。")
        }

        private fun readSseLineWithinLimit(source: BufferedSource): String? {
            val newlineIndex = source.indexOf(
                '\n'.code.toByte(),
                0L,
                MAX_SSE_LINE_BYTES + 1L,
            )
            if (newlineIndex >= 0L) return source.readUtf8Line()
            if (source.buffer.size > MAX_SSE_LINE_BYTES) {
                throw DeepSeekResponseException(DEEPSEEK_RESPONSE_TOO_LARGE_MESSAGE)
            }
            return if (source.buffer.size == 0L) null else source.readUtf8()
        }

        private fun readBodyWithinLimit(response: Response): String {
            val body = response.body ?: throw IOException("DeepSeek API returned empty body")
            if (body.contentLength() > MAX_NON_STREAM_RESPONSE_BYTES) {
                throw DeepSeekResponseException(DEEPSEEK_RESPONSE_TOO_LARGE_MESSAGE)
            }
            val source = body.source()
            if (source.request(MAX_NON_STREAM_RESPONSE_BYTES + 1L)) {
                throw DeepSeekResponseException(DEEPSEEK_RESPONSE_TOO_LARGE_MESSAGE)
            }
            return source.readUtf8()
        }
    }
}
