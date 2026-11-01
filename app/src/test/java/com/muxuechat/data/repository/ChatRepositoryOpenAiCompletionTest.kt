package com.muxuechat.data.repository

import com.muxuechat.data.local.ChatDao
import com.muxuechat.data.local.ConversationEntity
import com.muxuechat.data.local.ConversationContextEntity
import com.muxuechat.data.local.ConversationTokenUsageEntity
import com.muxuechat.data.local.MessageAttachmentEntity
import com.muxuechat.data.local.MessageEntity
import com.muxuechat.data.local.RpStateCommitResult
import com.muxuechat.data.local.RpTurnConfigurationSnapshot
import com.muxuechat.data.remote.DeepSeekApiClient
import com.muxuechat.data.remote.OpenAiCompatibleClient
import com.muxuechat.data.remote.RemoteClientLogger
import com.muxuechat.data.remote.XaiResponsesTransport
import com.muxuechat.domain.context.ContextCompressionMode
import com.muxuechat.domain.model.ChatMessage
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.MessageStatus
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 真 Repository + 真传输与工具执行器；仅用内存 DAO 和合成 HTTP 代替外部存储/网络。 */
class ChatRepositoryOpenAiCompletionTest {
    @Test
    fun toolFragmentWithoutTerminalEventFailsWithoutExecutingTool() = runBlocking {
        Fixture(this, streaming = true, replies = listOf(
            sse(reply(true, content = "收到的前言", calls = toolCalls(arguments = "{\"title\":")), done = false),
        )).use { fixture ->
            val result = fixture.send()
            assertTrue(result.isFailure)
            assertFailed(fixture, "收到的前言")
            assertTrue(fixture.drafts.drafts.value.isEmpty())
            assertEquals(1, fixture.requests.size)
            assertEquals(0, fixture.completionCommits)
        }
    }

    @Test
    fun lengthAndContentFilterPreservePartialTextAndFailInBothModes() = runBlocking {
        listOf(true, false).forEach { streaming ->
            listOf("length", "content_filter").forEach { reason ->
                val partial = reply(streaming, content = "已经收到的部分正文", finish = reason)
                Fixture(this, streaming, listOf(if (streaming) sse(partial) else partial.toString())).use { fixture ->
                    assertTrue(fixture.send().isFailure)
                    assertFailed(fixture, "已经收到的部分正文")
                    assertTrue(fixture.assistant().errorMessage.orEmpty().contains(
                        if (reason == "length") "截断" else "内容过滤",
                    ))
                    assertEquals(0, fixture.completionCommits)
                }
            }
        }
    }

    @Test
    fun refusalBecomesReadableCompletedReplyInBothModes() = runBlocking {
        listOf(true, false).forEach { streaming ->
            val refusal = reply(streaming, refusal = "无法帮助完成该请求。", finish = "stop")
            Fixture(this, streaming, listOf(if (streaming) sse(refusal) else refusal.toString())).use { fixture ->
                assertTrue(fixture.send().isSuccess)
                assertEquals(MessageStatus.COMPLETED, fixture.assistant().status)
                assertEquals("无法帮助完成该请求。", fixture.assistant().content)
                assertEquals(1, fixture.completionCommits)
                assertTrue(fixture.events.last().status == MessageStatus.COMPLETED)
            }
        }
    }

