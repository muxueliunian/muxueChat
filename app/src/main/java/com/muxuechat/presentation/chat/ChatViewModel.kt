package com.muxuechat.presentation.chat

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.muxuechat.data.local.ChatDatabase
import com.muxuechat.data.local.EncryptedApiKeyStore
import com.muxuechat.data.local.EncryptedCliProxyApiKeyStore
import com.muxuechat.data.local.EncryptedProviderKeyStore
import com.muxuechat.data.document.DocumentImportResult
import com.muxuechat.data.document.DocumentTextExtractor
import com.muxuechat.data.local.SettingsDataStore
import com.muxuechat.data.remote.DeepSeekApiClient
import com.muxuechat.data.remote.OpenAiCompatibleClient
import com.muxuechat.data.repository.ChatRepository
import com.muxuechat.data.repository.ChatRepositoryImpl
import com.muxuechat.data.repository.RpRepository
import com.muxuechat.data.repository.RpRepositoryImpl
import com.muxuechat.data.repository.SettingsRepository
import com.muxuechat.data.repository.SettingsRepositoryImpl
import com.muxuechat.data.remote.XaiResponsesTransport
import com.muxuechat.data.repository.XaiAuthRepository
import com.muxuechat.domain.model.ChatMessage
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.Conversation
import com.muxuechat.domain.model.ConversationRpSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.DeepSeekModels
import com.muxuechat.domain.model.DisplayScale
import com.muxuechat.domain.model.DisplaySettings
import com.muxuechat.domain.model.MessageStatus
import com.muxuechat.domain.model.ProviderAuthKind
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RoleCardDraft
import com.muxuechat.domain.model.RpMarkerMode
import com.muxuechat.domain.model.RpNarrativePerspective
import com.muxuechat.domain.model.RpPlotSuggestionPerspective
import com.muxuechat.domain.model.RpStatusPanel
import com.muxuechat.domain.model.WorldSetting
import com.muxuechat.domain.model.WorldSettingDraft
import com.muxuechat.domain.model.WritingStyleProfile
import com.muxuechat.domain.model.WritingStyleProfileDraft
import com.muxuechat.domain.context.ContextCompressionMode
import com.muxuechat.domain.context.ContextWindowPlan
import com.muxuechat.domain.prompt.AttachmentFormat
import com.muxuechat.domain.tool.SettingsToolDraft
import com.muxuechat.domain.usecase.ClearConversationUseCase
import com.muxuechat.domain.usecase.DeleteConversationUseCase
import com.muxuechat.domain.usecase.EditAssistantMessageUseCase
import com.muxuechat.domain.usecase.EditUserMessageAndRegenerateUseCase
import com.muxuechat.domain.usecase.RegenerateLastResponseUseCase
import com.muxuechat.domain.usecase.RenameConversationUseCase
import com.muxuechat.domain.usecase.RetryRpOptionsUseCase
import com.muxuechat.domain.usecase.SendMessageUseCase
import com.muxuechat.domain.usecase.StopGenerationUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val sendMessageUseCase: SendMessageUseCase,
    private val regenerateLastResponseUseCase: RegenerateLastResponseUseCase,
    private val retryRpOptionsUseCase: RetryRpOptionsUseCase,
    private val editUserMessageAndRegenerateUseCase: EditUserMessageAndRegenerateUseCase,
    private val editAssistantMessageUseCase: EditAssistantMessageUseCase,
    private val stopGenerationUseCase: StopGenerationUseCase,
    private val deleteConversationUseCase: DeleteConversationUseCase,
    private val renameConversationUseCase: RenameConversationUseCase,
    private val clearConversationUseCase: ClearConversationUseCase,
    private val chatRepository: ChatRepository,
    private val rpRepository: RpRepository,
    private val settingsRepository: SettingsRepository,
    private val documentTextExtractor: DocumentTextExtractor,
) : ViewModel() {
    private val activeConversationId = MutableStateFlow<String?>(null)
    private val transient = MutableStateFlow(TransientState())
    private var generationSession: UiGenerationSession? = null

    init {
        viewModelScope.launch {
            // 旧版本的 CLIProxyAPI 配置要在任何界面读取设置前搬成普通供应商记录。
            runCatching { settingsRepository.migrateLegacyProviderIfNeeded() }
                .onFailure { error -> Log.w(TAG, "Legacy provider migration failed: ${error.message}", error) }
            refreshSavedProviderKeys()
        }
    }

    private val messages = activeConversationId.flatMapLatest { conversationId ->
        if (conversationId == null) flowOf(emptyList()) else chatRepository.observeMessages(conversationId)
    }

    private val activeRpSettings = activeConversationId.flatMapLatest { conversationId ->
        if (conversationId == null) flowOf(null) else rpRepository.observeConversationRpSettings(conversationId)
    }

    private val activeRpStatusPanel = activeConversationId.flatMapLatest { conversationId ->
        if (conversationId == null) flowOf(null) else rpRepository.observeRpStatusPanel(conversationId)
    }

    /**
     * 上下文占用。
     *
     * 以前只有用户点开底部面板才算一次，于是顶栏百分比在第一次点开前一直是空的、之后一直是旧值。
     * 现在由 Repository 持续推送，顶栏和面板消费同一份数据。
     */
    private val contextWindowPlan: Flow<ContextWindowPlan?> = activeConversationId
        .flatMapLatest { conversationId ->
            if (conversationId == null) {
                flowOf(null)
            } else {
                chatRepository.observeContextWindow(conversationId)
                    .map<ContextWindowPlan, ContextWindowPlan?> { it }
                    // 估算失败不能把聊天页拖垮：占用显示不出来最多是少一个提示。
                    .catch { error ->
                        Log.w(TAG, "Observing context window failed: ${error.message}", error)
                        emit(null)
                    }
                    // 上游做了防抖，首个规划结果要等一小段时间。uiState 是 combine 出来的，
                    // 不先给一个占位值的话，切换会话后整页（消息、设置、RP）都会跟着一起空等。
                    .onStart { emit(null) }
            }
        }

    private val coreState = combine(
        chatRepository.observeConversations(),
        activeConversationId,
        messages,
        settingsRepository.settings,
        transient,
    ) { conversations, activeId, messages, settings, transient ->
        CoreState(
            conversations = conversations,
            activeConversationId = activeId,
            messages = messages,
            settings = settings,
            isGenerating = transient.isGenerating,
            retryingRpOptionsMessageId = transient.retryingRpOptionsMessageId,
            errorMessage = transient.errorMessage,
            isCompactingContext = transient.isCompactingContext,
            isSavingSettingsDraft = transient.isSavingSettingsDraft,
            pendingAttachment = transient.pendingAttachment,
            providersWithSavedKey = transient.providersWithSavedKey,
            isImportingAttachment = transient.isImportingAttachment,
            modelRefreshErrors = transient.modelRefreshErrors,
            refreshingModelsProviderId = transient.refreshingModelsProviderId,
        )
    }

    private val rpState = combine(
        rpRepository.observeRoleCards(),
        rpRepository.observeWorldSettings(),
        rpRepository.observeWritingStyleProfiles(),
        activeRpSettings,
        activeRpStatusPanel,
    ) { roleCards, worldSettings, writingStyles, activeRpSettings, activeRpStatusPanel ->
        RpUiSlice(
            roleCards = roleCards,
            worldSettings = worldSettings,
            writingStyleProfiles = writingStyles,
            activeRpSettings = activeRpSettings,
            activeRpStatusPanel = activeRpStatusPanel,
        )
    }

    private val toolState = combine(
        chatRepository.observeSettingsToolDrafts(),
        chatRepository.observeEnrichingMessageIds(),
    ) { drafts, enriching ->
        ToolUiSlice(drafts = drafts, enrichingMessageIds = enriching)
    }

    val uiState: StateFlow<ChatUiState> = combine(
        coreState,
        rpState,
        toolState,
        settingsRepository.displaySettings,
        contextWindowPlan,
    ) { core, rp, tool, display, contextPlan ->
        ChatUiState(
            conversations = core.conversations,
            activeConversationId = core.activeConversationId,
            messages = core.messages,
            settings = core.settings,
            displaySettings = display,
            isGenerating = core.isGenerating,
            retryingRpOptionsMessageId = core.retryingRpOptionsMessageId,
            errorMessage = core.errorMessage,
            roleCards = rp.roleCards,
            worldSettings = rp.worldSettings,
            activeRpSettings = rp.activeRpSettings,
            activeRoleCard = rp.roleCards.firstOrNull { it.id == rp.activeRpSettings?.roleCardId },
            activeWorldSetting = rp.worldSettings.firstOrNull { it.id == rp.activeRpSettings?.worldSettingId },
            activeRpStatusPanel = rp.activeRpStatusPanel,
            writingStyleProfiles = rp.writingStyleProfiles,
            activeWritingStyle = rp.writingStyleProfiles
                .firstOrNull { it.id == rp.activeRpSettings?.writingStyleId },
            enrichingMessageIds = tool.enrichingMessageIds,
            contextWindowPlan = contextPlan,
            isCompactingContext = core.isCompactingContext,
            settingsToolDrafts = tool.drafts,
            isSavingSettingsDraft = core.isSavingSettingsDraft,
            pendingAttachment = core.pendingAttachment,
            providersWithSavedKey = core.providersWithSavedKey,
            isImportingAttachment = core.isImportingAttachment,
            modelRefreshErrors = core.modelRefreshErrors,
            refreshingModelsProviderId = core.refreshingModelsProviderId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    fun createConversation() {
        viewModelScope.launch {
            runCatching {
                chatRepository.createConversation()
            }.onSuccess { conversation ->
                activeConversationId.value = conversation.id
                transient.update { it.copy(errorMessage = null) }
            }.onFailure { error ->
                Log.w(TAG, "Creating conversation failed: ${error.message}", error)
                transient.update { it.copy(errorMessage = "新建会话失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    fun selectConversation(conversationId: String?) {
        activeConversationId.value = conversationId
        transient.update { it.copy(errorMessage = null) }
    }

    /** 选择本地 .txt/.md 文件作为附件。提取失败时只提示，不改变已有附件。 */
    fun attachDocument(uri: Uri) {
        if (transient.value.isImportingAttachment) return
        transient.update { it.copy(isImportingAttachment = true) }
        viewModelScope.launch {
            try {
            when (val result = documentTextExtractor.extract(uri)) {
                is DocumentImportResult.Success -> {
                    Log.i(
                        TAG,
                        // 只记文件名和长度，正文绝不进日志。
                        "Document attached: name=${result.document.fileName} chars=${result.document.charCount}",
                    )
                    transient.update {
                        it.copy(
                            pendingAttachment = PendingAttachment(
                                fileName = result.document.fileName,
                                text = result.document.text,
                            ),
                            errorMessage = "已附加「${result.document.fileName}」（${result.document.charCount} 字）",
                        )
                    }
                }

                is DocumentImportResult.Failure -> {
                    transient.update { it.copy(errorMessage = result.reason) }
                }
            }
            } finally {
                transient.update { it.copy(isImportingAttachment = false) }
            }
        }
    }

    /** 直接粘贴一段文字作为附件，和文件导入走同一套规范化处理。 */
    fun attachPastedText(name: String, text: String) {
        val normalized = DocumentTextExtractor.normalize(text)
        when {
            normalized.isBlank() ->
                transient.update { it.copy(errorMessage = "粘贴的内容是空的。") }

            normalized.length > DocumentTextExtractor.MAX_TEXT_LENGTH ->
                transient.update {
                    it.copy(
                        errorMessage = "粘贴的内容 ${normalized.length} 字，超过上限 " +
                            "${DocumentTextExtractor.MAX_TEXT_LENGTH} 字，请先截取需要的部分。",
                    )
                }

            else -> transient.update {
                it.copy(
                    pendingAttachment = PendingAttachment(
                        fileName = name.trim().ifBlank { "粘贴的文本" },
                        text = normalized,
                    ),
                    errorMessage = "已附加文本（${normalized.length} 字）",
                )
            }
        }
    }

    fun clearAttachment() {
        transient.update { it.copy(pendingAttachment = null) }
    }

    fun sendMessage(content: String) {
        val currentConversationId = activeConversationId.value
        val attachment = transient.value.pendingAttachment
        val composed = AttachmentFormat.composeUserMessage(
            userText = content,
            attachmentName = attachment?.fileName,
            attachmentText = attachment?.text,
        )
        if (composed.isBlank()) return

        transient.update { it.copy(pendingAttachment = null) }
        startGeneration(
            initialConversationId = currentConversationId,
            autoSelectCreatedConversation = currentConversationId == null,
            operationLabel = "Sending message",
            fallbackError = "发送失败",
        ) {
            Log.i(
                TAG,
                "Sending message: conversationId=$currentConversationId length=${composed.length} " +
                    "hasAttachment=${attachment != null}",
            )
            sendMessageUseCase(currentConversationId, composed)
        }
    }

    fun regenerateLastResponse() {
        val conversationId = activeConversationId.value ?: return
        startGeneration(
            initialConversationId = conversationId,
            autoSelectCreatedConversation = false,
            operationLabel = "Regenerate",
            fallbackError = "重新生成失败",
        ) {
            Log.i(TAG, "Regenerating last response: conversationId=$conversationId")
            regenerateLastResponseUseCase(conversationId)
        }
    }

    fun retryLatestRpOptions() {
        val conversationId = activeConversationId.value ?: return
        if (transient.value.isGenerating) {
            transient.update { it.copy(errorMessage = "正在生成时不能重试建议") }
            return
        }
        if (transient.value.retryingRpOptionsMessageId != null) return

        val targetMessage = uiState.value.messages
            .lastOrNull {
                it.role == ChatRole.ASSISTANT &&
                    it.status == MessageStatus.COMPLETED &&
                    it.content.isNotBlank()
            } ?: return

        viewModelScope.launch {
            Log.i(TAG, "Retrying RP options: conversationId=$conversationId messageId=${targetMessage.id}")
            transient.update {
                it.copy(
                    retryingRpOptionsMessageId = targetMessage.id,
                    errorMessage = null,
                )
            }
            try {
                retryRpOptionsUseCase(conversationId).collect {
                    // 用户已切换会话时只让 Room 自然更新原会话，不能把界面强制切回。
                    transient.update { state -> state.copy(errorMessage = "剧情推进建议已更新") }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Retrying RP options failed: ${error.javaClass.simpleName}")
                transient.update {
                    it.copy(errorMessage = error.message ?: "剧情推进建议生成失败，可稍后重试")
                }
            } finally {
                transient.update { it.copy(retryingRpOptionsMessageId = null) }
            }
        }
    }

    fun editUserMessageAndRegenerate(messageId: String, content: String) {
        if (transient.value.isGenerating) {
            transient.update { it.copy(errorMessage = "正在生成时不能编辑历史消息") }
            return
        }

        startGeneration(
            initialConversationId = uiState.value.messages
                .firstOrNull { message -> message.id == messageId }
                ?.conversationId
                ?: activeConversationId.value,
            autoSelectCreatedConversation = false,
            operationLabel = "Editing user message",
            fallbackError = "编辑历史消息失败",
        ) {
            Log.i(TAG, "Editing user message and regenerating: messageId=$messageId length=${content.length}")
            editUserMessageAndRegenerateUseCase(messageId, content)
        }
    }

    /**
     * 就地改写一条模型回复。不重新生成、不删除后续消息，
     * 改完的正文直接作为历史参与下一次请求。
     */
    fun editAssistantMessage(messageId: String, content: String) {
        if (transient.value.isGenerating) {
            transient.update { it.copy(errorMessage = "正在生成时不能编辑回复") }
            return
        }

        viewModelScope.launch {
            Log.i(TAG, "Editing assistant message: messageId=$messageId length=${content.length}")
            transient.update { it.copy(errorMessage = null) }
            runCatching { editAssistantMessageUseCase(messageId, content) }
                .onSuccess {
                    transient.update { it.copy(errorMessage = "回复已修改") }
                }
                .onFailure { error ->
                    Log.w(TAG, "Editing assistant message failed: ${error.message}", error)
                    transient.update { it.copy(errorMessage = error.message ?: "编辑回复失败") }
                }
        }
    }

    fun stopGeneration() {
        val session = generationSession ?: return
        viewModelScope.launch {
            Log.i(TAG, "Stopping generation: sessionId=${session.sessionId} conversationId=${session.conversationId}")
            runCatching {
                // Repository 先按 conversation 定向取消并收束消息状态；若尚未注册，再取消 UI Job 兜底。
                stopGenerationUseCase(session.conversationId)
                session.job.cancelAndJoin()
            }.onFailure { error ->
                if (error !is CancellationException) {
                    Log.w(TAG, "Stopping generation failed: ${error.javaClass.simpleName}")
                    transient.update { it.copy(errorMessage = "停止生成失败，请重试") }
                }
            }
            finishUiGenerationSession(session.sessionId)
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            runCatching {
                deleteConversationUseCase(conversationId)
            }.onSuccess {
                if (activeConversationId.value == conversationId) {
                    activeConversationId.value = null
                }
                transient.update {
                    it.copy(
                        isGenerating = generationSession?.job?.isActive == true,
                        errorMessage = "会话已删除",
                    )
                }
            }.onFailure { error ->
                Log.w(TAG, "Deleting conversation failed: ${error.message}", error)
                transient.update { it.copy(errorMessage = "删除会话失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    fun renameConversation(conversationId: String, title: String) {
        viewModelScope.launch {
            runCatching {
                renameConversationUseCase(conversationId, title)
            }.onSuccess {
                transient.update { it.copy(errorMessage = "会话已重命名") }
            }.onFailure { error ->
                Log.w(TAG, "Renaming conversation failed: ${error.message}", error)
                transient.update { it.copy(errorMessage = "重命名失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    fun clearConversation(conversationId: String) {
        viewModelScope.launch {
            runCatching {
                clearConversationUseCase(conversationId)
            }.onSuccess {
                transient.update {
                    it.copy(
                        isGenerating = generationSession?.job?.isActive == true,
                        errorMessage = "会话已清空",
                    )
                }
            }.onFailure { error ->
                Log.w(TAG, "Clearing conversation failed: ${error.message}", error)
                transient.update {
                    it.copy(
                        isGenerating = generationSession?.job?.isActive == true,
                        errorMessage = "清空会话失败：${error.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    fun updateSettings(transform: (ChatSettings) -> ChatSettings) {
        viewModelScope.launch {
            runCatching { settingsRepository.updateSettings(transform) }
                .onFailure { error ->
                    Log.w(TAG, "Saving settings failed: ${error.javaClass.simpleName}")
                    transient.update { it.copy(errorMessage = "设置保存失败，请重试") }
                }
        }
    }

    fun updateDisplayScale(scale: DisplayScale) {
        viewModelScope.launch {
            runCatching {
                settingsRepository.updateDisplaySettings(DisplaySettings(displayScale = scale))
            }.onFailure { error ->
                Log.w(TAG, "Saving display settings failed: ${error.javaClass.simpleName}")
                transient.update { it.copy(errorMessage = "显示设置保存失败，请重试") }
            }
        }
    }

    fun saveApiKey(apiKey: String) {
        viewModelScope.launch {
            runCatching {
                settingsRepository.saveApiKey(apiKey)
            }.onSuccess {
                refreshSavedProviderKeys()
                Log.i(TAG, "API key saved")
                transient.update { it.copy(errorMessage = "API Key 已保存") }
            }.onFailure { error ->
                Log.w(TAG, "Saving API key failed: ${error.message}", error)
                transient.update { it.copy(errorMessage = "API Key 保存失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            runCatching {
                settingsRepository.clearApiKey()
            }.onSuccess {
                refreshSavedProviderKeys()
                Log.i(TAG, "API key cleared")
                transient.update { it.copy(errorMessage = "API Key 已清除") }
            }.onFailure { error ->
                Log.w(TAG, "Clearing API key failed: ${error.message}", error)
                transient.update { it.copy(errorMessage = "API Key 清除失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    fun saveProviderKey(providerId: String, apiKey: String) {
        viewModelScope.launch {
            runCatching {
                settingsRepository.saveProviderKey(providerId, apiKey)
            }.onSuccess {
                Log.i(TAG, "Provider key saved")
                refreshSavedProviderKeys()
                transient.update { it.copy(errorMessage = "API Key 已保存") }
            }.onFailure { error ->
                Log.w(TAG, "Saving provider key failed: ${error.message}", error)
                transient.update {
                    it.copy(errorMessage = "API Key 保存失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun clearProviderKey(providerId: String) {
        viewModelScope.launch {
            runCatching {
                settingsRepository.clearProviderKey(providerId)
            }.onSuccess {
                Log.i(TAG, "Provider key cleared")
                refreshSavedProviderKeys()
                transient.update { it.copy(errorMessage = "API Key 已清除") }
            }.onFailure { error ->
                Log.w(TAG, "Clearing provider key failed: ${error.message}", error)
                transient.update {
                    it.copy(errorMessage = "API Key 清除失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    /** 新增供应商。id 在这里生成，后续 Keystore alias 与模型缓存都以它为准。 */
    fun addCustomProvider(name: String, baseUrl: String) {
        viewModelScope.launch {
            val provider = CustomProvider(
                id = UUID.randomUUID().toString(),
                name = name,
                baseUrl = baseUrl,
            ).normalized()
            if (provider.baseUrl.isBlank()) {
                transient.update {
                    it.copy(errorMessage = "Base URL 无效：请填写 http:// 或 https:// 开头的地址")
                }
                return@launch
            }

            runCatching {
                settingsRepository.upsertCustomProvider(provider)
            }.onSuccess {
                transient.update {
                    it.copy(errorMessage = "已添加并切换到「${provider.displayName}」，请继续保存 API Key")
                }
            }.onFailure { error ->
                Log.w(TAG, "Adding provider failed: ${error.message}", error)
                transient.update { it.copy(errorMessage = "供应商添加失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    /**
     * 添加 xAI 订阅供应商：认证类型固定为 OAuth，地址由 [CustomProvider.normalized] 钉死。
     * 所有 xAI 供应商共用同一个登录，因此只允许存在一个。
     */
    fun addXaiProvider() {
        viewModelScope.launch {
            runCatching {
                if (settingsRepository.currentSettings().customProviders.any { it.usesXaiOAuth }) {
                    transient.update { it.copy(errorMessage = "已存在 xAI 订阅供应商") }
                    return@launch
                }
                val provider = CustomProvider(
                    id = UUID.randomUUID().toString(),
                    name = CustomProvider.XAI_DEFAULT_NAME,
                    baseUrl = CustomProvider.XAI_SUBSCRIPTION_BASE_URL,
                    authKind = ProviderAuthKind.XAI_OAUTH,
                ).normalized()
                settingsRepository.upsertCustomProvider(provider)
            }.onSuccess {
                transient.update {
                    it.copy(errorMessage = "已添加并切换到「${CustomProvider.XAI_DEFAULT_NAME}」，请登录 xAI 并填写模型名")
                }
            }.onFailure { error ->
                Log.w(TAG, "Adding xAI provider failed: ${error.javaClass.simpleName}")
                transient.update { it.copy(errorMessage = "供应商添加失败，请重试") }
            }
        }
    }

    /** 编辑已有供应商的名称、地址或手填模型列表。 */
    fun updateCustomProvider(
        providerId: String,
        transform: (CustomProvider) -> CustomProvider,
    ) {
        viewModelScope.launch {
            runCatching {
                settingsRepository.updateCustomProvider(providerId, transform)
            }.onFailure { error ->
                Log.w(TAG, "Updating provider failed: ${error.message}", error)
                transient.update { it.copy(errorMessage = "供应商保存失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    fun deleteCustomProvider(providerId: String) {
        viewModelScope.launch {
            runCatching {
                settingsRepository.deleteCustomProvider(providerId)
            }.onSuccess {
                refreshSavedProviderKeys()
                transient.update { it.copy(errorMessage = "供应商及其 API Key 已删除") }
            }.onFailure { error ->
                Log.w(TAG, "Deleting provider failed: ${error.message}", error)
                transient.update { it.copy(errorMessage = "供应商删除失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    /** 聊天页选择 DeepSeek 的某个模型：同时把路由切回 DeepSeek。 */
    fun selectDeepSeekModel(modelId: String) {
        val normalized = DeepSeekModels.normalizeSelectedId(modelId)
        updateSettings { it.copy(provider = ChatProviderId.DEEPSEEK, model = normalized) }
    }

    /** 聊天页选择某个自定义供应商的模型：同时把路由切到该供应商。 */
    fun selectCustomProviderModel(providerId: String, model: String) {
        viewModelScope.launch {
            runCatching {
                settingsRepository.selectCustomProviderModel(providerId, model)
            }.onFailure { error ->
                Log.w(TAG, "Selecting provider model failed: ${error.message}", error)
                transient.update { it.copy(errorMessage = "模型切换失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    fun refreshDeepSeekModels() {
        refreshProviderModels(ChatProviderId.DEEPSEEK.storageValue)
    }

    /** 拉取模型列表。失败只提示，已缓存的列表保持不变，用户仍可手填。 */
    fun refreshProviderModels(providerId: String) {
        if (transient.value.refreshingModelsProviderId != null) return

        transient.update { it.copy(refreshingModelsProviderId = providerId, modelRefreshErrors = it.modelRefreshErrors - providerId) }
        viewModelScope.launch {
            runCatching {
                if (providerId == ChatProviderId.DEEPSEEK.storageValue) {
                    settingsRepository.refreshDeepSeekModels()
                } else {
                    settingsRepository.refreshProviderModels(providerId)
                }
            }.onSuccess { models ->
                transient.update { it.copy(errorMessage = "已获取 ${models.size} 个模型") }
            }.onFailure { error ->
                Log.w(TAG, "Fetching provider models failed: ${error.javaClass.simpleName}")
                transient.update {
                    it.copy(
                        errorMessage = error.message ?: "模型列表获取失败，请手动填写模型名",
                        modelRefreshErrors = it.modelRefreshErrors + (providerId to "刷新失败，仍保留旧目录与当前选择。请检查连接与 Key 后重试。"),
                    )
                }
            }
            transient.update { it.copy(refreshingModelsProviderId = null) }
        }
    }

    /** Keystore 读写属于磁盘 I/O，不能在 Composable 里同步调用，统一在这里刷新。 */
    private fun refreshSavedProviderKeys() {
        viewModelScope.launch {
            val providerIds = settingsRepository.currentSettings().customProviders.map { it.id }
            val ids = withContext(Dispatchers.IO) {
                providerIds.filter { settingsRepository.hasProviderKey(it) }.toSet() +
                    if (!settingsRepository.getApiKey().isNullOrBlank()) setOf(ChatProviderId.DEEPSEEK.storageValue) else emptySet()
            }
            transient.update { it.copy(providersWithSavedKey = ids) }
        }
    }

    fun saveRoleCard(id: String?, draft: RoleCardDraft) {
        viewModelScope.launch {
            runCatching {
                val saved = rpRepository.saveRoleCard(id, draft)
                val conversationId = activeConversationId.value
                val activeRpSettings = uiState.value.activeRpSettings
                val shouldApplyToCurrentConversation = conversationId != null &&
                    activeRpSettings?.isRpEnabled == true &&
                    (id == null || activeRpSettings.roleCardId == saved.id)

                if (shouldApplyToCurrentConversation) {
                    rpRepository.updateConversationRpSettings(
                        conversationId = conversationId,
                        isRpEnabled = true,
                        roleCardId = saved.id,
                        worldSettingId = activeRpSettings.worldSettingId,
                        // 这里只改角色卡绑定，文风绑定原样带回，不能靠默认值。
                        writingStyleId = activeRpSettings.writingStyleId,
                    )
                }
                shouldApplyToCurrentConversation
            }.onSuccess { applied ->
                transient.update { state ->
                    state.copy(
                        errorMessage = if (applied) {
                            "角色卡已保存并应用到当前会话"
                        } else {
                            "角色卡已保存"
                        },
                    )
                }
            }.onFailure { error ->
                Log.w(TAG, "Saving role card failed: ${error.message}", error)
                transient.update { it.copy(errorMessage = "角色卡保存失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    fun deleteRoleCard(roleCardId: String) {
        viewModelScope.launch {
            runCatching {
                rpRepository.deleteRoleCard(roleCardId)
            }.onSuccess {
                transient.update { it.copy(errorMessage = "角色卡已删除") }
            }.onFailure { error ->
                Log.w(TAG, "Deleting role card failed: ${error.message}", error)
                transient.update { it.copy(errorMessage = "角色卡删除失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    fun saveWorldSetting(id: String?, draft: WorldSettingDraft) {
        viewModelScope.launch {
            runCatching {
                rpRepository.saveWorldSetting(id, draft)
            }.onSuccess {
                transient.update { state -> state.copy(errorMessage = "世界观已保存") }
            }.onFailure { error ->
                Log.w(TAG, "Saving world setting failed: ${error.message}", error)
                transient.update { it.copy(errorMessage = "世界观保存失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    fun deleteWorldSetting(worldSettingId: String) {
        viewModelScope.launch {
            runCatching {
                rpRepository.deleteWorldSetting(worldSettingId)
            }.onSuccess {
                transient.update { it.copy(errorMessage = "世界观已删除") }
            }.onFailure { error ->
                Log.w(TAG, "Deleting world setting failed: ${error.message}", error)
                transient.update { it.copy(errorMessage = "世界观删除失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    fun updateActiveConversationRpSettings(
        isRpEnabled: Boolean,
        roleCardId: String?,
        worldSettingId: String?,
        writingStyleId: String?,
    ) {
        updateActiveConversationRpSettings(
            isRpEnabled = isRpEnabled,
            roleCardId = roleCardId,
            worldSettingId = worldSettingId,
            writingStyleId = writingStyleId,
            protagonistName = null,
            protagonistDescription = null,
            protagonistInitialState = null,
            narrativeMinChars = null,
            narrativePerspective = null,
            plotSuggestionPerspective = null,
            rpMarkerMode = null,
        )
    }

    fun updateActiveConversationRpSettings(
        isRpEnabled: Boolean,
        roleCardId: String?,
        worldSettingId: String?,
        writingStyleId: String?,
        protagonistName: String?,
        protagonistDescription: String?,
        protagonistInitialState: String?,
        narrativeMinChars: Int? = null,
        narrativePerspective: RpNarrativePerspective? = null,
        plotSuggestionPerspective: RpPlotSuggestionPerspective? = null,
        rpMarkerMode: RpMarkerMode? = null,
    ) {
        viewModelScope.launch {
            runCatching {
                val conversationId = activeConversationId.value ?: chatRepository
                    .createConversation(if (isRpEnabled) "RP 对话" else null)
                    .also { activeConversationId.value = it.id }
                    .id
                val effectiveRpMarkerMode = when {
                    rpMarkerMode == null -> null
                    uiState.value.settings.rpMarkerAvailability().isAvailable -> rpMarkerMode
                    else -> RpMarkerMode.DISABLED
                }

                rpRepository.updateConversationRpSettings(
                    conversationId = conversationId,
                    isRpEnabled = isRpEnabled,
                    roleCardId = roleCardId,
                    worldSettingId = worldSettingId,
                    writingStyleId = writingStyleId,
                    protagonistName = protagonistName,
                    protagonistDescription = protagonistDescription,
                    protagonistInitialState = protagonistInitialState,
                    narrativeMinChars = narrativeMinChars,
                    narrativePerspective = narrativePerspective,
                    plotSuggestionPerspective = plotSuggestionPerspective,
                    rpMarkerMode = effectiveRpMarkerMode,
                )
            }.onSuccess {
                transient.update { it.copy(errorMessage = "RP 设置已更新") }
            }.onFailure { error ->
                Log.w(TAG, "Updating RP settings failed: ${error.message}", error)
                transient.update { it.copy(errorMessage = "RP 设置更新失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    // ---------- 上下文 ----------

    fun compactContextNow() {
        val conversationId = activeConversationId.value ?: return
        if (transient.value.isCompactingContext) return

        viewModelScope.launch {
            transient.update { it.copy(isCompactingContext = true, errorMessage = null) }
            runCatching { chatRepository.compactContext(conversationId) }
                .onSuccess { compacted ->
                    transient.update {
                        it.copy(
                            errorMessage = if (compacted) "已压缩更早的剧情" else "压缩失败，请稍后重试",
                        )
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Manual compaction failed: ${error.message}", error)
                    transient.update { it.copy(errorMessage = error.message ?: "压缩失败") }
                }
            transient.update { it.copy(isCompactingContext = false) }
        }
    }

    // 压缩与阈值改动都会经由设置流回到 observeContextWindow，不需要再手动刷新一次。
    fun updateCompressionMode(mode: ContextCompressionMode) {
        updateSettings { it.copy(contextCompressionMode = mode) }
    }

    fun updateCompressionTriggerPercent(percent: Int) {
        updateSettings { it.copy(contextAutoTriggerPercent = percent) }
    }

    fun updateContextCostCapEnabled(enabled: Boolean) {
        updateSettings { it.copy(contextCostCapEnabled = enabled) }
    }

    fun updateContextCostCapTokens(tokens: Long) {
        updateSettings { it.copy(contextCostCapTokens = tokens) }
    }

    // ---------- 工具草稿 ----------

    fun discardSettingsDraft(draftId: String) {
        chatRepository.discardSettingsToolDraft(draftId)
        transient.update { it.copy(errorMessage = "草稿已丢弃，未写入任何数据") }
    }

    /**
     * 确认保存草稿。三类设定走同一个事务，失败不会留下半成品。
     * 保存成功后才把草稿从待确认区移除。
     */
    fun confirmSettingsDraft(
        draftId: String,
        roleCard: RoleCardDraft?,
        worldSetting: WorldSettingDraft?,
        writingStyle: WritingStyleProfileDraft?,
        bindToConversation: Boolean,
    ) {
        if (transient.value.isSavingSettingsDraft) return

        viewModelScope.launch {
            transient.update { it.copy(isSavingSettingsDraft = true, errorMessage = null) }
            runCatching {
                rpRepository.saveGeneratedSettings(
                    roleCard = roleCard,
                    worldSetting = worldSetting,
                    writingStyle = writingStyle,
                    bindToConversationId = activeConversationId.value.takeIf { bindToConversation },
                )
            }.onSuccess { saved ->
                chatRepository.discardSettingsToolDraft(draftId)
                val labels = listOfNotNull(
                    saved.roleCard?.let { "角色卡" },
                    saved.worldSetting?.let { "世界观" },
                    saved.writingStyle?.let { "文风" },
                ).joinToString("、")
                transient.update {
                    it.copy(
                        errorMessage = if (saved.boundToConversation) {
                            "$labels 已保存并绑定到当前会话"
                        } else {
                            "$labels 已保存"
                        },
                    )
                }
            }.onFailure { error ->
                Log.w(TAG, "Saving generated settings failed: ${error.message}", error)
                transient.update { it.copy(errorMessage = "保存失败：${error.message ?: "未知错误"}") }
            }
            transient.update { it.copy(isSavingSettingsDraft = false) }
        }
    }

    // ---------- 文风 ----------

    fun saveWritingStyleProfile(id: String?, draft: WritingStyleProfileDraft) {
        viewModelScope.launch {
            runCatching {
                rpRepository.saveWritingStyleProfile(id, draft)
            }.onSuccess {
                transient.update { it.copy(errorMessage = "文风已保存") }
            }.onFailure { error ->
                Log.w(TAG, "Saving writing style failed: ${error.message}", error)
                transient.update { it.copy(errorMessage = "文风保存失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    fun deleteWritingStyleProfile(writingStyleId: String) {
        viewModelScope.launch {
            runCatching {
                rpRepository.deleteWritingStyleProfile(writingStyleId)
            }.onSuccess {
                transient.update { it.copy(errorMessage = "文风已删除") }
            }.onFailure { error ->
                Log.w(TAG, "Deleting writing style failed: ${error.message}", error)
                transient.update { it.copy(errorMessage = "文风删除失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    private fun startGeneration(
        initialConversationId: String?,
        autoSelectCreatedConversation: Boolean,
        operationLabel: String,
        fallbackError: String,
        flowFactory: () -> Flow<ChatMessage>,
    ) {
        generationSession?.job?.cancel(CancellationException("已开始新的生成"))

        val sessionId = UUID.randomUUID().toString()
        val settingsSnapshot = uiState.value.settings
        val providerSnapshot = UiProviderSnapshot(
            provider = settingsSnapshot.provider,
            baseUrl = settingsSnapshot.activeBaseUrl,
            model = settingsSnapshot.activeModel,
        )
        transient.update { it.copy(isGenerating = true, errorMessage = null) }

        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                flowFactory().collect { message ->
                    handleGenerationMessage(
                        sessionId = sessionId,
                        message = message,
                        autoSelectCreatedConversation = autoSelectCreatedConversation,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "$operationLabel failed: ${error.javaClass.simpleName}")
                if (generationSession?.sessionId == sessionId) {
                    transient.update { it.copy(errorMessage = error.message ?: fallbackError) }
                }
            } finally {
                finishUiGenerationSession(sessionId)
            }
        }
        generationSession = UiGenerationSession(
            sessionId = sessionId,
            conversationId = initialConversationId,
            assistantMessageId = null,
            job = job,
            providerSnapshot = providerSnapshot,
            status = UiGenerationStatus.STARTING,
            hasAutoSelectedConversation = false,
        )
        job.start()
    }

    private fun handleGenerationMessage(
        sessionId: String,
        message: ChatMessage,
        autoSelectCreatedConversation: Boolean,
    ) {
        val session = generationSession?.takeIf { it.sessionId == sessionId } ?: return
        val shouldConsumeAutoSelection = autoSelectCreatedConversation &&
            !session.hasAutoSelectedConversation
        generationSession = session.copy(
            conversationId = message.conversationId,
            assistantMessageId = message.id,
            status = message.status.toUiGenerationStatus(),
            // 第一条有效消息到达后即永久消费机会；即使当时用户已经切到别处，也不能在后续 delta 重试。
            hasAutoSelectedConversation = session.hasAutoSelectedConversation || shouldConsumeAutoSelection,
        )
        // 只有“空白页首次发送”可以自动选中新建会话；用户随后主动切到 B 时，A 的 delta 只落库。
        if (shouldConsumeAutoSelection && activeConversationId.value == null) {
            activeConversationId.value = message.conversationId
        }
        if (message.status == MessageStatus.COMPLETED && !message.errorMessage.isNullOrBlank()) {
            transient.update { it.copy(errorMessage = message.errorMessage) }
        }
    }

    private fun finishUiGenerationSession(sessionId: String) {
        if (generationSession?.sessionId != sessionId) return
        generationSession = null
        transient.update { it.copy(isGenerating = false) }
    }

    override fun onCleared() {
        generationSession?.job?.cancel(CancellationException("聊天界面已关闭"))
        generationSession = null
        chatRepository.cancelAllWork()
        super.onCleared()
    }

    private fun MessageStatus.toUiGenerationStatus(): UiGenerationStatus = when (this) {
        MessageStatus.PENDING -> UiGenerationStatus.STARTING
        MessageStatus.STREAMING -> UiGenerationStatus.STREAMING
        MessageStatus.COMPLETED -> UiGenerationStatus.COMPLETED
        MessageStatus.CANCELLED -> UiGenerationStatus.CANCELLED
        MessageStatus.FAILED -> UiGenerationStatus.FAILED
    }

    private data class UiGenerationSession(
        val sessionId: String,
        val conversationId: String?,
        val assistantMessageId: String?,
        val job: Job,
        val providerSnapshot: UiProviderSnapshot,
        val status: UiGenerationStatus,
        val hasAutoSelectedConversation: Boolean,
    )

    private data class UiProviderSnapshot(
        val provider: ChatProviderId,
        val baseUrl: String,
        val model: String,
    )

    private enum class UiGenerationStatus {
        STARTING,
        STREAMING,
        COMPLETED,
        CANCELLED,
        FAILED,
    }

    private data class TransientState(
        val isGenerating: Boolean = false,
        val retryingRpOptionsMessageId: String? = null,
        val errorMessage: String? = null,
        val isCompactingContext: Boolean = false,
        val isSavingSettingsDraft: Boolean = false,
        val pendingAttachment: PendingAttachment? = null,
        /** 已保存 API Key 的供应商 id；只表示"有没有"，不含明文。 */
        val providersWithSavedKey: Set<String> = emptySet(),
        val isImportingAttachment: Boolean = false,
        val modelRefreshErrors: Map<String, String> = emptyMap(),
        val refreshingModelsProviderId: String? = null,
    )

    private data class CoreState(
        val conversations: List<Conversation>,
        val activeConversationId: String?,
        val messages: List<ChatMessage>,
        val settings: ChatSettings,
        val isGenerating: Boolean,
        val retryingRpOptionsMessageId: String?,
        val errorMessage: String?,
        val isCompactingContext: Boolean,
        val isSavingSettingsDraft: Boolean,
        val pendingAttachment: PendingAttachment?,
        val providersWithSavedKey: Set<String>,
        val isImportingAttachment: Boolean,
        val modelRefreshErrors: Map<String, String>,
        val refreshingModelsProviderId: String?,
    )

    private data class RpUiSlice(
        val roleCards: List<RoleCard>,
        val worldSettings: List<WorldSetting>,
        val writingStyleProfiles: List<WritingStyleProfile>,
        val activeRpSettings: ConversationRpSettings?,
        val activeRpStatusPanel: RpStatusPanel?,
    )

    private data class ToolUiSlice(
        val drafts: List<SettingsToolDraft>,
        val enrichingMessageIds: Set<String>,
    )

    companion object {
        private const val TAG = "muxueChat.ChatViewModel"

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[APPLICATION_KEY]) as Application
                val database = ChatDatabase.getInstance(application)
                // 模型列表与聊天走同一个 client 实例：共用连接池，且"停止生成"不会误伤模型拉取。
                val openAiCompatibleClient = OpenAiCompatibleClient()
                val deepSeekApiClient = DeepSeekApiClient()
                // 只建一个：cancelActiveRequest 依赖同一个实例追踪在途 Call。
                // 版本号取包信息里的真实 versionName，不硬编码——上游会按它做版本门槛，
                // 写死会在 App 升级后继续上报旧版本。
                val appVersion = runCatching {
                    application.packageManager.getPackageInfo(application.packageName, 0).versionName
                }.getOrNull().orEmpty()
                val xaiResponsesTransport = XaiResponsesTransport(clientVersion = appVersion)
                val settingsRepository = SettingsRepositoryImpl(
                    settingsDataStore = SettingsDataStore(application),
                    apiKeyStore = EncryptedApiKeyStore(application),
                    providerKeyStore = EncryptedProviderKeyStore(application),
                    openAiCompatibleClient = openAiCompatibleClient,
                    deepSeekApiClient = deepSeekApiClient,
                    legacyCliProxyApiKeyStore = EncryptedCliProxyApiKeyStore(application),
                )
                val chatRepository = ChatRepositoryImpl(
                    chatDao = database.chatDao(),
                    settingsRepository = settingsRepository,
                    apiClient = deepSeekApiClient,
                    openAiCompatibleClient = openAiCompatibleClient,
                    xaiResponsesTransport = xaiResponsesTransport,
                    // 必须与设置页的 XaiAuthViewModel 共用同一个单例：刷新的串行化依赖同一把锁。
                    xaiAccessTokens = XaiAuthRepository.getInstance(application),
                )
                val rpRepository = RpRepositoryImpl(database = database)

                return ChatViewModel(
                    sendMessageUseCase = SendMessageUseCase(chatRepository),
                    regenerateLastResponseUseCase = RegenerateLastResponseUseCase(chatRepository),
                    retryRpOptionsUseCase = RetryRpOptionsUseCase(chatRepository),
                    editUserMessageAndRegenerateUseCase = EditUserMessageAndRegenerateUseCase(chatRepository),
                    editAssistantMessageUseCase = EditAssistantMessageUseCase(chatRepository),
                    stopGenerationUseCase = StopGenerationUseCase(chatRepository),
                    deleteConversationUseCase = DeleteConversationUseCase(chatRepository),
                    renameConversationUseCase = RenameConversationUseCase(chatRepository),
                    clearConversationUseCase = ClearConversationUseCase(chatRepository),
                    chatRepository = chatRepository,
                    rpRepository = rpRepository,
                    settingsRepository = settingsRepository,
                    documentTextExtractor = DocumentTextExtractor(application.contentResolver),
                ) as T
            }
        }
    }
}
