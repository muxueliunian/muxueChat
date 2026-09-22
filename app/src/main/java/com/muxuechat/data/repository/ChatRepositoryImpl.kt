package com.muxuechat.data.repository

import android.util.Log
import com.muxuechat.data.local.ChatDao
import com.muxuechat.data.local.ConversationEntity
import com.muxuechat.data.local.ConversationRpSettingsEntity
import com.muxuechat.data.local.MessageEntity
import com.muxuechat.data.local.RoleCardEntity
import com.muxuechat.data.local.RpStatusPanelEntity
import com.muxuechat.data.local.toEntity
import com.muxuechat.data.remote.OpenAiCompatibleClient
import com.muxuechat.data.remote.OpenAiCompatibleMessageDto
import com.muxuechat.data.remote.OpenAiCompatibleRequestFactory
import com.muxuechat.data.remote.OpenAiCompatibleStreamEvent
import com.muxuechat.data.remote.OpenAiCompatibleToolCallAccumulator
import com.muxuechat.data.remote.OpenAiCompatibleToolCallDto
import com.muxuechat.data.remote.OpenAiCompatibleUserFacingError
import com.muxuechat.data.remote.DeepSeekApiClient
import com.muxuechat.data.remote.DeepSeekMessageDto
import com.muxuechat.data.remote.DeepSeekRequestFactory
import com.muxuechat.data.remote.DeepSeekStreamEvent
import com.muxuechat.data.remote.DeepSeekToolCallAccumulator
import com.muxuechat.data.remote.DeepSeekToolCallDto
import com.muxuechat.data.remote.SettingsToolCatalog
import com.muxuechat.data.remote.XaiAuthException
import com.muxuechat.data.remote.XaiResponsesTransport
import com.muxuechat.domain.tool.SettingsTool
import com.muxuechat.domain.tool.SettingsToolArgumentParser
import com.muxuechat.domain.tool.SettingsToolDraft
import com.muxuechat.domain.tool.SettingsToolParseResult
import com.muxuechat.domain.model.ChatMessage
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.Conversation
import com.muxuechat.domain.model.ConversationTokenUsage
import com.muxuechat.domain.model.TokenUsageNormalizer
import com.muxuechat.domain.model.TokenUsageSnapshot
import com.muxuechat.domain.model.MessageStatus
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RpMetric
import com.muxuechat.domain.model.RpMetricDefinition
import com.muxuechat.domain.model.RpMessageMetadata
import com.muxuechat.domain.model.RpStatusPanel
import com.muxuechat.data.local.ConversationContextEntity
import com.muxuechat.data.local.ConversationTokenUsageEntity
import com.muxuechat.domain.context.ContextCompressionMode
import com.muxuechat.domain.context.ContextSummaryPrompt
import com.muxuechat.domain.context.ContextSummaryState
import com.muxuechat.domain.context.ChatContextInput
import com.muxuechat.domain.context.ChatContextPlanner
import com.muxuechat.domain.context.ContextToolDefinition
import com.muxuechat.domain.context.ContextWindowPlan
import com.muxuechat.domain.context.ModelContextCatalog
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import java.util.UUID

