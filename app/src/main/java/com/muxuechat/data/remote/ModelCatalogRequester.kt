package com.muxuechat.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 通用的 `GET {base}/models` 请求。
 *
 * 职责仅限于模型目录拉取：不发送聊天、不引入多 Provider SDK。
 * DeepSeek 与自定义供应商各自传入地址规范化、错误文案和 HTTP 映射。
 */
internal class ModelCatalogRequester(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val logger: RemoteClientLogger,
) {
    suspend fun listModels(
        apiKey: String,
        baseUrl: String,
        normalizeBaseUrl: (String) -> String,
        copy: ModelCatalogCopy,
        wrap: (String) -> IOException,
        mapHttpError: (Response, String) -> IOException,
        mapNetworkError: (IOException) -> Throwable,
    ): List<String> = suspendCancellableCoroutine { continuation ->
        val requestUrl = "${normalizeBaseUrl(baseUrl)}/models"
        val normalizedApiKey = apiKey.trim().also { key ->
            require(key.isNotEmpty()) { copy.missingApiKey }
            require(key.none { it == '\r' || it == '\n' }) { copy.invalidApiKey }
        }
        val request = Request.Builder()
            .url(requestUrl)
            .header("Authorization", "Bearer $normalizedApiKey")
            .header("User-Agent", "muxueChat/1.0")
            .get()
            .build()

        logger.info("Fetching model list")
        val call = okHttpClient.newCall(request)
        continuation.invokeOnCancellation {
            call.cancel()
        }
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val error = if (call.isCanceled()) {
                        CancellationException(copy.cancelled)
                    } else {
                        mapNetworkError(e)
                    }
                    continuation.resumeWith(Result.failure(error))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) {
                                logger.warn("Model list HTTP ${it.code}")
                                throw if (it.code == 404) {
                                    wrap(copy.notFound)
                                } else {
                                    mapHttpError(it, apiKey)
                                }
                            }

                            val body = readBodyWithinLimit(
                                response = it,
                                maxBytes = ModelCatalogParser.MAX_RESPONSE_BYTES,
                                tooLargeMessage = copy.tooLarge,
                                emptyBodyMessage = copy.emptyBody,
                                wrap = wrap,
                            )
                            when (val parsed = ModelCatalogParser.parse(body, json)) {
                                is ModelCatalogParseResult.Success -> {
                                    logger.info("Model list fetched: count=${parsed.ids.size}")
                                    parsed.ids
                                }
                                is ModelCatalogParseResult.Failure -> {
                                    if (parsed.reason == ModelCatalogParseResult.Reason.INVALID_JSON) {
                                        logger.warn("Model list parsing failed")
                                    }
                                    throw wrap(copy.messageFor(parsed.reason))
                                }
                            }
                        }
                    }
                    result.fold(
                        onSuccess = { models ->
                            continuation.resumeWith(Result.success(models))
                        },
                        onFailure = { error ->
                            val terminalError = when {
                                call.isCanceled() -> CancellationException(copy.cancelled)
                                error is CancellationException -> error
                                error is ModelCatalogUserFacingError -> error
                                error is IOException -> mapNetworkError(error)
                                else -> wrap(copy.parseFailed)
                            }
                            continuation.resumeWith(Result.failure(terminalError))
                        },
                    )
                }
            })
        } catch (_: RuntimeException) {
            call.cancel()
            continuation.resumeWith(Result.failure(wrap(copy.cannotStart)))
        }
    }

    private fun readBodyWithinLimit(
        response: Response,
        maxBytes: Long,
        tooLargeMessage: String,
        emptyBodyMessage: String,
        wrap: (String) -> IOException,
    ): String {
        val body = response.body ?: throw wrap(emptyBodyMessage)
        if (body.contentLength() > maxBytes) {
            throw wrap(tooLargeMessage)
        }
        val source = body.source()
        if (source.request(maxBytes + 1L)) {
            throw wrap(tooLargeMessage)
        }
        return source.readUtf8()
    }

    companion object {
        val OPENAI_COMPATIBLE_COPY = ModelCatalogCopy(
            missingApiKey = "请先在设置中保存供应商 API Key",
            invalidApiKey = "供应商 API Key 格式无效",
            cancelled = "模型列表请求已取消",
            notFound = "该供应商不提供模型列表接口（404），请手动填写模型名",
            parseFailed = "模型列表格式无法解析，请手动填写模型名",
            tooLarge = "供应商模型列表响应过大，请手动填写模型名",
            tooMany = "供应商返回的模型数量过多，请手动填写模型名",
            idTooLong = "供应商返回的模型名称过长，请手动填写模型名",
            empty = "供应商返回的模型列表为空，请手动填写模型名",
            cannotStart = "模型列表请求无法启动",
            emptyBody = "供应商返回了空响应",
        )

        val DEEPSEEK_COPY = ModelCatalogCopy(
            missingApiKey = "请先在设置中保存 DeepSeek API Key",
            invalidApiKey = "DeepSeek API Key 格式无效",
            cancelled = "模型列表请求已取消",
            notFound = "DeepSeek 未返回模型列表（404），请稍后重试",
            parseFailed = "DeepSeek 模型列表格式无法解析，请稍后重试",
            tooLarge = "DeepSeek 模型列表响应过大，请稍后重试",
            tooMany = "DeepSeek 返回的模型数量过多，请稍后重试",
            idTooLong = "DeepSeek 返回的模型名称过长，请稍后重试",
            empty = "DeepSeek 返回的模型列表为空，请稍后重试",
            cannotStart = "DeepSeek 模型列表请求无法启动",
            emptyBody = "DeepSeek 返回了空响应",
        )
    }
}
