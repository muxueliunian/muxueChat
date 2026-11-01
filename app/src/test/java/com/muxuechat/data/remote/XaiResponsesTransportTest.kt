package com.muxuechat.data.remote

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

    // ------------------------------------------------------------------ 图片（MULTIMODAL_PLAN 1.2 / 3.4）

    @Test
    fun textOnlyUserMessage_keepsSingleInputTextBytes() {
        collect(INTERLEAVED_SSE).getOrThrow()
        // 与引入图片前完全相同：单个 input_text，不带 type 字段之外的任何东西。
        assertTrue(
            recordedBody.get(),
            recordedBody.get().contains("""{"role":"user","content":[{"type":"input_text","text":"你好"}]}"""),
        )
        assertFalse(recordedBody.get().contains("input_image"))
    }

    @Test
    fun userImages_becomeInputImageWithDataUrlString_inOrder_withoutDetail() {
        val dir = java.nio.file.Files.createTempDirectory("xai-image").toFile()
        try {
            val firstBytes = ByteArray(1000) { it.toByte() }
            val secondBytes = byteArrayOf(7, 8)
            val first = ImagePayload(java.io.File(dir, "a.jpg").apply { writeBytes(firstBytes) }, "image/jpeg")
            val second = ImagePayload(java.io.File(dir, "b.jpg").apply { writeBytes(secondBytes) }, "image/jpeg")
            runBlocking {
                transportReturning(INTERLEAVED_SSE).streamChat(
                    TOKEN,
                    BASE_URL,
                    request(listOf(OpenAiCompatibleMessageDto(role = "user", content = "看看", images = listOf(first, second)))),
                ).toList()
            }

            val parts = json.parseToJsonElement(recordedBody.get()).jsonObject["input"]!!.jsonArray[0]
                .jsonObject["content"]!!.jsonArray
            assertEquals(listOf("input_text", "input_image", "input_image"), parts.map { it.jsonObject["type"]!!.jsonPrimitive.content })
            assertEquals("看看", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
            listOf(firstBytes, secondBytes).forEachIndexed { index, bytes ->
                val imageUrl = parts[index + 1].jsonObject["image_url"]
                // xAI 的 image_url 是字符串，不是 OpenAI 的 {"url": …} 对象。
                assertTrue(imageUrl is JsonPrimitive && imageUrl.isString)
                assertEquals(
                    "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(bytes),
                    imageUrl!!.jsonPrimitive.content,
                )
                assertNull(parts[index + 1].jsonObject["detail"])
            }
            // 占位符不会漏到线上。
            assertFalse(recordedBody.get().contains(first.placeholder))
            assertEquals(false, json.parseToJsonElement(recordedBody.get()).jsonObject["store"]?.jsonPrimitive?.content?.toBoolean())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun imageOnlyUserMessage_hasNoEmptyInputText() {
        val dir = java.nio.file.Files.createTempDirectory("xai-image-only").toFile()
        try {
            val image = ImagePayload(java.io.File(dir, "only.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }, "image/jpeg")
            runBlocking {
                transportReturning(INTERLEAVED_SSE).streamChat(
                    TOKEN,
                    BASE_URL,
                    request(listOf(OpenAiCompatibleMessageDto(role = "user", content = "", images = listOf(image)))),
                ).toList()
            }

            val parts = json.parseToJsonElement(recordedBody.get()).jsonObject["input"]!!.jsonArray[0]
                .jsonObject["content"]!!.jsonArray
            assertEquals(1, parts.size)
            assertEquals("input_image", parts.single().jsonObject["type"]!!.jsonPrimitive.content)
        } finally {
            dir.deleteRecursively()
        }
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
    fun clientVersionHeader_isAlwaysThreePartSemver() {
        // 实测：上游对两段版本号解析失败，会以 HTTP 426 回「version (1.0) is outdated」，
        // 尽管 1.0 语义上大于它要求的 0.1.202。只补格式，不谎报版本。
        assertEquals("1.0.0", XaiResponsesTransport.toSemver("1.0"))
        assertEquals("2.0.0", XaiResponsesTransport.toSemver("2"))
        assertEquals("1.6.0", XaiResponsesTransport.toSemver("1.6.0"))
        assertEquals("1.2.3", XaiResponsesTransport.toSemver("1.2.3.4"))
        assertEquals("1.2.3", XaiResponsesTransport.toSemver("1.2.3-beta"))
        assertEquals("1.0.0", XaiResponsesTransport.toSemver(""))
        assertEquals("1.0.0", XaiResponsesTransport.toSemver("   "))

        collect(INTERLEAVED_SSE).getOrThrow()
        assertEquals("9.9.9", recorded.get()!!.header("x-grok-client-version"))
    }

    // ------------------------------------------------------------------ 评审修复项

    @Test
    fun malformedEvent_failsInsteadOfBeingTreatedAsUnknownType() {
        // 「合法但没见过的类型」可以忽略；「解析不了」不行——否则残缺正文会照样成功落库。
        val sse = """
            data: {"type":"response.output_text.delta","output_index":1,"delta":"前半"}
            data: {"type":"response.output_text.delta","delta":
            data: {"type":"response.completed","response":{"status":"completed"}}
        """.trimIndent()

        val error = collect(sse).exceptionOrNull()

        assertTrue(error is XaiResponsesTransport.XaiResponsesException)
        assertEquals("xAI 返回内容无法解析", error?.message)
    }

    @Test
    fun concurrentMessageItems_failRatherThanInterleaveText() {
        // 按事件名路由解决了跨类型串台，解决不了同类型的顺序。
        // 两个并发正文项的合并语义没有采集过，宁可失败也不拼出顺序不明的正文。
        val sse = """
            data: {"type":"response.output_item.added","output_index":1,"item":{"id":"msg_1","type":"message"}}
            data: {"type":"response.output_text.delta","output_index":1,"delta":"A1"}
            data: {"type":"response.output_item.added","output_index":2,"item":{"id":"msg_2","type":"message"}}
            data: {"type":"response.completed","response":{"status":"completed"}}
        """.trimIndent()

        val error = collect(sse).exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("多段并行正文"))
    }

    @Test
    fun completedWithUnknownStatus_isNotTreatedAsSuccess() {
        // 只承认实测过的 "completed"。其它 status 的含义（截断？失败？）未采集。
        val sse = """
            data: {"type":"response.output_text.delta","output_index":1,"delta":"半句"}
            data: {"type":"response.completed","response":{"status":"incomplete"}}
        """.trimIndent()

        val error = collect(sse).exceptionOrNull()

        assertEquals("xAI 回复未正常结束，请重试", error?.message)
    }

    @Test
    fun completedWithoutResponseOrStatus_isNotTreatedAsSuccess() {
        listOf(
            """data: {"type":"response.completed"}""",
            """data: {"type":"response.completed","response":{}}""",
            """data: {"type":"response.completed","response":{"status":null}}""",
        ).forEach { sse ->
            val error = collect(sse).exceptionOrNull()
            assertTrue(error is XaiResponsesTransport.XaiResponsesException)
            assertEquals("xAI 回复未正常结束，请重试", error?.message)
        }
    }

    @Test
    fun nonPinnedAddress_isRejectedBeforeAnyCredentialLeavesTheApp() {
        // 冻结器已经钉死地址，但本类是唯一附加 Authorization 的地方，自己再挡一道。
        val error = runCatching {
            runBlocking {
                transportReturning(INTERLEAVED_SSE)
                    .streamChat(TOKEN, "https://evil.example/v1", request(listOf(userMessage())))
                    .toList()
            }
        }.exceptionOrNull()

        assertEquals("xAI 服务地址不合法，已中止请求", error?.message)
        assertNull("凭据不得离开 App", recorded.get())
    }

    @Test
    fun unauthorized_isDistinctSoCallerCanRefreshAndRetry() {
        val error = collect("", 401).exceptionOrNull()
        assertTrue(error is XaiResponsesTransport.XaiUnauthorizedException)
    }

    @Test
    fun unsupportedSamplingParams_areReportedRatherThanSilentlyDropped() {
        val logger = RecordingAuthLogger()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("synthetic")
                    .body(INTERLEAVED_SSE.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            }
            .build()
        runBlocking {
            XaiResponsesTransport(client, logger).streamChat(
                TOKEN,
                BASE_URL,
                OpenAiCompatibleChatRequest(
                    model = "grok-4.7",
                    messages = listOf(userMessage()),
                    stream = true,
                    temperature = 0.7,
                    topP = 0.9,
                ),
            ).toList()
        }

        val warning = logger.messages.single { it.contains("sampling params") }
        assertTrue(warning.contains("temperature"))
        assertTrue(warning.contains("top_p"))
        // 只记字段名，不记取值。
        assertFalse(warning.contains("0.7"))
    }

    // ------------------------------------------------------------------ 非流式语义

    @Test
    fun completeChat_aggregatesTheStreamBecauseNonStreamShapeWasNeverCaptured() = runBlocking {
        val chunk = transportReturning(TEXT_ONLY_SSE)
            .completeChat(TOKEN, BASE_URL, request(listOf(userMessage())))

        val message = chunk.choices.single().message!!
        assertEquals("你好呀", message.content)
        assertEquals("想一想", message.reasoningContent)
        assertNull(message.toolCalls)
        assertEquals("stop", chunk.choices.single().finishReason)
        assertEquals(10576, chunk.usage?.promptTokens)
        // 聚合走的是同一条流式请求，请求体里 stream 恒为 true。
        assertEquals(
            true,
            json.parseToJsonElement(recordedBody.get()).jsonObject["stream"]?.jsonPrimitive?.content?.toBoolean(),
        )
    }

    // ------------------------------------------------------------------ 工具调用（XAI-5）

    private fun toolDto(name: String = "create_role_card") = OpenAiCompatibleToolDto(
        function = OpenAiCompatibleFunctionDto(
            name = name,
            description = "创建角色卡草稿",
            parameters = json.parseToJsonElement("""{"type":"object","properties":{}}""").jsonObject,
        ),
    )

    private fun toolCallsOf(events: List<OpenAiCompatibleStreamEvent>): List<OpenAiCompatibleToolCallDto> =
        OpenAiCompatibleToolCallAccumulator().apply {
            events.filterIsInstance<OpenAiCompatibleStreamEvent.Delta>().forEach { accept(it.toolCalls) }
        }.build()

    @Test
    fun functionCall_isHandedOverOnceWithAuthoritativeArguments() {
        // INTERLEAVED_SSE 里 delta 只到 {"path":，.done 才是全文。上层累加器只会追加，
        // 若把 delta 透传下去再补全文，参数就会拼成两份。
        val events = collect(INTERLEAVED_SSE).getOrThrow()
        val call = toolCallsOf(events).single()

        assertEquals("call-abc-1", call.id)
        assertEquals("read", call.function?.name)
        assertEquals("""{"path":"a"}""", call.function?.arguments)
        val finish = events.filterIsInstance<OpenAiCompatibleStreamEvent.Delta>().mapNotNull { it.finishReason }
        assertEquals(listOf("tool_calls"), finish)
    }

    @Test
    fun capturedThreeCallRound_keepsOrderAndArgumentsPerOutputIndex() {
        // 线上格式 §3.2 的 004 序列：第三个调用的参数分片跨过了正文项收尾，
        // .done 甚至排在正文 output_item.done 之后。
        val events = collect(THREE_CALL_SSE).getOrThrow()
        val calls = toolCallsOf(events)

        assertEquals(listOf("call-u-0", "call-u-1", "call-u-2"), calls.map { it.id })
        assertEquals(listOf("list_dir", "bash", "bash"), calls.map { it.function?.name })
        assertEquals(
            listOf("""{"p":"."}""", """{"c":"ls"}""", """{"c":"pwd"}"""),
            calls.map { it.function?.arguments },
        )
        assertEquals(
            "前言",
            events.filterIsInstance<OpenAiCompatibleStreamEvent.Delta>().mapNotNull { it.content }.joinToString(""),
        )
    }

    @Test
    fun argumentsFallBackToDoneItemThenToAccumulatedDeltas() {
        val sse = """
            data: {"type":"response.output_item.added","output_index":0,"item":{"id":"fc_0","type":"function_call","name":"a","call_id":"call-x-0","arguments":""}}
            data: {"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"k\":"}
            data: {"type":"response.output_item.done","output_index":0,"item":{"id":"fc_0","type":"function_call","name":"a","call_id":"call-x-0","arguments":"{\"k\":1}"}}
            data: {"type":"response.output_item.added","output_index":1,"item":{"id":"fc_1","type":"function_call","name":"b","call_id":"call-x-1","arguments":""}}
            data: {"type":"response.function_call_arguments.delta","output_index":1,"delta":"{\"k\":"}
            data: {"type":"response.function_call_arguments.delta","output_index":1,"delta":"2}"}
            data: {"type":"response.output_item.done","output_index":1}
            data: {"type":"response.completed","response":{"status":"completed"}}
        """.trimIndent()

        val calls = toolCallsOf(collect(sse).getOrThrow())

        assertEquals(listOf("""{"k":1}""", """{"k":2}"""), calls.map { it.function?.arguments })
    }

    @Test
    fun functionCallNotFinishedBeforeCompleted_failsInsteadOfDroppingTheCall() {
        val sse = """
            data: {"type":"response.output_item.added","output_index":0,"item":{"id":"fc_0","type":"function_call","name":"a","call_id":"call-x-0"}}
            data: {"type":"response.function_call_arguments.delta","output_index":0,"delta":"{"}
            data: {"type":"response.completed","response":{"status":"completed"}}
        """.trimIndent()

        assertEquals("xAI 回复未正常结束，请重试", collect(sse).exceptionOrNull()?.message)
    }

    @Test
    fun functionCallWithoutCallId_failsBecauseItsResultCouldNeverBeReturned() {
        val sse = """
            data: {"type":"response.output_item.added","output_index":0,"item":{"id":"fc_0","type":"function_call","name":"a"}}
            data: {"type":"response.output_item.done","output_index":0}
            data: {"type":"response.completed","response":{"status":"completed"}}
        """.trimIndent()

        assertEquals("xAI 返回的工具调用不完整，请重试", collect(sse).exceptionOrNull()?.message)
    }

    @Test
    fun argumentsForUnknownItem_failAsUnparseable() {
        val sse = """
            data: {"type":"response.function_call_arguments.delta","output_index":7,"delta":"{"}
            data: {"type":"response.completed","response":{"status":"completed"}}
        """.trimIndent()

        assertEquals("xAI 返回内容无法解析", collect(sse).exceptionOrNull()?.message)
    }

    @Test
    fun toolRequest_translatesDefinitionsAndReplaysExchangeInCapturedShape() {
        val history = listOf(
            userMessage(),
            // 有前言的工具轮：正文 message 在前，调用在后（§2.1.1）。
            OpenAiCompatibleMessageDto(
                role = "assistant",
                content = "我来建卡",
                toolCalls = listOf(
                    OpenAiCompatibleToolCallDto(
                        id = "call-u-0",
                        function = OpenAiCompatibleFunctionCallDto("create_role_card", """{"name":"A"}"""),
                    ),
                    OpenAiCompatibleToolCallDto(
                        id = "call-u-1",
                        function = OpenAiCompatibleFunctionCallDto("create_world_setting", """{"name":"W"}"""),
                    ),
                ),
            ),
            OpenAiCompatibleMessageDto(role = "tool", content = "草稿已创建：1", toolCallId = "call-u-0"),
            OpenAiCompatibleMessageDto(role = "tool", content = "草稿已创建：2", toolCallId = "call-u-1"),
            // 纯工具调用、没有正文的第二轮。
            OpenAiCompatibleMessageDto(
                role = "assistant",
                toolCalls = listOf(
                    OpenAiCompatibleToolCallDto(
                        id = "call-u-2",
                        function = OpenAiCompatibleFunctionCallDto("create_writing_style", "{}"),
                    ),
                ),
            ),
            OpenAiCompatibleMessageDto(role = "tool", content = "草稿已创建：3", toolCallId = "call-u-2"),
        )
        runBlocking {
            transportReturning(TEXT_ONLY_SSE).streamChat(
                TOKEN,
                BASE_URL,
                request(history, tools = listOf(toolDto())).copy(toolChoice = "auto"),
            ).toList()
        }

        val body = json.parseToJsonElement(recordedBody.get()).jsonObject
        // 扁平形状（§2.2），没有 function 包裹层。
        val tool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("type", "name", "description", "parameters"), tool.keys)
        assertEquals("function", tool["type"]?.jsonPrimitive?.content)
        assertEquals("create_role_card", tool["name"]?.jsonPrimitive?.content)
        // 抓包里从未出现 tool_choice（§2）；回放也不带 reasoning，因此不请求加密推理。
        assertNull(body["tool_choice"])
        assertNull(body["include"])

        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf(
                null, "message", "function_call", "function_call", "function_call_output",
                "function_call_output", "function_call", "function_call_output",
            ),
            input.map { it["type"]?.jsonPrimitive?.content },
        )
        val firstCall = input[2]
        assertEquals(setOf("type", "call_id", "name", "arguments"), firstCall.keys)
        assertEquals("call-u-0", firstCall["call_id"]?.jsonPrimitive?.content)
        assertEquals("""{"name":"A"}""", firstCall["arguments"]?.jsonPrimitive?.content)
        val firstOutput = input[4]
        assertEquals(setOf("type", "call_id", "output"), firstOutput.keys)
        assertEquals("call-u-0", firstOutput["call_id"]?.jsonPrimitive?.content)
        assertEquals("草稿已创建：1", firstOutput["output"]?.jsonPrimitive?.content)
        assertTrue("不得把工具结果当成用户消息", input.none { it["role"]?.jsonPrimitive?.content == "tool" })
    }

    @Test
    fun requestWithoutTools_omitsToolsFieldSoItMatchesTheXai2Body() {
        collect(TEXT_ONLY_SSE).getOrThrow()

        val body = json.parseToJsonElement(recordedBody.get()).jsonObject
        assertEquals(setOf("model", "input", "stream", "store", "max_output_tokens"), body.keys)
    }

    @Test
    fun toolResultWithoutCallId_isRejectedBeforeAnythingIsSent() {
        val error = runCatching {
            runBlocking {
                transportReturning(TEXT_ONLY_SSE).streamChat(
                    TOKEN,
                    BASE_URL,
                    request(listOf(userMessage(), OpenAiCompatibleMessageDto(role = "tool", content = "结果"))),
                ).toList()
            }
        }.exceptionOrNull()

        // 带安全文案标记，才不会被上层按关键字收敛成别的错误。
        assertTrue(error is OpenAiCompatibleUserFacingError)
        assertNull(recorded.get())
    }

    @Test
    fun completeChat_returnsToolCallsLikeAStandardNonStreamResponse() = runBlocking {
        val chunk = transportReturning(THREE_CALL_SSE)
            .completeChat(TOKEN, BASE_URL, request(listOf(userMessage()), tools = listOf(toolDto())))

        val choice = chunk.choices.single()
        val calls = choice.message!!.toolCalls!!
        assertEquals(listOf("call-u-0", "call-u-1", "call-u-2"), calls.map { it.id })
        // 非流式响应里的完整调用不带流式拼接用的 index。
        assertTrue(calls.all { it.index == null })
        assertEquals("tool_calls", choice.finishReason)
    }


    // ------------------------------------------------------------------ models-v2（线上格式 §5.3）

    /** 实测响应的结构（字段取自抓包，值是合成的）。 */
    private val modelsV2Body = """
        {"object":"list","data":[
          {"id":"grok-4.7","object":"model","owned_by":"xAI","model":"grok-4.7","context_window":500000,
           "api_backend":"responses","reasoning_efforts":[{"id":"high","value":"high","label":"High","default":true}]},
          {"id":"grok-4.5","object":"model","owned_by":"xAI","context_window":500000,"api_backend":"responses"}
        ]}
    """.trimIndent()

    @Test
    fun listModels_sendsMeasuredHeadersOnlyAndParsesIds() {
        val models = runBlocking { transportReturning(modelsV2Body).listModels(TOKEN, "$BASE_URL/") }

        assertEquals(listOf("grok-4.5", "grok-4.7"), models)
        val request = recorded.get()!!
        assertEquals("GET", request.method)
        assertEquals("$BASE_URL/models-v2", request.url.toString())
        assertEquals("Bearer $TOKEN", request.header("Authorization"))
        assertEquals("application/json", request.header("Accept"))
        assertEquals("xai-grok-cli", request.header("X-XAI-Token-Auth"))
        assertEquals("authenticate-response", request.header("x-authenticateresponse"))
        assertEquals(XaiResponsesTransport.CLIENT_IDENTIFIER, request.header("x-grok-client-identifier"))
        assertEquals("9.9.9", request.header("x-grok-client-version"))
        assertEquals("interactive", request.header("x-grok-client-mode"))
        // 这几项只在 /responses 上出现过。
        listOf("x-grok-conv-id", "x-grok-session-id", "x-grok-req-id", "x-grok-model-override").forEach {
            assertNull(it, request.header(it))
        }
    }

    @Test
    fun listModels_unauthorizedIsTypedForRefreshRetry() {
        val error = runCatching {
            runBlocking { transportReturning("{}", code = 401).listModels(TOKEN, BASE_URL) }
        }.exceptionOrNull()

        assertTrue(error is XaiResponsesTransport.XaiUnauthorizedException)
    }

    @Test
    fun listModels_emptyOrMalformedCatalogFailsWithReadableCopy() {
        listOf("""{"object":"list","data":[]}""", "不是 JSON").forEach { body ->
            val error = runCatching {
                runBlocking { transportReturning(body).listModels(TOKEN, BASE_URL) }
            }.exceptionOrNull()
            assertTrue("body=$body", error is XaiResponsesTransport.XaiResponsesException)
            assertTrue(error!!.message!!.contains("手动填写模型名"))
        }
    }

    /** 与聊天请求同一道关口：token 绝不发往钉死地址以外的主机。 */
    @Test
    fun listModels_rejectsNonPinnedHostWithoutSending() {
        val error = runCatching {
            runBlocking { transportReturning(modelsV2Body).listModels(TOKEN, "https://api.x.ai/v1") }
        }.exceptionOrNull()

        assertTrue(error is XaiResponsesTransport.XaiResponsesException)
        assertNull(recorded.get())
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

        /** 纯文本一轮：不含任何工具项。 */
        val TEXT_ONLY_SSE = """
            data: {"type":"response.output_item.added","output_index":0,"item":{"id":"rs_1","type":"reasoning"}}
            data: {"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"想一想"}
            data: {"type":"response.output_item.done","output_index":0}
            data: {"type":"response.output_item.added","output_index":1,"item":{"id":"msg_1","type":"message"}}
            data: {"type":"response.output_text.delta","output_index":1,"delta":"你好呀"}
            data: {"type":"response.output_item.done","output_index":1}
            data: {"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":10576,"output_tokens":118,"total_tokens":10694}}}
        """.trimIndent()

        /**
         * 按线上格式 §3.2 的 004 顺序构造的三调用一轮：第三个调用的参数分片跨过了
         * 正文项收尾，`.done` 排在正文 output_item.done 之后。字段取自 §3.3 的补记。
         */
        val THREE_CALL_SSE = """
            data: {"type":"response.output_item.added","output_index":0,"item":{"id":"rs_1","type":"reasoning"}}
            data: {"type":"response.output_item.done","output_index":0}
            data: {"type":"response.output_item.added","output_index":1,"item":{"id":"msg_1","type":"message"}}
            data: {"type":"response.output_text.delta","output_index":1,"delta":"前言"}
            data: {"type":"response.output_item.added","output_index":2,"item":{"id":"fc_u_0","type":"function_call","name":"list_dir","call_id":"call-u-0","arguments":"","status":"in_progress"}}
            data: {"type":"response.function_call_arguments.delta","output_index":2,"item_id":"fc_u_0","delta":"{\"p\":\".\"}"}
            data: {"type":"response.function_call_arguments.done","output_index":2,"item_id":"fc_u_0","name":"list_dir","arguments":"{\"p\":\".\"}"}
            data: {"type":"response.output_item.done","output_index":2,"item":{"id":"fc_u_0","type":"function_call","name":"list_dir","call_id":"call-u-0","arguments":"{\"p\":\".\"}","status":"completed"}}
            data: {"type":"response.output_item.added","output_index":3,"item":{"id":"fc_u_1","type":"function_call","name":"bash","call_id":"call-u-1","arguments":"","status":"in_progress"}}
            data: {"type":"response.function_call_arguments.delta","output_index":3,"item_id":"fc_u_1","delta":"{\"c\":\"ls\"}"}
            data: {"type":"response.function_call_arguments.done","output_index":3,"item_id":"fc_u_1","name":"bash","arguments":"{\"c\":\"ls\"}"}
            data: {"type":"response.output_item.done","output_index":3,"item":{"id":"fc_u_1","type":"function_call","name":"bash","call_id":"call-u-1","arguments":"{\"c\":\"ls\"}","status":"completed"}}
            data: {"type":"response.output_item.added","output_index":4,"item":{"id":"fc_u_2","type":"function_call","name":"bash","call_id":"call-u-2","arguments":"","status":"in_progress"}}
            data: {"type":"response.function_call_arguments.delta","output_index":4,"item_id":"fc_u_2","delta":"{\"c\":"}
            data: {"type":"response.output_text.done","output_index":1,"text":"前言"}
            data: {"type":"response.content_part.done","output_index":1}
            data: {"type":"response.output_item.done","output_index":1}
            data: {"type":"response.function_call_arguments.delta","output_index":4,"item_id":"fc_u_2","delta":"\"pwd\"}"}
            data: {"type":"response.function_call_arguments.done","output_index":4,"item_id":"fc_u_2","name":"bash","arguments":"{\"c\":\"pwd\"}"}
            data: {"type":"response.output_item.done","output_index":4,"item":{"id":"fc_u_2","type":"function_call","name":"bash","call_id":"call-u-2","arguments":"{\"c\":\"pwd\"}","status":"completed"}}
            data: {"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":100,"output_tokens":20,"total_tokens":120}}}
        """.trimIndent()
    }
}