class ChatRepositoryImpl(
    private val chatDao: ChatDao,
    private val settingsRepository: SettingsRepository,
    private val apiClient: DeepSeekApiClient,
    /** 所有自定义供应商共用的 OpenAI 兼容聊天请求客户端。 */
    openAiCompatibleClient: OpenAiCompatibleClient,
    /** xAI 订阅链路的 Responses 传输适配器；与上面那个客户端藏在同一个入口之后。 */
    xaiResponsesTransport: XaiResponsesTransport,
    /** xAI 订阅登录的 token 来源；生产环境必须是 [XaiAuthRepository.getInstance]，刷新锁依赖该单例。 */
    xaiAccessTokens: XaiAccessTokenSource,
    private val promptComposer: PromptComposer = PromptComposer(),
    private val chatContextPlanner: ChatContextPlanner = ChatContextPlanner(promptComposer),
    private val settingsToolDraftStore: SettingsToolDraftStore = SettingsToolDraftStore(),
    private val settingsToolArgumentParser: SettingsToolArgumentParser = SettingsToolArgumentParser(),
    /** 后台补写 RP 元数据用。与请求流分离，正文完成后不再阻塞用户。 */
    private val enrichmentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val rpStateBlockParser: RpStateBlockParser = RpStateBlockParser(),
    private val rpMessageMetadataParser: RpMessageMetadataParser = RpMessageMetadataParser(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ChatRepository {
    private val providerRequestFreezer = ProviderRequestFreezer(settingsRepository, xaiAccessTokens)
    private val chatTransport = ChatTransportRouter(openAiCompatibleClient, xaiResponsesTransport)
    private val generationSessions = GenerationSessionRegistry()
    private val enrichmentTasks = RpEnrichmentTaskRegistry()

    /**
     * 正式历史保持不变时生成新分支所需的不可变快照。
     * 临时 assistant 是唯一允许在请求期间写入的行；其余修改延迟到成功提交事务。
     */
    private data class HistoryReplacementPlan(
        val expectedMessages: List<MessageEntity>,
        val obsoleteMessageIds: List<String>,
        val editedUserMessage: MessageEntity?,
        val promptHistory: List<PromptMessage>,
        val rpTurnSnapshot: RpTurnSnapshot,
    )

    private data class PreparedAssistant(
        val message: MessageEntity,
        val localStatusPanel: RpStatusPanel?,
        val hasLocalPanel: Boolean,
        val expectedRpState: Boolean,
        val needsEnrichment: Boolean,
    )

    /** 正文请求开始前冻结的 RP 轮次输入；完成提交与后台任务不得再查询“最新一轮”。 */
    private data class RpTurnSnapshot(
        val expectedRpSettings: ConversationRpSettingsEntity?,
        val expectedRoleCard: RoleCardEntity?,
        val rpSettings: com.muxuechat.domain.model.ConversationRpSettings?,
        val roleCard: RoleCard?,
        val definitions: List<RpMetricDefinition>,
        val currentPanel: RpStatusPanel?,
        val lastUser: String,
    )

    private data class RpEnrichmentInput(
        val expectedMessage: MessageEntity,
        val turn: RpTurnSnapshot,
        val hasLocalPanel: Boolean,
        val expectedRpState: Boolean,
        val requestSnapshot: ProviderRequestSnapshot,
    )

    override fun observeEnrichingMessageIds(): Flow<Set<String>> = enrichmentTasks.enrichingMessageIds

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

        val targetConversationId = conversationId ?: UUID.randomUUID().toString()
        val now = clock()
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

        var selectedProvider = ChatProviderId.DEEPSEEK
        val requestSnapshot = try {
            freezeProviderRequest { provider -> selectedProvider = provider }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val failed = persistSendPreflightFailure(
                conversationId = targetConversationId,
                userMessage = userMessage,
                provider = selectedProvider,
                error = error,
            ) ?: return@flow
            emit(failed.toDomain())
            throw IllegalStateException(failed.errorMessage)
        }

        // 清空/删除可能发生在本地预检期间；用户消息已不存在时绝不能继续创建 assistant 或发请求。
        if (chatDao.getConversation(targetConversationId) == null || chatDao.getMessage(userMessage.id) != userMessage) {
            return@flow
        }
        val sessionId = registerGenerationSession(
            conversationId = targetConversationId,
            requestSnapshot = requestSnapshot,
            isHistoryReplacement = false,
        )
        try {
            if (chatDao.getConversation(targetConversationId) == null ||
                chatDao.getMessage(userMessage.id) != userMessage
            ) {
                return@flow
            }
            emitAll(
                generateAssistant(
                    conversationId = targetConversationId,
                    requestSnapshot = requestSnapshot,
                    registeredSessionId = sessionId,
                ),
            )
        } finally {
            generationSessions.finish(sessionId)
        }
    }

    /** 普通发送预检失败仍保留用户输入，并落一条不含请求正文/凭据的可见失败回复。 */
    private suspend fun persistSendPreflightFailure(
        conversationId: String,
        userMessage: MessageEntity,
        provider: ChatProviderId,
        error: Throwable,
    ): MessageEntity? {
        if (chatDao.getConversation(conversationId) == null || chatDao.getMessage(userMessage.id) != userMessage) {
            return null
        }
        val failedAt = clock()
        val failed = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = ChatRole.ASSISTANT,
            content = "",
            reasoningContent = null,
            status = MessageStatus.FAILED,
            errorMessage = error.toSendPreflightUserFacingMessage(provider),
            createdAtMillis = failedAt,
            updatedAtMillis = failedAt,
        )
        return failed.takeIf {
            chatDao.insertFailedAssistantIfUserPresent(
                expectedUserMessage = userMessage,
                failedAssistant = failed,
                updatedAtMillis = failedAt,
            )
        }
    }

    override fun regenerateLastResponse(conversationId: String): Flow<ChatMessage> = flow {
        val messages = chatDao.getMessages(conversationId).filterNotIncompleteReplacementStaging()
        val lastAssistant = messages.lastOrNull { it.role == ChatRole.ASSISTANT }
            ?: error("没有可重新生成的回复")
        val lastConversationMessage = messages.lastOrNull {
            it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT
        }
        require(lastConversationMessage?.id == lastAssistant.id) { "只能重新生成最新回复" }
        val lastUserBeforeAssistant = messages
            .takeWhile { it.id != lastAssistant.id }
            .lastOrNull { it.role == ChatRole.USER }
            ?: error("没有可用于重新生成的用户消息")
        require(
            lastUserBeforeAssistant.status == MessageStatus.COMPLETED &&
                lastUserBeforeAssistant.content.isNotBlank(),
        ) { "上一条用户消息不可用" }

        // 凭据、地址和模型都在写入临时行前冻结；预检失败时数据库完全不变。
        val requestSnapshot = freezeProviderRequest()
        cancelEnrichmentForConversation(conversationId)
        val sessionId = registerGenerationSession(
            conversationId = conversationId,
            requestSnapshot = requestSnapshot,
            isHistoryReplacement = true,
        )
        try {
            check(chatDao.getMessages(conversationId).filterNotIncompleteReplacementStaging() == messages) {
                "消息已发生变化，请重试"
            }
            val promptHistory = HistoryReplacementHistory.beforeAssistant(messages, lastAssistant.id)
            val rpTurnSnapshot = captureRpTurnSnapshot(
                conversationId = conversationId,
                targetMessageId = lastAssistant.id,
                lastUserOverride = promptHistory.lastOrNull { it.role == "user" }?.content,
                resolveStatusBeforeTarget = true,
            )
            val replacementPlan = HistoryReplacementPlan(
                expectedMessages = messages,
                obsoleteMessageIds = listOf(lastAssistant.id),
                editedUserMessage = null,
                promptHistory = promptHistory,
                rpTurnSnapshot = rpTurnSnapshot,
            )

            emitAll(
                generateAssistant(
                    conversationId = conversationId,
                    requestSnapshot = requestSnapshot,
                    replacementPlan = replacementPlan,
                    registeredSessionId = sessionId,
                ),
            )
        } finally {
            generationSessions.finish(sessionId)
        }
    }

    override fun retryLatestRpOptions(conversationId: String): Flow<ChatMessage> = flow {
        val messages = chatDao.getMessages(conversationId)
        val lastAssistant = messages.lastOrNull {
            it.role == ChatRole.ASSISTANT &&
                it.status == MessageStatus.COMPLETED &&
                it.content.isNotBlank()
        } ?: error("没有可重试的最新回复")
        val lastConversationMessage = messages.lastOrNull { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
        require(lastConversationMessage?.id == lastAssistant.id) { "只能重试最新回复的剧情推进建议" }

        val requestSnapshot = freezeProviderRequest()
        val turn = captureRpTurnSnapshot(conversationId, lastAssistant.id)
        require(turn.roleCard != null) { "当前会话未启用可用的 RP 角色卡" }
        val input = RpEnrichmentInput(
            expectedMessage = lastAssistant,
            turn = turn,
            hasLocalPanel = true,
            expectedRpState = turn.definitions.isNotEmpty(),
            requestSnapshot = requestSnapshot,
        )
        val retryJob = currentCoroutineContext()[Job]
            ?: error("RP retry flow must run in a coroutine Job")
        val task = registerEnrichmentTask(input, retryJob)
        try {
            task.predecessor?.takeIf { it !== retryJob }?.join()
            check(isEnrichmentTaskCurrent(task)) { "补写任务已失效" }
            val savedMetadata = performRpEnrichment(input, task, requireOptions = true)
                ?: error("未能生成有效剧情推进建议")
            emit(lastAssistant.toDomain(savedMetadata))
        } finally {
            unregisterEnrichmentTask(task)
        }
    }

    override fun editUserMessageAndRegenerate(
        messageId: String,
        content: String,
    ): Flow<ChatMessage> = flow {
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { "消息内容不能为空" }

        val original = chatDao.getMessage(messageId) ?: error("消息不存在")
        require(original.role == ChatRole.USER) { "只能编辑玩家消息" }
        require(original.status == MessageStatus.COMPLETED) { "只能编辑已发送成功的玩家消息" }

        val conversationMessages = chatDao.getMessages(original.conversationId)
            .filterNotIncompleteReplacementStaging()
        val originalIndex = conversationMessages.indexOfFirst { it.id == original.id }
        require(originalIndex >= 0) { "消息不存在" }
        require(conversationMessages[originalIndex] == original) { "消息已发生变化，请重试" }

        // 与重新生成一致：先冻结完整路由快照，再允许创建非正式的临时回复。
        val requestSnapshot = freezeProviderRequest()
        cancelEnrichmentForConversation(original.conversationId)
        val sessionId = registerGenerationSession(
            conversationId = original.conversationId,
            requestSnapshot = requestSnapshot,
            isHistoryReplacement = true,
        )
        try {
            check(chatDao.getMessages(original.conversationId).filterNotIncompleteReplacementStaging() == conversationMessages) {
                "消息已发生变化，请重试"
            }
            val promptHistory = HistoryReplacementHistory.throughEditedUser(
                messages = conversationMessages,
                userMessageId = original.id,
                editedContent = trimmed,
            )
            val rpTurnSnapshot = captureRpTurnSnapshot(
                conversationId = original.conversationId,
                targetMessageId = original.id,
                lastUserOverride = trimmed,
                resolveStatusBeforeTarget = true,
            )
            val replacementPlan = HistoryReplacementPlan(
                expectedMessages = conversationMessages,
                obsoleteMessageIds = conversationMessages.drop(originalIndex + 1).map { it.id },
                editedUserMessage = original.copy(
                    content = trimmed,
                    status = MessageStatus.COMPLETED,
                    errorMessage = null,
                    updatedAtMillis = clock(),
                ),
                promptHistory = promptHistory,
                rpTurnSnapshot = rpTurnSnapshot,
            )

            emitAll(
                generateAssistant(
                    conversationId = original.conversationId,
                    requestSnapshot = requestSnapshot,
                    replacementPlan = replacementPlan,
                    registeredSessionId = sessionId,
                ),
            )
        } finally {
            generationSessions.finish(sessionId)
        }
    }

    override suspend fun editAssistantMessage(messageId: String, content: String) {
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { "消息内容不能为空" }

        val initial = chatDao.getMessage(messageId) ?: error("消息不存在")
        require(initial.role == ChatRole.ASSISTANT) { "只能编辑模型回复" }
        generationSessions.currentForConversation(initial.conversationId)?.let { session ->
            cancelGenerationSession(session)
        }
        cancelEnrichmentForConversation(initial.conversationId)

        val messages = chatDao.getMessages(initial.conversationId).filterNotIncompleteReplacementStaging()
        val originalIndex = messages.indexOfFirst { it.id == messageId }
        require(originalIndex >= 0) { "消息不存在" }
        val original = messages[originalIndex]
        require(original.role == ChatRole.ASSISTANT) { "只能编辑模型回复" }

        val now = clock()
        val affectedAssistantIds = messages.drop(originalIndex)
            .filter { it.role == ChatRole.ASSISTANT }
            .map { it.id }
        val downstreamCompletedAssistantIds = messages.drop(originalIndex + 1)
            .filter { it.role == ChatRole.ASSISTANT && it.status == MessageStatus.COMPLETED }
            .map { it.id }
        val editTurn = captureRpTurnSnapshot(
            conversationId = original.conversationId,
            targetMessageId = original.id,
            resolveStatusBeforeTarget = true,
        )
        val rollbackPanel = editTurn.currentPanel?.copy(updatedAtMillis = now)
        val committed = chatDao.editAssistantAndInvalidateDerivedData(
            expectedMessages = messages,
            editedMessage = original.copy(
                content = trimmed,
                reasoningContent = null,
                status = MessageStatus.COMPLETED,
                errorMessage = null,
                updatedAtMillis = now,
            ),
            expectedRpSettings = editTurn.expectedRpSettings,
            expectedRoleCard = editTurn.expectedRoleCard,
            affectedAssistantMessageIds = affectedAssistantIds,
            downstreamCompletedAssistantIds = downstreamCompletedAssistantIds,
            rollbackStatusPanel = rollbackPanel?.toEntity(),
            downstreamNotice = DOWNSTREAM_RP_INVALIDATED_NOTICE,
            updatedAtMillis = now,
        )
        check(committed) { "编辑期间消息历史已发生变化，请重试" }
        Log.i(TAG, "Assistant message edited: messageId=$messageId length=${trimmed.length}")
    }

    override suspend fun stopGeneration(conversationId: String?) {
        val session = generationSessions.currentForConversation(conversationId) ?: return
        cancelGenerationSession(session)
    }

    override suspend fun deleteConversation(conversationId: String) {
        generationSessions.currentForConversation(conversationId)?.let { session ->
            cancelGenerationSession(session)
        }
        cancelEnrichmentForConversation(conversationId)
        chatDao.deleteConversation(conversationId)
    }

    override fun cancelAllWork() {
        generationSessions.current()?.let { session ->
            session.job.cancel(CancellationException("聊天界面已关闭"))
        }
        val tasks = enrichmentTasks.removeAll()
        tasks.forEach { task -> task.job.cancel() }
        // Repository 由 ViewModel 独占；销毁后不再复用，必须同时关闭独立的后台 scope。
        enrichmentScope.cancel()
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
        generationSessions.currentForConversation(conversationId)?.let { session ->
            cancelGenerationSession(session)
        }
        cancelEnrichmentForConversation(conversationId)
        chatDao.clearConversationAndDerivedData(conversationId, clock())
    }

    private suspend fun cancelGenerationSession(session: GenerationSession) {
        val callerJob = currentCoroutineContext()[Job]
        session.job.cancel(CancellationException("生成已停止"))
        withContext(NonCancellable) {
            if (callerJob !== session.job) {
                session.job.join()
            }

            // Flow 的 CancellationException 分支是主路径；这里是竞态兜底，且严格限定到该 session 的消息。
            settleCancelledGenerationMessage(session)
        }
        generationSessions.finish(session.sessionId)
    }

    private suspend fun settleCancelledGenerationMessage(session: GenerationSession) {
        val messageId = session.assistantMessageId ?: return
        withContext(NonCancellable) {
            if (session.isHistoryReplacement) {
                // FIX-02：staging 从未成为正式历史，取消时只能删除，不能留下 CANCELLED 行。
                chatDao.deleteIncompleteHistoryReplacementMessage(
                    messageId = messageId,
                    conversationId = session.conversationId,
                    idPattern = "$HISTORY_REPLACEMENT_PREFIX%",
                    completedStatus = MessageStatus.COMPLETED,
                )
            } else {
                chatDao.updateGenerationMessageStatus(
                    messageId = messageId,
                    conversationId = session.conversationId,
                    pendingStatus = MessageStatus.PENDING,
                    streamingStatus = MessageStatus.STREAMING,
                    status = MessageStatus.CANCELLED,
                    errorMessage = null,
                    updatedAtMillis = clock(),
                )
            }
        }
    }

    private suspend fun cancelEnrichmentForConversation(conversationId: String) {
        val tasks = enrichmentTasks.removeConversation(conversationId)
        tasks.forEach { task -> task.job.cancel() }
        val callerJob = currentCoroutineContext()[Job]
        tasks.filter { task -> task.job !== callerJob }.forEach { task -> task.job.join() }
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

    private suspend fun freezeProviderRequest(
        onProviderSelected: (ChatProviderId) -> Unit = {},
    ): ProviderRequestSnapshot = providerRequestFreezer.freeze(onProviderSelected)

    /**
     * 在请求开始前冻结所有会影响路由的值，避免生成过程中切换 Provider、端点、模型或 Key 后，
     * 同一轮文本被拆到两个服务。凭据仅保留在局部变量，绝不写入消息、日志或 DataStore。
     */
    private fun generateAssistant(
        conversationId: String,
        requestSnapshot: ProviderRequestSnapshot? = null,
        replacementPlan: HistoryReplacementPlan? = null,
        registeredSessionId: String? = null,
    ): Flow<ChatMessage> = flow {
        val frozenRequest = requestSnapshot ?: freezeProviderRequest()
        val ownsSession = registeredSessionId == null
        val sessionId = registeredSessionId ?: registerGenerationSession(
            conversationId = conversationId,
            requestSnapshot = frozenRequest,
            isHistoryReplacement = replacementPlan != null,
        )
        try {
            // 在任何摘要网络请求之前冻结本轮 RP 输入。配置在压缩或正文生成期间发生变化时，
            // 主请求仍保持自洽，完成事务则通过 CAS 只提交正文、跳过旧派生数据。
            val rpTurnSnapshot = replacementPlan?.rpTurnSnapshot
                ?: captureRpTurnSnapshot(conversationId = conversationId)
            when (frozenRequest.provider) {
                ChatProviderId.DEEPSEEK -> {
                    emitAll(
                        generateDeepSeekAssistant(
                            conversationId = conversationId,
                            requestSnapshot = frozenRequest,
                            replacementPlan = replacementPlan,
                            sessionId = sessionId,
                            rpTurnSnapshot = rpTurnSnapshot,
                        ),
                    )
                }

                ChatProviderId.CUSTOM_OPENAI -> {
                    emitAll(
                        generateOpenAiCompatibleAssistant(
                            conversationId = conversationId,
                            requestSnapshot = frozenRequest,
                            replacementPlan = replacementPlan,
                            sessionId = sessionId,
                            rpTurnSnapshot = rpTurnSnapshot,
                        ),
                    )
                }
            }
        } finally {
            if (ownsSession) {
                generationSessions.finish(sessionId)
            }
        }
    }

    private suspend fun registerGenerationSession(
        conversationId: String,
        requestSnapshot: ProviderRequestSnapshot,
        isHistoryReplacement: Boolean,
    ): String {
        val generationJob = currentCoroutineContext()[Job]
            ?: error("Generation flow must run in a coroutine Job")
        val sessionId = UUID.randomUUID().toString()
        val session = GenerationSession(
            sessionId = sessionId,
            conversationId = conversationId,
            assistantMessageId = null,
            job = generationJob,
            providerSnapshot = GenerationProviderSnapshot(
                provider = requestSnapshot.provider,
                baseUrl = requestSnapshot.baseUrl,
                model = requestSnapshot.model,
            ),
            status = GenerationSessionStatus.STARTING,
            isHistoryReplacement = isHistoryReplacement,
        )
        try {
            generationSessions.start(session)?.let { previous ->
                if (previous.job !== generationJob) {
                    // 单生成策略：新请求接管前必须等旧请求完成不可取消的终态收束，不能只发 cancel 后并跑。
                    cancelGenerationSession(previous)
                }
            }
        } catch (error: Throwable) {
            generationSessions.finish(sessionId)
            throw error
        }
        return sessionId
    }

    /** 保留原有 DeepSeek 正文、工具、压缩与 RP 补写链路。 */
    private fun generateDeepSeekAssistant(
        conversationId: String,
        requestSnapshot: ProviderRequestSnapshot,
        replacementPlan: HistoryReplacementPlan? = null,
        sessionId: String,
        rpTurnSnapshot: RpTurnSnapshot,
    ): Flow<ChatMessage> = flow {
        val settings = requestSnapshot.settings
        val apiKey = requestSnapshot.requireApiKey()

        // 自动压缩放在发请求之前：这样本轮就能用上压缩后的上下文，
        // 而不是先超预算发一次、下轮才生效。代价是跨过阈值的那一轮会多等一次摘要请求。
        if (replacementPlan == null) {
            autoCompactIfNeeded(conversationId, requestSnapshot)
        }

        val startedAt = clock()
        val assistant = MessageEntity(
            id = newAssistantMessageId(replacementPlan),
            conversationId = conversationId,
            role = ChatRole.ASSISTANT,
            content = "",
            reasoningContent = null,
            status = MessageStatus.STREAMING,
            errorMessage = null,
            createdAtMillis = startedAt,
            updatedAtMillis = startedAt,
        )
        var current = assistant
        val streamingAccumulator = if (settings.streamEnabled) {
            StreamingMessageAccumulator(nowMillis = clock)
        } else {
            null
        }
        generationSessions.attachAssistant(
            sessionId = sessionId,
            assistantMessageId = assistant.id,
            isHistoryReplacement = replacementPlan != null,
        )
        try {
            chatDao.upsertMessage(assistant)
            emit(assistant.toDomain())
            val settingsToolsAvailable = shouldOfferSettingsTools(conversationId, settings)
            val tools = if (settingsToolsAvailable) SettingsToolCatalog.tools() else null
            // 工具轮产生的 assistant(tool_calls) 与 tool 结果消息，只在本次请求链里有效，不落库。
            val toolExchange = mutableListOf<DeepSeekMessageDto>()
            var contextPlan = planRequestContext(
                conversationId = conversationId,
                pendingAssistantId = assistant.id,
                settings = settings,
                settingsToolsAvailable = settingsToolsAvailable,
                replacementPlan = replacementPlan,
                rpTurnSnapshot = rpTurnSnapshot,
            )
            // 只保留最后一次真实网络请求的用量，不跨轮累加——累加出来的数字不是任何一次的 context 占用。
            var lastUsage: TokenUsageSnapshot? = null
            var round = 0
            while (true) {
                contextPlan.requireWithinBudget()
                val request = DeepSeekRequestFactory.build(
                    settings = settings,
                    messages = contextPlan.toDeepSeekMessages() + toolExchange,
                    purpose = DeepSeekRequestFactory.Purpose.CHAT,
                    tools = tools,
                    toolChoice = tools?.let { SettingsToolCatalog.TOOL_CHOICE_AUTO },
                )
                var roundUsage: TokenUsageSnapshot? = null

                val toolCalls: List<DeepSeekToolCallDto>
                var responseContent = ""
                val roundCursor = streamingAccumulator?.markRoundStart()
                var roundFlushDecision: StreamingFlushDecision? = null
                if (settings.streamEnabled) {
                    val textAccumulator = requireNotNull(streamingAccumulator)
                    val toolCallAccumulator = DeepSeekToolCallAccumulator()
                    var receivedTerminalEvent = false
                    apiClient.streamChat(apiKey, requestSnapshot.baseUrl, request).collect { event ->
                        when (event) {
                            is DeepSeekStreamEvent.Delta -> {
                                event.usage?.let { usage ->
                                    TokenUsageNormalizer.normalize(
                                        promptTokens = usage.promptTokens,
                                        completionTokens = usage.completionTokens,
                                        totalTokens = usage.totalTokens,
                                    )?.let { roundUsage = it }
                                }
                                toolCallAccumulator.accept(event.toolCalls)
                                textAccumulator.append(
                                    contentDelta = event.content,
                                    reasoningDelta = event.reasoningContent,
                                )?.let { decision ->
                                    current = applyStreamingFlush(current, decision) { emit(it) }
                                }
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
                    if (!receivedTerminalEvent && !toolCallAccumulator.hasCalls()) {
                        error("DeepSeek stream ended before completion")
                    }
                    val forcedRoundSnapshot = textAccumulator.forceSnapshot()
                    roundFlushDecision = forcedRoundSnapshot
                    current = current.withStreamingSnapshot(forcedRoundSnapshot.snapshot)
                    responseContent = textAccumulator.snapshotSince(requireNotNull(roundCursor)).content
                    toolCalls = if (tools != null) toolCallAccumulator.build() else emptyList()
                } else {
                    val response = apiClient.completeChat(apiKey, requestSnapshot.baseUrl, request)
                    roundUsage = response.usage?.let { usage ->
                        TokenUsageNormalizer.normalize(
                            promptTokens = usage.promptTokens,
                            completionTokens = usage.completionTokens,
                            totalTokens = usage.totalTokens,
                        )
                    }
                    val message = response.choices.firstOrNull()?.message
                    responseContent = message?.content.orEmpty()
                    current = current.copy(
                        content = current.content + responseContent,
                        reasoningContent = appendNullable(current.reasoningContent, message?.reasoningContent),
                        updatedAtMillis = clock(),
                    )
                    toolCalls = if (tools != null) message?.toolCalls.orEmpty() else emptyList()
                }
                lastUsage = roundUsage

                if (toolCalls.isEmpty()) break

                round++
                if (round > MAX_TOOL_ROUNDS) {
                    // 不静默截断：告诉用户发生了什么，而不是让他以为模型没反应。
                    Log.w(TAG, "Tool rounds exceeded: conversationId=$conversationId rounds=$round")
                    val notice = "（已达到本轮工具调用次数上限，剩余操作请另起一条消息继续。）"
                    if (streamingAccumulator == null) {
                        current = current.copy(
                            content = current.content.appendNotice(notice),
                            updatedAtMillis = clock(),
                        )
                    } else {
                        streamingAccumulator.appendNotice(notice)
                        current = current.withStreamingSnapshot(streamingAccumulator.forceSnapshot().snapshot)
                    }
                    break
                }

                // 只有确实还要进入下一轮网络请求时才刷出本轮中间态；最终轮直接进入原子完成事务，
                // 避免完整响应与 COMPLETED 提交之间留下可取消的 Room/UI 挂起点。
                roundFlushDecision?.let { decision ->
                    current = applyStreamingFlush(current, decision) { emit(it) }
                }

                toolExchange += DeepSeekMessageDto(
                    role = "assistant",
                    content = responseContent.takeIf { it.isNotBlank() },
                    // index 只用于流式拼接，不属于请求体字段，回传前清掉。
                    toolCalls = toolCalls.map { it.copy(index = null) },
                )
                toolExchange += executeSettingsToolCalls(conversationId, toolCalls)
                // 工具轮新增的消息也占 context，必须带上它们重新规划，否则越调用越接近超限却无人察觉。
                contextPlan = planRequestContext(
                    conversationId = conversationId,
                    pendingAssistantId = assistant.id,
                    settings = settings,
                    settingsToolsAvailable = settingsToolsAvailable,
                    replacementPlan = replacementPlan,
                    rpTurnSnapshot = rpTurnSnapshot,
                    toolExchange = toolExchange.toEstimationMessages(),
                )
            }

            if (current.status == MessageStatus.STREAMING) {
                streamingAccumulator?.forceSnapshot(StreamingTerminalReason.COMPLETED)?.snapshot?.let { snapshot ->
                    current = current.withStreamingSnapshot(snapshot)
                }
                val completed = completeAssistantMessage(
                    conversationId = conversationId,
                    message = current,
                    requestSnapshot = requestSnapshot,
                    replacementPlan = replacementPlan,
                    capturedTurn = rpTurnSnapshot,
                    tokenUsage = lastUsage.toUsageEntity(conversationId, requestSnapshot),
                )
                current = completed
                generationSessions.updateStatus(sessionId, GenerationSessionStatus.COMPLETED)
                emit(completed.toDomain())
            }
        } catch (error: CancellationException) {
            if (current.status == MessageStatus.COMPLETED || isCompletionCommitted(current)) {
                throw error
            }
            streamingAccumulator?.forceSnapshot(StreamingTerminalReason.CANCELLED)?.snapshot?.let { snapshot ->
                current = current.withStreamingSnapshot(snapshot)
            }
            val cancelled = current.copy(
                status = MessageStatus.CANCELLED,
                updatedAtMillis = maxOf(current.updatedAtMillis, clock()),
            )
            withContext(NonCancellable) {
                persistOrDiscardTerminalMessage(cancelled, replacementPlan)
            }
            generationSessions.updateStatus(sessionId, GenerationSessionStatus.CANCELLED)
            emit(cancelled.toDomain())
            throw error
        } catch (error: Throwable) {
            if (current.status == MessageStatus.COMPLETED || isCompletionCommitted(current)) {
                throw error
            }
            Log.w(TAG, "Generation failed: ${error.toSafeLogDescriptor()}")
            streamingAccumulator?.forceSnapshot(StreamingTerminalReason.FAILED)?.snapshot?.let { snapshot ->
                current = current.withStreamingSnapshot(snapshot)
            }
            val failed = current.copy(
                status = MessageStatus.FAILED,
                errorMessage = error.toUserFacingMessage(requestSnapshot.provider),
                updatedAtMillis = maxOf(current.updatedAtMillis, clock()),
            )
            withContext(NonCancellable) {
                persistOrDiscardTerminalMessage(failed, replacementPlan)
            }
            generationSessions.updateStatus(sessionId, GenerationSessionStatus.FAILED)
            // 会话可能在 session 注册前就被删除；此时只向 collector 抛错，不能发事件诱导 UI 选回幽灵 id。
            if (chatDao.getConversation(conversationId) != null) {
                emit(failed.toDomain())
            }
            throw IllegalStateException(failed.errorMessage)
        }
    }

    /** 供应商的正文、工具、压缩与 RP 补写全部复用同一 [ProviderRequestSnapshot]。 */
    private fun generateOpenAiCompatibleAssistant(
        conversationId: String,
        requestSnapshot: ProviderRequestSnapshot,
        replacementPlan: HistoryReplacementPlan? = null,
        sessionId: String,
        rpTurnSnapshot: RpTurnSnapshot,
    ): Flow<ChatMessage> = flow {
        val settings = requestSnapshot.settings
        val credential = requestSnapshot.credential
        if (replacementPlan == null) {
            autoCompactIfNeeded(conversationId, requestSnapshot)
        }

        val startedAt = clock()
        val assistant = MessageEntity(
            id = newAssistantMessageId(replacementPlan),
            conversationId = conversationId,
            role = ChatRole.ASSISTANT,
            content = "",
            reasoningContent = null,
            status = MessageStatus.STREAMING,
            errorMessage = null,
            createdAtMillis = startedAt,
            updatedAtMillis = startedAt,
        )
        var current = assistant
        val streamingAccumulator = if (settings.streamEnabled) {
            StreamingMessageAccumulator(nowMillis = clock)
        } else {
            null
        }
        generationSessions.attachAssistant(
            sessionId = sessionId,
            assistantMessageId = assistant.id,
            isHistoryReplacement = replacementPlan != null,
        )
        try {
            chatDao.upsertMessage(assistant)
            emit(assistant.toDomain())
            val settingsToolsAvailable = shouldOfferSettingsTools(conversationId, settings)
            val tools = if (settingsToolsAvailable) {
                SettingsToolCatalog.openAiCompatibleTools()
            } else {
                null
            }
            // 工具轮产生的 assistant(tool_calls) 与 tool 结果消息只在这次请求链有效，不落库。
            val toolExchange = mutableListOf<OpenAiCompatibleMessageDto>()
            var contextPlan = planRequestContext(
                conversationId = conversationId,
                pendingAssistantId = assistant.id,
                settings = settings,
                settingsToolsAvailable = settingsToolsAvailable,
                replacementPlan = replacementPlan,
                rpTurnSnapshot = rpTurnSnapshot,
            )
            var lastUsage: TokenUsageSnapshot? = null
            var round = 0
            while (true) {
                contextPlan.requireWithinBudget()
                val request = OpenAiCompatibleRequestFactory.build(
                    settings = settings,
                    model = requestSnapshot.model,
                    messages = contextPlan.toOpenAiCompatibleMessages() + toolExchange,
                    purpose = OpenAiCompatibleRequestFactory.Purpose.CHAT,
                    reasoningEffort = requestSnapshot.reasoningEffort,
                    tools = tools,
                    toolChoice = tools?.let { SettingsToolCatalog.TOOL_CHOICE_AUTO },
                    includeStreamUsage = requestSnapshot.includeStreamUsage,
                )
                var roundUsage: TokenUsageSnapshot? = null

                val toolCalls: List<OpenAiCompatibleToolCallDto>
                // 回传 assistant(tool_calls) 时只带本轮的正文，不能重复上一轮的工具前言。
                var responseContent = ""
                val roundCursor = streamingAccumulator?.markRoundStart()
                var roundFlushDecision: StreamingFlushDecision? = null
                if (settings.streamEnabled) {
                    val textAccumulator = requireNotNull(streamingAccumulator)
                    val toolCallAccumulator = OpenAiCompatibleToolCallAccumulator()
                    var receivedTerminalEvent = false
                    chatTransport.streamChat(
                        credential = credential,
                        baseUrl = requestSnapshot.baseUrl,
                        requestDto = request,
                    ).collect { event ->
                        when (event) {
                            is OpenAiCompatibleStreamEvent.Delta -> {
                                event.usage?.let { usage ->
                                    TokenUsageNormalizer.normalize(
                                        promptTokens = usage.promptTokens,
                                        completionTokens = usage.completionTokens,
                                        totalTokens = usage.totalTokens,
                                    )?.let { roundUsage = it }
                                }
                                toolCallAccumulator.accept(event.toolCalls)
                                textAccumulator.append(
                                    contentDelta = event.content,
                                    // 支持思考的模型会先推送 reasoning 增量，聊天页复用已有的折叠区展示。
                                    reasoningDelta = event.reasoningContent,
                                )?.let { decision ->
                                    current = applyStreamingFlush(current, decision) { emit(it) }
                                }
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
                    if (!receivedTerminalEvent && !toolCallAccumulator.hasCalls()) {
                        error("OpenAI-compatible stream ended before completion")
                    }
                    val forcedRoundSnapshot = textAccumulator.forceSnapshot()
                    roundFlushDecision = forcedRoundSnapshot
                    current = current.withStreamingSnapshot(forcedRoundSnapshot.snapshot)
                    responseContent = textAccumulator.snapshotSince(requireNotNull(roundCursor)).content
                    toolCalls = if (tools != null) toolCallAccumulator.build() else emptyList()
                } else {
                    val response = chatTransport.completeChat(
                        credential = credential,
                        baseUrl = requestSnapshot.baseUrl,
                        requestDto = request,
                    )
                    roundUsage = response.usage?.let { usage ->
                        TokenUsageNormalizer.normalize(
                            promptTokens = usage.promptTokens,
                            completionTokens = usage.completionTokens,
                            totalTokens = usage.totalTokens,
                        )
                    }
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
                lastUsage = roundUsage

                if (toolCalls.isEmpty()) break

                round++
                if (round > MAX_TOOL_ROUNDS) {
                    Log.w(TAG, "OpenAI-compatible tool rounds exceeded: conversationId=$conversationId rounds=$round")
                    val notice = "（已达到本轮工具调用次数上限，剩余操作请另起一条消息继续。）"
                    if (streamingAccumulator == null) {
                        current = current.copy(
                            content = current.content.appendNotice(notice),
                            updatedAtMillis = clock(),
                        )
                    } else {
                        streamingAccumulator.appendNotice(notice)
                        current = current.withStreamingSnapshot(streamingAccumulator.forceSnapshot().snapshot)
                    }
                    break
                }

                roundFlushDecision?.let { decision ->
                    current = applyStreamingFlush(current, decision) { emit(it) }
                }

                toolExchange += OpenAiCompatibleMessageDto(
                    role = "assistant",
                    content = responseContent.takeIf { it.isNotBlank() },
                    // index 只用于流式拼接，不属于标准回传消息字段。
                    toolCalls = toolCalls.map { it.copy(index = null) },
                )
                toolExchange += executeOpenAiCompatibleSettingsToolCalls(conversationId, toolCalls)
                contextPlan = planRequestContext(
                    conversationId = conversationId,
                    pendingAssistantId = assistant.id,
                    settings = settings,
                    settingsToolsAvailable = settingsToolsAvailable,
                    replacementPlan = replacementPlan,
                    rpTurnSnapshot = rpTurnSnapshot,
                    toolExchange = toolExchange.toEstimationMessages(),
                )
            }

            if (current.status == MessageStatus.STREAMING) {
                streamingAccumulator?.forceSnapshot(StreamingTerminalReason.COMPLETED)?.snapshot?.let { snapshot ->
                    current = current.withStreamingSnapshot(snapshot)
                }
                val completed = completeAssistantMessage(
                    conversationId = conversationId,
                    message = current,
                    requestSnapshot = requestSnapshot,
                    replacementPlan = replacementPlan,
                    capturedTurn = rpTurnSnapshot,
                    tokenUsage = lastUsage.toUsageEntity(conversationId, requestSnapshot),
                )
                current = completed
                generationSessions.updateStatus(sessionId, GenerationSessionStatus.COMPLETED)
                emit(completed.toDomain())
            }
        } catch (error: CancellationException) {
            if (current.status == MessageStatus.COMPLETED || isCompletionCommitted(current)) {
                throw error
            }
            streamingAccumulator?.forceSnapshot(StreamingTerminalReason.CANCELLED)?.snapshot?.let { snapshot ->
                current = current.withStreamingSnapshot(snapshot)
            }
            val cancelled = current.copy(
                status = MessageStatus.CANCELLED,
                updatedAtMillis = maxOf(current.updatedAtMillis, clock()),
            )
            withContext(NonCancellable) {
                persistOrDiscardTerminalMessage(cancelled, replacementPlan)
            }
            generationSessions.updateStatus(sessionId, GenerationSessionStatus.CANCELLED)
            emit(cancelled.toDomain())
            throw error
        } catch (error: Throwable) {
            if (current.status == MessageStatus.COMPLETED || isCompletionCommitted(current)) {
                throw error
            }
            Log.w(TAG, "OpenAI-compatible generation failed: ${error.javaClass.simpleName}")
            streamingAccumulator?.forceSnapshot(StreamingTerminalReason.FAILED)?.snapshot?.let { snapshot ->
                current = current.withStreamingSnapshot(snapshot)
            }
            val failed = current.copy(
                status = MessageStatus.FAILED,
                errorMessage = error.toUserFacingMessage(requestSnapshot.provider),
                updatedAtMillis = maxOf(current.updatedAtMillis, clock()),
            )
            withContext(NonCancellable) {
                persistOrDiscardTerminalMessage(failed, replacementPlan)
            }
            generationSessions.updateStatus(sessionId, GenerationSessionStatus.FAILED)
            if (chatDao.getConversation(conversationId) != null) {
                emit(failed.toDomain())
            }
            throw IllegalStateException(failed.errorMessage)
        }
    }

    /**
     * 把累加器的一份节流决策映射到当前消息。终态原因只用于保证最终快照完整，消息状态仍由
     * completeAssistantMessage / persistOrDiscardTerminalMessage 的既有事务路径负责。
     */
    private suspend fun applyStreamingFlush(
        message: MessageEntity,
        decision: StreamingFlushDecision,
        emitMessage: suspend (ChatMessage) -> Unit,
    ): MessageEntity {
        val updated = message.withStreamingSnapshot(decision.snapshot)
        if (decision.flushRoom) {
            chatDao.updateMessage(updated)
        }
        if (decision.flushUi) {
            emitMessage(updated.toDomain())
        }
        return updated
    }

    private fun MessageEntity.withStreamingSnapshot(snapshot: StreamingMessageSnapshot): MessageEntity = copy(
        content = snapshot.content,
        reasoningContent = snapshot.reasoningContent,
        updatedAtMillis = maxOf(this.updatedAtMillis, snapshot.observedAtMillis),
    )

    private fun newAssistantMessageId(replacementPlan: HistoryReplacementPlan?): String =
        if (replacementPlan == null) {
            UUID.randomUUID().toString()
        } else {
            "$HISTORY_REPLACEMENT_PREFIX${UUID.randomUUID()}"
        }

    /** 失败/取消的替换回复不是正式历史；只向 Flow 报告终态，Room 中立即回收临时行。 */
    private suspend fun persistOrDiscardTerminalMessage(
        message: MessageEntity,
        replacementPlan: HistoryReplacementPlan?,
    ) {
        if (replacementPlan == null) {
            chatDao.updateMessage(message)
            return
        }

        withContext(NonCancellable) {
            runCatching {
                if (chatDao.getMessage(message.id)?.status != MessageStatus.COMPLETED) {
                    chatDao.deleteMessage(message.id)
                }
            }
                .onFailure { error ->
                    Log.w(TAG, "Failed to discard history replacement staging: ${error.javaClass.simpleName}")
                }
        }
    }

    /** NonCancellable 完成事务返回边界可能重新抛出取消；此时不得把已完成行覆盖成取消/失败。 */
    private suspend fun isCompletionCommitted(message: MessageEntity): Boolean = withContext(NonCancellable) {
        chatDao.getMessage(message.id)?.status == MessageStatus.COMPLETED
    }

    private fun List<MessageEntity>.filterNotIncompleteReplacementStaging(): List<MessageEntity> =
        filterNot { message ->
            message.id.startsWith(HISTORY_REPLACEMENT_PREFIX) &&
                message.status != MessageStatus.COMPLETED
        }

    /**
     * 取出可进入 prompt 的历史。只包含已完成的用户/助手消息，
     * 失败、取消、流式中的消息都不算，避免把半成品当成剧情。
     */
    private suspend fun loadPromptHistory(
        conversationId: String,
        excludeMessageId: String? = null,
    ): List<PromptMessage> = chatDao.getMessages(conversationId).toPromptHistory(excludeMessageId)

    /**
     * 纯映射，不碰数据库：请求路径从 DAO 查一次，上下文视窗的 Flow 直接复用已经推送过来的行，
     * 两边必须用同一套过滤规则，否则 UI 显示的占用和真正发出去的请求会对不上。
     */
    private fun List<MessageEntity>.toPromptHistory(
        excludeMessageId: String? = null,
    ): List<PromptMessage> = this
        .filter { it.id != excludeMessageId }
        .filter { it.status == MessageStatus.COMPLETED }
        .filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
        .filter { it.content.isNotBlank() }
        .map { PromptMessage(role = it.role.name.lowercase(), content = it.content) }

    private suspend fun getSummaryState(conversationId: String): ContextSummaryState =
        chatDao.getConversationContext(conversationId)?.toDomain() ?: ContextSummaryState.EMPTY

    /**
     * 上下文占用的**唯一**响应式来源。
     *
     * 旧实现只在用户打开底部面板时算一次，于是顶栏百分比在第一次打开前是空的、之后一直是旧值。
     * 这里改成消息、摘要、RP 配置、状态栏、上次用量和全局设置任意一项变化都重算。
     *
     * `debounce` + `mapLatest` 是必需的：流式生成期间消息行会高频更新，
     * 不能每次 flush 都对全量历史重新估算。
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    override fun observeContextWindow(conversationId: String): Flow<ContextWindowPlan> {
        val conversationSignals = combine(
            chatDao.observeMessages(conversationId),
            chatDao.observeConversationContext(conversationId),
            chatDao.observeConversationRpSettings(conversationId),
            chatDao.observeRpStatusPanel(conversationId),
            chatDao.observeConversationTokenUsage(conversationId),
        ) { messages, context, rpSettings, panel, usage ->
            ContextWindowSignals(
                messages = messages,
                summaryState = context?.toDomain() ?: ContextSummaryState.EMPTY,
                rpSettings = rpSettings,
                statusPanel = panel,
                tokenUsage = usage,
            )
        }

        // 世界观与文风的**正文**修改不会改动 conversation_rp_settings，
        // 因此必须单独观察它们，否则改完设定后面板上的 token 数不动。
        val settingSignals = combine(
            settingsRepository.settings,
            chatDao.observeWorldSettings(),
            chatDao.observeWritingStyleProfiles(),
        ) { settings, _, _ -> settings }

        return combine(conversationSignals, settingSignals) { signals, settings -> signals to settings }
            .debounce(CONTEXT_WINDOW_DEBOUNCE_MILLIS)
            .mapLatest { (signals, settings) -> planContextWindow(conversationId, signals, settings) }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()
    }

    private data class ContextWindowSignals(
        val messages: List<MessageEntity>,
        val summaryState: ContextSummaryState,
        val rpSettings: ConversationRpSettingsEntity?,
        val statusPanel: RpStatusPanelEntity?,
        val tokenUsage: ConversationTokenUsageEntity?,
    )

    private suspend fun planContextWindow(
        conversationId: String,
        signals: ContextWindowSignals,
        settings: ChatSettings,
    ): ContextWindowPlan {
        val normalized = settings.normalized()
        val rpSettings = signals.rpSettings?.toDomain()
        val rpEnabled = rpSettings?.isRpEnabled == true
        val roleCard = rpSettings?.takeIf { rpEnabled }?.roleCardId?.let { chatDao.getRoleCard(it)?.toDomain() }

        return chatContextPlanner.plan(
            buildContextInput(
                settings = normalized,
                rpSettings = rpSettings,
                roleCard = roleCard,
                worldSetting = loadWorldSetting(rpSettings, rpEnabled),
                writingStyle = loadWritingStyle(rpSettings, rpEnabled),
                // 状态栏只有在真的绑了角色卡时才会进 prompt，估算必须跟请求保持一致。
                statusPanel = signals.statusPanel?.toDomain()?.takeIf { roleCard != null },
                history = signals.messages.toPromptHistory(),
                summaryState = signals.summaryState,
                settingsToolsAvailable = normalized.settingsToolsEnabledForActiveProvider && !rpEnabled,
                lastApiUsage = signals.tokenUsage?.toDomain(),
            ),
        )
    }

    override fun observeSettingsToolDrafts(): Flow<List<SettingsToolDraft>> = settingsToolDraftStore.drafts

    override fun discardSettingsToolDraft(draftId: String) {
        settingsToolDraftStore.remove(draftId)
    }

    override suspend fun compactContext(conversationId: String): Boolean {
        val requestSnapshot = freezeProviderRequest()
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
        val plan = chatContextPlanner.planCompaction(
            buildRequestContextInput(
                conversationId = conversationId,
                settings = settings,
                history = loadPromptHistory(conversationId),
                summaryState = getSummaryState(conversationId),
                rpTurnSnapshot = null,
            ),
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
                        requestSnapshot.requireApiKey(),
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
                    chatTransport.completeChat(
                        requestSnapshot.credential,
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
            Log.w(TAG, "Context compaction failed: ${error.toSafeLogDescriptor()}")
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
            Log.w(TAG, "Unknown tool requested: nameLength=${requestedName.length}")
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

        val plan = chatContextPlanner.plan(
            buildRequestContextInput(
                conversationId = conversationId,
                settings = settings,
                history = loadPromptHistory(conversationId),
                summaryState = getSummaryState(conversationId),
                rpTurnSnapshot = null,
            ),
        )
        if (!chatContextPlanner.shouldAutoCompact(plan)) return

        Log.i(
            TAG,
            "Auto compaction triggered: conversationId=$conversationId " +
                "usagePercent=${plan.usagePercent} triggerPercent=${policy.autoTriggerPercent} " +
                "estimatedTokens=${plan.estimatedInputTokens} budgetTokens=${plan.inputBudgetTokens}",
        )
        runCompaction(conversationId, requestSnapshot)
    }

    /**
     * 规划一次正文请求。
     *
     * 这是请求路径唯一的上下文入口：返回的 [ContextWindowPlan.requestMessages] 就是要发出去的内容，
     * 估算、裁剪、压缩判断和 UI 全部来自同一份规划结果，不再各算各的。
     */
    private suspend fun planRequestContext(
        conversationId: String,
        pendingAssistantId: String,
        settings: ChatSettings,
        settingsToolsAvailable: Boolean,
        replacementPlan: HistoryReplacementPlan? = null,
        rpTurnSnapshot: RpTurnSnapshot,
        toolExchange: List<PromptMessage> = emptyList(),
    ): ContextWindowPlan {
        val fullHistory = replacementPlan?.promptHistory
            ?: loadPromptHistory(conversationId, excludeMessageId = pendingAssistantId)

        val plan = chatContextPlanner.plan(
            buildRequestContextInput(
                conversationId = conversationId,
                settings = settings,
                history = fullHistory,
                // 编辑历史后，既有摘要的覆盖范围已不再可信；成功提交时会在同一事务内删除它。
                summaryState = if (replacementPlan != null) {
                    ContextSummaryState.EMPTY
                } else {
                    getSummaryState(conversationId)
                },
                rpTurnSnapshot = rpTurnSnapshot,
                settingsToolsAvailable = settingsToolsAvailable,
                toolExchange = toolExchange,
            ),
        )

        if (plan.hardTrimmedMessageCount > 0) {
            // 硬裁剪是保底安全网，走到这里说明摘要没能顶住，属于真正的信息损失，值得记一笔。
            Log.w(
                TAG,
                "Context hard-trimmed: conversationId=$conversationId " +
                    "trimmedMessages=${plan.hardTrimmedMessageCount} " +
                    "summarized=${plan.summarizedMessageCount} " +
                    "estimatedTokens=${plan.estimatedInputTokens} budgetTokens=${plan.inputBudgetTokens}",
            )
        }
        return plan
    }

    /**
     * 组装请求路径的规划输入。
     *
     * RP 配置、角色卡和状态面板必须来自请求开始时的同一轮快照 [rpTurnSnapshot]。若生成期间发生
     * 配置变化，完成事务的 CAS 会只提交正文并丢弃旧快照，不能在这里重新读取“最新配置”混入旧请求。
     * 压缩路径没有轮次快照，此时才允许现查——它本来就是独立的一次请求。
     */
    private suspend fun buildRequestContextInput(
        conversationId: String,
        settings: ChatSettings,
        history: List<PromptMessage>,
        summaryState: ContextSummaryState,
        rpTurnSnapshot: RpTurnSnapshot?,
        settingsToolsAvailable: Boolean? = null,
        toolExchange: List<PromptMessage> = emptyList(),
    ): ChatContextInput {
        val rpSettings = rpTurnSnapshot?.rpSettings
            ?: chatDao.getConversationRpSettings(conversationId)?.toDomain()
        val rpEnabled = rpSettings?.isRpEnabled == true
        val roleCard = if (rpTurnSnapshot != null) {
            rpTurnSnapshot.roleCard
        } else {
            rpSettings?.takeIf { rpEnabled }?.roleCardId?.let { chatDao.getRoleCard(it)?.toDomain() }
        }
        val statusPanel = if (rpTurnSnapshot != null) {
            rpTurnSnapshot.currentPanel
        } else {
            roleCard?.let { chatDao.getRpStatusPanel(conversationId)?.toDomain() }
        }

        return buildContextInput(
            settings = settings,
            rpSettings = rpSettings,
            roleCard = roleCard,
            worldSetting = loadWorldSetting(rpSettings, rpEnabled),
            writingStyle = loadWritingStyle(rpSettings, rpEnabled),
            statusPanel = statusPanel,
            history = history,
            summaryState = summaryState,
            settingsToolsAvailable = settingsToolsAvailable
                ?: (settings.settingsToolsEnabledForActiveProvider && !rpEnabled),
            toolExchange = toolExchange,
            // 请求路径不展示「上次实际用量」，没必要为此在每个工具轮多查一次库；
            // UI 的那条链路直接消费 observeConversationTokenUsage 推送过来的行。
            lastApiUsage = null,
        )
    }

    private fun buildContextInput(
        settings: ChatSettings,
        rpSettings: com.muxuechat.domain.model.ConversationRpSettings?,
        roleCard: RoleCard?,
        worldSetting: com.muxuechat.domain.model.WorldSetting?,
        writingStyle: com.muxuechat.domain.model.WritingStyleProfile?,
        statusPanel: RpStatusPanel?,
        history: List<PromptMessage>,
        summaryState: ContextSummaryState,
        settingsToolsAvailable: Boolean,
        toolExchange: List<PromptMessage> = emptyList(),
        lastApiUsage: ConversationTokenUsage? = null,
    ): ChatContextInput = ChatContextInput(
        protocol = settings.provider,
        settings = settings,
        rpSettings = rpSettings,
        roleCard = roleCard,
        worldSetting = worldSetting,
        writingStyle = writingStyle,
        statusPanel = statusPanel,
        history = history,
        summaryState = summaryState,
        toolDefinitions = if (settingsToolsAvailable) SettingsToolCatalog.contextDefinitions() else emptyList(),
        toolExchange = toolExchange,
        spec = ModelContextCatalog.resolve(settings),
        policy = settings.contextCompressionPolicy,
        lastApiUsage = lastApiUsage,
    )

    private suspend fun loadWorldSetting(
        rpSettings: com.muxuechat.domain.model.ConversationRpSettings?,
        rpEnabled: Boolean,
    ) = rpSettings
        ?.takeIf { rpEnabled }
        ?.worldSettingId
        ?.let { chatDao.getWorldSetting(it)?.toDomain() }

    private suspend fun loadWritingStyle(
        rpSettings: com.muxuechat.domain.model.ConversationRpSettings?,
        rpEnabled: Boolean,
    ) = rpSettings
        ?.takeIf { rpEnabled }
        ?.writingStyleId
        ?.let { chatDao.getWritingStyleProfile(it)?.toDomain() }

    /**
     * 工具轮消息的等价文本，只用于计入预算。
     *
     * assistant 的 tool_calls 与 tool 结果都是实打实发出去的字节，旧实现在工具轮里
     * 完全不重新规划上下文，等于每多一轮就凭空少算一截。
     */
    private fun List<DeepSeekMessageDto>.toEstimationMessages(): List<PromptMessage> = map { dto ->
        PromptMessage(
            role = dto.role,
            content = buildString {
                append(dto.content.orEmpty())
                dto.toolCalls.orEmpty().forEach { call ->
                    call.function?.name?.let { name -> append('\n').append(name) }
                    call.function?.arguments?.let { args -> append('\n').append(args) }
                }
            },
        )
    }

    @JvmName("openAiCompatibleToEstimationMessages")
    private fun List<OpenAiCompatibleMessageDto>.toEstimationMessages(): List<PromptMessage> = map { dto ->
        PromptMessage(
            role = dto.role,
            content = buildString {
                append(dto.content.orEmpty())
                dto.toolCalls.orEmpty().forEach { call ->
                    call.function?.name?.let { name -> append('\n').append(name) }
                    call.function?.arguments?.let { args -> append('\n').append(args) }
                }
            },
        )
    }

    private fun ContextWindowPlan.toDeepSeekMessages(): List<DeepSeekMessageDto> =
        requestMessages.map { DeepSeekMessageDto(role = it.role, content = it.content) }

    private fun ContextWindowPlan.toOpenAiCompatibleMessages(): List<OpenAiCompatibleMessageDto> =
        requestMessages.map { OpenAiCompatibleMessageDto(role = it.role, content = it.content) }

    /** 规划结果放不下时在本地失败，不去换一个服务端 400。 */
    private fun ContextWindowPlan.requireWithinBudget() {
        overflowError?.let { throw ContextOverflowException(it) }
    }

    /** 把本轮网络响应的用量落成可持久化的行；上游没给用量时返回 null，调用方不得覆盖旧快照。 */
    private fun TokenUsageSnapshot?.toUsageEntity(
        conversationId: String,
        requestSnapshot: ProviderRequestSnapshot,
    ): ConversationTokenUsageEntity? {
        val usage = this ?: return null
        return ConversationTokenUsageEntity(
            conversationId = conversationId,
            providerKey = requestSnapshot.usageProviderKey,
            model = requestSnapshot.model,
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            totalTokens = usage.totalTokens,
            effectiveContextTokens = ModelContextCatalog.resolve(requestSnapshot.settings).effectiveContextTokens,
            capturedAtMillis = clock(),
        )
    }

    /** 与 [ModelContextCatalog.providerKeyOf] 同源，避免持久化的 key 与 UI 的比较口径分叉。 */
    private val ProviderRequestSnapshot.usageProviderKey: String
        get() = ModelContextCatalog.providerKeyOf(settings)

    private suspend fun completeAssistantMessage(
        conversationId: String,
        message: MessageEntity,
        requestSnapshot: ProviderRequestSnapshot,
        replacementPlan: HistoryReplacementPlan? = null,
        capturedTurn: RpTurnSnapshot,
        /** 上游没返回用量时为 null，DAO 会保留上一份有效快照，而不是写入一行空数据。 */
        tokenUsage: ConversationTokenUsageEntity? = null,
    ): MessageEntity {
        val prepared = prepareAssistantMessage(conversationId, message, capturedTurn)
        val completed = prepared.message
        // 即使正文没有携带本地状态块，也要把本轮沿用的当前面板先落到消息快照。
        // 后台补写失败或尚未启动时，后续编辑/重生成仍能恢复到准确的历史切点。
        val completionStatusSnapshot = prepared.localStatusPanel
            ?: capturedTurn.currentPanel?.takeIf { prepared.expectedRpState }
        val completionMetadataSnapshot = completionStatusSnapshot?.let { panel ->
            createRpMessageMetadata(
                message = completed,
                metadata = null,
                statusPanelSnapshot = panel,
                now = completed.updatedAtMillis,
            ).toEntity()
        }

        val rpSnapshotCurrent = if (replacementPlan != null) {
            val committedStatusPanel = completionStatusSnapshot
                ?.copy(updatedAtMillis = completed.updatedAtMillis)
            // 一旦响应已完整生成，正式切换必须作为不可取消的短事务跑完，避免“已提交但向上抛取消”
            // 让 catch 误删刚转正的消息。旧分支补写的取消/等待也必须包含在这里，否则取消可能
            // 恰好打断在事务之前。网络请求本身不在 Room 事务里。
            withContext(NonCancellable) {
                cancelEnrichmentForConversation(conversationId)
                chatDao.commitHistoryReplacement(
                    conversationId = conversationId,
                    expectedMessages = replacementPlan.expectedMessages,
                    obsoleteMessageIds = replacementPlan.obsoleteMessageIds,
                    editedUserMessage = replacementPlan.editedUserMessage?.copy(
                        updatedAtMillis = completed.updatedAtMillis,
                    ),
                    completedAssistant = completed,
                    rpStatusPanel = committedStatusPanel?.toEntity(),
                    updatedAtMillis = completed.updatedAtMillis,
                    expectedRpSettings = capturedTurn.expectedRpSettings,
                    expectedRoleCard = capturedTurn.expectedRoleCard,
                    rpMetadataSnapshot = completionMetadataSnapshot,
                    tokenUsage = tokenUsage,
                )
            }
        } else {
            // 正文已经完整收到后，完成状态与本地 RP 派生数据必须作为一个不可取消短事务提交。
            // RP 配置若已变化，DAO 仍提交正文，但会保留用户的新 panel 并返回 false。
            withContext(NonCancellable) {
                chatDao.completeAssistantAndApplyRpState(
                    completedAssistant = completed,
                    expectedRpSettings = capturedTurn.expectedRpSettings,
                    expectedRoleCard = capturedTurn.expectedRoleCard,
                    rpStatusPanel = prepared.localStatusPanel?.toEntity(),
                    rpMetadataSnapshot = completionMetadataSnapshot,
                    tokenUsage = tokenUsage,
                    updatedAtMillis = completed.updatedAtMillis,
                )
            }
        }

        if (prepared.needsEnrichment && rpSnapshotCurrent) {
            runCatching {
                launchRpEnrichment(
                    RpEnrichmentInput(
                        expectedMessage = completed,
                        turn = capturedTurn.copy(
                            currentPanel = prepared.localStatusPanel ?: capturedTurn.currentPanel,
                        ),
                        hasLocalPanel = prepared.hasLocalPanel,
                        expectedRpState = prepared.expectedRpState,
                        requestSnapshot = requestSnapshot,
                    ),
                )
            }.onFailure { error ->
                // 正式历史已经提交，后台补写调度失败不能把成功回复回滚成失败或删除。
                Log.w(TAG, "Failed to schedule RP enrichment: ${error.javaClass.simpleName}")
            }
        }
        return completed
    }

    private suspend fun prepareAssistantMessage(
        conversationId: String,
        message: MessageEntity,
        turn: RpTurnSnapshot,
    ): PreparedAssistant {
        val contentExtraction = rpStateBlockParser.extract(message.content)
        val reasoningExtraction = message.reasoningContent?.let { rpStateBlockParser.extract(it) }
        val expectedRpState = turn.definitions.isNotEmpty()
        val needsEnrichment = turn.roleCard != null

        // 只做本地解析。剧情建议、内心独白、兜底状态都需要额外的网络往返，
        // 放在这里会让用户看完正文后还要干等一整次请求，因此移到后台补写。
        val panel = contentExtraction.panel ?: reasoningExtraction?.panel
        val now = maxOf(message.updatedAtMillis, clock())
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
        val localStatusPanel = panel?.takeIf { expectedRpState }?.let { panel ->
            RpStatusPanel(
                conversationId = conversationId,
                metrics = normalizeRpMetrics(turn.definitions, panel.metrics),
                relationship = panel.relationship,
                scene = panel.scene,
                rawJson = panel.rawJson,
                updatedAtMillis = now,
            )
        }
        return PreparedAssistant(
            message = completed,
            localStatusPanel = localStatusPanel,
            hasLocalPanel = localStatusPanel != null,
            expectedRpState = expectedRpState,
            needsEnrichment = needsEnrichment,
        )
    }

    /**
     * 后台补写剧情建议、内心独白和兜底状态。
     *
     * 与正文分离的原因：这两个请求加起来可能是一整次完整往返，串行执行会让
     * 「正文已经流完了但消息还在转圈」。改成后台补写后，结果通过 Room 的 Flow
     * 自然推回 UI，用户先看到正文，建议稍后出现。
     */
    private fun launchRpEnrichment(input: RpEnrichmentInput) {
        lateinit var task: RpEnrichmentTask
        val job = enrichmentScope.launch(start = CoroutineStart.LAZY) {
            try {
                task.predecessor?.takeIf { it !== currentCoroutineContext()[Job] }?.join()
                if (isEnrichmentTaskCurrent(task)) {
                    performRpEnrichment(input, task, requireOptions = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // 补写失败不能影响已经完成的正文，只记录不抛出。
                Log.w(TAG, "RP enrichment failed: ${error.javaClass.simpleName}")
            }
        }
        task = enrichmentTasks.register(
            conversationId = input.expectedMessage.conversationId,
            messageId = input.expectedMessage.id,
            job = job,
        )
        task.job.start()
    }

    private fun registerEnrichmentTask(input: RpEnrichmentInput, job: Job): RpEnrichmentTask =
        enrichmentTasks.register(
            conversationId = input.expectedMessage.conversationId,
            messageId = input.expectedMessage.id,
            job = job,
        )

    private fun unregisterEnrichmentTask(task: RpEnrichmentTask) = enrichmentTasks.unregister(task)

    private fun isEnrichmentTaskCurrent(task: RpEnrichmentTask): Boolean = enrichmentTasks.isCurrent(task)

    private suspend fun performRpEnrichment(
        input: RpEnrichmentInput,
        task: RpEnrichmentTask,
        requireOptions: Boolean,
    ): RpMessageMetadata? {
        check(isEnrichmentTaskCurrent(task)) { "补写任务已失效" }
        val metadata = requestRpMessageMetadata(input)
        if (requireOptions) {
            require(metadata != null && metadata.options.isNotEmpty()) { "未能生成有效剧情推进建议" }
        }
        val fallbackPanel = if (
            !input.hasLocalPanel && metadata?.statusPanel == null && input.expectedRpState
        ) {
            requestFallbackRpStatusPanel(input)
        } else {
            null
        }

        val now = clock()
        val resolvedPanel = metadata?.statusPanel ?: fallbackPanel
        val candidatePanel = resolvedPanel?.let { panel ->
            RpStatusPanel(
                conversationId = input.expectedMessage.conversationId,
                metrics = normalizeRpMetrics(input.turn.definitions, panel.metrics),
                relationship = panel.relationship,
                scene = panel.scene,
                rawJson = panel.rawJson,
                updatedAtMillis = now,
            )
        }
        val hasMetadataContent = metadata?.let { it.innerThought.isNotBlank() || it.options.isNotEmpty() } == true
        val savedMetadata = if (hasMetadataContent || candidatePanel != null) {
            createRpMessageMetadata(
                message = input.expectedMessage,
                metadata = metadata,
                statusPanelSnapshot = candidatePanel ?: input.turn.currentPanel,
                now = now,
            )
        } else {
            null
        }
        val missingStateMessage = if (
            !input.hasLocalPanel && resolvedPanel == null && input.expectedRpState
        ) {
            "本次回复未更新状态栏，已保留上一次状态。"
        } else {
            null
        }

        check(isEnrichmentTaskCurrent(task)) { "补写任务已失效" }
        val committed = chatDao.commitRpEnrichment(
            expectedMessage = input.expectedMessage,
            expectedRpSettings = input.turn.expectedRpSettings,
            expectedRoleCard = input.turn.expectedRoleCard,
            metadata = savedMetadata?.toEntity(),
            statusPanel = candidatePanel?.toEntity(),
            missingStateMessage = missingStateMessage,
            updatedAtMillis = now,
        )
        check(committed) { "回复或 RP 设置已发生变化，补写结果已丢弃" }
        return savedMetadata
    }

    /**
     * 冻结本轮 RP 配置、角色、当前面板与最近用户输入，不在请求前写库。
     * 历史替换可传入切点；普通发送省略切点时使用当前最后一条已完成用户消息。
     */
    private suspend fun captureRpTurnSnapshot(
        conversationId: String,
        targetMessageId: String? = null,
        lastUserOverride: String? = null,
        resolveStatusBeforeTarget: Boolean = false,
    ): RpTurnSnapshot {
        val messages = chatDao.getMessages(conversationId)
        require(!resolveStatusBeforeTarget || targetMessageId != null) { "恢复切点状态时必须提供目标消息" }
        val configuration = chatDao.getRpTurnConfigurationSnapshot(
            conversationId = conversationId,
            statusBeforeMessageId = targetMessageId.takeIf { resolveStatusBeforeTarget },
        )
        val rpSettingsEntity = configuration.settings
        val rpSettings = rpSettingsEntity?.toDomain()
        val activeRoleCardEntity = configuration.roleCard
        val roleCard = activeRoleCardEntity?.toDomain()
        val definitions = roleCard?.metricDefinitions.orEmpty()
        val statusPanel = if (resolveStatusBeforeTarget) {
            resolveRpStatusFromSnapshot(
                conversationId = conversationId,
                definitions = definitions,
                previousMetadata = configuration.statusBeforeMetadata,
                now = clock(),
            )
        } else {
            configuration.statusPanel?.toDomain()
        }
        return RpTurnSnapshot(
            expectedRpSettings = rpSettingsEntity,
            expectedRoleCard = activeRoleCardEntity,
            rpSettings = rpSettings,
            roleCard = roleCard,
            definitions = definitions,
            currentPanel = statusPanel,
            lastUser = lastUserOverride ?: if (targetMessageId != null) {
                RpHistoryMutation.lastCompletedUserContentBefore(
                    messages = messages,
                    targetMessageId = targetMessageId,
                )
            } else {
                messages.lastOrNull {
                    it.role == ChatRole.USER &&
                        it.status == MessageStatus.COMPLETED &&
                        it.content.isNotBlank()
                }?.content.orEmpty()
            },
        )
    }

    private fun resolveRpStatusFromSnapshot(
        conversationId: String,
        definitions: List<RpMetricDefinition>,
        previousMetadata: com.muxuechat.data.local.MessageRpMetadataEntity?,
        now: Long,
    ): RpStatusPanel? {
        if (definitions.isEmpty()) return null

        val previousSnapshot = previousMetadata
            ?.toDomain()
            ?.statusPanelSnapshot

        if (previousSnapshot != null) {
            return previousSnapshot.copy(
                metrics = normalizeRpMetrics(definitions, previousSnapshot.metrics),
                updatedAtMillis = now,
            )
        }

        return RpStatusPanel(
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
        )
    }

    private fun createRpMessageMetadata(
        message: MessageEntity,
        metadata: RpMessageMetadataDraft?,
        statusPanelSnapshot: RpStatusPanel?,
        now: Long,
    ): RpMessageMetadata {
        val options = metadata?.options.orEmpty()
        return RpMessageMetadata(
            messageId = message.id,
            conversationId = message.conversationId,
            innerThought = metadata?.innerThought.orEmpty().trim(),
            options = if (options.any { it.isNotBlank() }) ensureThreeOptions(options) else emptyList(),
            statusPanelSnapshot = statusPanelSnapshot,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
    }

    private suspend fun requestRpMessageMetadata(input: RpEnrichmentInput): RpMessageMetadataDraft? {
        val conversationId = input.expectedMessage.conversationId
        val assistantContent = input.expectedMessage.content
        val requestSnapshot = input.requestSnapshot
        val settings = requestSnapshot.settings
        val roleCard = input.turn.roleCard ?: return null

        val promptMessages = promptComposer.composeRpMetadata(
            RpMetadataPromptContext(
                rpSettings = input.turn.rpSettings,
                roleCard = roleCard,
                definitions = input.turn.definitions,
                currentPanel = input.turn.currentPanel,
                lastUser = input.turn.lastUser,
                assistantContent = assistantContent,
            ),
        )

        // 用户可以给 RP 辅助请求单独指定一个便宜模型；留空时保持与正文一致。
        val auxiliaryModelOverride = settings.rpAuxiliaryModel.trim()

        return runCatching {
            val content = when (requestSnapshot.provider) {
                ChatProviderId.DEEPSEEK -> {
                    val request = DeepSeekRequestFactory.build(
                        settings = settings,
                        messages = promptMessages.map { message ->
                            DeepSeekMessageDto(role = message.role, content = message.content)
                        },
                        purpose = DeepSeekRequestFactory.Purpose.RP_METADATA,
                        // 传原始覆盖值而不是 effectiveRpAuxiliaryModel：留空时让工厂回落到
                        // 它自己归一化过的 model，避免绕过 normalizeSelectedId。
                        modelOverride = auxiliaryModelOverride,
                    )
                    apiClient.completeChat(
                        requestSnapshot.requireApiKey(),
                        requestSnapshot.baseUrl,
                        request,
                    ).choices.firstOrNull()?.message?.content.orEmpty()
                }

                ChatProviderId.CUSTOM_OPENAI -> {
                    val request = OpenAiCompatibleRequestFactory.build(
                        settings = settings,
                        // 留空时回落到本次请求快照里的模型，保持与覆盖前完全一致的行为。
                        model = auxiliaryModelOverride.ifBlank { requestSnapshot.model },
                        messages = promptMessages.map { message ->
                            OpenAiCompatibleMessageDto(role = message.role, content = message.content)
                        },
                        purpose = OpenAiCompatibleRequestFactory.Purpose.RP_METADATA,
                    )
                    chatTransport.completeChat(
                        requestSnapshot.credential,
                        requestSnapshot.baseUrl,
                        request,
                    ).choices.firstOrNull()?.message?.content.orEmpty()
                }
            }
            rpMessageMetadataParser.parse(content).also { parsed ->
                Log.i(
                    TAG,
                    "RP metadata parsed: conversationId=$conversationId " +
                        // 模型名不是敏感信息，但能直接看出辅助模型覆盖有没有真的生效。
                        "auxiliaryModelOverridden=${auxiliaryModelOverride.isNotBlank()} " +
                        "responseLength=${content.length} optionsCount=${parsed?.options?.size ?: 0} " +
                        "parseSuccess=${parsed != null}",
                )
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "RP metadata request failed: ${error.toSafeLogDescriptor()}")
        }.getOrNull()
    }

    private suspend fun requestFallbackRpStatusPanel(input: RpEnrichmentInput): RpStatusPanelDraft? {
        val conversationId = input.expectedMessage.conversationId
        val assistantContent = input.expectedMessage.content
        val requestSnapshot = input.requestSnapshot
        val settings = requestSnapshot.settings
        val definitions = input.turn.definitions
        if (definitions.isEmpty()) return null

        val promptMessages = buildRpStatusUpdateMessages(
            definitions = definitions,
            currentPanel = input.turn.currentPanel,
            lastUser = input.turn.lastUser,
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
                        modelOverride = settings.rpAuxiliaryModel,
                    )
                    apiClient.completeChat(
                        requestSnapshot.requireApiKey(),
                        requestSnapshot.baseUrl,
                        request,
                    ).choices.firstOrNull()?.message?.content.orEmpty()
                }

                ChatProviderId.CUSTOM_OPENAI -> {
                    val request = OpenAiCompatibleRequestFactory.build(
                        settings = settings,
                        model = settings.rpAuxiliaryModel.trim().ifBlank { requestSnapshot.model },
                        messages = promptMessages.map { message ->
                            OpenAiCompatibleMessageDto(role = message.role, content = message.content)
                        },
                        purpose = OpenAiCompatibleRequestFactory.Purpose.RP_STATE_UPDATE,
                    )
                    chatTransport.completeChat(
                        requestSnapshot.credential,
                        requestSnapshot.baseUrl,
                        request,
                    ).choices.firstOrNull()?.message?.content.orEmpty()
                }
            }
            parseLooseRpStatusPanel(content)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Fallback RP status update failed: ${error.toSafeLogDescriptor()}")
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

    private fun normalizeRpMetrics(
        definitions: List<RpMetricDefinition>,
        metrics: List<RpMetric>,
    ): List<RpMetric> {
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

    private fun Throwable.toUserFacingMessage(provider: ChatProviderId): String =
        // 上下文超限的文案完全由 App 生成，不含上游原文或凭据，可以原样展示；
        // 收敛成「请求失败」反而让用户不知道该去缩短哪一段。
        if (this is ContextOverflowException) {
            message.orEmpty()
        } else {
            when (provider) {
                ChatProviderId.DEEPSEEK -> toDeepSeekUserFacingMessage()
                ChatProviderId.CUSTOM_OPENAI -> toOpenAiCompatibleUserFacingMessage()
            }
        }

    /** 本地预检失败只落固定安全文案，不把异常原文、地址或凭据相关内容写进消息。 */
    private fun Throwable.toSendPreflightUserFacingMessage(provider: ChatProviderId): String {
        // 这些文案完全由 App 生成、保证不含 token，原样展示才能让用户知道到底缺什么；
        // 按 Provider 收敛的兜底句会提到 Base URL 与 API Key，而 xAI 供应商没有这两项。
        if (this is XaiAuthException || this is SafeUserFacingError) return message.orEmpty()
        val mentionsApiKey = message.orEmpty().contains("API Key", ignoreCase = true)
        return when (provider) {
            ChatProviderId.DEEPSEEK -> if (mentionsApiKey) {
                "请先在设置中保存 DeepSeek API Key。"
            } else {
                "发送前检查失败，请检查 DeepSeek 地址、模型和 API Key。"
            }

            ChatProviderId.CUSTOM_OPENAI -> if (mentionsApiKey) {
                "请先在设置中保存当前供应商的 API Key。"
            } else {
                "供应商配置无效，请检查 Base URL、模型和 API Key。"
            }
        }
    }

    /** DeepSeek 的任何异常原文都不得进入日志、消息表或 UI。 */
    private fun Throwable.toDeepSeekUserFacingMessage(): String = toSafeDeepSeekUserFacingMessage()

    /**
     * 客户端标记的异常已经只保留结构化 code/message，并完成凭据清理和长度限制，
     * 可以直接展示；其他来源的异常仍按 Provider 收敛，避免把未知原文写入消息表。
     */
    private fun Throwable.toOpenAiCompatibleUserFacingMessage(): String {
        val raw = message.orEmpty()
        if (this is OpenAiCompatibleUserFacingError) return raw
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
        const val HISTORY_REPLACEMENT_PREFIX = "history-replacement:"

        /**
         * 传给状态更新器的正文上下文上限。取的是尾部而不是开头：
         * 状态变化和情绪转折通常发生在一段剧情的后半段，从开头截断会把关键信息丢掉。
         */
        const val STATE_UPDATE_CONTEXT_LIMIT = 4000
        const val OPTION_COUNT = 3
        const val DOWNSTREAM_RP_INVALIDATED_NOTICE = "前序回复已被编辑，本条回复保留，但其 RP 派生信息已失效。"

        /** 单条消息内允许的工具调用轮数上限，防止模型反复调用打转。 */
        const val MAX_TOOL_ROUNDS = 3

        /**
         * 上下文视窗重算的防抖窗口。
         *
         * 流式期间消息行会以远高于人眼的频率更新，每次都对全量历史重新估算纯属浪费；
         * 面板和顶栏晚 150ms 更新用户完全无感。
         */
        const val CONTEXT_WINDOW_DEBOUNCE_MILLIS = 150L
    }
}

/**
 * 本地上下文规划失败：不可裁剪的内容本身就装不进模型窗口。
 *
 * 单独一个类型是为了让 [ChatRepositoryImpl.toUserFacingMessage] 能放行它的文案——
 * 其余异常一律收敛成固定安全文案，避免把上游原文或凭据相关内容写进消息表。
 * 本异常的 message 全部由 App 生成，不含任何外部输入。
 */
internal class ContextOverflowException(message: String) : IllegalStateException(message)
