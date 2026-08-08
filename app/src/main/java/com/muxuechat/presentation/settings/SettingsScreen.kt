package com.muxuechat.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.DisplayScale
import com.muxuechat.domain.model.DisplaySettings
import com.muxuechat.domain.model.ReasoningEffort
import com.muxuechat.domain.model.ThinkingMode
import com.muxuechat.presentation.settings.components.ChoiceOption
import com.muxuechat.presentation.settings.components.ChoiceRow
import com.muxuechat.presentation.settings.components.SettingsRow
import com.muxuechat.presentation.settings.components.SettingsSection
import com.muxuechat.presentation.settings.components.SliderField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: ChatSettings,
    displaySettings: DisplaySettings,
    statusMessage: String?,
    onSettingsChange: (ChatSettings) -> Unit,
    onDisplayScaleChange: (DisplayScale) -> Unit,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
    providersWithSavedKey: Set<String>,
    refreshingModelsProviderId: String?,
    onAddProvider: (name: String, baseUrl: String) -> Unit,
    onUpdateProvider: (CustomProvider) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onSaveProviderKey: (providerId: String, apiKey: String) -> Unit,
    onClearProviderKey: (String) -> Unit,
    onRefreshProviderModels: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        if (!statusMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(statusMessage)
        }
    }

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
                    IconButton(onClick = onBack) {
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "除各服务的 Key 外，设置会自动保存，并在下一次发送消息时生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            ProviderSection(
                settings = settings,
                onChange = onSettingsChange,
                onAddProvider = onAddProvider,
            )
            if (settings.isCustomProvider) {
                val activeProvider = settings.activeCustomProvider
                if (activeProvider != null) {
                    CustomProviderSection(
                        provider = activeProvider,
                        hasSavedKey = providersWithSavedKey.contains(activeProvider.id),
                        isRefreshingModels = refreshingModelsProviderId == activeProvider.id,
                        onUpdate = onUpdateProvider,
                        onDelete = { onDeleteProvider(activeProvider.id) },
                        onSaveKey = { key -> onSaveProviderKey(activeProvider.id, key) },
                        onClearKey = { onClearProviderKey(activeProvider.id) },
                        onRefreshModels = { onRefreshProviderModels(activeProvider.id) },
                    )
                }
            } else {
                ApiKeySection(onSave = onSaveApiKey, onClear = onClearApiKey)
                ConnectionSection(settings = settings, onChange = onSettingsChange)
            }
            DisplaySection(
                displaySettings = displaySettings,
                onDisplayScaleChange = onDisplayScaleChange,
            )
            SystemPromptSection(settings = settings, onChange = onSettingsChange)
            GenerationSection(settings = settings, onChange = onSettingsChange)
            SamplingSection(settings = settings, onChange = onSettingsChange)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProviderSection(
    settings: ChatSettings,
    onChange: (ChatSettings) -> Unit,
    onAddProvider: (name: String, baseUrl: String) -> Unit,
) {
    var addDialogVisible by remember { mutableStateOf(false) }

    SettingsSection(
        title = "服务",
        description = "目前只支持两种协议：DeepSeek 官方协议，以及 OpenAI 兼容格式" +
            "（POST /chat/completions、GET /models）。Anthropic、Gemini 等原生协议不支持，" +
            "请改用它们的 OpenAI 兼容端点。具体模型在聊天页顶部切换。",
    ) {
        ProviderRow(
            title = "DeepSeek",
            subtitle = settings.baseUrl,
            selected = !settings.isCustomProvider,
            onClick = { onChange(settings.copy(provider = ChatProviderId.DEEPSEEK)) },
        )
        settings.customProviders.forEach { provider ->
            ProviderRow(
                title = provider.displayName,
                subtitle = provider.baseUrl.ifBlank { "未填写 Base URL" },
                selected = settings.isCustomProvider && settings.activeCustomProvider?.id == provider.id,
                onClick = {
                    onChange(
                        settings.copy(
                            provider = ChatProviderId.CUSTOM_OPENAI,
                            activeCustomProviderId = provider.id,
                        ),
                    )
                },
            )
        }
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedButton(
                onClick = { addDialogVisible = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("添加供应商")
            }
        }
    }

    if (addDialogVisible) {
        AddProviderDialog(
            onDismiss = { addDialogVisible = false },
            onConfirm = { name, baseUrl ->
                addDialogVisible = false
                onAddProvider(name, baseUrl)
            },
        )
    }
}

