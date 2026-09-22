package com.muxuechat.data.remote

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * XAI-2 传输适配器。断言全部对着 `docs/XAI_WIRE_FORMAT.md` 的实测记录，
 * 未采集的场景只断言「保守兜底」，不断言任何猜出来的事件形状。
 */
class XaiResponsesTransportTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val recorded = AtomicReference<Request?>(null)
    private val recordedBody = AtomicReference<String>("")

    private fun transportReturning(sse: String, code: Int = 200): XaiResponsesTransport {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                recorded.set(request)
                recordedBody.set(
                    okio.Buffer().also { request.body?.writeTo(it) }.readUtf8(),
                )
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("synthetic")
                    .body(sse.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            }
            .build()
        return XaiResponsesTransport(client, RecordingAuthLogger(), clientVersion = "9.9.9", sessionId = SESSION)
    }

    private fun request(
        messages: List<OpenAiCompatibleMessageDto>,
        tools: List<OpenAiCompatibleToolDto>? = null,
        reasoningEffort: String? = null,
    ) = OpenAiCompatibleChatRequest(
        model = "grok-4.7",
        messages = messages,
        stream = true,
        maxTokens = 4096,
        reasoningEffort = reasoningEffort,
        tools = tools,
    )

    private fun collect(sse: String, code: Int = 200): Result<List<OpenAiCompatibleStreamEvent>> =
        runCatching {
            runBlocking {
                transportReturning(sse, code)
                    .streamChat(TOKEN, BASE_URL, request(listOf(userMessage())))
                    .toList()
            }
        }

    private fun userMessage() = OpenAiCompatibleMessageDto(role = "user", content = "你好")

    // ------------------------------------------------------------------ SSE 翻译

    @Test
    fun interleavedOutputItems_doNotMixBodyAndReasoning() {
        // 线上格式 §3.2 的实测序列：正文项还没收尾，工具调用项就插进来了。
        // 任何「记住当前 item 类型再路由」的解析器都会在这里把工具参数写进正文。
        val events = collect(INTERLEAVED_SSE).getOrThrow()
        val deltas = events.filterIsInstance<OpenAiCompatibleStreamEvent.Delta>()

        assertEquals("想一想", deltas.mapNotNull { it.reasoningContent }.joinToString(""))
        assertEquals("你好呀", deltas.mapNotNull { it.content }.joinToString(""))
        assertTrue(events.last() is OpenAiCompatibleStreamEvent.Done)
    }

    @Test
    fun completedEvent_normalizesUsageFieldNames() {
        val events = collect(INTERLEAVED_SSE).getOrThrow()
        val usage = events.filterIsInstance<OpenAiCompatibleStreamEvent.Delta>()
            .mapNotNull { it.usage }
            .single()

        // Responses 用 input/output_tokens，上层只认 prompt/completion_tokens（§4）。
        assertEquals(10576, usage.promptTokens)
        assertEquals(118, usage.completionTokens)
        assertEquals(10694, usage.totalTokens)
    }

    @Test
    fun unknownEventTypes_areIgnoredRatherThanGuessed() {
        // §3.4：截断、错误、取消、429、异常断流的事件名都未采集，不得臆测语义。
        val sse = """
            data: {"type":"response.created"}
            data: {"type":"response.some_event_we_have_never_seen","delta":"x"}
            data: {"type":"response.output_text.delta","output_index":1,"delta":"喵"}
            data: {"type":"response.completed","response":{"status":"completed"}}
        """.trimIndent()

        val deltas = collect(sse).getOrThrow().filterIsInstance<OpenAiCompatibleStreamEvent.Delta>()

        assertEquals("喵", deltas.mapNotNull { it.content }.joinToString(""))
    }

    @Test
    fun streamWithoutCompletedEvent_failsInsteadOfPretendingSuccess() {
        val sse = """
            data: {"type":"response.output_text.delta","output_index":1,"delta":"半句"}
        """.trimIndent()

        val error = collect(sse).exceptionOrNull()

        assertTrue(error is XaiResponsesTransport.XaiResponsesException)
        assertEquals("xAI 回复未正常结束，请重试", error?.message)
    }

    @Test
    fun httpErrors_mapToReadableMessagesWithoutLeakingToken() {
        listOf(401 to "重新登录", 429 to "额度", 503 to "暂时不可用").forEach { (code, fragment) ->
            val error = collect("", code).exceptionOrNull()
            assertTrue("HTTP $code", error?.message.orEmpty().contains(fragment))
            assertFalse(error?.message.orEmpty().contains(TOKEN))
        }
    }

    // ------------------------------------------------------------------ 请求翻译

    @Test
    fun requestBody_matchesCapturedResponsesShape() {
        runCatching {
            runBlocking {
                transportReturning(INTERLEAVED_SSE).streamChat(
                    TOKEN,
                    BASE_URL,
                    request(
                        listOf(
                            OpenAiCompatibleMessageDto(role = "system", content = "你是助手"),
                            userMessage(),
                            OpenAiCompatibleMessageDto(role = "assistant", content = "上一轮"),
                        ),
                        reasoningEffort = "medium",
                    ),
                ).toList()
            }
        }.getOrThrow()

        val body = json.parseToJsonElement(recordedBody.get()).jsonObject
        assertEquals("grok-4.7", body["model"]?.jsonPrimitive?.content)
        assertEquals(true, body["stream"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(false, body["store"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(4096, body["max_output_tokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals("medium", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertEquals("auto", body["reasoning"]?.jsonObject?.get("summary")?.jsonPrimitive?.content)
        // 实测没有 instructions 字段，系统提示是 input[0] 且角色为 developer、内容为纯字符串（§2）。
        assertNull(body["instructions"])
        assertNull(body["previous_response_id"])

        val input = body["input"]!!.jsonArray
        assertEquals("developer", input[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("你是助手", input[0].jsonObject["content"]?.jsonPrimitive?.content)

        assertEquals("user", input[1].jsonObject["role"]?.jsonPrimitive?.content)
        val userPart = (input[1].jsonObject["content"] as JsonArray)[0].jsonObject
        assertEquals("input_text", userPart["type"]?.jsonPrimitive?.content)
        assertEquals("你好", userPart["text"]?.jsonPrimitive?.content)

        assertEquals("message", input[2].jsonObject["type"]?.jsonPrimitive?.content)
        val assistantPart = (input[2].jsonObject["content"] as JsonArray)[0].jsonObject
        assertEquals("output_text", assistantPart["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun headers_identifyMuxueChatItselfAndVaryPerRequest() {
        collect(INTERLEAVED_SSE).getOrThrow()
        val first = recorded.get()!!
        collect(INTERLEAVED_SSE).getOrThrow()
        val second = recorded.get()!!

        assertEquals("Bearer $TOKEN", first.header("Authorization"))
        assertEquals("text/event-stream", first.header("Accept"))
        assertEquals("xai-grok-cli", first.header("X-XAI-Token-Auth"))
        // 实测证明上游接受发送方自报的身份，因此不冒充 Grok CLI（§1.1）。
        assertEquals("muxuechat", first.header("x-grok-client-identifier"))
        assertEquals("grok-4.7", first.header("x-grok-model-override"))
        // conv-id 与 session-id 同值且跨请求稳定；req-id 每请求不同。
        assertEquals(first.header("x-grok-conv-id"), first.header("x-grok-session-id"))
        assertEquals(second.header("x-grok-conv-id"), first.header("x-grok-conv-id"))
        assertTrue(first.header("x-grok-req-id") != second.header("x-grok-req-id"))
        assertTrue(first.url.toString().endsWith("/responses"))
    }

    @Test
    fun toolRequests_areRejectedBecauseResultRoundTripWasNeverCaptured() {
        val tool = OpenAiCompatibleToolDto(
            function = OpenAiCompatibleFunctionDto("f", "d", JsonObject(emptyMap())),
        )

        val error = runCatching {
            runBlocking {
                transportReturning(INTERLEAVED_SSE)
                    .streamChat(TOKEN, BASE_URL, request(listOf(userMessage()), tools = listOf(tool)))
                    .toList()
            }
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("暂不支持工具调用"))
    }

    // ------------------------------------------------------------------ 非流式语义

    @Test
    fun completeChat_aggregatesTheStreamBecauseNonStreamShapeWasNeverCaptured() = runBlocking {
        val chunk = transportReturning(INTERLEAVED_SSE)
            .completeChat(TOKEN, BASE_URL, request(listOf(userMessage())))

        val message = chunk.choices.single().message!!
        assertEquals("你好呀", message.content)
        assertEquals("想一想", message.reasoningContent)
        assertEquals("stop", chunk.choices.single().finishReason)
        assertEquals(10576, chunk.usage?.promptTokens)
        // 聚合走的是同一条流式请求，请求体里 stream 恒为 true。
        assertEquals(
            true,
            json.parseToJsonElement(recordedBody.get()).jsonObject["stream"]?.jsonPrimitive?.content?.toBoolean(),
        )
    }

    private companion object {
        const val TOKEN = "synthetic-xai-access-token"
        const val BASE_URL = "https://cli-chat-proxy.grok.com/v1"
        const val SESSION = "11111111-2222-3333-4444-555555555555"

        /**
         * 按线上格式 §3.2 的实测顺序构造：reasoning 先收尾，正文项 output_index=1 被
         * function_call 项插队，正文的 output_text.done 反而排在工具项之后。
         */
        val INTERLEAVED_SSE = """
            data: {"type":"response.created"}
            data: {"type":"response.output_item.added","output_index":0,"item":{"id":"rs_1","type":"reasoning"}}
            data: {"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"想一"}
            data: {"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"想"}
            data: {"type":"response.output_item.done","output_index":0}
            data: {"type":"response.output_item.added","output_index":1,"item":{"id":"msg_1","type":"message"}}
            data: {"type":"response.content_part.added","output_index":1}
            data: {"type":"response.output_text.delta","output_index":1,"delta":"你好"}
            data: {"type":"response.output_item.added","output_index":2,"item":{"id":"fc_1","type":"function_call","name":"read","call_id":"call-abc-1"}}
            data: {"type":"response.function_call_arguments.delta","output_index":2,"delta":"{\"path\":"}
            data: {"type":"response.function_call_arguments.done","output_index":2,"arguments":"{\"path\":\"a\"}"}
            data: {"type":"response.output_item.done","output_index":2}
            data: {"type":"response.output_text.delta","output_index":1,"delta":"呀"}
            data: {"type":"response.output_text.done","output_index":1,"text":"你好呀"}
            data: {"type":"response.content_part.done","output_index":1}
            data: {"type":"response.output_item.done","output_index":1}
            data: {"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":10576,"output_tokens":118,"total_tokens":10694}}}
        """.trimIndent()
    }
}
