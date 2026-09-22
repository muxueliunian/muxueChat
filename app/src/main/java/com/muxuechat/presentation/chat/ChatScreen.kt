package com.muxuechat.presentation.chat

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RoleCardDraft
import com.muxuechat.domain.model.RpMarkerMode
import com.muxuechat.domain.model.RpNarrativePerspective
import com.muxuechat.domain.model.RpPlotSuggestionPerspective
import com.muxuechat.domain.model.WorldSetting
import com.muxuechat.domain.model.WorldSettingDraft
import com.muxuechat.domain.model.WritingStyleProfile
import com.muxuechat.domain.model.WritingStyleProfileDraft
import com.muxuechat.data.document.DocumentTextExtractor
import com.muxuechat.domain.context.ContextCompressionMode
import com.muxuechat.domain.tool.SettingsToolDraft
import com.muxuechat.presentation.chat.components.ChatTopBar
import com.muxuechat.presentation.chat.components.ContextWindowSheet
import com.muxuechat.presentation.chat.components.ConversationDrawer
import com.muxuechat.presentation.chat.components.SettingsDraftConfirmation
import com.muxuechat.presentation.chat.components.SettingsDraftSheet
import com.muxuechat.presentation.chat.components.MessageInputBar
import com.muxuechat.presentation.chat.components.MessageList
import com.muxuechat.presentation.chat.components.ModelPickerSheet
import com.muxuechat.presentation.chat.components.RoleCardEditorSheet
import com.muxuechat.presentation.chat.components.RpSettingsSheet
import com.muxuechat.presentation.chat.components.RpStatusPanelCard
import com.muxuechat.presentation.chat.components.WorldSettingEditorSheet
import com.muxuechat.presentation.chat.components.WritingStyleEditorSheet
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onSelectConversation: (String?) -> Unit,
    onSendMessage: (String) -> Unit,
    onStopGeneration: () -> Unit,
    onRegenerate: () -> Unit,
    onRetryRpOptions: () -> Unit,
    onDeleteConversation: (String) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onClearConversation: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onUpdateRpSettings: (
        isRpEnabled: Boolean,
        roleCardId: String?,
        worldSettingId: String?,
        writingStyleId: String?,
        protagonistName: String,
        protagonistDescription: String,
        protagonistInitialState: String,
        narrativeMinChars: Int,
        narrativePerspective: RpNarrativePerspective,
        plotSuggestionPerspective: RpPlotSuggestionPerspective,
        rpMarkerMode: RpMarkerMode,
    ) -> Unit,
    onSaveRoleCard: (id: String?, draft: RoleCardDraft) -> Unit,
    onDeleteRoleCard: (roleCardId: String) -> Unit,
    onSaveWorldSetting: (id: String?, draft: WorldSettingDraft) -> Unit,
    onDeleteWorldSetting: (worldSettingId: String) -> Unit,
    onSaveWritingStyle: (id: String?, draft: WritingStyleProfileDraft) -> Unit,
    onDeleteWritingStyle: (writingStyleId: String) -> Unit,
    onEditUserMessage: (messageId: String, newContent: String) -> Unit,
    onEditAssistantMessage: (messageId: String, newContent: String) -> Unit,
    onAttachDocument: (Uri) -> Unit,
    onRemoveAttachment: () -> Unit,
    onCompactContextNow: () -> Unit,
    onCompressionModeChange: (ContextCompressionMode) -> Unit,
    onCompressionTriggerChange: (Int) -> Unit,
    onConfirmSettingsDraft: (SettingsDraftConfirmation) -> Unit,
    onDiscardSettingsDraft: (String) -> Unit,
    onSelectDeepSeekModel: (String) -> Unit,
    onSelectCustomProviderModel: (providerId: String, model: String) -> Unit,
    onRefreshProviderModels: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val canUseSettingsTools = state.settings.settingsToolsEnabledForActiveProvider

    var inputValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }

    var rpSheetVisible by rememberSaveable { mutableStateOf(false) }
    var rpSettingsDraft by remember { mutableStateOf<RpSettingsDraft?>(null) }
    var roleCardEditor by remember { mutableStateOf<RoleCardEditorTarget?>(null) }
    var worldSettingEditor by remember { mutableStateOf<WorldSettingEditorTarget?>(null) }
    var pendingDeleteRoleCard by remember { mutableStateOf<RoleCard?>(null) }
    var pendingDeleteWorldSetting by remember { mutableStateOf<WorldSetting?>(null) }
    var writingStyleEditor by remember { mutableStateOf<WritingStyleEditorTarget?>(null) }
    var pendingDeleteWritingStyle by remember { mutableStateOf<WritingStyleProfile?>(null) }
    var editingUserMessage by remember { mutableStateOf<MessageEditTarget?>(null) }
    var editingAssistantMessage by remember { mutableStateOf<MessageEditTarget?>(null) }
    var pendingCreatedRoleCardIds by remember { mutableStateOf<Set<String>?>(null) }
    var pendingCreatedWorldSettingIds by remember { mutableStateOf<Set<String>?>(null) }
    var pendingCreatedWritingStyleIds by remember { mutableStateOf<Set<String>?>(null) }
    var contextSheetVisible by rememberSaveable { mutableStateOf(false) }
    var modelSheetVisible by rememberSaveable { mutableStateOf(false) }
    // 被用户关闭、但尚未确认或丢弃的草稿。
    var collapsedDraftIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    // 一次只展示最早的一份未收起的草稿，逐份确认，避免多个 Sheet 叠在一起。
    val visibleDraft = state.settingsToolDrafts.firstOrNull { it.id !in collapsedDraftIds }

    // SAF 选择器。只接受 text 类型，不申请任何存储权限。
    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onAttachDocument) }

    fun closeDrawerThenSelect(conversationId: String?) {
        scope.launch {
            drawerState.close()
            if (conversationId != state.activeConversationId) {
                onSelectConversation(conversationId)
            }
        }
    }

    fun dismissRoleCardEditor() {
        val target = roleCardEditor ?: return
        if (target.id == null) {
            pendingCreatedRoleCardIds = null
        }
        roleCardEditor = null
        rpSheetVisible = true
    }

    fun collapseSettingsDraft(draftId: String) {
        collapsedDraftIds = collapsedDraftIds + draftId
    }

    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
        }
    }

    val activeConversationTitle = state.conversations
        .firstOrNull { it.id == state.activeConversationId }
        ?.title
        ?.takeIf { it.isNotBlank() }
        ?: "新的对话"

    val isRpActive = state.activeRpSettings?.isRpEnabled == true
    val showStatusPanel = isRpActive && state.activeRpStatusPanel != null
    val rpMarkerAvailability = state.settings.rpMarkerAvailability()

    LaunchedEffect(state.roleCards, pendingCreatedRoleCardIds) {
        val beforeIds = pendingCreatedRoleCardIds ?: return@LaunchedEffect
        val created = state.roleCards
            .filterNot { it.id in beforeIds }
            .maxByOrNull { it.createdAtMillis }
            ?: return@LaunchedEffect
        rpSettingsDraft = (rpSettingsDraft ?: RpSettingsDraft.from(state)).copy(
            roleCardId = created.id,
        )
        pendingCreatedRoleCardIds = null
    }

    LaunchedEffect(state.worldSettings, pendingCreatedWorldSettingIds) {
        val beforeIds = pendingCreatedWorldSettingIds ?: return@LaunchedEffect
        val created = state.worldSettings
            .filterNot { it.id in beforeIds }
            .maxByOrNull { it.createdAtMillis }
            ?: return@LaunchedEffect
        rpSettingsDraft = (rpSettingsDraft ?: RpSettingsDraft.from(state)).copy(
            worldSettingId = created.id,
        )
        pendingCreatedWorldSettingIds = null
    }

    LaunchedEffect(state.writingStyleProfiles, pendingCreatedWritingStyleIds) {
        val beforeIds = pendingCreatedWritingStyleIds ?: return@LaunchedEffect
        val created = state.writingStyleProfiles
            .filterNot { it.id in beforeIds }
            .maxByOrNull { it.createdAtMillis }
            ?: return@LaunchedEffect
        rpSettingsDraft = (rpSettingsDraft ?: RpSettingsDraft.from(state)).copy(
            writingStyleId = created.id,
        )
        pendingCreatedWritingStyleIds = null
    }

    // 抽屉展开时必须自己吃掉返回键。这台机器逻辑宽度只有 361dp，而 ModalDrawerSheet 默认
    // 就占 360dp，右侧遮罩窄到点不中，返回键是唯一可靠的关闭方式；不拦就会直接退出 App。
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                conversations = state.conversations,
                activeId = state.activeConversationId,
                onSelect = {
                    closeDrawerThenSelect(it)
                },
                onNewChat = {
                    closeDrawerThenSelect(null)
                },
                onDelete = onDeleteConversation,
                onRename = onRenameConversation,
                onClear = onClearConversation,
            )
        },
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing.only(
                WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
            ),
            topBar = {
                ChatTopBar(
                    conversationTitle = activeConversationTitle,
                    // 顶栏必须跟随当前 Provider，否则用自定义供应商时会显示 DeepSeek 的模型。
                    providerLabel = state.settings.activeProviderLabel,
                    modelLabel = state.settings.activeModel.ifBlank { "未选择模型" },
                    isRpActive = isRpActive,
                    onSelectModel = { modelSheetVisible = true },
                    onMenu = { scope.launch { drawerState.open() } },
                    onOpenRp = {
                        rpSettingsDraft = RpSettingsDraft.from(state)
                        rpSheetVisible = true
                    },
                    // 上下文占用由 Flow 持续推送，打开面板不需要再手动刷新一次。
                    onOpenContext = { contextSheetVisible = true },
                    onOpenSettings = onOpenSettings,
                    contextUsagePercent = state.contextWindowPlan?.usagePercent,
                )
            },
            bottomBar = {
                MessageInputBar(
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    isGenerating = state.isGenerating,
                    onSend = {
                        val text = inputValue.text.trim()
                        if (text.isNotEmpty() || state.pendingAttachment != null) {
                            onSendMessage(text)
                            inputValue = TextFieldValue("")
                        }
                    },
                    onStop = onStopGeneration,
                    attachment = state.pendingAttachment,
                    isImportingAttachment = state.isImportingAttachment,
                    // 附件是给设定生成工具用的，RP 进行中工具不可用，入口也一并隐藏。
                    canAttach = !isRpActive && canUseSettingsTools,
                    onAttach = { documentPicker.launch(DocumentTextExtractor.SAF_MIME_TYPES) },
                    onRemoveAttachment = onRemoveAttachment,
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (showStatusPanel) {
                    RpStatusPanelCard(
                        panel = state.activeRpStatusPanel!!,
                        roleCardName = state.activeRoleCard?.name,
                    )
                }
                // 草稿被收起后仍然存在，这条提示是把它重新打开的唯一入口。
                val collapsedDrafts = state.settingsToolDrafts.filter { it.id in collapsedDraftIds }
                if (collapsedDrafts.isNotEmpty()) {
                    PendingDraftBanner(
                        drafts = collapsedDrafts,
                        onOpen = { draftId -> collapsedDraftIds = collapsedDraftIds - draftId },
                    )
                }
                MessageList(
                    messages = state.messages,
                    isGenerating = state.isGenerating,
                    retryingRpOptionsMessageId = state.retryingRpOptionsMessageId,
                    enrichingMessageIds = state.enrichingMessageIds,
                    canRetryMissingRpOptions = isRpActive && state.activeRoleCard != null,
                    onCopy = { text ->
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("message", text)),
                            )
                            snackbarHostState.showSnackbar("已复制")
                        }
                    },
                    onRegenerate = onRegenerate,
                    onRetryRpOptions = onRetryRpOptions,
                    onSendOption = { option ->
                        if (!state.isGenerating) {
                            onSendMessage(option)
                        }
                    },
                    onEditOption = { option ->
                        inputValue = TextFieldValue(
                            text = option,
                            selection = TextRange(option.length),
                        )
                    },
                    onEditUserMessage = { message ->
                        if (!state.isGenerating) {
                            editingUserMessage = MessageEditTarget(
                                messageId = message.id,
                                initialContent = message.content,
                            )
                        }
                    },
                    onEditAssistantMessage = { message ->
                        if (!state.isGenerating) {
                            editingAssistantMessage = MessageEditTarget(
                                messageId = message.id,
                                initialContent = message.content,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (modelSheetVisible) {
        ModelPickerSheet(
            settings = state.settings,
            refreshingModelsProviderId = state.refreshingModelsProviderId,
            refreshErrors = state.modelRefreshErrors,
            onSelectDeepSeekModel = onSelectDeepSeekModel,
            onSelectCustomProviderModel = onSelectCustomProviderModel,
            onRefreshProviderModels = onRefreshProviderModels,
            onOpenSettings = onOpenSettings,
            onDismiss = { modelSheetVisible = false },
        )
    }

    if (contextSheetVisible) {
        ContextWindowSheet(
            plan = state.contextWindowPlan,
            isCompacting = state.isCompactingContext,
            onCompactNow = onCompactContextNow,
            onModeChange = onCompressionModeChange,
            onTriggerPercentChange = onCompressionTriggerChange,
            onOpenSettings = {
                contextSheetVisible = false
                onOpenSettings()
            },
            onDismiss = { contextSheetVisible = false },
        )
    }

    visibleDraft?.let { draft ->
        SettingsDraftSheet(
            draft = draft,
            isSaving = state.isSavingSettingsDraft,
            onConfirm = onConfirmSettingsDraft,
            onDiscard = onDiscardSettingsDraft,
            // 关闭不等于丢弃：草稿仍留在待确认区，可以从提示条重新打开。
            onDismiss = { collapseSettingsDraft(draft.id) },
        )
    }

    if (rpSheetVisible) {
        val draft = rpSettingsDraft ?: RpSettingsDraft.from(state)
        RpSettingsSheet(
            initialRpEnabled = isRpActive,
            initialRoleCardId = state.activeRpSettings?.roleCardId,
            initialWorldSettingId = state.activeRpSettings?.worldSettingId,
            initialWritingStyleId = state.activeRpSettings?.writingStyleId,
            initialProtagonistName = state.activeRpSettings?.protagonistName.orEmpty(),
            initialProtagonistDescription = state.activeRpSettings?.protagonistDescription.orEmpty(),
            initialProtagonistInitialState = state.activeRpSettings?.protagonistInitialState.orEmpty(),
            initialNarrativeMinChars = state.activeRpSettings?.narrativeMinChars ?: 0,
            initialNarrativePerspective = state.activeRpSettings
                ?.narrativePerspective
                ?: RpNarrativePerspective.DEFAULT,
            initialPlotSuggestionPerspective = state.activeRpSettings
                ?.plotSuggestionPerspective
                ?: RpPlotSuggestionPerspective.DEFAULT,
            initialRpMarkerMode = state.activeRpSettings?.rpMarkerMode ?: RpMarkerMode.DEFAULT,
            draftRpEnabled = draft.isRpEnabled,
            draftRoleCardId = draft.roleCardId,
            draftWorldSettingId = draft.worldSettingId,
            draftWritingStyleId = draft.writingStyleId,
            draftProtagonistName = draft.protagonistName,
            draftProtagonistDescription = draft.protagonistDescription,
            draftProtagonistInitialState = draft.protagonistInitialState,
            draftNarrativeMinCharsText = draft.narrativeMinCharsText,
            draftNarrativePerspective = draft.narrativePerspective,
            draftPlotSuggestionPerspective = draft.plotSuggestionPerspective,
            draftRpMarkerMode = draft.rpMarkerMode,
            showRpMarkerOption = state.settings.usesDeepSeekParameters,
            isRpMarkerAvailable = rpMarkerAvailability.isAvailable,
            rpMarkerUnavailableReason = rpMarkerAvailability.unavailableReason,
            roleCards = state.roleCards,
            worldSettings = state.worldSettings,
            writingStyles = state.writingStyleProfiles,
            onDraftRpEnabledChange = {
                rpSettingsDraft = draft.copy(isRpEnabled = it)
            },
            onDraftRoleCardIdChange = {
                rpSettingsDraft = draft.copy(roleCardId = it)
            },
            onDraftWorldSettingIdChange = {
                rpSettingsDraft = draft.copy(worldSettingId = it)
            },
            onDraftWritingStyleIdChange = {
                rpSettingsDraft = draft.copy(writingStyleId = it)
            },
            onDraftProtagonistNameChange = {
                rpSettingsDraft = draft.copy(protagonistName = it)
            },
            onDraftProtagonistDescriptionChange = {
                rpSettingsDraft = draft.copy(protagonistDescription = it)
            },
            onDraftProtagonistInitialStateChange = {
                rpSettingsDraft = draft.copy(protagonistInitialState = it)
            },
            onDraftNarrativeMinCharsTextChange = {
                rpSettingsDraft = draft.copy(narrativeMinCharsText = it)
            },
            onDraftNarrativePerspectiveChange = {
                rpSettingsDraft = draft.copy(narrativePerspective = it)
            },
            onDraftPlotSuggestionPerspectiveChange = {
                rpSettingsDraft = draft.copy(plotSuggestionPerspective = it)
            },
            onDraftRpMarkerModeChange = {
                rpSettingsDraft = draft.copy(rpMarkerMode = it)
            },
            onSave = { enabled,
                    roleCardId,
                    worldSettingId,
                    writingStyleId,
                    name,
                    description,
                    initialState,
                    narrativeMinChars,
                    narrativePerspective,
                    plotSuggestionPerspective,
                    rpMarkerMode ->
                onUpdateRpSettings(
                    enabled,
                    roleCardId,
                    worldSettingId,
                    writingStyleId,
                    name,
                    description,
                    initialState,
                    narrativeMinChars,
                    narrativePerspective,
                    plotSuggestionPerspective,
                    rpMarkerMode,
                )
                rpSheetVisible = false
                rpSettingsDraft = null
            },
            onCreateRoleCard = {
                pendingCreatedRoleCardIds = state.roleCards.map { it.id }.toSet()
                rpSheetVisible = false
                roleCardEditor = RoleCardEditorTarget.newRoleCard()
            },
            onEditRoleCard = { card ->
                rpSheetVisible = false
                roleCardEditor = RoleCardEditorTarget.fromExisting(card)
            },
            onDeleteRoleCard = { card ->
                pendingDeleteRoleCard = card
            },
            onCreateWorldSetting = {
                pendingCreatedWorldSettingIds = state.worldSettings.map { it.id }.toSet()
                rpSheetVisible = false
                worldSettingEditor = WorldSettingEditorTarget.newWorldSetting()
            },
            onEditWorldSetting = { world ->
                rpSheetVisible = false
                worldSettingEditor = WorldSettingEditorTarget.fromExisting(world)
            },
            onDeleteWorldSetting = { world ->
                pendingDeleteWorldSetting = world
            },
            onCreateWritingStyle = {
                pendingCreatedWritingStyleIds = state.writingStyleProfiles.map { it.id }.toSet()
                rpSheetVisible = false
                writingStyleEditor = WritingStyleEditorTarget.newWritingStyle()
            },
            onEditWritingStyle = { style ->
                rpSheetVisible = false
                writingStyleEditor = WritingStyleEditorTarget.fromExisting(style)
            },
            onDeleteWritingStyle = { style ->
                pendingDeleteWritingStyle = style
            },
            onDismiss = {
                rpSheetVisible = false
                rpSettingsDraft = null
                pendingCreatedRoleCardIds = null
                pendingCreatedWorldSettingIds = null
                pendingCreatedWritingStyleIds = null
            },
        )
    }

    roleCardEditor?.let { target ->
        RoleCardEditorSheet(
            isEditing = target.id != null,
            initialDraft = target.draft,
            onSave = { draft ->
                onSaveRoleCard(target.id, draft)
                roleCardEditor = null
                rpSheetVisible = true
            },
            onDismiss = {
                dismissRoleCardEditor()
            },
        )
    }

    worldSettingEditor?.let { target ->
        WorldSettingEditorSheet(
            isEditing = target.id != null,
            initialDraft = target.draft,
            onSave = { draft ->
                onSaveWorldSetting(target.id, draft)
                worldSettingEditor = null
                rpSheetVisible = true
            },
            onDismiss = {
                if (target.id == null) {
                    pendingCreatedWorldSettingIds = null
                }
                worldSettingEditor = null
                rpSheetVisible = true
            },
        )
    }

    writingStyleEditor?.let { target ->
        WritingStyleEditorSheet(
            isEditing = target.id != null,
            initialDraft = target.draft,
            onSave = { draft ->
                onSaveWritingStyle(target.id, draft)
                writingStyleEditor = null
                rpSheetVisible = true
            },
            onDismiss = {
                if (target.id == null) {
                    pendingCreatedWritingStyleIds = null
                }
                writingStyleEditor = null
                rpSheetVisible = true
            },
        )
    }

    pendingDeleteWritingStyle?.let { style ->
        AlertDialog(
            onDismissRequest = { pendingDeleteWritingStyle = null },
            title = { Text("删除文风") },
            text = {
                Text(
                    text = "确定删除「${style.name.ifBlank { "未命名文风" }}」吗？" +
                        "已经绑定该文风的会话会解绑，但历史消息保留。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteWritingStyle(style.id)
                        pendingDeleteWritingStyle = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteWritingStyle = null }) {
                    Text("取消")
                }
            },
        )
    }

    pendingDeleteRoleCard?.let { card ->
        AlertDialog(
            onDismissRequest = { pendingDeleteRoleCard = null },
            title = { Text("删除角色卡") },
            text = {
                Text(
                    text = "确定删除「${card.name.ifBlank { "未命名角色" }}」吗？" +
                        "已经绑定该角色卡的会话会解绑，但历史消息保留。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteRoleCard(card.id)
                        pendingDeleteRoleCard = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRoleCard = null }) {
                    Text("取消")
                }
            },
        )
    }

    editingUserMessage?.let { target ->
        EditUserMessageDialog(
            initialContent = target.initialContent,
            onConfirm = { newContent ->
                onEditUserMessage(target.messageId, newContent)
                editingUserMessage = null
            },
            onDismiss = { editingUserMessage = null },
        )
    }

    editingAssistantMessage?.let { target ->
        EditAssistantMessageDialog(
            initialContent = target.initialContent,
            onConfirm = { newContent ->
                onEditAssistantMessage(target.messageId, newContent)
                editingAssistantMessage = null
            },
            onDismiss = { editingAssistantMessage = null },
        )
    }

    pendingDeleteWorldSetting?.let { world ->
        AlertDialog(
            onDismissRequest = { pendingDeleteWorldSetting = null },
            title = { Text("删除世界观") },
            text = {
                Text(
                    text = "确定删除「${world.title.ifBlank { "未命名世界观" }}」吗？" +
                        "已经绑定该世界观的会话会解绑，但历史消息保留。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteWorldSetting(world.id)
                        pendingDeleteWorldSetting = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteWorldSetting = null }) {
                    Text("取消")
                }
            },
        )
    }
}

