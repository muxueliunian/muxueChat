package com.muxuechat.data.repository

import com.muxuechat.data.local.ChatDao
import com.muxuechat.data.local.ConversationEntity
import com.muxuechat.data.local.MessageEntity
import com.muxuechat.data.remote.DeepSeekApiClient
import com.muxuechat.data.remote.OpenAiCompatibleClient
import com.muxuechat.data.remote.RemoteClientLogger
import com.muxuechat.data.remote.XaiResponsesTransport
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.MessageStatus
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryConversationTitleTest {
    @Test
    fun manualRenameWhileRequestIsPending_preservesManualTitleAndReportsConflict() = runBlocking {
        val fixture = Fixture(this)
        val request = async { runCatching { fixture.repository.renameConversationWithAi(CONVERSATION_ID) } }
        try {
            withTimeout(TIMEOUT_MILLIS) { fixture.requestStarted.await() }
            fixture.repository.renameConversation(CONVERSATION_ID, MANUAL_TITLE)
            fixture.allowResponse.countDown()

            val result = withTimeout(TIMEOUT_MILLIS) { request.await() }

            assertEquals(MANUAL_TITLE, fixture.conversation.get()?.title)
            assertReadableFailure(result)
        } finally {
            fixture.allowResponse.countDown()
            request.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun deletionWhileRequestIsPending_doesNotReportTitleSaved() = runBlocking {
        val fixture = Fixture(this)
        val request = async { runCatching { fixture.repository.renameConversationWithAi(CONVERSATION_ID) } }
        try {
            withTimeout(TIMEOUT_MILLIS) { fixture.requestStarted.await() }
            fixture.repository.deleteConversation(CONVERSATION_ID)
            fixture.allowResponse.countDown()

            val result = withTimeout(TIMEOUT_MILLIS) { request.await() }

            assertNull(fixture.conversation.get())
            assertReadableFailure(result)
        } finally {
            fixture.allowResponse.countDown()
            request.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun unchangedConversation_savesAndReturnsGeneratedTitle() = runBlocking {
        val fixture = Fixture(this)
        try {
            fixture.allowResponse.countDown()

            val title = withTimeout(TIMEOUT_MILLIS) {
                fixture.repository.renameConversationWithAi(CONVERSATION_ID)
            }

            assertEquals(AI_TITLE, title)
            assertEquals(AI_TITLE, fixture.conversation.get()?.title)
            assertTrue(fixture.conversation.get()!!.updatedAtMillis > INITIAL_TIMESTAMP)
        } finally {
            fixture.close()
        }
    }

    private fun assertReadableFailure(result: Result<String>) {
        val error = result.exceptionOrNull()
        assertTrue("迟到的 AI 标题不能报告保存成功", error is IllegalStateException)
        assertTrue("冲突原因应提供中文提示", error?.message.orEmpty().any { it in '\u4e00'..'\u9fff' })
    }

    /** 请求在拦截器内暂停，不接触真实网络或已保存的凭据。 */
    private class Fixture(scope: CoroutineScope) : AutoCloseable {
        val conversation = AtomicReference<ConversationEntity?>(
            ConversationEntity(CONVERSATION_ID, "原会话", INITIAL_TIMESTAMP, INITIAL_TIMESTAMP),
        )
        val requestStarted = CompletableDeferred<Unit>()
        val allowResponse = CountDownLatch(1)
        private val clock = AtomicLong(INITIAL_TIMESTAMP)
        private val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestStarted.complete(Unit)
                check(allowResponse.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) { "测试未释放模拟响应" }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("synthetic")
                    .body(
                        """{"choices":[{"message":{"role":"assistant","content":"$AI_TITLE"},"finish_reason":"stop"}]}"""
                            .toResponseBody("application/json".toMediaType()),
                    )
                    .build()
            }
            .build()

        private val dao = Proxy.newProxyInstance(
            ChatDao::class.java.classLoader,
            arrayOf(ChatDao::class.java),
        ) { _, method, arguments ->
            val args = requireNotNull(arguments)
            assertEquals(CONVERSATION_ID, args[0])
            when (method.name) {
                "getConversation" -> conversation.get()
                "getMessages" -> listOf(
                    MessageEntity(
                        id = "user-1",
                        conversationId = CONVERSATION_ID,
                        role = ChatRole.USER,
                        content = "聊聊如何整理旅行计划",
                        reasoningContent = null,
                        status = MessageStatus.COMPLETED,
                        errorMessage = null,
                        createdAtMillis = INITIAL_TIMESTAMP,
                        updatedAtMillis = INITIAL_TIMESTAMP,
                    ),
                )
                "updateConversationTitle" -> {
                    conversation.updateAndGet { row ->
                        row?.copy(title = args[1] as String, updatedAtMillis = args[2] as Long)
                    }
                    Unit
                }
                "updateConversationTitleIfUnchanged" -> {
                    val row = conversation.get()
                    if (row != null && row.title == args[1] && row.updatedAtMillis == args[2] &&
                        conversation.compareAndSet(
                            row,
                            row.copy(title = args[3] as String, updatedAtMillis = args[4] as Long),
                        )
                    ) {
                        1
                    } else {
                        0
                    }
                }
                "deleteConversation" -> {
                    conversation.set(null)
                    Unit
                }
                else -> error("不应调用 DAO 方法：${method.name}")
            }
        } as ChatDao

        private val settingsRepository = Proxy.newProxyInstance(
            SettingsRepository::class.java.classLoader,
            arrayOf(SettingsRepository::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "withCurrentProviderCredentials" -> {
                    @Suppress("UNCHECKED_CAST")
                    val transform = arguments!![0] as (ChatSettings, String?) -> Any?
                    transform(ChatSettings(baseUrl = "https://synthetic.invalid"), "synthetic-key")
                }
                else -> error("不应调用设置方法：${method.name}")
            }
        } as SettingsRepository

        val repository = ChatRepositoryImpl(
            chatDao = dao,
            settingsRepository = settingsRepository,
            apiClient = DeepSeekApiClient(client, NO_OP_LOGGER),
            openAiCompatibleClient = OpenAiCompatibleClient(),
            xaiResponsesTransport = XaiResponsesTransport(),
            xaiAccessTokens = object : XaiAccessTokenSource {
                override suspend fun getValidAccessToken(rejectedAccessToken: String?): String =
                    error("DeepSeek 测试不应读取 xAI 凭据")
            },
            enrichmentScope = scope,
            clock = clock::incrementAndGet,
        )

        override fun close() {
            allowResponse.countDown()
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private companion object {
        const val CONVERSATION_ID = "conversation"
        const val MANUAL_TITLE = "我手动设置的标题"
        const val AI_TITLE = "旅行计划整理"
        const val INITIAL_TIMESTAMP = 100L
        const val TIMEOUT_MILLIS = 5_000L
        val NO_OP_LOGGER = object : RemoteClientLogger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
        }
    }
}