    @Test
    fun fullToolExchangeReturnsResultAndPersistsFinalRoundUsageInBothModes() = runBlocking {
        listOf(true, false).forEach { streaming ->
            val tool = reply(streaming, content = "准备创建。", finish = "tool_calls", calls = toolCalls())
            val final = reply(streaming, content = "草稿已创建，可以预览。", finish = "stop")
            val usage = buildJsonObject {
                put("prompt_tokens", 12)
                put("completion_tokens", 5)
                put("total_tokens", 17)
            }
            val replies = if (streaming) {
                listOf(sse(tool), sse(final, buildJsonObject {
                    put("choices", JsonArray(emptyList()))
                    put("usage", usage)
                }))
            } else {
                listOf(tool.toString(), JsonObject(final + ("usage" to usage)).toString())
            }
            Fixture(this, streaming, replies).use { fixture ->
                assertTrue(fixture.send().isSuccess)
                assertEquals(MessageStatus.COMPLETED, fixture.assistant().status)
                assertEquals("准备创建。草稿已创建，可以预览。", fixture.assistant().content)
                assertEquals(1, fixture.drafts.drafts.value.size)
                assertEquals("雨城", fixture.drafts.drafts.value.single().worldSetting?.title)
                assertEquals(2, fixture.requests.size)
                assertNotNull(fixture.requests.first()["tools"])
                val exchange = fixture.requests.last()["messages"]!!.jsonArray.map { it.jsonObject }
                val assistantCall = exchange.first { it["tool_calls"] != null }
                val returned = exchange.single { it["role"]?.jsonPrimitive?.content == "tool" }
                assertEquals("call-synthetic-1", assistantCall["tool_calls"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
                assertEquals("call-synthetic-1", returned["tool_call_id"]!!.jsonPrimitive.content)
                assertTrue(returned["content"]!!.jsonPrimitive.content.contains("等待用户"))
                assertEquals(12, fixture.usage?.promptTokens)
                assertEquals(5, fixture.usage?.completionTokens)
                assertEquals(17, fixture.usage?.totalTokens)
                assertEquals(1, fixture.completionCommits)
            }
        }
    }

    @Test
    fun incompleteOrRefusedSummaryDoesNotOverwriteContextOrAdvanceCoverage() = runBlocking {
        listOf(
            reply(false, content = "这是一段非空但被截断的摘要", finish = "length"),
            reply(false, content = "这是一段非空但被过滤的摘要", finish = "content_filter"),
            reply(false, content = "这是一段非空正文", refusal = "无法提供所需摘要", finish = "stop"),
        ).forEach { response ->
            Fixture(this, streaming = false, replies = listOf(response.toString()), compaction = true).use { fixture ->
                val before = requireNotNull(fixture.context)
                assertFalse(fixture.compact())
                assertEquals(1, fixture.requests.size)
                assertEquals(before, fixture.context)
                assertEquals(before.summary, fixture.context?.summary)
                assertEquals(before.coveredMessageCount, fixture.context?.coveredMessageCount)
                assertEquals(before.updatedAtMillis, fixture.context?.updatedAtMillis)
                assertEquals(0, fixture.contextWrites)
                val summaryInput = fixture.requests.single()["messages"]!!.jsonArray
                    .last().jsonObject["content"]!!.jsonPrimitive.content
                assertTrue(summaryInput.contains(before.summary))
                assertTrue(summaryInput.contains("历史"))
            }
        }
    }

    @Test
    fun normallyCompletedSummaryUpdatesContextAndCoverageThroughSameRepositoryPath() = runBlocking {
        val response = reply(false, content = "已经完整合并旧摘要和新增历史。", finish = "stop")
        Fixture(this, streaming = false, replies = listOf(response.toString()), compaction = true).use { fixture ->
            val before = requireNotNull(fixture.context)
            assertTrue(fixture.compact())
            val after = requireNotNull(fixture.context)
            assertEquals(1, fixture.requests.size)
            assertEquals(1, fixture.contextWrites)
            assertEquals("已经完整合并旧摘要和新增历史。", after.summary)
            assertTrue(after.coveredMessageCount > before.coveredMessageCount)
            assertTrue(after.updatedAtMillis > before.updatedAtMillis)
        }
    }

    private fun assertFailed(fixture: Fixture, text: String) {
        val assistant = fixture.assistant()
        assertEquals(MessageStatus.FAILED, assistant.status)
        assertEquals(text, assistant.content)
        assertTrue(assistant.errorMessage.orEmpty().any { it in '\u4e00'..'\u9fff' })
        assertTrue(fixture.events.last().status == MessageStatus.FAILED)
        assertFalse(fixture.events.any { it.status == MessageStatus.COMPLETED })
    }

    private class Fixture(
        scope: CoroutineScope, streaming: Boolean, replies: List<String>, compaction: Boolean = false,
    ) : AutoCloseable {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val events = mutableListOf<ChatMessage>()
        val drafts = SettingsToolDraftStore()
        private val messages = ConcurrentHashMap<String, MessageEntity>()
        private val clock = AtomicLong(100L)
        private var conversation = ConversationEntity(CONVERSATION_ID, "协议回归", 100L, 100L)
        var usage: ConversationTokenUsageEntity? = null
            private set
        var completionCommits = 0
            private set
        var context: ConversationContextEntity? = if (compaction) {
            ConversationContextEntity(CONVERSATION_ID, "原有摘要记录已经发生的完整事实。", 2, 42L)
        } else null
            private set
        var contextWrites = 0
            private set
        init {
            if (compaction) {
                repeat(12) { index ->
                    val row = MessageEntity(
                        id = "history-$index", conversationId = CONVERSATION_ID,
                        role = if (index % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT,
                        content = "第${index}段历史：" + "历史".repeat(300), reasoningContent = null,
                        status = MessageStatus.COMPLETED, errorMessage = null,
                        createdAtMillis = index.toLong(), updatedAtMillis = index.toLong(),
                    )
                    messages[row.id] = row
                }
            }
        }
        private val settings = ChatSettings(
            provider = ChatProviderId.CUSTOM_OPENAI,
            customProviders = listOf(CustomProvider(
                id = "synthetic-provider", name = "合成服务", baseUrl = "https://synthetic.invalid/v1",
                selectedModel = "synthetic-model", toolCallingEnabled = !compaction,
                modelContextWindows = mapOf("synthetic-model" to if (compaction) 8_000L else 128_000L),
            )),
            activeCustomProviderId = "synthetic-provider", streamEnabled = streaming,
            contextCompressionMode = if (compaction) ContextCompressionMode.MANUAL else ContextCompressionMode.OFF,
            maxTokens = 512,
        )
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val index = requests.size
            requests += Json.parseToJsonElement(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).jsonObject
            check(index < replies.size) { "发生了未预期的额外 HTTP 请求" }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body(replies[index].toResponseBody((if (streaming) "text/event-stream" else "application/json").toMediaType()))
                .build()
        }.build()
        private val dao = Proxy.newProxyInstance(ChatDao::class.java.classLoader, arrayOf(ChatDao::class.java)) { _, method, arguments ->
            val args = arguments.orEmpty()
            when (method.name) {
                "getConversation" -> conversation
                "getMessage" -> messages[args[0] as String]
                "getMessages" -> messages.values.sortedBy { it.createdAtMillis }
                // 这些用例都是纯文本会话：没有任何图片附件。
                "getAttachments" -> emptyList<MessageAttachmentEntity>()
                "getConversationRpSettings", "getConversationTokenUsage" -> null
                "getConversationContext" -> context
                "upsertConversationContext" -> {
                    context = args[0] as ConversationContextEntity
                    contextWrites++
                    Unit
                }
                "getRpTurnConfigurationSnapshot" -> RpTurnConfigurationSnapshot(null, null, null, null, null)
                "upsertMessage" -> { val message = args[0] as MessageEntity; messages[message.id] = message; Unit }
                "updateMessage" -> { val message = args[0] as MessageEntity; messages[message.id] = message; Unit }
                "touchConversation" -> { conversation = conversation.copy(updatedAtMillis = args[1] as Long); Unit }
                "completeAssistantAndApplyRpState" -> {
                    val completed = args[0] as MessageEntity
                    messages[completed.id] = completed
                    usage = args[6] as ConversationTokenUsageEntity?
                    completionCommits++
                    RpStateCommitResult.APPLIED
                }
                else -> error("未预期的 DAO 调用：${method.name}")
            }
        } as ChatDao
        private val settingsRepository = Proxy.newProxyInstance(
            SettingsRepository::class.java.classLoader, arrayOf(SettingsRepository::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "withCurrentProviderCredentials" -> {
                    @Suppress("UNCHECKED_CAST")
                    val transform = arguments!![0] as (ChatSettings, String?) -> Any?
                    transform(settings, "synthetic-key")
                }
                else -> error("未预期的设置调用：${method.name}")
            }
        } as SettingsRepository
        private val repository = ChatRepositoryImpl(
            chatDao = dao, settingsRepository = settingsRepository,
            apiClient = DeepSeekApiClient(), openAiCompatibleClient = OpenAiCompatibleClient(client, NO_OP_LOGGER),
            xaiResponsesTransport = XaiResponsesTransport(),
            xaiAccessTokens = object : XaiAccessTokenSource {
                override suspend fun getValidAccessToken(rejectedAccessToken: String?): String = error("不应读取 xAI 凭据")
            },
            settingsToolDraftStore = drafts, enrichmentScope = scope, clock = clock::incrementAndGet,
        )

        suspend fun send(): Result<Unit> = runCatching {
            withTimeout(5_000L) { repository.sendMessage(CONVERSATION_ID, "请帮我处理当前请求").collect { events += it } }
        }

        suspend fun compact(): Boolean = withTimeout(5_000L) { repository.compactContext(CONVERSATION_ID) }

        fun assistant(): MessageEntity = messages.values.single { it.role == ChatRole.ASSISTANT }

        override fun close() {
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun toolCalls(arguments: String = "{\"title\":\"雨城\",\"content\":\"常年下雨的港口城市\"}") = JsonArray(listOf(buildJsonObject {
        put("index", 0)
        put("id", "call-synthetic-1")
        put("type", "function")
        put("function", buildJsonObject { put("name", "create_world_setting"); put("arguments", arguments) })
    }))

    private fun reply(
        streaming: Boolean, content: String? = null, refusal: String? = null,
        finish: String? = null, calls: JsonArray? = null,
    ): JsonObject = buildJsonObject {
        put("choices", JsonArray(listOf(buildJsonObject {
            put("index", 0)
            put(if (streaming) "delta" else "message", buildJsonObject {
                put("role", "assistant")
                content?.let { put("content", it) }
                refusal?.let { put("refusal", it) }
                calls?.let { put("tool_calls", it) }
            })
            put("finish_reason", finish?.let(::JsonPrimitive) ?: JsonNull)
        })))
    }

    private fun sse(vararg chunks: JsonObject, done: Boolean = true): String =
        chunks.joinToString("\n\n", postfix = "\n\n") { "data: $it" } + if (done) "data: [DONE]\n\n" else ""

    private companion object {
        const val CONVERSATION_ID = "synthetic-conversation"
        val NO_OP_LOGGER = object : RemoteClientLogger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
        }
    }
}
