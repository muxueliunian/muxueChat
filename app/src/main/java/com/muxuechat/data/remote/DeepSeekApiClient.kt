package com.muxuechat.data.remote

import android.util.Log
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DeepSeekApiClient(
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    },
    private val parser: DeepSeekStreamParser = DeepSeekStreamParser(json),
) {
    /** 正文流与 RP 后台补写可能短暂重叠；停止时必须全部取消。 */
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()

    fun streamChat(
        apiKey: String,
        baseUrl: String,
        requestDto: DeepSeekChatRequest,
    ): Flow<DeepSeekStreamEvent> = callbackFlow {
        val requestUrl = "${baseUrl.normalizedBaseUrl()}/chat/completions"
        Log.i(TAG, "Starting DeepSeek stream request: model=${requestDto.model}")
        val body = json.encodeToString(requestDto).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(requestUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val call = okHttpClient.newCall(request)
        activeCalls.add(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) {
                    close(kotlinx.coroutines.CancellationException("DeepSeek 请求已取消"))
                } else {
                    Log.w(TAG, "DeepSeek stream request failed: ${e.javaClass.simpleName}")
                    close(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        Log.w(TAG, "DeepSeek stream HTTP ${it.code}")
                        close(IOException("DeepSeek API returned HTTP ${it.code}"))
                        return
                    }

                    val source = it.body?.source()
                    if (source == null) {
                        close(IOException("DeepSeek API returned empty body"))
                        return
                    }

                    try {
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            val event = parser.parseLine(line) ?: continue
                            Log.d(TAG, "DeepSeek stream event: ${event.javaClass.simpleName}")
                            trySend(event)
                            if (event == DeepSeekStreamEvent.Done) break
                        }
                        Log.i(TAG, "DeepSeek stream request finished")
                        close()
                    } catch (error: Throwable) {
                        if (call.isCanceled()) {
                            close(kotlinx.coroutines.CancellationException("DeepSeek 请求已取消"))
                        } else {
                            Log.w(TAG, "DeepSeek stream parsing failed: ${error.javaClass.simpleName}")
                            close(error)
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

    suspend fun completeChat(
        apiKey: String,
        baseUrl: String,
        requestDto: DeepSeekChatRequest,
    ): DeepSeekChatChunkDto = withContext(Dispatchers.IO) {
        val requestUrl = "${baseUrl.normalizedBaseUrl()}/chat/completions"
        Log.i(TAG, "Starting DeepSeek non-stream request: model=${requestDto.model}")
        val body = json.encodeToString(requestDto).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(requestUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val call = okHttpClient.newCall(request)
        activeCalls.add(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "DeepSeek non-stream HTTP ${response.code}")
                    throw IOException("DeepSeek API returned HTTP ${response.code}")
                }

                val responseBody = response.body?.string()
                    ?: throw IOException("DeepSeek API returned empty body")
                Log.i(TAG, "DeepSeek non-stream request finished")
                json.decodeFromString<DeepSeekChatChunkDto>(responseBody)
            }
        } catch (error: IOException) {
            if (call.isCanceled()) {
                throw kotlinx.coroutines.CancellationException("DeepSeek 请求已取消")
            }
            throw error
        } finally {
            activeCalls.remove(call)
        }
    }

    fun cancelActiveStream() {
        activeCalls.toList().forEach { call ->
            call.cancel()
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        private const val TAG = "muxueChat.DeepSeek"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun String.normalizedBaseUrl(): String =
        trim().ifBlank { DEFAULT_BASE_URL }.trimEnd('/')
}
