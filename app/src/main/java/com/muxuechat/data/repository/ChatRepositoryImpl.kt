package com.muxuechat.data.repository

import android.util.Log
import com.muxuechat.data.local.ChatDao
import com.muxuechat.data.local.ConversationEntity
import com.muxuechat.data.local.MessageEntity
import com.muxuechat.data.local.toEntity
import com.muxuechat.data.remote.OpenAiCompatibleClient
import com.muxuechat.data.remote.OpenAiCompatibleMessageDto
import com.muxuechat.data.remote.OpenAiCompatibleRequestFactory
import com.muxuechat.data.remote.OpenAiCompatibleStreamEvent
import com.muxuechat.data.remote.OpenAiCompatibleToolCallAccumulator
import com.muxuechat.data.remote.OpenAiCompatibleToolCallDto
import com.muxuechat.data.remote.DeepSeekApiClient
import com.muxuechat.data.remote.DeepSeekMessageDto
import com.muxuechat.data.remote.DeepSeekRequestFactory
import com.muxuechat.data.remote.DeepSeekStreamEvent
import com.muxuechat.data.remote.DeepSeekToolCallAccumulator
import com.muxuechat.data.remote.DeepSeekToolCallDto
import com.muxuechat.data.remote.SettingsToolCatalog
import com.muxuechat.domain.tool.SettingsTool
import com.muxuechat.domain.tool.SettingsToolArgumentParser
import com.muxuechat.domain.tool.SettingsToolDraft
import com.muxuechat.domain.tool.SettingsToolParseResult
import com.muxuechat.domain.model.ChatMessage
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.Conversation
import com.muxuechat.domain.model.MessageStatus
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RpMetric
import com.muxuechat.domain.model.RpMetricDefinition
import com.muxuechat.domain.model.RpMessageMetadata
import com.muxuechat.domain.model.RpStatusPanel
import com.muxuechat.data.local.ConversationContextEntity
import com.muxuechat.domain.context.ContextCompressionMode
import com.muxuechat.domain.context.ContextSummaryPrompt
import com.muxuechat.domain.context.ContextSummaryState
import com.muxuechat.domain.context.ContextWindowPlan
import com.muxuechat.domain.context.ContextWindowPlanner
import com.muxuechat.domain.prompt.PromptComposer
import com.muxuechat.domain.prompt.PromptContext
import com.muxuechat.domain.prompt.PromptMessage
import com.muxuechat.domain.prompt.RpMetadataPromptContext
import com.muxuechat.domain.rp.RpMessageMetadataDraft
import com.muxuechat.domain.rp.RpMessageMetadataParser
import com.muxuechat.domain.rp.RpStatusPanelDraft
import com.muxuechat.domain.rp.RpStateBlockFormat
import com.muxuechat.domain.rp.RpStateBlockParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class ChatRepositoryImpl(
    private val chatDao: ChatDao,
    private val settingsRepository: SettingsRepository,
    private val apiClient: DeepSeekApiClient,
    /** 所有自定义供应商共用的 OpenAI 兼容聊天请求客户端。 */
    private val openAiCompatibleClient: OpenAiCompatibleClient,
    private val promptComposer: PromptComposer = PromptComposer(),
    private val contextWindowPlanner: ContextWindowPlanner = ContextWindowPlanner(),
    private val settingsToolDraftStore: SettingsToolDraftStore = SettingsToolDraftStore(),
    private val settingsToolArgumentParser: SettingsToolArgumentParser = SettingsToolArgumentParser(),
    /** 后台补写 RP 元数据用。与请求流分离，正文完成后不再阻塞用户。 */
    private val enrichmentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val rpStateBlockParser: RpStateBlockParser = RpStateBlockParser(),
    private val rpMessageMetadataParser: RpMessageMetadataParser = RpMessageMetadataParser(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ChatRepository {
    private val enrichmentJobs = ConcurrentHashMap<String, Job>()
    private val enrichingMessageIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * 单轮请求的不可变路由快照。API Key 只在内存中短暂保留，绝不进入日志、消息或持久化层。
     * 后台 RP 补写复用同一快照，避免用户切换设置后把同一轮请求拆到两个 Provider。
     */
    private data class ProviderRequestSnapshot(
        val provider: ChatProviderId,
        val settings: ChatSettings,
        val apiKey: String,
        val baseUrl: String,
        val model: String,
        /** 自定义供应商的 `reasoning_effort`；DeepSeek 走自己的 thinking 协议，这里恒为 null。 */
        val reasoningEffort: String? = null,
    )

    override fun observeEnrichingMessageIds(): Flow<Set<String>> = enrichingMessageIds

    override fun observeConversations(): Flow<List<Conversation>> =
        chatDao.observeConversations().map { rows -> rows.map { it.toDomain() } }

    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> = combine(
        chatDao.observeMessages(conversationId),
        chatDao.observeMessageRpMetadata(conversationId),
    ) { messages, metadataRows ->
        val metadataByMessageId = metadataRows.associate { it.messageId to it.toDomain() }
        messages.map { message -> message.toDomain(metadataByMessageId[message.id]) }
    }

    override suspend fun createConversation(title: String?): Conversation {
        val now = clock()
        val conversation = ConversationEntity(
            id = UUID.randomUUID().toString(),
            title = title?.trim()?.take(MAX_TITLE_LENGTH)?.ifBlank { null } ?: "新对话",
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        chatDao.upsertConversation(conversation)
        return conversation.toDomain()
    }

    override fun sendMessage(conversationId: String?, content: String): Flow<ChatMessage> = flow {
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { "Message content is empty" }

        val now = clock()
        val targetConversationId = conversationId ?: UUID.randomUUID().toString()
        ensureConversation(targetConversationId, trimmed, now)

        val userMessage = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = targetConversationId,
            role = ChatRole.USER,
            content = trimmed,
            reasoningContent = null,
            status = MessageStatus.COMPLETED,
            errorMessage = null,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        chatDao.upsertMessage(userMessage)
        chatDao.touchConversation(targetConversationId, now)

        emitAll(generateAssistant(targetConversationId))
    }

    override fun regenerateLastResponse(conversationId: String): Flow<ChatMessage> = flow {
        val messages = chatDao.getMessages(conversationId)
        val lastAssistant = messages.lastOrNull { it.role == ChatRole.ASSISTANT }
            ?: error("No assistant response to regenerate")
        val lastUserBeforeAssistant = messages
            .takeWhile { it.id != lastAssistant.id }
            .lastOrNull { it.role == ChatRole.USER }
            ?: error("No user message to regenerate from")

        val now = clock()
        chatDao.deleteMessage(lastAssistant.id)
        restoreRpStatusBefore(
            conversationId = conversationId,
            createdAtMillis = lastAssistant.createdAtMillis,
            now = now,
        )
        chatDao.touchConversation(conversationId, now)
        require(lastUserBeforeAssistant.content.isNotBlank()) { "Last user message is empty" }

        emitAll(generateAssistant(conversationId))
    }

    override fun retryLatestRpOptions(conversationId: String): Flow<ChatMessage> = flow {
        val messages = chatDao.getMessages(conversationId)
        val lastAssistant = messages
            .lastOrNull {
                it.role == ChatRole.ASSISTANT &&
                    it.status == MessageStatus.COMPLETED &&
                    it.content.isNotBlank()
            }
            ?: error("没有可重试的最新回复")
        val lastConversationMessage = messages.lastOrNull { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
        require(lastConversationMessage?.id == lastAssistant.id) { "只能重试最新回复的剧情推进建议" }
        require(getActiveRoleCard(conversationId) != null) { "当前会话未启用可用的 RP 角色卡" }

        val requestSnapshot = freezeProviderRequest(settingsRepository.settings.first())
        val metadata = requestRpMessageMetadata(
            conversationId = conversationId,
            assistantContent = lastAssistant.content,
            requestSnapshot = requestSnapshot,
        ) ?: error("未能生成有效剧情推进建议")
        require(metadata.options.isNotEmpty()) { "未能生成有效剧情推进建议" }

        val now = clock()
        val statusPanelSnapshot = metadata.statusPanel?.let { panel ->
            RpStatusPanel(
                conversationId = conversationId,
                metrics = normalizeRpMetrics(conversationId, panel.metrics),
                relationship = panel.relationship,
                scene = panel.scene,
                rawJson = panel.rawJson,
                updatedAtMillis = now,
            ).also { chatDao.upsertRpStatusPanel(it.toEntity()) }
        } ?: chatDao.getRpStatusPanel(conversationId)?.toDomain()
        val savedMetadata = saveRpMessageMetadata(
            message = lastAssistant,
            metadata = metadata,
            statusPanelSnapshot = statusPanelSnapshot,
            now = now,
        )
        chatDao.touchConversation(conversationId, now)
        emit(lastAssistant.toDomain(savedMetadata))
    }

    override fun editUserMessageAndRegenerate(
        messageId: String,
        content: String,
    ): Flow<ChatMessage> = flow {
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { "消息内容不能为空" }

        val original = chatDao.getMessage(messageId) ?: error("消息不存在")
        require(original.role == ChatRole.USER) { "只能编辑玩家消息" }

        val now = clock()
        val conversationMessages = chatDao.getMessages(original.conversationId)
        val originalIndex = conversationMessages.indexOfFirst { it.id == original.id }
        require(originalIndex >= 0) { "消息不存在" }
        conversationMessages
            .drop(originalIndex + 1)
            .forEach { message -> chatDao.deleteMessage(message.id) }
        chatDao.updateMessage(
            original.copy(
                content = trimmed,
                status = MessageStatus.COMPLETED,
                errorMessage = null,
                updatedAtMillis = now,
            ),
        )
        restoreRpStatusBefore(
            conversationId = original.conversationId,
            createdAtMillis = original.createdAtMillis,
            now = now,
        )
        chatDao.touchConversation(original.conversationId, now)

        emitAll(generateAssistant(original.conversationId))
    }

    override suspend fun editAssistantMessage(messageId: String, content: String) {
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { "消息内容不能为空" }

        val original = chatDao.getMessage(messageId) ?: error("消息不存在")
        require(original.role == ChatRole.ASSISTANT) { "只能编辑模型回复" }

        val now = clock()
        // 状态强制置为 COMPLETED：loadPromptHistory() 只收 COMPLETED，
        // 否则手工改好的失败/中断回复仍然进不了后续请求的历史。
        chatDao.updateMessage(
            original.copy(
                content = trimmed,
                status = MessageStatus.COMPLETED,
                errorMessage = null,
                updatedAtMillis = now,
            ),
        )
        chatDao.touchConversation(original.conversationId, now)
        Log.i(TAG, "Assistant message edited: messageId=$messageId length=${trimmed.length}")
    }

    override suspend fun stopGeneration() {
        apiClient.cancelActiveStream()
        openAiCompatibleClient.cancelActiveRequest()
        // 用户点了停止，后台补写也应当一起停，不然还会继续产生费用。
        enrichmentJobs.values.forEach { it.cancel() }
        enrichmentJobs.clear()
        enrichingMessageIds.value = emptySet()
        chatDao.updateAllMessagesWithStatus(
            fromStatus = MessageStatus.STREAMING,
            status = MessageStatus.CANCELLED,
            errorMessage = null,
            updatedAtMillis = clock(),
        )
    }

    override suspend fun deleteConversation(conversationId: String) {
        chatDao.deleteConversation(conversationId)
    }

    override suspend fun renameConversation(conversationId: String, title: String) {
        val trimmed = title.trim()
        require(trimmed.isNotEmpty()) { "Conversation title is empty" }
        chatDao.updateConversationTitle(
            conversationId = conversationId,
            title = trimmed.take(MAX_TITLE_LENGTH),
            updatedAtMillis = clock(),
        )
    }

    override suspend fun clearConversation(conversationId: String) {
        chatDao.clearMessages(conversationId)
        chatDao.deleteRpStatusPanel(conversationId)
        // 消息都清空了，覆盖它们的摘要必须一起清掉，否则会残留一段无主的前情。
        chatDao.deleteConversationContext(conversationId)
        chatDao.touchConversation(conversationId, clock())
    }

    private suspend fun ensureConversation(conversationId: String, firstMessage: String, now: Long) {
        if (chatDao.getConversation(conversationId) != null) return

        chatDao.upsertConversation(
            ConversationEntity(
                id = conversationId,
                title = firstMessage.take(40).ifBlank { "新对话" },
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
    }

    private suspend fun freezeProviderRequest(settings: ChatSettings): ProviderRequestSnapshot {
        val normalized = settings.normalized()
        return when (normalized.provider) {
            ChatProviderId.DEEPSEEK -> ProviderRequestSnapshot(
                provider = ChatProviderId.DEEPSEEK,
                settings = normalized,
                apiKey = settingsRepository.getApiKey()
                    ?: error("请先在设置中保存 DeepSeek API Key"),
                baseUrl = normalized.activeBaseUrl,
                model = normalized.activeModel,
            )

            ChatProviderId.CUSTOM_OPENAI -> {
                val activeProvider = normalized.activeCustomProvider
                    ?: error("请先在设置中添加一个供应商")
                if (!normalized.isCustomProviderConfigured) {
                    error("请先在设置中配置「${activeProvider.displayName}」的地址和模型")
                }
                ProviderRequestSnapshot(
                    provider = ChatProviderId.CUSTOM_OPENAI,
                    settings = normalized,
                    apiKey = settingsRepository.getProviderKey(activeProvider.id)
                        ?: error("请先在设置中保存「${activeProvider.displayName}」的 API Key"),
                    baseUrl = normalized.activeBaseUrl,
                    model = normalized.activeModel,
                    reasoningEffort = activeProvider.reasoningEffort.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    /**
     * 在请求开始前冻结所有会影响路由的值，避免生成过程中切换 Provider、端点、模型或 Key 后，
     * 同一轮文本被拆到两个服务。凭据仅保留在局部变量，绝不写入消息、日志或 DataStore。
     */
    private fun generateAssistant(conversationId: String): Flow<ChatMessage> = flow {
        val requestSnapshot = freezeProviderRequest(settingsRepository.settings.first())
        when (requestSnapshot.provider) {
            ChatProviderId.DEEPSEEK -> {
                emitAll(
                    generateDeepSeekAssistant(
                        conversationId = conversationId,
                        requestSnapshot = requestSnapshot,
                    ),
                )
            }

            ChatProviderId.CUSTOM_OPENAI -> {
                emitAll(
                    generateOpenAiCompatibleAssistant(
                        conversationId = conversationId,
                        requestSnapshot = requestSnapshot,
                    ),
                )
            }
        }
    }

    /** 保留原有 DeepSeek 正文、工具、压缩与 RP 补写链路。 */
    private fun generateDeepSeekAssistant(
        conversationId: String,
        requestSnapshot: ProviderRequestSnapshot,
    ): Flow<ChatMessage> = flow {
        val settings = requestSnapshot.settings
        val apiKey = requestSnapshot.apiKey

        // 自动压缩放在发请求之前：这样本轮就能用上压缩后的上下文，
        // 而不是先超预算发一次、下轮才生效。代价是跨过阈值的那一轮会多等一次摘要请求。
        autoCompactIfNeeded(conversationId, requestSnapshot)

        val startedAt = clock()
        val assistant = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = ChatRole.ASSISTANT,
            content = "",
            reasoningContent = null,
            status = MessageStatus.STREAMING,
            errorMessage = null,
            createdAtMillis = startedAt,
            updatedAtMillis = startedAt,
        )
        chatDao.upsertMessage(assistant)
        emit(assistant.toDomain())

        val baseMessages = buildDeepSeekMessages(conversationId, assistant.id, settings)
        val tools = if (shouldOfferSettingsTools(conversationId, settings)) SettingsToolCatalog.tools() else null
        // 工具轮产生的 assistant(tool_calls) 与 tool 结果消息，只在本次请求链里有效，不落库。
        val toolExchange = mutableListOf<DeepSeekMessageDto>()

        var current = assistant
        try {
            var round = 0
            while (true) {
                val request = DeepSeekRequestFactory.build(
                    settings = settings,
                    messages = baseMessages + toolExchange,
                    purpose = DeepSeekRequestFactory.Purpose.CHAT,
                    tools = tools,
                    toolChoice = tools?.let { SettingsToolCatalog.TOOL_CHOICE_AUTO },
                )

                val toolCalls: List<DeepSeekToolCallDto>
                if (settings.streamEnabled) {
                    val accumulator = DeepSeekToolCallAccumulator()
                    var receivedTerminalEvent = false
                    apiClient.streamChat(apiKey, requestSnapshot.baseUrl, request).collect { event ->
                        when (event) {
                            is DeepSeekStreamEvent.Delta -> {
                                accumulator.accept(event.toolCalls)
                                val updated = current.copy(
                                    content = current.content + event.content.orEmpty(),
                                    reasoningContent = appendNullable(
                                        current.reasoningContent,
                                        event.reasoningContent,
                                    ),
                                    status = MessageStatus.STREAMING,
                                    updatedAtMillis = clock(),
                                )
                                current = updated
                                chatDao.updateMessage(updated)
                                emit(updated.toDomain())
                                if (event.finishReason != null) {
                                    receivedTerminalEvent = true
                                }
                            }

                            DeepSeekStreamEvent.Done -> {
                                receivedTerminalEvent = true
                            }
                        }
                    }
                    // 只调用工具、不产出正文时也是正常结束，不能当成流中断。
                    if (!receivedTerminalEvent && !accumulator.hasCalls()) {
                        error("DeepSeek stream ended before completion")
                    }
                    toolCalls = if (tools != null) accumulator.build() else emptyList()
                } else {
                    val response = apiClient.completeChat(apiKey, requestSnapshot.baseUrl, request)
                    val message = response.choices.firstOrNull()?.message
                    current = current.copy(
                        content = current.content + message?.content.orEmpty(),
                        reasoningContent = appendNullable(current.reasoningContent, message?.reasoningContent),
                        updatedAtMillis = clock(),
                    )
                    toolCalls = if (tools != null) message?.toolCalls.orEmpty() else emptyList()
                }

                if (toolCalls.isEmpty()) break

                round++
                if (round > MAX_TOOL_ROUNDS) {
                    // 不静默截断：告诉用户发生了什么，而不是让他以为模型没反应。
                    Log.w(TAG, "Tool rounds exceeded: conversationId=$conversationId rounds=$round")
                    current = current.copy(
                        content = current.content.appendNotice(
                            "（已达到本轮工具调用次数上限，剩余操作请另起一条消息继续。）",
                        ),
                        updatedAtMillis = clock(),
                    )
                    break
                }

                toolExchange += DeepSeekMessageDto(
                    role = "assistant",
                    content = current.content.takeIf { it.isNotBlank() },
                    // index 只用于流式拼接，不属于请求体字段，回传前清掉。
                    toolCalls = toolCalls.map { it.copy(index = null) },
                )
                toolExchange += executeSettingsToolCalls(conversationId, toolCalls)
            }

            if (current.status == MessageStatus.STREAMING) {
                val completed = completeAssistantMessage(conversationId, current, requestSnapshot)
                current = completed
                emit(completed.toDomain())
            }
        } catch (error: CancellationException) {
            val cancelled = current.copy(status = MessageStatus.CANCELLED, updatedAtMillis = clock())
            chatDao.updateMessage(cancelled)
            emit(cancelled.toDomain())
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Generation failed: ${error.message}", error)
            val failed = current.copy(
                status = MessageStatus.FAILED,
                errorMessage = error.toUserFacingMessage(requestSnapshot.provider),
                updatedAtMillis = clock(),
            )
            chatDao.updateMessage(failed)
            emit(failed.toDomain())
            throw IllegalStateException(failed.errorMessage, error)
        }
    }

    /** 供应商的正文、工具、压缩与 RP 补写全部复用同一 [ProviderRequestSnapshot]。 */
    private fun generateOpenAiCompatibleAssistant(
        conversationId: String,
        requestSnapshot: ProviderRequestSnapshot,
    ): Flow<ChatMessage> = flow {
        val settings = requestSnapshot.settings
        val apiKey = requestSnapshot.apiKey
        autoCompactIfNeeded(conversationId, requestSnapshot)

        val startedAt = clock()
        val assistant = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = ChatRole.ASSISTANT,
            content = "",
            reasoningContent = null,
            status = MessageStatus.STREAMING,
            errorMessage = null,
            createdAtMillis = startedAt,
            updatedAtMillis = startedAt,
        )
        chatDao.upsertMessage(assistant)
        emit(assistant.toDomain())

        val settingsToolsAvailable = shouldOfferSettingsTools(conversationId, settings)
        val baseMessages = buildOpenAiCompatibleMessages(
            conversationId = conversationId,
            pendingAssistantId = assistant.id,
            settings = settings,
            settingsToolsAvailable = settingsToolsAvailable,
        )
        val tools = if (settingsToolsAvailable) {
            SettingsToolCatalog.openAiCompatibleTools()
        } else {
            null
        }
        // 工具轮产生的 assistant(tool_calls) 与 tool 结果消息只在这次请求链有效，不落库。
        val toolExchange = mutableListOf<OpenAiCompatibleMessageDto>()

        var current = assistant
        try {
            var round = 0
            while (true) {
                val request = OpenAiCompatibleRequestFactory.build(
                    settings = settings,
                    model = requestSnapshot.model,
                    messages = baseMessages + toolExchange,
                    purpose = OpenAiCompatibleRequestFactory.Purpose.CHAT,
                    reasoningEffort = requestSnapshot.reasoningEffort,
                    tools = tools,
                    toolChoice = tools?.let { SettingsToolCatalog.TOOL_CHOICE_AUTO },
                )

                val toolCalls: List<OpenAiCompatibleToolCallDto>
                // 回传 assistant(tool_calls) 时只带本轮的正文，不能重复上一轮的工具前言。
                var responseContent = ""
                if (settings.streamEnabled) {
                    val accumulator = OpenAiCompatibleToolCallAccumulator()
                    var receivedTerminalEvent = false
                    openAiCompatibleClient.streamChat(
                        apiKey = apiKey,
                        baseUrl = requestSnapshot.baseUrl,
                        requestDto = request,
                    ).collect { event ->
                        when (event) {
                            is OpenAiCompatibleStreamEvent.Delta -> {
                                accumulator.accept(event.toolCalls)
                                responseContent += event.content.orEmpty()
                                val updated = current.copy(
                                    content = current.content + event.content.orEmpty(),
                                    // 支持思考的模型会先推送 reasoning 增量，聊天页复用已有的折叠区展示。
                                    reasoningContent = appendNullable(
                                        current.reasoningContent,
                                        event.reasoningContent,
                                    ),
                                    status = MessageStatus.STREAMING,
                                    updatedAtMillis = clock(),
                                )
                                current = updated
                                chatDao.updateMessage(updated)
                                emit(updated.toDomain())
                                if (event.finishReason != null) {
                                    receivedTerminalEvent = true
                                }
                            }

                            OpenAiCompatibleStreamEvent.Done -> {
                                receivedTerminalEvent = true
                            }
                        }
                    }
                    // 某些上游在纯工具调用后会直接断流；只要已收到调用，就继续下一轮。
                    if (!receivedTerminalEvent && !accumulator.hasCalls()) {
                        error("OpenAI-compatible stream ended before completion")
                    }
                    toolCalls = if (tools != null) accumulator.build() else emptyList()
                } else {
                    val response = openAiCompatibleClient.completeChat(
                        apiKey = apiKey,
                        baseUrl = requestSnapshot.baseUrl,
                        requestDto = request,
                    )
                    val message = response.choices.firstOrNull()?.message
                    responseContent = message?.content.orEmpty()
                    current = current.copy(
                        content = current.content + message?.content.orEmpty(),
                        reasoningContent = appendNullable(current.reasoningContent, message?.anyReasoning),
                        updatedAtMillis = clock(),
                    )
                    toolCalls = if (tools != null) {
                        message?.toolCalls.orEmpty().filter { !it.id.isNullOrBlank() }
                    } else {
                        emptyList()
                    }
                }

                if (toolCalls.isEmpty()) break

                round++
                if (round > MAX_TOOL_ROUNDS) {
                    Log.w(TAG, "OpenAI-compatible tool rounds exceeded: conversationId=$conversationId rounds=$round")
                    current = current.copy(
                        content = current.content.appendNotice(
                            "（已达到本轮工具调用次数上限，剩余操作请另起一条消息继续。）",
                        ),
                        updatedAtMillis = clock(),
                    )
                    break
                }

                toolExchange += OpenAiCompatibleMessageDto(
                    role = "assistant",
                    content = responseContent.takeIf { it.isNotBlank() },
                    // index 只用于流式拼接，不属于标准回传消息字段。
                    toolCalls = toolCalls.map { it.copy(index = null) },
                )
                toolExchange += executeOpenAiCompatibleSettingsToolCalls(conversationId, toolCalls)
            }

            if (current.status == MessageStatus.STREAMING) {
                val completed = completeAssistantMessage(conversationId, current, requestSnapshot)
                current = completed
                emit(completed.toDomain())
            }
        } catch (error: CancellationException) {
            val cancelled = current.copy(status = MessageStatus.CANCELLED, updatedAtMillis = clock())
            chatDao.updateMessage(cancelled)
            emit(cancelled.toDomain())
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "OpenAI-compatible generation failed: ${error.javaClass.simpleName}")
            val failed = current.copy(
                status = MessageStatus.FAILED,
                errorMessage = error.toUserFacingMessage(requestSnapshot.provider),
                updatedAtMillis = clock(),
            )
            chatDao.updateMessage(failed)
            emit(failed.toDomain())
            throw IllegalStateException(failed.errorMessage, error)
        }
    }

    /**
     * 取出可进入 prompt 的历史。只包含已完成的用户/助手消息，
     * 失败、取消、流式中的消息都不算，避免把半成品当成剧情。
     */
    private suspend fun loadPromptHistory(
        conversationId: String,
        excludeMessageId: String? = null,
    ): List<PromptMessage> = chatDao.getMessages(conversationId)
        .filter { it.id != excludeMessageId }
        .filter { it.status == MessageStatus.COMPLETED }
        .filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
        .filter { it.content.isNotBlank() }
        .map { PromptMessage(role = it.role.name.lowercase(), content = it.content) }

    private suspend fun getSummaryState(conversationId: String): ContextSummaryState =
        chatDao.getConversationContext(conversationId)?.toDomain() ?: ContextSummaryState.EMPTY

    override suspend fun planContextWindow(conversationId: String): ContextWindowPlan {
        val settings = settingsRepository.settings.first()
        return contextWindowPlanner.plan(
            history = loadPromptHistory(conversationId),
            summaryState = getSummaryState(conversationId),
            policy = settings.contextCompressionPolicy,
        )
    }

    override fun observeSettingsToolDrafts(): Flow<List<SettingsToolDraft>> = settingsToolDraftStore.drafts

    override fun discardSettingsToolDraft(draftId: String) {
        settingsToolDraftStore.remove(draftId)
    }

    override suspend fun compactContext(conversationId: String): Boolean {
        val requestSnapshot = freezeProviderRequest(settingsRepository.settings.first())
        return runCompaction(conversationId, requestSnapshot)
            ?: error("当前历史还不够长，没有可压缩的内容。")
    }

    /**
     * 执行一次压缩：把最老的一段历史换成摘要。
     *
     * 返回 null 表示没有可压缩的内容；返回 false 表示请求失败或摘要为空。
     * 失败时**不写入任何状态**，下次仍会重试，绝不会留下半截摘要。
     */
    private suspend fun runCompaction(
        conversationId: String,
        requestSnapshot: ProviderRequestSnapshot,
    ): Boolean? {
        val settings = requestSnapshot.settings
        val history = loadPromptHistory(conversationId)
        val summaryState = getSummaryState(conversationId)
        val plan = contextWindowPlanner.planCompaction(
            history = history,
            summaryState = summaryState,
            policy = settings.contextCompressionPolicy,
        ) ?: return null

        val isRoleplay = chatDao.getConversationRpSettings(conversationId)?.toDomain()?.isRpEnabled == true
        val promptMessages = ContextSummaryPrompt.build(plan, isRoleplay)

        return runCatching {
            val summary = when (requestSnapshot.provider) {
                ChatProviderId.DEEPSEEK -> {
                    val request = DeepSeekRequestFactory.build(
                        settings = settings,
                        messages = promptMessages.map { message ->
                            DeepSeekMessageDto(role = message.role, content = message.content)
                        },
                        purpose = DeepSeekRequestFactory.Purpose.CONTEXT_SUMMARY,
                    )
                    apiClient.completeChat(
                        requestSnapshot.apiKey,
                        requestSnapshot.baseUrl,
                        request,
                    ).choices.firstOrNull()?.message?.content.orEmpty().trim()
                }

                ChatProviderId.CUSTOM_OPENAI -> {
                    val request = OpenAiCompatibleRequestFactory.build(
                        settings = settings,
                        model = requestSnapshot.model,
                        messages = promptMessages.map { message ->
                            OpenAiCompatibleMessageDto(role = message.role, content = message.content)
                        },
                        purpose = OpenAiCompatibleRequestFactory.Purpose.CONTEXT_SUMMARY,
                    )
                    openAiCompatibleClient.completeChat(
                        requestSnapshot.apiKey,
                        requestSnapshot.baseUrl,
                        request,
                    ).choices.firstOrNull()?.message?.content.orEmpty().trim()
                }
            }
            if (summary.isBlank()) {
                Log.w(TAG, "Context compaction returned empty summary: conversationId=$conversationId")
                return@runCatching false
            }

            chatDao.upsertConversationContext(
                ConversationContextEntity(
                    conversationId = conversationId,
                    summary = summary,
                    coveredMessageCount = plan.newCoveredMessageCount,
                    updatedAtMillis = clock(),
                ),
            )
            Log.i(
                TAG,
                "Context compacted: conversationId=$conversationId " +
                    "summarizedMessages=${plan.messagesToSummarize.size} " +
                    "sourceChars=${plan.charsToSummarize} summaryChars=${summary.length} " +
                    "coveredTotal=${plan.newCoveredMessageCount}",
            )
            true
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Context compaction failed: ${error.message}", error)
        }.getOrDefault(false)
    }

    /**
     * 工具只在**非 RP 模式**下挂载。
     *
     * RP 进行中给模型工具，它可能在剧情推进到一半时突然去建角色卡，直接打断沉浸；
     * 而且 RP 的 system prompt 已经很长，再加工具定义纯属浪费 token。
     */
    private suspend fun shouldOfferSettingsTools(conversationId: String, settings: ChatSettings): Boolean {
        if (!settings.settingsToolsEnabledForActiveProvider) return false
        val rpEnabled = chatDao.getConversationRpSettings(conversationId)?.toDomain()?.isRpEnabled == true
        return !rpEnabled
    }

    /**
     * 执行工具调用：解析参数 → 生成草稿 → 存入待确认区。
     *
     * **绝不写数据库。** 返回给模型的结果只说"草稿已创建"和 id，不回传草稿全文——
     * 否则同一份内容会在上下文里存两遍，白白翻倍 token。
     */
    private fun executeSettingsToolCalls(
        conversationId: String,
        toolCalls: List<DeepSeekToolCallDto>,
    ): List<DeepSeekMessageDto> = executeSettingsToolInvocations(
        conversationId = conversationId,
        calls = toolCalls.mapNotNull { call ->
            call.id?.takeIf { it.isNotBlank() }?.let { id ->
                SettingsToolInvocation(
                    id = id,
                    name = call.function?.name,
                    arguments = call.function?.arguments.orEmpty(),
                )
            }
        },
    ).map { result ->
        DeepSeekMessageDto(
            role = "tool",
            content = result.content,
            toolCallId = result.toolCallId,
        )
    }

    /** 将标准 OpenAI tool result 包装成协议要求的 role="tool" 消息。 */
    private fun executeOpenAiCompatibleSettingsToolCalls(
        conversationId: String,
        toolCalls: List<OpenAiCompatibleToolCallDto>,
    ): List<OpenAiCompatibleMessageDto> = executeSettingsToolInvocations(
        conversationId = conversationId,
        calls = toolCalls.mapNotNull { call ->
            call.id?.takeIf { it.isNotBlank() }?.let { id ->
                SettingsToolInvocation(
                    id = id,
                    name = call.function?.name,
                    arguments = call.function?.arguments.orEmpty(),
                )
            }
        },
    ).map { result ->
        OpenAiCompatibleMessageDto(
            role = "tool",
            content = result.content,
            toolCallId = result.toolCallId,
        )
    }

    /**
     * 两条协议共享相同的工具业务语义：解析参数 → 只创建内存草稿 → 向模型返回简短结果。
     * DTO 适配保留在上方，避免 domain 草稿逻辑依赖任一供应商的网络类型。
     */
    private fun executeSettingsToolInvocations(
        conversationId: String,
        calls: List<SettingsToolInvocation>,
    ): List<SettingsToolExecutionResult> = calls.map { call ->
        val requestedName = call.name
        val tool = SettingsTool.fromApiName(requestedName)
        val resultText = if (requestedName.isNullOrBlank()) {
            Log.w(TAG, "Tool request missing name")
            "调用失败：模型没有给出工具名称。请补齐名称后重试。"
        } else if (tool == null) {
            Log.w(TAG, "Unknown tool requested: name=$requestedName")
            "调用失败：不存在名为 $requestedName 的工具。"
        } else {
            when (
                val parsed = settingsToolArgumentParser.parse(
                    tool = tool,
                    argumentsJson = call.arguments,
                    conversationId = conversationId,
                )
            ) {
                is SettingsToolParseResult.Success -> {
                    settingsToolDraftStore.add(parsed.draft)
                    Log.i(
                        TAG,
                        "Settings draft created: tool=${tool.apiName} draftId=${parsed.draft.id} " +
                            "warnings=${parsed.draft.warnings.size}",
                    )
                    buildString {
                        append("已创建${parsed.draft.typeLabel}草稿（id=${parsed.draft.id}），")
                        append("正在等待用户在预览界面确认。")
                        if (parsed.draft.warnings.isNotEmpty()) {
                            append("有 ${parsed.draft.warnings.size} 处参数已被本地规则修正。")
                        }
                        append("请简短告知用户草稿已生成、可以预览修改，不要重复输出草稿内容。")
                    }
                }

                is SettingsToolParseResult.Failure -> {
                    Log.w(TAG, "Settings tool parse failed: tool=${tool.apiName} reason=${parsed.reason}")
                    "调用失败：${parsed.reason}请补齐必要字段后重试，或直接询问用户。"
                }
            }
        }
        SettingsToolExecutionResult(toolCallId = call.id, content = resultText)
    }

    private data class SettingsToolInvocation(
        val id: String,
        val name: String?,
        val arguments: String,
    )

    private data class SettingsToolExecutionResult(
        val toolCallId: String,
        val content: String,
    )

    private fun String.appendNotice(notice: String): String =
        if (isBlank()) notice else trimEnd() + "\n\n" + notice

    /** 自动模式下，占用达到阈值就先压缩再发请求。任何失败都不阻断本轮生成。 */
    private suspend fun autoCompactIfNeeded(
        conversationId: String,
        requestSnapshot: ProviderRequestSnapshot,
    ) {
        val settings = requestSnapshot.settings
        val policy = settings.contextCompressionPolicy
        if (policy.mode != ContextCompressionMode.AUTO) return

        val plan = contextWindowPlanner.plan(
            history = loadPromptHistory(conversationId),
            summaryState = getSummaryState(conversationId),
            policy = policy,
        )
        if (!contextWindowPlanner.shouldAutoCompact(plan)) return

        Log.i(
            TAG,
            "Auto compaction triggered: conversationId=$conversationId " +
                "usagePercent=${plan.usagePercent} triggerPercent=${policy.autoTriggerPercent}",
        )
        runCompaction(conversationId, requestSnapshot)
    }

    private suspend fun buildDeepSeekMessages(
        conversationId: String,
        pendingAssistantId: String,
        settings: ChatSettings,
    ): List<DeepSeekMessageDto> = promptComposer.composeForDeepSeekRequest(
        buildPromptContext(
            conversationId = conversationId,
            pendingAssistantId = pendingAssistantId,
            settings = settings,
            settingsToolsAvailable = shouldOfferSettingsTools(conversationId, settings),
        ),
    ).map { message ->
        DeepSeekMessageDto(role = message.role, content = message.content)
    }

    private suspend fun buildOpenAiCompatibleMessages(
        conversationId: String,
        pendingAssistantId: String,
        settings: ChatSettings,
        settingsToolsAvailable: Boolean,
    ): List<OpenAiCompatibleMessageDto> = promptComposer.composeForOpenAiCompatibleRequest(
        buildPromptContext(
            conversationId = conversationId,
            pendingAssistantId = pendingAssistantId,
            settings = settings,
            settingsToolsAvailable = settingsToolsAvailable,
        ),
    ).map { message ->
        OpenAiCompatibleMessageDto(role = message.role, content = message.content)
    }

    private suspend fun buildPromptContext(
        conversationId: String,
        pendingAssistantId: String,
        settings: ChatSettings,
        settingsToolsAvailable: Boolean,
    ): PromptContext {
        val fullHistory = loadPromptHistory(conversationId, excludeMessageId = pendingAssistantId)

        val plan = contextWindowPlanner.plan(
            history = fullHistory,
            summaryState = getSummaryState(conversationId),
            policy = settings.contextCompressionPolicy,
        )
        if (plan.hardTrimmedMessageCount > 0) {
            // 硬裁剪是保底安全网，走到这里说明摘要没能顶住，属于真正的信息损失，值得记一笔。
            Log.w(
                TAG,
                "Context hard-trimmed: conversationId=$conversationId " +
                    "trimmedMessages=${plan.hardTrimmedMessageCount} " +
                    "summarized=${plan.summarizedMessageCount} " +
                    "verbatimChars=${plan.verbatimChars} totalChars=${plan.totalHistoryChars}",
            )
        }
        // 摘要作为独立 system 消息插在逐字历史之前。
        val history = listOfNotNull(plan.summaryMessage) + plan.messages

        val rpSettings = chatDao.getConversationRpSettings(conversationId)?.toDomain()
        val roleCard = rpSettings
            ?.takeIf { it.isRpEnabled }
            ?.roleCardId
            ?.let { chatDao.getRoleCard(it)?.toDomain() }
        val worldSetting = rpSettings
            ?.takeIf { it.isRpEnabled }
            ?.worldSettingId
            ?.let { chatDao.getWorldSetting(it)?.toDomain() }
        val writingStyle = rpSettings
            ?.takeIf { it.isRpEnabled }
            ?.writingStyleId
            ?.let { chatDao.getWritingStyleProfile(it)?.toDomain() }
        val statusPanel = rpSettings
            ?.takeIf { it.isRpEnabled }
            ?.let { chatDao.getRpStatusPanel(conversationId)?.toDomain() }

        return PromptContext(
            settings = settings,
            rpSettings = rpSettings,
            roleCard = roleCard,
            worldSetting = worldSetting,
            writingStyle = writingStyle,
            statusPanel = statusPanel,
            history = history,
            settingsToolsAvailable = settingsToolsAvailable,
        )
    }

    private suspend fun completeAssistantMessage(
        conversationId: String,
        message: MessageEntity,
        requestSnapshot: ProviderRequestSnapshot,
    ): MessageEntity {
        val contentExtraction = rpStateBlockParser.extract(message.content)
        val reasoningExtraction = message.reasoningContent?.let { rpStateBlockParser.extract(it) }
        val expectedRpState = shouldExpectRpStateBlock(conversationId)
        val needsEnrichment = shouldGenerateRpMetadata(conversationId)

        // 只做本地解析。剧情建议、内心独白、兜底状态都需要额外的网络往返，
        // 放在这里会让用户看完正文后还要干等一整次请求，因此移到后台补写。
        val panel = contentExtraction.panel ?: reasoningExtraction?.panel
        val now = clock()
        val missingExpectedState = panel == null && expectedRpState
        val completed = message.copy(
            content = contentExtraction.cleanContent,
            reasoningContent = reasoningExtraction?.cleanContent ?: message.reasoningContent,
            status = MessageStatus.COMPLETED,
            // 还要后台补状态时先不报"未更新状态栏"，否则每轮都会误报一次。
            errorMessage = if (missingExpectedState && !needsEnrichment) {
                "本次回复未更新状态栏，已保留上一次状态。"
            } else {
                null
            },
            updatedAtMillis = now,
        )
        chatDao.updateMessage(completed)

        val statusPanelSnapshot = panel?.let { panel ->
            RpStatusPanel(
                conversationId = conversationId,
                metrics = normalizeRpMetrics(conversationId, panel.metrics),
                relationship = panel.relationship,
                scene = panel.scene,
                rawJson = panel.rawJson,
                updatedAtMillis = now,
            ).also { chatDao.upsertRpStatusPanel(it.toEntity()) }
        } ?: chatDao.getRpStatusPanel(conversationId)?.toDomain()

        chatDao.touchConversation(conversationId, completed.updatedAtMillis)

        if (needsEnrichment) {
            launchRpEnrichment(
                conversationId = conversationId,
                message = completed,
                hasLocalPanel = panel != null,
                expectedRpState = expectedRpState,
                requestSnapshot = requestSnapshot,
            )
        }
        return completed
    }

    /**
     * 后台补写剧情建议、内心独白和兜底状态。
     *
     * 与正文分离的原因：这两个请求加起来可能是一整次完整往返，串行执行会让
     * 「正文已经流完了但消息还在转圈」。改成后台补写后，结果通过 Room 的 Flow
     * 自然推回 UI，用户先看到正文，建议稍后出现。
     */
    private fun launchRpEnrichment(
        conversationId: String,
        message: MessageEntity,
        hasLocalPanel: Boolean,
        expectedRpState: Boolean,
        requestSnapshot: ProviderRequestSnapshot,
    ) {
        enrichmentJobs[message.id]?.cancel()
        enrichingMessageIds.update { it + message.id }
        enrichmentJobs[message.id] = enrichmentScope.launch {
            try {
                val metadata = requestRpMessageMetadata(
                    conversationId = conversationId,
                    assistantContent = message.content,
                    requestSnapshot = requestSnapshot,
                )
                val fallbackPanel = if (!hasLocalPanel && metadata?.statusPanel == null && expectedRpState) {
                    requestFallbackRpStatusPanel(
                        conversationId = conversationId,
                        assistantContent = message.content,
                        requestSnapshot = requestSnapshot,
                    )
                } else {
                    null
                }

                val now = clock()
                val resolvedPanel = metadata?.statusPanel ?: fallbackPanel
                val statusPanelSnapshot = resolvedPanel?.let { panel ->
                    RpStatusPanel(
                        conversationId = conversationId,
                        metrics = normalizeRpMetrics(conversationId, panel.metrics),
                        relationship = panel.relationship,
                        scene = panel.scene,
                        rawJson = panel.rawJson,
                        updatedAtMillis = now,
                    ).also { chatDao.upsertRpStatusPanel(it.toEntity()) }
                } ?: chatDao.getRpStatusPanel(conversationId)?.toDomain()

                if (metadata != null && (metadata.innerThought.isNotBlank() || metadata.options.isNotEmpty())) {
                    saveRpMessageMetadata(
                        message = message,
                        metadata = metadata,
                        statusPanelSnapshot = statusPanelSnapshot,
                        now = now,
                    )
                }

                // 补写完仍然没有状态时才提示，避免正文刚结束就误报。
                if (!hasLocalPanel && resolvedPanel == null && expectedRpState) {
                    chatDao.getMessage(message.id)?.let { latest ->
                        chatDao.updateMessage(
                            latest.copy(
                                errorMessage = "本次回复未更新状态栏，已保留上一次状态。",
                                updatedAtMillis = clock(),
                            ),
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // 补写失败不能影响已经完成的正文，只记录不抛出。
                Log.w(TAG, "RP enrichment failed: ${error.message}", error)
            } finally {
                enrichingMessageIds.update { it - message.id }
                enrichmentJobs.remove(message.id)
            }
        }
    }

    private suspend fun shouldExpectRpStateBlock(conversationId: String): Boolean =
        getActiveMetricDefinitions(conversationId).isNotEmpty()

    private suspend fun shouldGenerateRpMetadata(conversationId: String): Boolean =
        getActiveRoleCard(conversationId) != null

    private suspend fun restoreRpStatusBefore(
        conversationId: String,
        createdAtMillis: Long,
        now: Long,
    ) {
        val previousSnapshot = chatDao.getLatestMessageRpMetadataBefore(conversationId, createdAtMillis)
            ?.toDomain()
            ?.statusPanelSnapshot

        if (previousSnapshot != null) {
            chatDao.upsertRpStatusPanel(
                previousSnapshot.copy(updatedAtMillis = now).toEntity(),
            )
            return
        }

        resetRpStatusToInitial(conversationId, now)
    }

    private suspend fun resetRpStatusToInitial(conversationId: String, now: Long) {
        val roleCard = getActiveRoleCard(conversationId)
        val definitions = roleCard?.metricDefinitions.orEmpty()
        if (definitions.isEmpty()) {
            chatDao.deleteRpStatusPanel(conversationId)
            return
        }

        chatDao.upsertRpStatusPanel(
            RpStatusPanel(
                conversationId = conversationId,
                metrics = definitions.map { definition ->
                    RpMetric(
                        name = definition.name,
                        value = definition.initialValue,
                        min = definition.minValue,
                        max = definition.maxValue,
                    )
                },
                relationship = "",
                scene = "",
                rawJson = "",
                updatedAtMillis = now,
            ).toEntity(),
        )
    }

    private suspend fun saveRpMessageMetadata(
        message: MessageEntity,
        metadata: RpMessageMetadataDraft,
        statusPanelSnapshot: RpStatusPanel?,
        now: Long,
    ): RpMessageMetadata {
        val saved = RpMessageMetadata(
            messageId = message.id,
            conversationId = message.conversationId,
            innerThought = metadata.innerThought.trim(),
            options = ensureThreeOptions(metadata.options),
            statusPanelSnapshot = statusPanelSnapshot,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        chatDao.upsertMessageRpMetadata(saved.toEntity())
        return saved
    }

    private suspend fun requestRpMessageMetadata(
        conversationId: String,
        assistantContent: String,
        requestSnapshot: ProviderRequestSnapshot,
    ): RpMessageMetadataDraft? {
        val settings = requestSnapshot.settings
        val roleCard = getActiveRoleCard(conversationId) ?: return null
        val rpSettings = chatDao.getConversationRpSettings(conversationId)?.toDomain()
        val currentPanel = chatDao.getRpStatusPanel(conversationId)?.toDomain()
        val definitions = roleCard.metricDefinitions
        val messages = chatDao.getMessages(conversationId)
        val lastUser = messages
            .filter { it.role == ChatRole.USER && it.status == MessageStatus.COMPLETED }
            .lastOrNull()
            ?.content
            .orEmpty()

        val promptMessages = promptComposer.composeRpMetadata(
            RpMetadataPromptContext(
                rpSettings = rpSettings,
                roleCard = roleCard,
                definitions = definitions,
                currentPanel = currentPanel,
                lastUser = lastUser,
                assistantContent = assistantContent,
            ),
        )

        return runCatching {
            val content = when (requestSnapshot.provider) {
                ChatProviderId.DEEPSEEK -> {
                    val request = DeepSeekRequestFactory.build(
                        settings = settings,
                        messages = promptMessages.map { message ->
                            DeepSeekMessageDto(role = message.role, content = message.content)
                        },
                        purpose = DeepSeekRequestFactory.Purpose.RP_METADATA,
                    )
                    apiClient.completeChat(
                        requestSnapshot.apiKey,
                        requestSnapshot.baseUrl,
                        request,
                    ).choices.firstOrNull()?.message?.content.orEmpty()
                }

                ChatProviderId.CUSTOM_OPENAI -> {
                    val request = OpenAiCompatibleRequestFactory.build(
                        settings = settings,
                        model = requestSnapshot.model,
                        messages = promptMessages.map { message ->
                            OpenAiCompatibleMessageDto(role = message.role, content = message.content)
                        },
                        purpose = OpenAiCompatibleRequestFactory.Purpose.RP_METADATA,
                    )
                    openAiCompatibleClient.completeChat(
                        requestSnapshot.apiKey,
                        requestSnapshot.baseUrl,
                        request,
                    ).choices.firstOrNull()?.message?.content.orEmpty()
                }
            }
            rpMessageMetadataParser.parse(content).also { parsed ->
                Log.i(
                    TAG,
                    "RP metadata parsed: conversationId=$conversationId " +
                        "responseLength=${content.length} optionsCount=${parsed?.options?.size ?: 0} " +
                        "parseSuccess=${parsed != null}",
                )
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "RP metadata request failed: ${error.message}", error)
        }.getOrNull()
    }

    private suspend fun requestFallbackRpStatusPanel(
        conversationId: String,
        assistantContent: String,
        requestSnapshot: ProviderRequestSnapshot,
    ): RpStatusPanelDraft? {
        val settings = requestSnapshot.settings
        val definitions = getActiveMetricDefinitions(conversationId)
        if (definitions.isEmpty()) return null

        val currentPanel = chatDao.getRpStatusPanel(conversationId)?.toDomain()
        val lastUser = chatDao.getMessages(conversationId)
            .filter { it.role == ChatRole.USER && it.status == MessageStatus.COMPLETED }
            .lastOrNull()
            ?.content
            .orEmpty()

        val promptMessages = buildRpStatusUpdateMessages(
            definitions = definitions,
            currentPanel = currentPanel,
            lastUser = lastUser,
            assistantContent = assistantContent,
        )

        return runCatching {
            val content = when (requestSnapshot.provider) {
                ChatProviderId.DEEPSEEK -> {
                    val request = DeepSeekRequestFactory.build(
                        settings = settings,
                        messages = promptMessages.map { message ->
                            DeepSeekMessageDto(role = message.role, content = message.content)
                        },
                        purpose = DeepSeekRequestFactory.Purpose.RP_STATE_UPDATE,
                    )
                    apiClient.completeChat(
                        requestSnapshot.apiKey,
                        requestSnapshot.baseUrl,
                        request,
                    ).choices.firstOrNull()?.message?.content.orEmpty()
                }

                ChatProviderId.CUSTOM_OPENAI -> {
                    val request = OpenAiCompatibleRequestFactory.build(
                        settings = settings,
                        model = requestSnapshot.model,
                        messages = promptMessages.map { message ->
                            OpenAiCompatibleMessageDto(role = message.role, content = message.content)
                        },
                        purpose = OpenAiCompatibleRequestFactory.Purpose.RP_STATE_UPDATE,
                    )
                    openAiCompatibleClient.completeChat(
                        requestSnapshot.apiKey,
                        requestSnapshot.baseUrl,
                        request,
                    ).choices.firstOrNull()?.message?.content.orEmpty()
                }
            }
            parseLooseRpStatusPanel(content)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Fallback RP status update failed: ${error.message}", error)
        }.getOrNull()
    }

    private fun buildRpStatusUpdateMessages(
        definitions: List<RpMetricDefinition>,
        currentPanel: RpStatusPanel?,
        lastUser: String,
        assistantContent: String,
    ): List<PromptMessage> {
        val system = """
            你是 muxueChat 的 RP 状态更新器，只负责根据最近一轮对话更新状态面板。
            必须只输出一个 <muxue_state> JSON 状态块，不要输出正文、解释、Markdown、代码围栏或额外字符。
            metrics 必须只包含用户定义过的状态条，不要新增、改名或遗漏。
            value 必须是整数，并且必须在对应 min/max 范围内。
            如果最近一轮没有足够理由改变某个数值，保持当前值。
        """.trimIndent()

        val user = buildString {
            appendLine("状态条定义与当前值：")
            definitions.forEach { definition ->
                val current = currentPanel?.metrics
                    ?.firstOrNull { it.name == definition.name }
                    ?.value
                    ?.coerceIn(definition.minValue, definition.maxValue)
                    ?: definition.initialValue
                appendLine(
                    "- ${definition.name}: 当前 $current，范围 ${definition.minValue}-${definition.maxValue}，初始 ${definition.initialValue}",
                )
                if (definition.description.isNotBlank()) {
                    appendLine("  含义：${definition.description}")
                }
                if (definition.progressRule.isNotBlank()) {
                    appendLine("  变化规则：${definition.progressRule}")
                }
            }
            appendLine()
            appendLine("当前关系：${currentPanel?.relationship.orEmpty().ifBlank { "未记录" }}")
            appendLine("当前场景：${currentPanel?.scene.orEmpty().ifBlank { "未记录" }}")
            appendLine()
            appendLine("最近用户发言：")
            appendLine(lastUser.take(STATE_UPDATE_CONTEXT_LIMIT))
            appendLine()
            appendLine("最新角色回复：")
            appendLine(assistantContent.takeTailForContext(STATE_UPDATE_CONTEXT_LIMIT))
            appendLine()
            appendLine("输出格式示例：")
            appendLine(buildStateBlockExample(definitions, currentPanel))
        }

        return listOf(
            PromptMessage(role = "system", content = system),
            PromptMessage(role = "user", content = user),
        )
    }

    private fun buildStateBlockExample(
        definitions: List<RpMetricDefinition>,
        currentPanel: RpStatusPanel?,
    ): String = RpStateBlockFormat.stateBlockExample(
        definitions = definitions,
        panel = currentPanel,
        useCurrentValues = true,
    )

    private fun parseLooseRpStatusPanel(content: String): RpStatusPanelDraft? {
        rpStateBlockParser.extract(content).panel?.let { return it }

        val cleaned = content
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val jsonStart = cleaned.indexOf('{')
        val jsonEnd = cleaned.lastIndexOf('}')
        if (jsonStart == -1 || jsonEnd <= jsonStart) return null

        val rawJson = cleaned.substring(jsonStart, jsonEnd + 1)
        return rpStateBlockParser.extract("<muxue_state>$rawJson</muxue_state>").panel
    }

    private suspend fun normalizeRpMetrics(
        conversationId: String,
        metrics: List<RpMetric>,
    ): List<RpMetric> {
        val definitions = getActiveMetricDefinitions(conversationId)
        if (definitions.isEmpty()) {
            return metrics.map { metric ->
                val min = metric.min
                val max = if (metric.max > min) metric.max else min + 1
                metric.copy(value = metric.value.coerceIn(min, max), min = min, max = max)
            }
        }

        return definitions.map { definition ->
            val matched = metrics.firstOrNull { it.name == definition.name }
            val value = matched?.value?.coerceIn(definition.minValue, definition.maxValue)
                ?: definition.initialValue
            RpMetric(
                name = definition.name,
                value = value,
                min = definition.minValue,
                max = definition.maxValue,
            )
        }
    }

    private suspend fun getActiveMetricDefinitions(conversationId: String): List<RpMetricDefinition> {
        return getActiveRoleCard(conversationId)?.metricDefinitions.orEmpty()
    }

    private suspend fun getActiveRoleCard(conversationId: String): RoleCard? {
        val rpSettings = chatDao.getConversationRpSettings(conversationId)?.toDomain()
            ?.takeIf { it.isRpEnabled }
            ?: return null
        return rpSettings.roleCardId?.let { chatDao.getRoleCard(it)?.toDomain() }
    }

    private fun appendNullable(current: String?, delta: String?): String? {
        if (delta.isNullOrEmpty()) return current
        return current.orEmpty() + delta
    }

    /**
     * 截取尾部而不是开头。状态变化、情绪转折和场景切换通常发生在一段剧情的后半段，
     * 从开头截断会把最需要被状态更新器看到的信息丢掉。
     */
    private fun String.takeTailForContext(limit: Int): String {
        if (length <= limit) return this
        return "（前文已省略）\n" + takeLast(limit)
    }

    private fun ensureThreeOptions(options: List<String>): List<String> {
        val cleaned = options.mapNotNull { option -> option.trim().takeIf { it.isNotBlank() } }
        if (cleaned.size >= OPTION_COUNT) return cleaned.take(OPTION_COUNT)

        val fallbackOptions = listOf(
            "继续观察她的反应，寻找新的推进点。",
            "主动开口，把对话推进到下一步。",
            "换一种方式打破眼前的僵局。",
        )
        return (cleaned + fallbackOptions)
            .distinct()
            .take(OPTION_COUNT)
    }

    private fun Throwable.toUserFacingMessage(provider: ChatProviderId): String = when (provider) {
        ChatProviderId.DEEPSEEK -> toDeepSeekUserFacingMessage()
        ChatProviderId.CUSTOM_OPENAI -> toOpenAiCompatibleUserFacingMessage()
    }

    /** DeepSeek 文案保持原有语义，避免既有 MVP 的错误提示回归。 */
    private fun Throwable.toDeepSeekUserFacingMessage(): String {
        val raw = message.orEmpty()
        return when {
            "HTTP 401" in raw -> "API Key 无效或未授权，请检查 DeepSeek API Key。"
            "HTTP 402" in raw -> "DeepSeek 账户余额不足或账单不可用。"
            "HTTP 429" in raw -> "请求过于频繁或触发限流，请稍后再试。"
            "HTTP 5" in raw -> "DeepSeek 服务暂时异常，请稍后再试。"
            raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("UnknownHost", ignoreCase = true) -> "网络不可用或无法解析 api.deepseek.com。"
            raw.contains("timeout", ignoreCase = true) -> "网络请求超时，请检查网络或稍后重试。"
            raw.contains("stream ended before completion", ignoreCase = true) -> "流式响应提前结束，没有收到完整回复。"
            raw.contains("thinking options type cannot be disabled", ignoreCase = true) ->
                "思考模式关闭时不能发送 reasoning_effort，请更新应用后重试。"
            raw.isNotBlank() -> raw
            else -> "生成失败，请检查 API Key、网络和模型配置。"
        }
    }

    /**
     * OpenAI 兼容链路的 HTTP/网络错误在客户端中已剥离响应体；这里再按 Provider 收敛，
     * 确保用户不会在使用自定义供应商时看到 DeepSeek 的域名、余额或 Key 提示。
     */
    private fun Throwable.toOpenAiCompatibleUserFacingMessage(): String {
        val raw = message.orEmpty()
        return when {
            raw.contains("401") || raw.contains("鉴权失败") || raw.contains("API Key") ->
                "供应商 API Key 无效或未授权，请检查设置。"
            raw.contains("402") || raw.contains("余额") || raw.contains("订阅") ->
                "供应商账户不可用或额度不足，请检查该服务的账户状态。"
            raw.contains("403") || raw.contains("拒绝访问") ->
                "供应商拒绝访问，请检查服务权限和 API Key。"
            raw.contains("429") || raw.contains("过于频繁") || raw.contains("限流") ->
                "供应商当前被限流，请稍后再试。"
            raw.contains("HTTP 5") || raw.contains("暂时不可用") ->
                "供应商服务暂时异常，请稍后再试。"
            raw.contains("HTTPS") || raw.contains("SSL", ignoreCase = true) ||
                raw.contains("证书") ->
                "供应商的 HTTPS 证书校验失败，请检查服务证书和地址。"
            raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("UnknownHost", ignoreCase = true) || raw.contains("无法连接供应商") ->
                "无法连接供应商，请检查网络、服务是否运行及 Base URL。"
            raw.contains("timeout", ignoreCase = true) ->
                "供应商请求超时，请检查网络或稍后再试。"
            raw.contains("stream ended before completion", ignoreCase = true) ->
                "供应商流式响应提前结束，没有收到完整回复。"
            raw.contains("地址无效") || raw.contains("Base URL", ignoreCase = true) ->
                "供应商地址无效，请在设置中填写 http:// 或 https:// 开头的 Base URL。"
            else -> "供应商请求失败，请检查 API Key、网络、Base URL 和模型配置。"
        }
    }

    private companion object {
        const val TAG = "muxueChat.ChatRepository"
        const val MAX_TITLE_LENGTH = 80

        /**
         * 传给状态更新器的正文上下文上限。取的是尾部而不是开头：
         * 状态变化和情绪转折通常发生在一段剧情的后半段，从开头截断会把关键信息丢掉。
         */
        const val STATE_UPDATE_CONTEXT_LIMIT = 4000
        const val OPTION_COUNT = 3

        /** 单条消息内允许的工具调用轮数上限，防止模型反复调用打转。 */
        const val MAX_TOOL_ROUNDS = 3
    }
}
