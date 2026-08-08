package com.muxuechat.data.remote

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * 所有自定义供应商共用的 OpenAI 兼容客户端。
 *
 * 它只发送标准的 `POST {base}/chat/completions` 与 `GET {base}/models`；
 * 上游服务自己的 OAuth 与账号体系完全由该服务处理，客户端不会接触、保存或
 * 透传任何 OAuth 凭据，只携带用户填写的 API Key。
 */
class OpenAiCompatibleClient(
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    },
    private val parser: OpenAiCompatibleStreamParser = OpenAiCompatibleStreamParser(json),
) {
    /**
     * RP 正文完成后可能还会后台补写元数据；这些请求与下一条正文请求可以短暂重叠。
     * 因此必须追踪全部在途 Call，停止生成时不能只取消最后创建的一条。
     */
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()

    fun streamChat(
        apiKey: String,
        baseUrl: String,
        requestDto: OpenAiCompatibleChatRequest,
    ): Flow<OpenAiCompatibleStreamEvent> {
        require(requestDto.stream) { "供应商流式请求必须设置 stream=true" }
        val request = buildRequest(
            apiKey = apiKey,
            baseUrl = baseUrl,
            requestDto = requestDto,
            acceptsEventStream = true,
        )

        return callbackFlow {
            Log.i(TAG, "Starting OpenAI-compatible stream request: model=${requestDto.model}")
            val call = okHttpClient.newCall(request)
            activeCalls.add(call)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (call.isCanceled()) {
                        close(CancellationException("供应商请求已取消"))
                        return
                    }

                    Log.w(TAG, "OpenAI-compatible stream request failed: ${e.javaClass.simpleName}")
                    close(toUserReadableNetworkError(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            Log.w(TAG, "OpenAI-compatible stream HTTP ${it.code}")
                            close(httpError(it.code))
                            return
                        }

                        val source = it.body?.source()
                        if (source == null) {
                            close(IOException("供应商返回了空响应"))
                            return
                        }

                        try {
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                val event = parser.parseLine(line) ?: continue
                                if (trySend(event).isFailure) break
                                if (event == OpenAiCompatibleStreamEvent.Done) break
                            }
                            Log.i(TAG, "OpenAI-compatible stream request finished")
                            close()
                        } catch (error: IOException) {
                            if (call.isCanceled()) {
                                close(CancellationException("供应商请求已取消"))
                            } else {
                                Log.w(TAG, "OpenAI-compatible stream read failed: ${error.javaClass.simpleName}")
                                close(toUserReadableNetworkError(error))
                            }
                        } catch (error: Throwable) {
                            if (call.isCanceled()) {
                                close(CancellationException("供应商请求已取消"))
                            } else {
                                Log.w(TAG, "OpenAI-compatible stream parsing failed: ${error.javaClass.simpleName}")
                                close(IOException("供应商返回内容无法解析"))
                            }
                        }
                    }
                }
            })

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
    ): OpenAiCompatibleChatChunkDto = withContext(Dispatchers.IO) {
        require(!requestDto.stream) { "供应商非流式请求必须设置 stream=false" }
        val request = buildRequest(
            apiKey = apiKey,
            baseUrl = baseUrl,
            requestDto = requestDto,
            acceptsEventStream = false,
        )
        Log.i(TAG, "Starting OpenAI-compatible non-stream request: model=${requestDto.model}")

        val call = okHttpClient.newCall(request)
        activeCalls.add(call)
        val response = try {
            call.execute()
        } catch (error: IOException) {
            if (call.isCanceled()) {
                throw CancellationException("供应商请求已取消")
            }
            throw toUserReadableNetworkError(error)
        }

        try {
            response.use {
                if (!it.isSuccessful) {
                    Log.w(TAG, "OpenAI-compatible non-stream HTTP ${it.code}")
                    throw httpError(it.code)
                }

                val responseBody = it.body?.string()
                    ?: throw OpenAiCompatibleResponseException("供应商返回了空响应")
                Log.i(TAG, "OpenAI-compatible non-stream request finished")
                try {
                    json.decodeFromString<OpenAiCompatibleChatChunkDto>(responseBody)
                } catch (error: Throwable) {
                    Log.w(TAG, "OpenAI-compatible non-stream parsing failed: ${error.javaClass.simpleName}")
                    throw OpenAiCompatibleResponseException("供应商返回内容无法解析")
                }
            }
        } catch (error: IOException) {
            if (call.isCanceled()) {
                throw CancellationException("供应商请求已取消")
            }
            throw if (error is OpenAiCompatibleResponseException) error else toUserReadableNetworkError(error)
        } finally {
            activeCalls.remove(call)
        }
    }

    /**
     * 拉取供应商声明的可用模型列表。
     *
     * 这是个**可选能力**：不少 OpenAI 兼容服务并不实现 `/models`，或返回结构不同。
     * 因此这里失败就抛可读异常，由上层降级到手填，绝不能影响聊天本身。
     * 请求不进 [activeCalls]：它与生成无关，"停止生成"不应该把它一起取消。
     */
    suspend fun listModels(apiKey: String, baseUrl: String): List<String> = withContext(Dispatchers.IO) {
        val requestUrl = "${normalizeBaseUrl(baseUrl)}/models"
        val proxyApiKey = apiKey.trim().also { key ->
            require(key.isNotEmpty()) { "请先在设置中保存供应商 API Key" }
            require(key.none { it == '\r' || it == '\n' }) { "供应商 API Key 格式无效" }
        }
        val request = Request.Builder()
            .url(requestUrl)
            .header("Authorization", "Bearer $proxyApiKey")
            .get()
            .build()

        Log.i(TAG, "Fetching model list")
        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (error: IOException) {
            throw toUserReadableNetworkError(error)
        }

        response.use {
            if (!it.isSuccessful) {
                Log.w(TAG, "Model list HTTP ${it.code}")
                throw if (it.code == 404) {
                    OpenAiCompatibleResponseException("该供应商不提供模型列表接口（404），请手动填写模型名")
                } else {
                    httpError(it.code)
                }
            }

            val body = it.body?.string()
                ?: throw OpenAiCompatibleResponseException("供应商返回了空响应")
            val models = runCatching {
                json.decodeFromString<OpenAiCompatibleModelsResponseDto>(body)
            }.getOrElse { error ->
                Log.w(TAG, "Model list parsing failed: ${error.javaClass.simpleName}")
                throw OpenAiCompatibleResponseException("模型列表格式无法解析，请手动填写模型名")
            }
                .data
                .map { model -> model.id.trim() }
                .filter { id -> id.isNotEmpty() }
                .distinct()
                .sorted()

            if (models.isEmpty()) {
                throw OpenAiCompatibleResponseException("供应商返回的模型列表为空，请手动填写模型名")
            }
            Log.i(TAG, "Model list fetched: count=${models.size}")
            models
        }
    }

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
    ): Request {
        require(requestDto.model.isNotBlank()) { "请配置供应商模型名" }
        require(requestDto.messages.isNotEmpty()) { "供应商消息不能为空" }

        val requestUrl = "${normalizeBaseUrl(baseUrl)}/chat/completions"
        val proxyApiKey = apiKey.trim().also { key ->
            require(key.isNotEmpty()) { "请先在设置中保存供应商 API Key" }
            require(key.none { it == '\r' || it == '\n' }) { "供应商 API Key 格式无效" }
        }
        val body = json.encodeToString(requestDto).toRequestBody(JSON_MEDIA_TYPE)

        return Request.Builder()
            .url(requestUrl)
            .header("Authorization", "Bearer $proxyApiKey")
            .header("Content-Type", "application/json")
            .apply {
                if (acceptsEventStream) {
                    header("Accept", "text/event-stream")
                }
            }
            .post(body)
            .build()
    }

    private class OpenAiCompatibleResponseException(message: String) : IOException(message)

    companion object {
        private const val TAG = "muxueChat.OpenAiCompatible"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

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
    }
}
