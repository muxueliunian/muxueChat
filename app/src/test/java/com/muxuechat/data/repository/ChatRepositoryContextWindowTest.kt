package com.muxuechat.data.repository

import com.muxuechat.data.local.ChatDao
import com.muxuechat.data.local.ConversationContextEntity
import com.muxuechat.data.local.ConversationRpSettingsEntity
import com.muxuechat.data.local.ConversationTokenUsageEntity
import com.muxuechat.data.local.MessageAttachmentEntity
import com.muxuechat.data.local.MessageEntity
import com.muxuechat.data.local.ProtagonistProfileEntity
import com.muxuechat.data.local.RpStatusPanelEntity
import com.muxuechat.data.local.RoleCardEntity
import com.muxuechat.data.local.SupportingCharacterEntity
import com.muxuechat.data.local.WorldSettingEntity
import com.muxuechat.data.local.WritingStyleProfileEntity
import com.muxuechat.data.remote.DeepSeekApiClient
import com.muxuechat.data.remote.OpenAiCompatibleClient
import com.muxuechat.data.remote.XaiResponsesTransport
import com.muxuechat.domain.context.ContextWindowPlan
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.MessageStatus
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实 Repository 验证可空输入的组合和后续重算，不启动数据库或网络。
 * DAO 流由测试提供，因此通过不代表 Android Room 的首值/失效通知已复验通过。
 */
class ChatRepositoryContextWindowTest {
    @Test
    fun missingOptionalRows_emitsInitialPlanWithoutWritingRows() = runBlocking {
        val fixture = fixture(this)

        val plan = withTimeout(FLOW_TIMEOUT_MILLIS) {
            fixture.repository.observeContextWindow(CONVERSATION_ID).first()
        }

        assertEquals(2, plan.totalHistoryMessageCount)
        assertEquals(listOf("原问题", "原回复"), plan.requestMessages.takeLast(2).map { it.content })
        assertTrue(plan.inputBudgetTokens > 0L)
        assertNull(plan.lastApiUsage)
        assertNull(plan.overflowError)
    }

    @Test
    fun messageUpdate_recomputesPlanWhileOptionalRowsRemainMissing() = runBlocking {
        val fixture = fixture(this)
        val plans = Channel<ContextWindowPlan>(Channel.UNLIMITED)
        val observer = launch {
            fixture.repository.observeContextWindow(CONVERSATION_ID).collect { plans.send(it) }
        }

        try {
            val initial = withTimeout(FLOW_TIMEOUT_MILLIS) { plans.receive() }
            assertEquals(2, initial.totalHistoryMessageCount)

            val newContent = "新增问题".repeat(100)
            fixture.messages.value += message("user-2", ChatRole.USER, newContent, 3)

            val updated = withTimeout(FLOW_TIMEOUT_MILLIS) { plans.receive() }
            assertEquals(3, updated.totalHistoryMessageCount)
            assertEquals(newContent, updated.requestMessages.last().content)
            assertTrue(updated.estimatedInputTokens > initial.estimatedInputTokens)
            assertNull(updated.lastApiUsage)
            assertNull(updated.overflowError)
        } finally {
            observer.cancelAndJoin()
            plans.close()
        }
    }

    @Test
    fun roleCardTextUpdate_recomputesExistingSubscriptionWithoutChangingMetrics() = runBlocking {
        val fixture = fixture(this, rpEnabled = true)
        val plans = Channel<ContextWindowPlan>(Channel.UNLIMITED)
        val observer = launch {
            fixture.repository.observeContextWindow(CONVERSATION_ID).collect { plans.send(it) }
        }
        try {
            val initial = withTimeout(FLOW_TIMEOUT_MILLIS) { plans.receive() }
            val newPersona = "新增的角色背景与人物设定。".repeat(100)
            fixture.roleCards.value = fixture.roleCards.value.map { it.copy(persona = newPersona) }
            val updated = withTimeout(FLOW_TIMEOUT_MILLIS) { plans.receive() }
            assertTrue(updated.estimatedInputTokens > initial.estimatedInputTokens)
            assertTrue(updated.requestMessages.any { newPersona in it.content })
        } finally {
            observer.cancelAndJoin()
            plans.close()
        }
    }