@Composable
private fun ProviderRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainer,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (selected) {
            Text(
                text = "使用中",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, baseUrl: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    val canConfirm = name.isNotBlank() && baseUrl.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加供应商") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(CustomProvider.MAX_NAME_LENGTH) },
                    singleLine = true,
                    label = { Text("名称") },
                    placeholder = { Text("例如 OpenRouter") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = quietTextFieldColors(),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    singleLine = true,
                    label = { Text("Base URL") },
                    placeholder = { Text("https://example.com/v1") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = quietTextFieldColors(),
                )
                Text(
                    text = "仅支持 OpenAI 兼容格式。填到 /v1 为止即可，" +
                        "App 会自动追加 /chat/completions 与 /models。" +
                        "局域网服务可以用 http://，但内容不加密。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), baseUrl.trim()) },
                enabled = canConfirm,
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun DisplaySection(
    displaySettings: DisplaySettings,
    onDisplayScaleChange: (DisplayScale) -> Unit,
) {
    SettingsSection(
        title = "显示大小",
        description = "在 Android 系统字体大小的基础上叠加，仅影响文字与行距，不会改变图标和按钮尺寸。",
    ) {
        ChoiceRow(
            options = DisplayScale.entries.map { ChoiceOption(it, it.label) },
            selected = displaySettings.displayScale,
            onSelect = onDisplayScaleChange,
        )
    }
}

@Composable
private fun ApiKeySection(
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) = SecureKeySection(
    title = "DeepSeek API Key",
    description = "已保存的 Key 出于安全不会再次展示。若需更换，请输入新的 Key 并保存。",
    placeholder = "输入 DeepSeek API Key",
    onSave = onSave,
    onClear = onClear,
)

/**
 * 当前选中的自定义供应商的完整编辑区。
 *
 * 名称与 Base URL 沿用"实时保存"的约定；API Key 与删除操作则必须显式触发，
 * 避免打字过程中把半截 Key 或误触的删除写进存储。
 */
@Composable
private fun CustomProviderSection(
    provider: CustomProvider,
    hasSavedKey: Boolean,
    isRefreshingModels: Boolean,
    onUpdate: (CustomProvider) -> Unit,
    onDelete: () -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onRefreshModels: () -> Unit,
) {
    val nameField = rememberSyncedTextFieldValue(external = provider.name)
    val baseUrlField = rememberSyncedTextFieldValue(external = provider.baseUrl)
    var deleteDialogVisible by remember { mutableStateOf(false) }

    SettingsSection(
        title = "供应商设置",
        description = buildString {
            append("支持 http:// 与 https:// 地址。局域网自建服务请填写手机可访问的 IP 或域名，")
            append("不要填 localhost 或 127.0.0.1。")
            if (CustomProvider.isCleartext(provider.baseUrl)) {
                append("\n⚠ 当前是明文 http：API Key 与聊天内容不加密，只建议用于自己可控的局域网。")
            }
        },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = nameField.value,
                onValueChange = { new ->
                    nameField.value = new
                    if (new.text != provider.name) {
                        onUpdate(provider.copy(name = new.text))
                    }
                },
                singleLine = true,
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = quietTextFieldColors(),
            )
            OutlinedTextField(
                value = baseUrlField.value,
                onValueChange = { new ->
                    baseUrlField.value = new
                    if (new.text != provider.baseUrl) {
                        onUpdate(provider.copy(baseUrl = new.text))
                    }
                },
                singleLine = true,
                label = { Text("Base URL") },
                placeholder = { Text("https://example.com/v1") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = quietTextFieldColors(),
            )
        }
    }

    SecureKeySection(
        title = "${provider.displayName} 的 API Key",
        description = if (hasSavedKey) {
            "已保存一个 Key，出于安全不会再次展示。若需更换，请输入新的 Key 并保存。"
        } else {
            "尚未保存 Key。只填写该服务签发的 API Key，不要填写 OAuth Token 或 Cookie。"
        },
        placeholder = "输入 API Key",
        onSave = onSaveKey,
        onClear = onClearKey,
    )

    ProviderModelsSection(
        provider = provider,
        isRefreshing = isRefreshingModels,
        onUpdate = onUpdate,
        onRefresh = onRefreshModels,
    )

    ProviderReasoningSection(provider = provider, onUpdate = onUpdate)

    ProviderToolCallingSection(provider = provider, onUpdate = onUpdate)

    SettingsSection(title = "移除") {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedButton(
                onClick = { deleteDialogVisible = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("删除此供应商", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (deleteDialogVisible) {
        AlertDialog(
            onDismissRequest = { deleteDialogVisible = false },
            title = { Text("删除「${provider.displayName}」？") },
            text = { Text("该供应商的地址、模型列表与已保存的 API Key 都会被删除，且无法恢复。已有的聊天记录不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteDialogVisible = false
                        onDelete()
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogVisible = false }) { Text("取消") }
            },
        )
    }
}

/** 每个 OpenAI 兼容端点单独确认工具能力，避免把 tools 发给只支持普通聊天的反代。 */
@Composable
private fun ProviderToolCallingSection(
    provider: CustomProvider,
    onUpdate: (CustomProvider) -> Unit,
) {
    SettingsSection(
        title = "工具调用",
        description = "仅当该端点和所选模型支持标准 OpenAI tools / tool_calls 时开启。" +
            "开启后，非 RP 聊天可创建角色卡、世界观和文风草稿；不支持时请保持关闭，以免请求报错。",
    ) {
        SettingsRow(
            title = "启用标准工具调用",
            subtitle = if (provider.toolCallingEnabled) "此供应商会接收 tools 与 tool_choice 字段" else "默认关闭，兼容仅支持普通聊天的反代",
            trailing = {
                Switch(
                    checked = provider.toolCallingEnabled,
                    onCheckedChange = { onUpdate(provider.copy(toolCallingEnabled = it)) },
                    colors = quietSwitchColors(),
                )
            },
        )
    }
}

/**
 * 思考等级：直接对应请求体里的 `reasoning_effort` 字段。
 *
 * 取值不做白名单校验——各家支持的档位并不一致（还有 minimal、none、xhigh 等），
 * 预设按钮只是快捷填充。留空表示完全不发送该字段，这是默认行为：
 * 不支持思考的模型收到未知字段可能直接 400。
 */
@Composable
private fun ProviderReasoningSection(
    provider: CustomProvider,
    onUpdate: (CustomProvider) -> Unit,
) {
    val effortField = rememberSyncedTextFieldValue(external = provider.reasoningEffort)

    SettingsSection(
        title = "思考等级",
        description = "对应 OpenAI 兼容协议的 reasoning_effort 字段。留空则不发送该字段；" +
            "若服务或模型不支持思考，填了可能导致请求失败。仅用于正文对话，" +
            "RP 补写与上下文摘要不会发送。",
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = effortField.value,
                onValueChange = { new ->
                    val trimmed = new.text.take(CustomProvider.MAX_REASONING_EFFORT_LENGTH)
                    effortField.value = new.copy(text = trimmed)
                    if (trimmed != provider.reasoningEffort) {
                        onUpdate(provider.copy(reasoningEffort = trimmed))
                    }
                },
                singleLine = true,
                label = { Text("reasoning_effort") },
                placeholder = { Text("留空表示不发送") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = quietTextFieldColors(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CustomProvider.REASONING_EFFORT_PRESETS.forEach { preset ->
                    val selected = provider.reasoningEffort == preset
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer,
                            )
                            .clickable { onUpdate(provider.copy(reasoningEffort = preset)) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = preset,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                TextButton(
                    onClick = { onUpdate(provider.copy(reasoningEffort = "")) },
                    enabled = provider.reasoningEffort.isNotBlank(),
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Text("不发送")
                }
            }
        }
    }
}

/**
 * 模型列表管理。
 *
 * 拉取是"尽力而为"：不少 OpenAI 兼容服务没有 /models，所以手填入口必须常驻，
 * 不能只在拉取失败后才出现。
 */
@Composable
private fun ProviderModelsSection(
    provider: CustomProvider,
    isRefreshing: Boolean,
    onUpdate: (CustomProvider) -> Unit,
    onRefresh: () -> Unit,
) {
    var manualModel by remember { mutableStateOf("") }

    SettingsSection(
        title = "可用模型",
        description = "聊天页顶部从这个列表里选择模型。服务不支持 /models 时可以手动添加。",
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (provider.models.isEmpty()) {
                Text(
                    text = "还没有模型。点击下方按钮拉取，或手动添加一个模型名。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                provider.models.forEach { model ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (model == provider.selectedModel) {
                            Text(
                                text = "使用中",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        TextButton(
                            onClick = {
                                onUpdate(provider.copy(models = provider.models - model))
                            },
                        ) {
                            Text("移除")
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = manualModel,
                    onValueChange = { manualModel = it },
                    singleLine = true,
                    placeholder = { Text("手动添加模型名") },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = quietTextFieldColors(),
                )
                TextButton(
                    onClick = {
                        val model = manualModel.trim()
                        if (model.isNotEmpty()) {
                            onUpdate(provider.copy(models = provider.models + model))
                            manualModel = ""
                        }
                    },
                    enabled = manualModel.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("添加")
                }
            }

            OutlinedButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(if (isRefreshing) "正在获取…" else "从服务获取模型列表")
            }
        }
    }
}

@Composable
private fun SecureKeySection(
    title: String,
    description: String,
    placeholder: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    SettingsSection(
        title = title,
        description = description,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                placeholder = { Text(placeholder) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = quietTextFieldColors(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        if (input.isNotBlank()) {
                            onSave(input.trim())
                            input = ""
                        }
                    },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text("保存 Key")
                }
                TextButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text("清除已保存")
                }
            }
        }
    }
}

@Composable
private fun ConnectionSection(
    settings: ChatSettings,
    onChange: (ChatSettings) -> Unit,
) {
    val fieldValue = rememberSyncedTextFieldValue(external = settings.baseUrl)
    SettingsSection(title = "连接") {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = fieldValue.value,
                onValueChange = { new ->
                    fieldValue.value = new
                    if (new.text != settings.baseUrl) {
                        onChange(settings.copy(baseUrl = new.text))
                    }
                },
                singleLine = true,
                placeholder = { Text("https://api.deepseek.com") },
                label = { Text("Base URL") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = quietTextFieldColors(),
            )
        }
    }
}

@Composable
private fun SystemPromptSection(
    settings: ChatSettings,
    onChange: (ChatSettings) -> Unit,
) {
    val fieldValue = rememberSyncedTextFieldValue(external = settings.systemPrompt)
    SettingsSection(title = "System Prompt") {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = fieldValue.value,
                onValueChange = { new ->
                    fieldValue.value = new
                    if (new.text != settings.systemPrompt) {
                        onChange(settings.copy(systemPrompt = new.text))
                    }
                },
                placeholder = { Text("可选，用于设置助手的角色或风格") },
                minLines = 3,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = quietTextFieldColors(),
            )
        }
    }
}

/**
 * 用本地 TextFieldValue 保留光标/selection；当外部值（来自异步 Flow 回流）
 * 与本地 text 不一致时，才把光标放到末尾并刷新本地状态。
 * 这样可避免上游单向覆盖打断中文 IME 的 composition 或把光标跳到开头。
 */
@Composable
private fun rememberSyncedTextFieldValue(external: String): MutableState<TextFieldValue> {
    val state = remember {
        mutableStateOf(TextFieldValue(text = external, selection = TextRange(external.length)))
    }
    LaunchedEffect(external) {
        if (external != state.value.text) {
            state.value = TextFieldValue(
                text = external,
                selection = TextRange(external.length),
            )
        }
    }
    return state
}

@Composable
private fun GenerationSection(
    settings: ChatSettings,
    onChange: (ChatSettings) -> Unit,
) {
    SettingsSection(title = "生成") {
        SettingsRow(
            title = "流式输出",
            subtitle = "开启后逐字返回模型响应",
            trailing = {
                Switch(
                    checked = settings.streamEnabled,
                    onCheckedChange = { onChange(settings.copy(streamEnabled = it)) },
                    colors = quietSwitchColors(),
                )
            },
        )
        if (settings.usesDeepSeekParameters) {
            SettingsRow(
                title = "思考模式",
                subtitle = "开启后模型会先生成推理过程",
                trailing = {
                    Switch(
                        checked = settings.thinkingMode == ThinkingMode.ENABLED,
                        onCheckedChange = {
                            onChange(
                                settings.copy(
                                    thinkingMode = if (it) ThinkingMode.ENABLED else ThinkingMode.DISABLED,
                                ),
                            )
                        },
                        colors = quietSwitchColors(),
                    )
                },
            )
            if (settings.thinkingMode == ThinkingMode.ENABLED) {
                Text(
                    text = "思考强度",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
                ChoiceRow(
                    options = listOf(
                        ChoiceOption(ReasoningEffort.HIGH, "high"),
                        ChoiceOption(ReasoningEffort.MAX, "max"),
                    ),
                    selected = settings.reasoningEffort,
                    onSelect = { onChange(settings.copy(reasoningEffort = it)) },
                )
            }
        }
        MaxTokensRow(settings = settings, onChange = onChange)
        SettingsRow(
            title = "设定生成工具",
            subtitle = "总开关。关闭 RP 时，允许已启用工具能力的供应商创建角色卡、世界观和文风草稿",
            trailing = {
                Switch(
                    checked = settings.settingsToolsEnabled,
                    onCheckedChange = { onChange(settings.copy(settingsToolsEnabled = it)) },
                    colors = quietSwitchColors(),
                )
            },
        )
    }
}

@Composable
private fun MaxTokensRow(
    settings: ChatSettings,
    onChange: (ChatSettings) -> Unit,
) {
    var text by remember(settings.maxTokens) { mutableStateOf(settings.maxTokens.toString()) }
    // 只有落在合法区间内才写回，避免把 0 或几亿这种值一路带到请求体。
    val parsed = ChatSettings.parseMaxTokensOrNull(text)
    LaunchedEffect(text) {
        if (parsed != null && parsed != settings.maxTokens) {
            onChange(settings.copy(maxTokens = parsed))
        }
    }
    SettingsRow(
        title = "最大 Token",
        subtitle = if (parsed == null) {
            "请输入 ${ChatSettings.MIN_MAX_TOKENS}~${ChatSettings.MAX_MAX_TOKENS} 之间的数值"
        } else {
            "单次响应的上限（${ChatSettings.MIN_MAX_TOKENS}~${ChatSettings.MAX_MAX_TOKENS}）"
        },
        trailing = {
            OutlinedTextField(
                value = text,
                onValueChange = { new -> text = new.filter { it.isDigit() }.take(6) },
                isError = parsed == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .width(110.dp)
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = quietTextFieldColors(),
            )
        },
    )
}

@Composable
private fun SamplingSection(
    settings: ChatSettings,
    onChange: (ChatSettings) -> Unit,
) {
    val effective = settings.samplingParametersEffective
    SettingsSection(
        title = "采样参数",
        description = if (!effective) "当前已开启思考模式，下列采样参数暂不会生效。" else null,
    ) {
        SliderField(
            title = "Temperature",
            value = settings.temperature,
            onValueChange = { onChange(settings.copy(temperature = it)) },
            range = 0f..2f,
            enabled = effective,
        )
        SliderField(
            title = "Top P",
            value = settings.topP,
            onValueChange = { onChange(settings.copy(topP = it)) },
            range = 0f..1f,
            enabled = effective,
        )
        // Presence / Frequency Penalty 已被 DeepSeek 废弃且永不生效，故整体移除。
    }
}

@Composable
private fun quietTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun quietSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
)