private data class MessageEditTarget(
    val messageId: String,
    val initialContent: String,
)

private data class RpSettingsDraft(
    val isRpEnabled: Boolean,
    val roleCardId: String?,
    val worldSettingId: String?,
    val writingStyleId: String?,
    val protagonistName: String,
    val protagonistDescription: String,
    val protagonistInitialState: String,
    val narrativeMinCharsText: String,
    val narrativePerspective: RpNarrativePerspective,
    val plotSuggestionPerspective: RpPlotSuggestionPerspective,
    val rpMarkerMode: RpMarkerMode,
) {
    companion object {
        fun from(state: ChatUiState): RpSettingsDraft = RpSettingsDraft(
            isRpEnabled = state.activeRpSettings?.isRpEnabled == true,
            roleCardId = state.activeRpSettings?.roleCardId,
            worldSettingId = state.activeRpSettings?.worldSettingId,
            writingStyleId = state.activeRpSettings?.writingStyleId,
            protagonistName = state.activeRpSettings?.protagonistName.orEmpty(),
            protagonistDescription = state.activeRpSettings?.protagonistDescription.orEmpty(),
            protagonistInitialState = state.activeRpSettings?.protagonistInitialState.orEmpty(),
            narrativeMinCharsText = state.activeRpSettings
                ?.narrativeMinChars
                ?.takeIf { it > 0 }
                ?.toString()
                .orEmpty(),
            narrativePerspective = state.activeRpSettings
                ?.narrativePerspective
                ?: RpNarrativePerspective.DEFAULT,
            plotSuggestionPerspective = state.activeRpSettings
                ?.plotSuggestionPerspective
                ?: RpPlotSuggestionPerspective.DEFAULT,
            rpMarkerMode = state.activeRpSettings?.rpMarkerMode ?: RpMarkerMode.DEFAULT,
        )
    }
}

