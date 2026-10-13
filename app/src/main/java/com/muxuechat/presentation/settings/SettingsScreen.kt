package com.muxuechat.presentation.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.*
import com.muxuechat.presentation.chat.components.ModelPickerSheet
import com.muxuechat.presentation.common.ModelNotes
import com.muxuechat.presentation.common.components.ModelNoteList
import com.muxuechat.presentation.common.describeDirectorySource
import com.muxuechat.presentation.common.displayName
import com.muxuechat.presentation.settings.components.SettingsGroup
import com.muxuechat.presentation.settings.sections.*
import com.muxuechat.presentation.theme.MinTouchTarget
import com.muxuechat.presentation.theme.Spacing

typealias SettingsMutation = (ChatSettings) -> ChatSettings
typealias CustomProviderMutation = (CustomProvider) -> CustomProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: ChatSettings,
    displaySettings: DisplaySettings,
    statusMessage: String?,
    onSettingsChange: (SettingsMutation) -> Unit,
    onDisplayScaleChange: (DisplayScale) -> Unit,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
    providersWithSavedKey: Set<String>,
    refreshingModelsProviderId: String?,
    onAddProvider: (name: String, baseUrl: String) -> Unit,
    onAddXaiProvider: () -> Unit,
    onAddGoogleProvider: () -> Unit,
    onUpdateProvider: (providerId: String, transform: CustomProviderMutation) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onSaveProviderKey: (providerId: String, apiKey: String) -> Unit,
    onClearProviderKey: (String) -> Unit,
    onRefreshProviderModels: (String) -> Unit,
    xaiAuthState: XaiAuthUiState,
    onXaiLogin: () -> Unit,
    onXaiCancelLogin: () -> Unit,
    onXaiSignOut: () -> Unit,
    onXaiBrowserUnavailable: () -> Unit,
    googleAuthState: GoogleAuthUiState,
    onGoogleLogin: () -> Unit,
    onGoogleSignOut: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    refreshErrors: Map<String, String> = emptyMap(),
) {
    var modelPickerVisible by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val flushCoordinator = remember { SettingsFlushCoordinator() }
    val flushAndBack = {
        flushCoordinator.flush()
        onBack()
    }

    LaunchedEffect(statusMessage) {
        if (!statusMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(statusMessage)
        }
    }
    BackHandler(onBack = flushAndBack)

    CompositionLocalProvider(LocalSettingsFlushCoordinator provides flushCoordinator) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "设置",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = flushAndBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "返回",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "除各服务的 Key 外，设置会自动保存，并在下一次发送消息时生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = Spacing.Content,
                        vertical = Spacing.Small,
                    ),
                )
                SettingsGroup(
                    title = "服务与模型",
                    summary = "${settings.activeProviderLabel} · ${settings.activeModel.ifBlank { "未选择模型" }}",
                    initiallyExpanded = true,
                ) {
                    // 这是整个设置页最常用的入口，之前是一个裸 TextButton，
                    // 既看不出可点，也和下面各分组的缩进对不齐。
                    Button(
                        onClick = { flushCoordinator.flush(); modelPickerVisible = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.Content, vertical = Spacing.Small)
                            .heightIn(min = MinTouchTarget),
                    ) {
                        Text("选择模型：${settings.activeModel.ifBlank { "尚未选择" }}")
                    }
                    ProviderSection(
                        settings = settings,
                        onChange = { transform -> flushCoordinator.flush(); onSettingsChange(transform) },
                        onAddProvider = onAddProvider,
                        onAddXaiProvider = { flushCoordinator.flush(); onAddXaiProvider() },
                        onAddGoogleProvider = { flushCoordinator.flush(); onAddGoogleProvider() },
                        onDeleteProvider = { id -> flushCoordinator.flush(); onDeleteProvider(id) },
                    )
                    refreshErrors[if (settings.isCustomProvider) settings.activeCustomProvider?.id else ChatProviderId.DEEPSEEK.storageValue]?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(Spacing.Content))
                    }
                    val provider = settings.activeCustomProvider
                    key(settings.provider, provider?.id) {
                        if (settings.isCustomProvider && provider != null) {
                            CustomProviderSection(
                                provider = provider,
                                hasSavedKey = provider.id in providersWithSavedKey,
                                isRefreshingThis = refreshingModelsProviderId == provider.id,
                                isRefreshingOther = refreshingModelsProviderId != null && refreshingModelsProviderId != provider.id,
                                onUpdate = { onUpdateProvider(provider.id, it) },
                                onDelete = { flushCoordinator.flush(); onDeleteProvider(provider.id) },
                                onSaveKey = { onSaveProviderKey(provider.id, it) },
                                onClearKey = { onClearProviderKey(provider.id) },
                                onRefreshModels = { onRefreshProviderModels(provider.id) },
                                xaiAuthContent = {
                                    XaiAuthSection(
                                        state = xaiAuthState,
                                        onLogin = onXaiLogin,
                                        onCancelLogin = onXaiCancelLogin,
                                        onSignOut = onXaiSignOut,
                                        onBrowserUnavailable = onXaiBrowserUnavailable,
                                    )
                                },
                                googleAuthContent = {
                                    GoogleAuthSection(
                                        state = googleAuthState,
                                        onLogin = onGoogleLogin,
                                        onSignOut = onGoogleSignOut,
                                    )
                                },
                            )
                        } else {
                            SecureKeySection(
                                // 输入框下方的 supportingText 已经说明了「需要手动保存」，
                                // 状态行也写了「不显示明文」，这里不必再重复一遍。
                                title = "DeepSeek API Key", description = "",
                                placeholder = "输入 API Key", hasSavedKey = ChatProviderId.DEEPSEEK.storageValue in providersWithSavedKey,
                                onSave = onSaveApiKey, onClear = onClearApiKey,
                            )
                            DeepSeekConnectionSection(settings, onSettingsChange)
                            // 目录来源、模型备注和刷新按钮是同一件事的三个部分，
                            // 之前各自带着不同的缩进（16dp / 0dp / 0dp）散在外面，左边参差不齐。
                            Column(
                                modifier = Modifier.padding(
                                    horizontal = Spacing.Content,
                                    vertical = Spacing.Small,
                                ),
                                verticalArrangement = Arrangement.spacedBy(Spacing.Small),
                            ) {
                                Text(
                                    describeDirectorySource(settings.deepSeekModelDirectory),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                ModelNoteList(ModelNotes.deepSeekNotes(settings.model, settings.deepSeekModelDirectory, settings.deepSeekModelContextWindows[settings.model]))
                                RefreshModelsButton(
                                    isRefreshingThis = refreshingModelsProviderId == ChatProviderId.DEEPSEEK.storageValue,
                                    isRefreshingOther = refreshingModelsProviderId != null && refreshingModelsProviderId != ChatProviderId.DEEPSEEK.storageValue,
                                    idleLabel = "刷新 DeepSeek 模型目录",
                                    onRefresh = { flushCoordinator.flush(); onRefreshProviderModels(ChatProviderId.DEEPSEEK.storageValue) },
                                )
                            }
                        }
                    }
                }
                SettingsGroup(
                    title = "xAI 订阅登录",
                    summary = xaiAuthSummary(xaiAuthState.auth),
                ) {
                    XaiAuthSection(
                        state = xaiAuthState,
                        onLogin = onXaiLogin,
                        onCancelLogin = onXaiCancelLogin,
                        onSignOut = onXaiSignOut,
                        onBrowserUnavailable = onXaiBrowserUnavailable,
                    )
                }
                SettingsGroup(
                    title = "Google Vertex 登录",
                    summary = googleAuthSummary(googleAuthState.auth),
                ) {
                    GoogleAuthSection(
                        state = googleAuthState,
                        onLogin = onGoogleLogin,
                        onSignOut = onGoogleSignOut,
                    )
                }
                SettingsGroup(
                    title = "RP 辅助请求",
                    summary = rpAuxiliarySummary(settings),
                ) {
                    RpAuxiliaryModelSection(settings, onSettingsChange)
                }
                SettingsGroup("提示词与生成", "${if (settings.streamEnabled) "流式输出" else "完整回复"} · 输出上限 ${settings.maxTokens}") {
                    SystemPromptSection(settings, onSettingsChange)
                    GenerationSection(settings, onSettingsChange)
                }
                SettingsGroup("思考与采样", if (settings.usesDeepSeekParameters) {
                    if (settings.thinkingMode == ThinkingMode.ENABLED) "思考已开启 · ${settings.reasoningEffort.displayName}；采样不生效" else "思考已关闭；采样生效"
                } else {
                    settings.activeCustomProvider
                        ?.let { "reasoning_effort：${it.reasoningEffort.ifBlank { "不发送" }}" }
                        ?: "尚未配置自定义供应商"
                }) {
                    key(settings.activeCustomProvider?.id) {
                        ThinkingAndSamplingSection(settings, onSettingsChange, onUpdateProvider)
                    }
                }
                SettingsGroup("上下文管理", if (settings.contextCostCapEnabled) "输入成本上限 ${settings.contextCostCapTokens} tokens" else "按模型窗口计算，无额外成本上限") {
                    ContextCapacitySection(
                        settings = settings,
                        isRefreshingThis = refreshingModelsProviderId == ChatProviderId.DEEPSEEK.storageValue,
                        isRefreshingOther = refreshingModelsProviderId != null && refreshingModelsProviderId != ChatProviderId.DEEPSEEK.storageValue,
                        onRefreshDeepSeekModels = { flushCoordinator.flush(); onRefreshProviderModels(ChatProviderId.DEEPSEEK.storageValue) },
                        onChange = onSettingsChange,
                    )
                }
                SettingsGroup("外观", "${displaySettings.displayScale.label} · 深浅色跟随系统") {
                    DisplaySection(displaySettings, onDisplayScaleChange)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
    if (modelPickerVisible) {
        ModelPickerSheet(
            settings, refreshingModelsProviderId,
            refreshErrors = refreshErrors,
            onSelectDeepSeekModel = { model -> onSettingsChange { it.copy(provider = ChatProviderId.DEEPSEEK, model = model) } },
            onSelectCustomProviderModel = { id, model ->
                onUpdateProvider(id) { it.copy(selectedModel = model) }
                onSettingsChange { it.copy(provider = ChatProviderId.CUSTOM_OPENAI, activeCustomProviderId = id) }
            },
            onRefreshProviderModels = onRefreshProviderModels,
            onOpenSettings = { modelPickerVisible = false },
            onDismiss = { modelPickerVisible = false },
        )
    }

}
