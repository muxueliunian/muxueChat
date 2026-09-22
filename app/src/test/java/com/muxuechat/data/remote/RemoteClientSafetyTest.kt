package com.muxuechat.data.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

class RemoteClientSafetyTest {
    @Test
    fun deepSeekKey_isTrimmedAndControlCharactersAreRejectedWithoutEcho() = runBlocking {
        val observedRequest = AtomicReference<Request>()
        val client = DeepSeekApiClient(
            clientReturning(
                bodyFactory = { VALID_CHAT_RESPONSE.toResponseBody(JSON_MEDIA_TYPE) },
                requestObserver = observedRequest::set,
            ),
            NO_OP_LOGGER,
        )

        client.completeChat("  $SYNTHETIC_KEY  ", BASE_URL, deepSeekRequest(stream = false))

        assertEquals("Bearer $SYNTHETIC_KEY", observedRequest.get().header("Authorization"))

        val unsafeKey = "prefix\r\n$SENSITIVE_MARKER"
        val error = runCatching {
            client.completeChat(unsafeKey, BASE_URL, deepSeekRequest(stream = false))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("DeepSeek API Key 格式无效", error?.message)
        assertFalse(error?.message.orEmpty().contains(SENSITIVE_MARKER))
    }

    @Test
    fun clientLogs_doNotContainModelKeyOrStreamPayload_andOnlyAggregateEvents() = runBlocking {
        val deepSeekLogger = RecordingLogger()
        val deepSeekClient = DeepSeekApiClient(
            clientReturning {
                deepSeekSseBody(SENSITIVE_MARKER).toResponseBody(EVENT_STREAM_MEDIA_TYPE)
            },
            deepSeekLogger,
        )
        val openAiLogger = RecordingLogger()
        val openAiClient = OpenAiCompatibleClient(
            clientReturning { VALID_CHAT_RESPONSE.toResponseBody(JSON_MEDIA_TYPE) },
            openAiLogger,
        )

        deepSeekClient.streamChat(
            apiKey = SYNTHETIC_KEY,
            baseUrl = BASE_URL,
            requestDto = deepSeekRequest(stream = true, model = SENSITIVE_MARKER),
        ).toList()
        openAiClient.completeChat(
            apiKey = SYNTHETIC_KEY,
            baseUrl = BASE_URL,
            requestDto = openAiRequest(stream = false, model = SENSITIVE_MARKER),
        )

        val allLogs = deepSeekLogger.allMessages() + openAiLogger.allMessages()
        assertTrue(deepSeekLogger.debugMessages.isEmpty())
        assertTrue(deepSeekLogger.infoMessages.any { it.contains("events=2") })
        assertFalse(allLogs.any { it.contains(SENSITIVE_MARKER) })
        assertFalse(allLogs.any { it.contains(SYNTHETIC_KEY) })
    }

    @Test
    fun deepSeekMalformedResponse_isFixedSerializationErrorWithoutRawCause() = runBlocking {
        val logger = RecordingLogger()
        val client = DeepSeekApiClient(
            clientReturning {
                MALFORMED_SENSITIVE_RESPONSE.toResponseBody(JSON_MEDIA_TYPE)
            },
            logger,
        )

        val error = runCatching {
            client.completeChat(SYNTHETIC_KEY, BASE_URL, deepSeekRequest(stream = false))
        }.exceptionOrNull()

        assertTrue(error is SerializationException)
        assertEquals("DeepSeek response could not be parsed", error?.message)
        assertNull(error?.cause)
        assertFalse(error?.message.orEmpty().contains(SENSITIVE_MARKER))
        assertFalse(logger.allMessages().any { it.contains(SENSITIVE_MARKER) })
        assertEquals(0, activeCallCount(client, DeepSeekApiClient::class.java))
    }

    @Test
    fun streamSourceFailure_closesBothFlowsWithoutLeakingRawException() = runBlocking {
        val deepSeekClient = DeepSeekApiClient(
            clientReturning { throwingSourceBody(EVENT_STREAM_MEDIA_TYPE) },
            NO_OP_LOGGER,
        )
        val openAiClient = OpenAiCompatibleClient(
            clientReturning { throwingSourceBody(EVENT_STREAM_MEDIA_TYPE) },
            NO_OP_LOGGER,
        )

        val deepSeekError = runCatching {
            deepSeekClient.streamChat(
                SYNTHETIC_KEY,
                BASE_URL,
                deepSeekRequest(stream = true),
            ).toList()
        }.exceptionOrNull()
        val openAiError = runCatching {
            openAiClient.streamChat(
                SYNTHETIC_KEY,
                BASE_URL,
                openAiRequest(stream = true),
            ).toList()
        }.exceptionOrNull()

        assertTrue(deepSeekError is SerializationException)
        assertEquals("DeepSeek response could not be parsed", deepSeekError?.message)
        assertNull(deepSeekError?.cause)
        assertFalse(deepSeekError?.message.orEmpty().contains(SENSITIVE_MARKER))
        assertEquals("供应商返回内容无法解析", openAiError?.message)
        assertNull(openAiError?.cause)
        assertFalse(openAiError?.message.orEmpty().contains(SENSITIVE_MARKER))
        awaitNoActiveCalls(deepSeekClient, DeepSeekApiClient::class.java)
        awaitNoActiveCalls(openAiClient, OpenAiCompatibleClient::class.java)
    }

    @Test
    fun oversizedSseLine_isRejectedAndCallsAreReleased() = runBlocking {
        val oversizedLine = "data: " + "x".repeat((DeepSeekApiClient.MAX_SSE_LINE_BYTES + 1L).toInt())
        val deepSeekClient = DeepSeekApiClient(
            clientReturning { oversizedLine.toResponseBody(EVENT_STREAM_MEDIA_TYPE) },
            NO_OP_LOGGER,
        )
        val openAiClient = OpenAiCompatibleClient(
            clientReturning { oversizedLine.toResponseBody(EVENT_STREAM_MEDIA_TYPE) },
            NO_OP_LOGGER,
        )

        val deepSeekError = runCatching {
            deepSeekClient.streamChat(
                SYNTHETIC_KEY,
                BASE_URL,
                deepSeekRequest(stream = true),
            ).toList()
        }.exceptionOrNull()
        val openAiError = runCatching {
            openAiClient.streamChat(
                SYNTHETIC_KEY,
                BASE_URL,
                openAiRequest(stream = true),
            ).toList()
        }.exceptionOrNull()

        assertEquals("DeepSeek response exceeded safe size limit", deepSeekError?.message)
        assertEquals("供应商流式响应单行过大，已停止解析", openAiError?.message)
        awaitNoActiveCalls(deepSeekClient, DeepSeekApiClient::class.java)
        awaitNoActiveCalls(openAiClient, OpenAiCompatibleClient::class.java)
    }

    @Test
    fun oversizedNonStreamBodies_areRejectedBeforeAllocationAndCallsAreReleased() = runBlocking {
        val deepSeekClient = DeepSeekApiClient(
            clientReturning {
                declaredLengthBody(
                    DeepSeekApiClient.MAX_NON_STREAM_RESPONSE_BYTES + 1L,
                    VALID_CHAT_RESPONSE,
                )
            },
            NO_OP_LOGGER,
        )
        val openAiClient = OpenAiCompatibleClient(
            clientReturning {
                declaredLengthBody(
                    OpenAiCompatibleClient.MAX_NON_STREAM_RESPONSE_BYTES + 1L,
                    VALID_CHAT_RESPONSE,
                )
            },
            NO_OP_LOGGER,
        )

        val deepSeekError = runCatching {
            deepSeekClient.completeChat(
                SYNTHETIC_KEY,
                BASE_URL,
                deepSeekRequest(stream = false),
            )
        }.exceptionOrNull()
        val openAiError = runCatching {
            openAiClient.completeChat(
                SYNTHETIC_KEY,
                BASE_URL,
                openAiRequest(stream = false),
            )
        }.exceptionOrNull()

        assertEquals("DeepSeek response exceeded safe size limit", deepSeekError?.message)
        assertEquals("供应商响应过大，已停止解析", openAiError?.message)
        assertEquals(0, activeCallCount(deepSeekClient, DeepSeekApiClient::class.java))
        assertEquals(0, activeCallCount(openAiClient, OpenAiCompatibleClient::class.java))
    }

    @Test
    fun modelList_enforcesBodyCountAndIdLimits() = runBlocking {
        val oversizedBodyClient = OpenAiCompatibleClient(
            clientReturning {
                declaredLengthBody(
                    OpenAiCompatibleClient.MAX_MODELS_RESPONSE_BYTES + 1L,
                    VALID_MODELS_RESPONSE,
                )
            },
            NO_OP_LOGGER,
        )
        val tooManyModels = (0..OpenAiCompatibleClient.MAX_MODEL_COUNT).joinToString(",") { index ->
            "{\"id\":\"model-$index\"}"
        }
        val tooManyClient = OpenAiCompatibleClient(
            clientReturning {
                "{\"data\":[$tooManyModels]}".toResponseBody(JSON_MEDIA_TYPE)
            },
            NO_OP_LOGGER,
        )
        val longId = "m".repeat(OpenAiCompatibleClient.MAX_MODEL_ID_LENGTH + 1)
        val longIdClient = OpenAiCompatibleClient(
            clientReturning {
                "{\"data\":[{\"id\":\"$longId\"}]}".toResponseBody(JSON_MEDIA_TYPE)
            },
            NO_OP_LOGGER,
        )

        val bodyError = runCatching {
            oversizedBodyClient.listModels(SYNTHETIC_KEY, BASE_URL)
        }.exceptionOrNull()
        val countError = runCatching {
            tooManyClient.listModels(SYNTHETIC_KEY, BASE_URL)
        }.exceptionOrNull()
        val idError = runCatching {
            longIdClient.listModels(SYNTHETIC_KEY, BASE_URL)
        }.exceptionOrNull()

        assertEquals("供应商模型列表响应过大，请手动填写模型名", bodyError?.message)
        assertEquals("供应商返回的模型数量过多，请手动填写模型名", countError?.message)
        assertEquals("供应商返回的模型名称过长，请手动填写模型名", idError?.message)

        val deepSeekTooManyClient = DeepSeekApiClient(
            clientReturning { "{\"data\":[$tooManyModels]}".toResponseBody(JSON_MEDIA_TYPE) },
            NO_OP_LOGGER,
        )
        val deepSeekError = runCatching {
            deepSeekTooManyClient.listModels(SYNTHETIC_KEY, BASE_URL)
        }.exceptionOrNull()
        assertEquals("DeepSeek 返回的模型数量过多，请稍后重试", deepSeekError?.message)
    }

    @Test
    fun deepSeekModelList_sendsBearerGetAndDoesNotLogSecrets() = runBlocking {
        val observedRequest = AtomicReference<Request>()
        val logger = RecordingLogger()
        val client = DeepSeekApiClient(
            clientReturning(
                bodyFactory = { VALID_DEEPSEEK_MODELS_RESPONSE.toResponseBody(JSON_MEDIA_TYPE) },
                requestObserver = observedRequest::set,
            ),
            logger,
        )

        val models = client.listModels("  $SYNTHETIC_KEY  ", BASE_URL)

        assertEquals(
            listOf("deepseek-v4-flash", "deepseek-v4-flash-vision-exp", "deepseek-v4-pro"),
            models,
        )
        assertEquals("GET", observedRequest.get().method)
        assertTrue(observedRequest.get().url.toString().endsWith("/models"))
        assertEquals("Bearer $SYNTHETIC_KEY", observedRequest.get().header("Authorization"))
        assertFalse(logger.allMessages().any { it.contains(SYNTHETIC_KEY) })
    }

    @Test
    fun openAiHttpErrors_surfaceStructuredProviderCodeAndMessageForBothChatModes() = runBlocking {
        val errorBody =
            """{"error":{"code":"1214","message":"reasoning_effort 参数非法"}}"""
        val nonStreamClient = OpenAiCompatibleClient(
            clientReturning(statusCode = 400) { errorBody.toResponseBody(JSON_MEDIA_TYPE) },
            NO_OP_LOGGER,
        )
        val streamClient = OpenAiCompatibleClient(
            clientReturning(statusCode = 400) { errorBody.toResponseBody(JSON_MEDIA_TYPE) },
            NO_OP_LOGGER,
        )

        val nonStreamError = runCatching {
            nonStreamClient.completeChat(
                SYNTHETIC_KEY,
                BASE_URL,
                openAiRequest(stream = false),
            )
        }.exceptionOrNull()
        val streamError = runCatching {
            streamClient.streamChat(
                SYNTHETIC_KEY,
                BASE_URL,
                openAiRequest(stream = true),
            ).toList()
        }.exceptionOrNull()

        val expected = "供应商请求失败（HTTP 400，错误码 1214）：reasoning_effort 参数非法"
        assertEquals(expected, nonStreamError?.message)
        assertEquals(expected, streamError?.message)
        assertEquals(0, activeCallCount(nonStreamClient, OpenAiCompatibleClient::class.java))
        awaitNoActiveCalls(streamClient, OpenAiCompatibleClient::class.java)
    }

    @Test
    fun openAiHttpErrors_redactCredentialsAndIgnoreUnstructuredBodies() = runBlocking {
        val logger = RecordingLogger()
        val structuredClient = OpenAiCompatibleClient(
            clientReturning(statusCode = 400) {
                """
                {
                  "error": {
                    "code": 1214,
                    "message": "Authorization: Bearer $SENSITIVE_MARKER key=$SYNTHETIC_KEY\n${"x".repeat(400)}"
                  }
                }
                """.trimIndent().toResponseBody(JSON_MEDIA_TYPE)
            },
            logger,
        )
        val unstructuredClient = OpenAiCompatibleClient(
            clientReturning(statusCode = 400) {
                "gateway rejected $SENSITIVE_MARKER".toResponseBody(JSON_MEDIA_TYPE)
            },
            logger,
        )

        val structuredError = runCatching {
            structuredClient.completeChat(SYNTHETIC_KEY, BASE_URL, openAiRequest(stream = false))
        }.exceptionOrNull()
        val unstructuredError = runCatching {
            unstructuredClient.completeChat(SYNTHETIC_KEY, BASE_URL, openAiRequest(stream = false))
        }.exceptionOrNull()

        assertTrue(structuredError?.message.orEmpty().contains("错误码 1214"))
        assertTrue(structuredError?.message.orEmpty().contains("<已隐藏>"))
        assertFalse(structuredError?.message.orEmpty().contains(SYNTHETIC_KEY))
        assertFalse(structuredError?.message.orEmpty().contains(SENSITIVE_MARKER))
        assertTrue(structuredError?.message.orEmpty().length < 400)
        assertEquals("供应商请求失败（HTTP 400）", unstructuredError?.message)
        assertFalse(logger.allMessages().any { it.contains(SYNTHETIC_KEY) || it.contains(SENSITIVE_MARKER) })
    }

    private suspend fun awaitNoActiveCalls(instance: Any, type: Class<*>) {
        withTimeout(5_000) {
            while (activeCallCount(instance, type) != 0) delay(1)
        }
    }

    private fun activeCallCount(instance: Any, type: Class<*>): Int {
        val field = type.getDeclaredField("activeCalls").apply { isAccessible = true }
        return (field.get(instance) as Set<*>).size
    }

    private fun clientReturning(
        requestObserver: ((Request) -> Unit)? = null,
        statusCode: Int = 200,
        bodyFactory: () -> ResponseBody,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            requestObserver?.invoke(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(statusCode)
                .message("test response")
                .body(bodyFactory())
                .build()
        }
        .build()

    private fun declaredLengthBody(declaredLength: Long, content: String): ResponseBody =
        object : ResponseBody() {
            private val source = Buffer().writeUtf8(content)

            override fun contentType(): MediaType = JSON_MEDIA_TYPE

            override fun contentLength(): Long = declaredLength

            override fun source(): BufferedSource = source
        }

    private fun throwingSourceBody(contentType: MediaType): ResponseBody =
        object : ResponseBody() {
            override fun contentType(): MediaType = contentType

            override fun contentLength(): Long = -1L

            override fun source(): BufferedSource {
                throw IllegalStateException("$SENSITIVE_MARKER Authorization Bearer raw-secret")
            }
        }

    private fun deepSeekRequest(
        stream: Boolean,
        model: String = "deepseek-v4-flash",
    ) = DeepSeekChatRequest(
        model = model,
        messages = listOf(DeepSeekMessageDto(role = "user", content = "test")),
        stream = stream,
    )

    private fun openAiRequest(
        stream: Boolean,
        model: String = "test-model",
    ) = OpenAiCompatibleChatRequest(
        model = model,
        messages = listOf(OpenAiCompatibleMessageDto(role = "user", content = "test")),
        stream = stream,
    )

    private fun deepSeekSseBody(content: String): String =
        "data: {\"choices\":[{\"delta\":{\"content\":\"$content\"}}]}\n\n" +
            "data: [DONE]\n\n"

    private class RecordingLogger : RemoteClientLogger {
        val debugMessages: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val infoMessages: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val warnMessages: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun debug(message: String) {
            debugMessages += message
        }

        override fun info(message: String) {
            infoMessages += message
        }

        override fun warn(message: String) {
            warnMessages += message
        }

        fun allMessages(): List<String> = debugMessages + infoMessages + warnMessages
    }

    private companion object {
        const val BASE_URL = "https://example.test/v1"
        const val SYNTHETIC_KEY = "synthetic-test-key"
        const val SENSITIVE_MARKER = "sensitive-marker-9f17"
        const val VALID_CHAT_RESPONSE =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}"
        const val VALID_MODELS_RESPONSE = "{\"data\":[{\"id\":\"test-model\"}]}"
        const val VALID_DEEPSEEK_MODELS_RESPONSE =
            "{\"data\":[{\"id\":\"deepseek-v4-pro\"},{\"id\":\"deepseek-v4-flash\"}," +
                "{\"id\":\"deepseek-v4-flash-vision-exp\"}]}"
        const val MALFORMED_SENSITIVE_RESPONSE =
            "not-json $SENSITIVE_MARKER Authorization Bearer raw-secret https://private.example/path"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val EVENT_STREAM_MEDIA_TYPE = "text/event-stream; charset=utf-8".toMediaType()
        val NO_OP_LOGGER = object : RemoteClientLogger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
        }
    }
}