@Composable
private fun EditUserMessageDialog(
    initialContent: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialContent,
                selection = TextRange(initialContent.length),
            ),
        )
    }
    val canConfirm = text.text.isNotBlank()
    val guardedDismiss = com.muxuechat.presentation.common.components.rememberGuardedDismiss(text.text != initialContent, onDismiss)
    AlertDialog(
        onDismissRequest = guardedDismiss,
        title = { Text("编辑用户消息") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "重新发送会删除这条消息之后的所有回复与决策，并回滚 RP 状态。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    minLines = 3,
                    maxLines = 8,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = { onConfirm(text.text.trim()) },
            ) {
                Text("重新发送")
            }
        },
        dismissButton = {
            TextButton(onClick = guardedDismiss) {
                Text("取消")
            }
        },
    )
}

/**
 * 编辑模型回复。与编辑用户消息不同，这里**不重新生成、不删除任何后续消息**——
 * 改完就落库，直接作为历史参与后面的请求。RP 里只想修掉某一段不满意的描写时，
 * 重跑会连带丢掉后面已经满意的剧情。
 */
@Composable
private fun EditAssistantMessageDialog(
    initialContent: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialContent,
                selection = TextRange(initialContent.length),
            ),
        )
    }
    val canConfirm = text.text.isNotBlank()
    val guardedDismiss = com.muxuechat.presentation.common.components.rememberGuardedDismiss(text.text != initialContent, onDismiss)
    AlertDialog(
        onDismissRequest = guardedDismiss,
        title = { Text("编辑模型回复") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "只改这一条，不会重新生成，也不会删除后面的消息。" +
                        "保存后的正文会作为历史参与下一次请求。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    minLines = 6,
                    maxLines = 16,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = { onConfirm(text.text.trim()) },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = guardedDismiss) {
                Text("取消")
            }
        },
    )
}