    private fun fixture(scope: CoroutineScope, rpEnabled: Boolean = false): Fixture {
        val messages = MutableStateFlow(
            listOf(
                message("user-1", ChatRole.USER, "原问题", 1),
                message("assistant-1", ChatRole.ASSISTANT, "原回复", 2),
            ),
        )
        val roleCards = MutableStateFlow(listOf(RoleCardEntity(
            id = "role", name = "角色", description = "", persona = "原设定",
            speakingStyle = "", userIdentity = "", boundaries = "", metricDefinitionsJson = "[]",
            createdAtMillis = 0L, updatedAtMillis = 0L,
        )))
        val rpSettings = if (rpEnabled) ConversationRpSettingsEntity(
            conversationId = CONVERSATION_ID, isRpEnabled = true, roleCardId = "role", worldSettingId = null,
            protagonistName = "", protagonistDescription = "", protagonistInitialState = "",
            narrativeMinChars = 0, narrativePerspective = "", plotSuggestionPerspective = "",
            rpMarkerMode = "", updatedAtMillis = 0L,
        ) else null
        val observedFlows: Map<String, Flow<*>> = mapOf(
            "observeMessages" to messages,
            "observeAttachments" to MutableStateFlow(emptyList<MessageAttachmentEntity>()),
            "observeConversationContext" to MutableStateFlow<ConversationContextEntity?>(null),
            "observeConversationRpSettings" to MutableStateFlow(rpSettings),
            "observeRpStatusPanel" to MutableStateFlow<RpStatusPanelEntity?>(null),
            "observeConversationTokenUsage" to MutableStateFlow<ConversationTokenUsageEntity?>(null),
            "observeWorldSettings" to MutableStateFlow(emptyList<WorldSettingEntity>()),
            "observeWritingStyleProfiles" to MutableStateFlow(emptyList<WritingStyleProfileEntity>()),
            "observeProtagonistProfiles" to MutableStateFlow(emptyList<ProtagonistProfileEntity>()),
            "observeRoleCards" to roleCards,
            "observeConversationSupportingCharacters" to MutableStateFlow(emptyList<SupportingCharacterEntity>()),
        )
        val dao = Proxy.newProxyInstance(
            ChatDao::class.java.classLoader,
            arrayOf(ChatDao::class.java),
        ) { _, method, arguments ->
            if (method.name == "getRoleCard") return@newProxyInstance roleCards.value.single()
            if (method.name == "getConversationSupportingCharacters") return@newProxyInstance emptyList<SupportingCharacterEntity>()
            val result = observedFlows[method.name] ?: error("不应调用 DAO 方法：${method.name}")
            if (method.parameterCount == 1) {
                assertEquals(CONVERSATION_ID, arguments?.single())
            }
            result
        } as ChatDao
        val settings = MutableStateFlow(ChatSettings())
        val settingsRepository = Proxy.newProxyInstance(
            SettingsRepository::class.java.classLoader,
            arrayOf(SettingsRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSettings" -> settings
                else -> error("不应调用设置方法：${method.name}")
            }
        } as SettingsRepository
        val repository = ChatRepositoryImpl(
            chatDao = dao,
            settingsRepository = settingsRepository,
            apiClient = DeepSeekApiClient(),
            openAiCompatibleClient = OpenAiCompatibleClient(),
            xaiResponsesTransport = XaiResponsesTransport(),
            xaiAccessTokens = object : XaiAccessTokenSource {
                override suspend fun getValidAccessToken(rejectedAccessToken: String?): String =
                    error("上下文观察不应读取凭据")
            },
            enrichmentScope = scope,
        )
        return Fixture(repository, messages, roleCards)
    }

    private data class Fixture(
        val repository: ChatRepositoryImpl,
        val messages: MutableStateFlow<List<MessageEntity>>,
        val roleCards: MutableStateFlow<List<RoleCardEntity>>,
    )

    private fun message(id: String, role: ChatRole, content: String, createdAt: Long) = MessageEntity(
        id = id,
        conversationId = CONVERSATION_ID,
        role = role,
        content = content,
        reasoningContent = null,
        status = MessageStatus.COMPLETED,
        errorMessage = null,
        createdAtMillis = createdAt,
        updatedAtMillis = createdAt,
    )

    private companion object {
        const val CONVERSATION_ID = "conversation"
        const val FLOW_TIMEOUT_MILLIS = 5_000L
    }
}