private data class RoleCardEditorTarget(
    val id: String?,
    val draft: RoleCardDraft,
) {
    companion object {
        fun newRoleCard(): RoleCardEditorTarget = RoleCardEditorTarget(
            id = null,
            draft = RoleCardDraft(name = ""),
        )

        fun fromExisting(card: RoleCard): RoleCardEditorTarget = RoleCardEditorTarget(
            id = card.id,
            draft = RoleCardDraft(
                name = card.name,
                description = card.description,
                persona = card.persona,
                speakingStyle = card.speakingStyle,
                userIdentity = card.userIdentity,
                openingMessage = card.openingMessage,
                boundaries = card.boundaries,
                metricDefinitions = card.metricDefinitions,
            ),
        )
    }
}

private data class WorldSettingEditorTarget(
    val id: String?,
    val draft: WorldSettingDraft,
) {
    companion object {
        fun newWorldSetting(): WorldSettingEditorTarget = WorldSettingEditorTarget(
            id = null,
            draft = WorldSettingDraft(title = ""),
        )

        fun fromExisting(world: WorldSetting): WorldSettingEditorTarget = WorldSettingEditorTarget(
            id = world.id,
            draft = WorldSettingDraft(
                title = world.title,
                content = world.content,
            ),
        )
    }
}

private data class WritingStyleEditorTarget(
    val id: String?,
    val draft: WritingStyleProfileDraft,
) {
    companion object {
        fun newWritingStyle(): WritingStyleEditorTarget = WritingStyleEditorTarget(
            id = null,
            draft = WritingStyleProfileDraft(name = ""),
        )

        fun fromExisting(style: WritingStyleProfile): WritingStyleEditorTarget = WritingStyleEditorTarget(
            id = style.id,
            draft = WritingStyleProfileDraft(
                name = style.name,
                content = style.content,
            ),
        )
    }
}

/**
 * 待确认草稿的提示条。
 *
 * 草稿只存在于内存中，用户下滑收起后如果没有入口就等于丢了。
 */
@Composable
private fun PendingDraftBanner(
    drafts: List<SettingsToolDraft>,
    onOpen: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        drafts.forEach { draft ->
            Surface(
                onClick = { onOpen(draft.id) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${draft.typeLabel}草稿「${draft.displayName}」待确认",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "尚未保存，点击继续处理",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
