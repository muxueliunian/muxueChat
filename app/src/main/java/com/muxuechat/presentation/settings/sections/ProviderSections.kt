package com.muxuechat.presentation.settings.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.DeepSeekModels
import com.muxuechat.domain.model.RpAuxiliaryProvider
import com.muxuechat.presentation.common.ModelListWindow
import com.muxuechat.presentation.common.components.MetaLine
import com.muxuechat.presentation.common.components.ModelSearchField
import com.muxuechat.presentation.common.components.TruncatedListHint
import com.muxuechat.presentation.common.components.quietTextFieldColors
import com.muxuechat.presentation.common.describeLastRefreshed
import com.muxuechat.presentation.common.describeRefreshAction
import com.muxuechat.presentation.settings.CustomProviderMutation
import com.muxuechat.presentation.settings.DebouncedTextSetting
import com.muxuechat.presentation.settings.LocalSettingsFlushCoordinator
import com.muxuechat.presentation.settings.SettingsMutation
import com.muxuechat.presentation.settings.quietSwitchColors
import com.muxuechat.presentation.settings.rememberSyncedTextFieldValue
import com.muxuechat.presentation.settings.components.SettingsDivider
import com.muxuechat.presentation.settings.components.SettingsRow
import com.muxuechat.presentation.settings.components.SettingsSection
import com.muxuechat.presentation.theme.Corner
import com.muxuechat.presentation.theme.MinTouchTarget
import com.muxuechat.presentation.theme.Spacing

/** Base URL 的行内校验结果。空地址、非法地址、明文 http 是三种不同的话，不能混成一句。 */
internal sealed interface BaseUrlValidation {
    data object Empty : BaseUrlValidation
    data object Invalid : BaseUrlValidation
    data class Cleartext(val normalized: String) : BaseUrlValidation
    data class Secure(val normalized: String) : BaseUrlValidation

    val isError: Boolean get() = this is Invalid || this is Empty
}

internal fun validateBaseUrl(raw: String): BaseUrlValidation {
    if (raw.isBlank()) return BaseUrlValidation.Empty
    val normalized = CustomProvider.normalizeBaseUrl(raw)
    if (normalized.isEmpty()) return BaseUrlValidation.Invalid
    return if (CustomProvider.isCleartext(normalized)) {
        BaseUrlValidation.Cleartext(normalized)
    } else {
        BaseUrlValidation.Secure(normalized)
    }
}

internal fun BaseUrlValidation.message(): String = when (this) {
    BaseUrlValidation.Empty -> "请输入 Base URL，例如服务提供的 https://example.com/v1。"
    BaseUrlValidation.Invalid ->
        "地址无法解析。需要以 http:// 或 https:// 开头，且不能包含用户名、查询串或 #片段。"
    is BaseUrlValidation.Cleartext ->
        "明文 HTTP：API Key 与聊天内容不加密，只建议用于自己可控的局域网服务。"
    is BaseUrlValidation.Secure -> "地址格式有效；连接是否可用需要通过刷新模型验证。"
}

/** 供应商列表。当前供应商必须一眼可见，因此选中项同时用边框、勾选图标和文字标注。 */
@Composable
fun ProviderSection(
    settings: ChatSettings,
    onChange: (SettingsMutation) -> Unit,
    onAddProvider: (name: String, baseUrl: String) -> Unit,
    onAddXaiProvider: () -> Unit,
    onAddGoogleProvider: () -> Unit,
    onDeleteProvider: (String) -> Unit,
) {
    var addDialogVisible by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<CustomProvider?>(null) }

    SettingsSection(
        title = "当前服务",
        description = "只支持两种协议：DeepSeek 官方协议，以及 OpenAI 兼容格式" +
            "（POST /chat/completions、GET /models）。Anthropic、Gemini 等原生协议不支持，" +
            "请改用它们的 OpenAI 兼容端点。",
    ) {
        ProviderRow(
            title = "DeepSeek",
            subtitle = settings.baseUrl,
            trailingLabel = if (!settings.isCustomProvider) {
                settings.model.ifBlank { "未选择模型" }
            } else {
                null
            },
            selected = !settings.isCustomProvider,
            onClick = { onChange { it.copy(provider = ChatProviderId.DEEPSEEK) } },
        )
        settings.customProviders.forEach { provider ->
            val selected = settings.isCustomProvider &&
                settings.activeCustomProvider?.id == provider.id
            ProviderRow(
                title = provider.displayName,
                subtitle = if (provider.usesXaiOAuth) {
                    "xAI 订阅登录 · ${provider.baseUrl}"
                } else if (provider.usesGoogleOAuth) {
                    "Google Vertex 登录 · ${provider.googleProjectId.ifBlank { "未填写项目 ID" }}"
                } else {
                    provider.baseUrl.ifBlank { "未填写 Base URL" }
                },
                trailingLabel = if (selected) {
                    provider.selectedModel.ifBlank { "未选择模型" }
                } else {
                    null
                },
                selected = selected,
                onClick = {
                    onChange { current ->
                        current.copy(
                            provider = ChatProviderId.CUSTOM_OPENAI,
                            activeCustomProviderId = provider.id,
                        )
                    }
                },
                onDelete = { deleteTarget = provider },
            )
        }
        Row(modifier = Modifier.padding(vertical = Spacing.Small)) {
            OutlinedButton(
                onClick = { addDialogVisible = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            ) {
                Text("添加 OpenAI 兼容供应商")
            }
        }
        val hasXaiProvider = settings.customProviders.any { it.usesXaiOAuth }
        Column(modifier = Modifier.padding(bottom = Spacing.Small)) {
            OutlinedButton(
                onClick = onAddXaiProvider,
                enabled = !hasXaiProvider,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            ) {
                Text("添加 xAI 订阅供应商")
            }
            if (hasXaiProvider) {
                // 禁用必须说明原因：所有 xAI 供应商共用同一个登录，多建一个没有意义。
                MetaLine("已添加 xAI 订阅供应商；所有 xAI 请求共用同一个登录，只能添加一个。")
            }
        }
        // Google 登录是全局的，但项目 ID 属于供应商：不同项目可以各建一个供应商。
        Column(modifier = Modifier.padding(bottom = Spacing.Small)) {
            OutlinedButton(
                onClick = onAddGoogleProvider,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            ) {
                Text("添加 Google Vertex 供应商")
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

    deleteTarget?.let { provider ->
        DeleteProviderDialog(
            provider = provider,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                onDeleteProvider(provider.id)
            },
        )
    }
}

@Composable
internal fun DeleteProviderDialog(
    provider: CustomProvider,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除「${provider.displayName}」？") },
        text = {
            Text(
                if (provider.usesXaiOAuth) {
                    "会同时删除：模型列表与逐模型上下文窗口。此操作无法撤销。\n\n" +
                        "不会退出 xAI 登录；如需退出，请到「xAI 订阅登录」分组操作。已有的聊天记录不受影响。"
                } else if (provider.usesGoogleOAuth) {
                    "会同时删除：项目 ID、模型列表与逐模型上下文窗口。此操作无法撤销。\n\n" +
                        "不会退出 Google 登录；如需退出，请到「Google Vertex 登录」分组操作。已有的聊天记录不受影响。"
                } else {
                    "会同时删除：地址、模型列表、逐模型上下文窗口，" +
                        "以及保存在这台设备上的该供应商 API Key。此操作无法撤销。\n\n" +
                        "已有的聊天记录不受影响。"
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除供应商与 Key", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ProviderRow(
    title: String,
    subtitle: String,
    trailingLabel: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            // 外层只留纵向间隔：横向再缩进的话，卡片就会比它所属分组的标题更靠里。
            .padding(vertical = Spacing.Tight)
            .clip(Corner.Control)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainer,
            )
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = Corner.Control,
            )
            .clickable(onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = if (selected) {
                    "$title，使用中，地址 $subtitle"
                } else {
                    "$title，地址 $subtitle"
                }
            }
            .padding(horizontal = Spacing.Medium, vertical = Spacing.Small),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics { },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 2.dp),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (trailingLabel != null) {
                Text(
                    text = "使用中 · $trailingLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        onDelete?.let { delete ->
            IconButton(onClick = delete, modifier = Modifier.size(MinTouchTarget)) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "删除供应商 $title",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, baseUrl: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    val validation = validateBaseUrl(baseUrl)
    val canConfirm = name.isNotBlank() &&
        (validation is BaseUrlValidation.Secure || validation is BaseUrlValidation.Cleartext)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加供应商") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(CustomProvider.MAX_NAME_LENGTH) },
                    singleLine = true,
                    isError = name.isBlank(),
                    label = { Text("名称") },
                    placeholder = { Text("例如 OpenRouter") },
                    supportingText = {
                        Text(if (name.isBlank()) "请填写一个便于识别的名称" else "用于列表和顶栏显示")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = Corner.Control,
                    colors = quietTextFieldColors(),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    singleLine = true,
                    isError = validation.isError,
                    label = { Text("Base URL") },
                    placeholder = { Text("https://example.com/v1") },
                    supportingText = { Text(validation.message()) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    shape = Corner.Control,
                    colors = quietTextFieldColors(),
                )
                Text(
                    text = "仅支持 OpenAI 兼容格式。局域网服务可以用 http://，但内容不加密。",
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * API Key 输入。
 *
 * 两条不可动摇的规则：已保存的 Key 永远不回显；清除必须二次确认——
 * 误触清除会让下一次发送直接 401，而 Key 本身在设备上已经找不回来了。
 */
@Composable
fun SecureKeySection(
    title: String,
    description: String,
    placeholder: String,
    hasSavedKey: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var clearDialogVisible by remember { mutableStateOf(false) }

    SettingsSection(title = title, description = description) {
        Column(
            modifier = Modifier.padding(vertical = Spacing.Small),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            MetaLine(
                text = if (hasSavedKey) {
                    "状态：已保存一个 Key（不显示明文）"
                } else {
                    "状态：尚未保存 Key"
                },
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                placeholder = { Text(placeholder) },
                label = { Text(title) },
                supportingText = { Text("Key 不会自动保存，需要点击下方「保存 Key」。") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = Corner.Control,
                colors = quietTextFieldColors(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                OutlinedButton(
                    onClick = {
                        if (input.isNotBlank()) {
                            onSave(input.trim())
                            input = ""
                        }
                    },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget),
                ) {
                    Text("保存 Key")
                }
                TextButton(
                    onClick = { clearDialogVisible = true },
                    enabled = hasSavedKey,
                    modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget),
                ) {
                    Text(if (hasSavedKey) "清除已保存" else "无可清除")
                }
            }
        }
    }

    if (clearDialogVisible) {
        AlertDialog(
            onDismissRequest = { clearDialogVisible = false },
            title = { Text("清除$title？") },
            text = { Text("清除后本机不再保存该 Key，下一次发送会因缺少鉴权而失败，需要重新粘贴。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearDialogVisible = false
                        onClear()
                    },
                ) {
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearDialogVisible = false }) { Text("取消") }
            },
        )
    }
}

/** DeepSeek 的连接地址。明文提示与自定义供应商共用同一套校验文案。 */
@Composable
fun DeepSeekConnectionSection(
    settings: ChatSettings,
    onChange: (SettingsMutation) -> Unit,
) {
    val fieldValue = rememberSyncedTextFieldValue(external = settings.baseUrl)
    DebouncedTextSetting(
        fieldValue = fieldValue,
        persistedValue = settings.baseUrl,
        canCommit = { !validateBaseUrl(it).isError },
        onCommit = { value -> onChange { it.copy(baseUrl = value) } },
    )
    val validation = validateBaseUrl(fieldValue.value.text)
    SettingsSection(title = "DeepSeek 连接") {
        Column(modifier = Modifier.padding(vertical = Spacing.Small)) {
            OutlinedTextField(
                value = fieldValue.value,
                onValueChange = { new -> fieldValue.value = new },
                singleLine = true,
                isError = validation.isError,
                placeholder = { Text(ChatSettings.DEFAULT_BASE_URL) },
                label = { Text("Base URL") },
                supportingText = { Text(validation.message()) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = Corner.Control,
                colors = quietTextFieldColors(),
            )
        }
    }
}

/**
 * 当前选中的自定义供应商的编辑区。
 *
 * 名称与 Base URL 沿用「实时保存」的约定；API Key 与删除必须显式触发，
 * 避免打字过程中把半截 Key 或误触的删除写进存储。
 */
@Composable
fun CustomProviderSection(
    provider: CustomProvider,
    hasSavedKey: Boolean,
    isRefreshingThis: Boolean,
    isRefreshingOther: Boolean,
    onUpdate: (CustomProviderMutation) -> Unit,
    onDelete: () -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onRefreshModels: () -> Unit,
    /** xAI 订阅供应商用它替代 API Key 输入区，复用设置页已有的 [XaiAuthSection]。 */
    xaiAuthContent: @Composable () -> Unit = {},
    /** Google Vertex 供应商用它替代 API Key 输入区，复用设置页已有的 [GoogleAuthSection]。 */
    googleAuthContent: @Composable () -> Unit = {},
) {
    val flushCoordinator = LocalSettingsFlushCoordinator.current
    val nameField = rememberSyncedTextFieldValue(external = provider.name)
    val baseUrlField = rememberSyncedTextFieldValue(external = provider.baseUrl)
    val projectIdField = rememberSyncedTextFieldValue(external = provider.googleProjectId)
    var deleteDialogVisible by remember { mutableStateOf(false) }
    DebouncedTextSetting(
        fieldValue = nameField,
        persistedValue = provider.name,
        canCommit = { it.isNotBlank() },
        onCommit = { value -> onUpdate { it.copy(name = value) } },
    )
    if (!provider.usesOAuth) {
        DebouncedTextSetting(
            fieldValue = baseUrlField,
            persistedValue = provider.baseUrl,
            canCommit = { !validateBaseUrl(it).isError },
            onCommit = { value -> onUpdate { it.copy(baseUrl = value) } },
        )
    }
    if (provider.usesGoogleOAuth) {
        DebouncedTextSetting(
            fieldValue = projectIdField,
            persistedValue = provider.googleProjectId,
            // 非法项目 ID 不落库：地址由它推导，半截内容会让 baseUrl 反复归零。
            canCommit = { it.isBlank() || CustomProvider.isValidGoogleProjectId(it.trim()) },
            onCommit = { value -> onUpdate { it.copy(googleProjectId = value.trim()) } },
        )
    }
    val validation = validateBaseUrl(baseUrlField.value.text)

    SettingsSection(
        title = "供应商设置",
        description = if (provider.usesXaiOAuth) {
            "xAI 订阅供应商的地址由 App 固定，不可编辑，也不适用明文 HTTP。"
        } else if (provider.usesGoogleOAuth) {
            "Google Vertex 的地址由项目 ID 推导，主机固定为 ${CustomProvider.GOOGLE_VERTEX_HOST}，区域固定 global，" +
                "只走 https，不可编辑。"
        } else {
            "局域网自建服务请填写手机可访问的 IP 或域名，不要填 localhost 或 127.0.0.1。"
        },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
            modifier = Modifier.padding(vertical = Spacing.Small),
        ) {
            OutlinedTextField(
                value = nameField.value,
                onValueChange = { new ->
                    nameField.value = new.copy(text = new.text.take(CustomProvider.MAX_NAME_LENGTH))
                },
                singleLine = true,
                isError = nameField.value.text.isBlank(),
                label = { Text("名称") },
                supportingText = {
                    Text(
                        if (nameField.value.text.isBlank()) {
                            "名称不能为空，暂未保存"
                        } else {
                            "最多 ${CustomProvider.MAX_NAME_LENGTH} 个字符"
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = Corner.Control,
                colors = quietTextFieldColors(),
            )
            if (provider.usesXaiOAuth) {
                MetaLine("地址（固定）：${provider.baseUrl}")
            } else if (provider.usesGoogleOAuth) {
                val projectError = projectIdField.value.text.isNotBlank() &&
                    !CustomProvider.isValidGoogleProjectId(projectIdField.value.text.trim())
                OutlinedTextField(
                    value = projectIdField.value,
                    onValueChange = { new -> projectIdField.value = new.copy(text = new.text.take(MAX_PROJECT_ID_LENGTH)) },
                    singleLine = true,
                    isError = projectError,
                    label = { Text("GCP 项目 ID") },
                    placeholder = { Text("my-project-123456") },
                    supportingText = {
                        Text(
                            when {
                                projectIdField.value.text.isBlank() -> "必填。注意是项目 ID，不是项目名称或项目编号"
                                projectError -> "格式无效，暂未保存：6~30 位小写字母、数字、连字符，字母开头"
                                else -> "格式有效；是否有权限需要发一条消息验证"
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = Corner.Control,
                    colors = quietTextFieldColors(),
                )
                MetaLine(
                    if (provider.baseUrl.isBlank()) "地址：填写有效项目 ID 后自动生成"
                    else "地址（固定主机，自动生成）：${provider.baseUrl}",
                )
            } else {
                OutlinedTextField(
                    value = baseUrlField.value,
                    onValueChange = { new -> baseUrlField.value = new },
                    singleLine = true,
                    isError = validation.isError,
                    label = { Text("Base URL") },
                    placeholder = { Text("https://example.com/v1") },
                    supportingText = { Text(validation.message()) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = Corner.Control,
                    colors = quietTextFieldColors(),
                )
            }
        }
    }

    if (provider.usesXaiOAuth) {
        SettingsSection(title = "xAI 登录") {
            xaiAuthContent()
        }
    } else if (provider.usesGoogleOAuth) {
        SettingsSection(title = "Google 登录") {
            googleAuthContent()
        }
    } else {
        SecureKeySection(
            title = "${provider.displayName} 的 API Key",
            description = "只填写该服务签发的 API Key，不要填写 OAuth Token 或 Cookie。",
            placeholder = "输入 API Key",
            hasSavedKey = hasSavedKey,
            onSave = onSaveKey,
            onClear = onClearKey,
        )
    }

    ProviderModelsSection(
        provider = provider,
        isRefreshingThis = isRefreshingThis,
        isRefreshingOther = isRefreshingOther,
        onUpdate = onUpdate,
        onRefresh = {
            flushCoordinator.flush()
            onRefreshModels()
        },
    )

    // xAI 订阅链路只给工具开关：用量总在 response.completed 里返回，适配器不发 stream_options，
    // 「请求流式用量统计」对它没有意义，显示出来反而让人以为关掉就拿不到用量。
    if (provider.usesGoogleOAuth) {
        com.muxuechat.presentation.settings.components.SettingsGroup("高级连接选项", "工具调用") {
            GoogleToolCallingSection(provider = provider, onUpdate = onUpdate)
        }
    } else if (!provider.usesXaiOAuth) {
        com.muxuechat.presentation.settings.components.SettingsGroup("高级连接选项", "工具、用量与接口兼容") {
            ProviderToolCallingSection(provider = provider, onUpdate = onUpdate)
            ProviderProtocolSection(provider = provider, onUpdate = onUpdate)
        }
    } else {
        com.muxuechat.presentation.settings.components.SettingsGroup("高级连接选项", "工具调用") {
            XaiToolCallingSection(provider = provider, onUpdate = onUpdate)
        }
    }

    SettingsSection(title = "移除供应商") {
        Row(modifier = Modifier.padding(vertical = Spacing.Small)) {
            OutlinedButton(
                onClick = { deleteDialogVisible = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            ) {
                Text("删除此供应商", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (deleteDialogVisible) {
        DeleteProviderDialog(
            provider = provider,
            onDismiss = { deleteDialogVisible = false },
            onConfirm = {
                deleteDialogVisible = false
                onDelete()
            },
        )
    }
}

/** 每个 OpenAI 兼容端点单独确认工具能力，避免把 tools 发给只支持普通聊天的反代。 */
@Composable
private fun ProviderToolCallingSection(
    provider: CustomProvider,
    onUpdate: (CustomProviderMutation) -> Unit,
) {
    SettingsSection(
        title = "端点能力",
        description = "这两项都是可选字段。不支持的旧反代收到未知字段可能直接 400，" +
            "所以默认关闭，由你按自己服务的实际能力开启。",
    ) {
        SettingsRow(
            title = "启用标准工具调用",
            subtitle = if (provider.toolCallingEnabled) {
                "会发送 tools 与 tool_choice；非 RP 聊天可创建角色卡、世界观、文风和主角草稿"
            } else {
                "默认关闭，兼容仅支持普通聊天的反代"
            },
            trailing = {
                Switch(
                    checked = provider.toolCallingEnabled,
                    onCheckedChange = { enabled -> onUpdate { it.copy(toolCallingEnabled = enabled) } },
                    colors = quietSwitchColors(),
                )
            },
        )
        SettingsDivider()
        SettingsRow(
            title = "请求流式用量统计",
            subtitle = if (provider.streamUsageEnabled) {
                "流式请求会附带 stream_options，用于获取真实 token 用量"
            } else {
                "默认关闭。开启后流式请求才可能返回真实用量，不支持的反代可能报错"
            },
            trailing = {
                Switch(
                    checked = provider.streamUsageEnabled,
                    onCheckedChange = { enabled -> onUpdate { it.copy(streamUsageEnabled = enabled) } },
                    colors = quietSwitchColors(),
                )
            },
        )
    }
}

/**
 * Google Vertex 的工具开关。实测：工具轮回放必须原样带回服务端返回的 `extra_content`
 * （AGENT.md §2「不透明字段回传」），App 已处理；默认仍关闭，由用户显式开启。
 */
@Composable
private fun GoogleToolCallingSection(
    provider: CustomProvider,
    onUpdate: (CustomProviderMutation) -> Unit,
) {
    SettingsSection(
        title = "端点能力",
        description = "开启后非 RP 聊天可让模型生成设定草稿，确认后才会保存；RP 请求从不发送工具。" +
            "若出现 400 之类的失败，关闭此项即可恢复普通聊天。",
    ) {
        SettingsRow(
            title = "启用工具调用",
            subtitle = if (provider.toolCallingEnabled) {
                "非 RP 聊天中，模型可以生成设定草稿，确认后才会保存"
            } else {
                "默认关闭"
            },
            trailing = {
                Switch(
                    checked = provider.toolCallingEnabled,
                    onCheckedChange = { enabled -> onUpdate { it.copy(toolCallingEnabled = enabled) } },
                    colors = quietSwitchColors(),
                )
            },
        )
    }
}

/**
 * xAI 订阅链路的工具开关。与自定义供应商共用 [CustomProvider.toolCallingEnabled]，默认关闭。
 *
 * 回放时不带 reasoning 与 `fc_` id 是否被上游接受仍待真机确认（线上格式 §5.5），
 * 所以如实写明，出问题时用户知道关掉它就能恢复普通聊天。
 */
@Composable
private fun XaiToolCallingSection(
    provider: CustomProvider,
    onUpdate: (CustomProviderMutation) -> Unit,
) {
    SettingsSection(
        title = "端点能力",
        description = "xAI 链路的工具调用经 Responses 协议转换，仍在验证中；" +
            "开启后若出现请求失败，关闭此项即可恢复普通聊天。",
    ) {
        SettingsRow(
            title = "启用工具调用",
            subtitle = if (provider.toolCallingEnabled) {
                "非 RP 聊天中，模型可以生成设定草稿，确认后才会保存"
            } else {
                "默认关闭"
            },
            trailing = {
                Switch(
                    checked = provider.toolCallingEnabled,
                    onCheckedChange = { enabled -> onUpdate { it.copy(toolCallingEnabled = enabled) } },
                    colors = quietSwitchColors(),
                )
            },
        )
    }
}

/**
 * 模型列表管理。
 *
 * 拉取是「尽力而为」：不少 OpenAI 兼容服务没有 /models，所以手填入口必须常驻。
 * 列表本身可能有上千项，因此只渲染「当前选中 + 已配置窗口 + 搜索命中的前若干个」，
 * 绝不一次性把整个目录展开成一堆输入框。
 */
@Composable
private fun ProviderModelsSection(
    provider: CustomProvider,
    isRefreshingThis: Boolean,
    isRefreshingOther: Boolean,
    onUpdate: (CustomProviderMutation) -> Unit,
    onRefresh: () -> Unit,
) {
    var manualModel by remember { mutableStateOf("") }
    var query by rememberSaveable(provider.id) { mutableStateOf("") }
    val pinned = (listOf(provider.selectedModel) + provider.modelContextWindows.keys)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_PINNED_MODELS)
    val window = ModelListWindow.of(
        all = provider.models,
        pinnedIds = pinned.filter { it in provider.models || it == provider.selectedModel },
        query = query,
    )

    SettingsSection(
        title = "可用模型",
        description = if (provider.usesXaiOAuth) {
            "模型列表来自 xAI 订阅服务，需先登录。窗口覆盖统一在「上下文管理」中配置。"
        } else if (provider.usesGoogleOAuth) {
            "Vertex 的 OpenAI 兼容端点不提供模型列表（实测 404），请手动添加模型 ID，例如 " +
                "${CustomProvider.GOOGLE_DEFAULT_MODEL}。窗口覆盖统一在「上下文管理」中配置。"
        } else {
            "服务不支持 /models 时可手动添加。窗口覆盖统一在「上下文管理」中配置。"
        },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
            modifier = Modifier.padding(vertical = Spacing.Small),
        ) {
            MetaLine(
                text = "共 ${provider.models.size} 个已知模型 · " +
                    describeLastRefreshed(provider.modelsUpdatedAtMillis),
            )
            if (provider.models.size > VISIBLE_WITHOUT_SEARCH) {
                ModelSearchField(
                    value = query,
                    onValueChange = { query = it },
                    label = "搜索此供应商的模型",
                )
            }

            if (window.isEmpty) {
                Text(
                    text = if (query.isBlank()) {
                        "还没有模型。点击下方按钮拉取，或手动添加一个模型名。"
                    } else {
                        "没有匹配「$query」的模型。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                (window.pinned + window.others).forEach { model ->
                    key(model) {
                        ProviderModelRow(
                            model = model,
                            isSelected = model == provider.selectedModel,
                            configuredWindow = provider.modelContextWindows[model],
                            onRemove = {
                                onUpdate {
                                    it.copy(
                                        models = it.models - model,
                                        // 这是唯一会删除窗口配置的路径：用户明确不要这个模型了。
                                        // `/models` 刷新不走这里。
                                        modelContextWindows = it.modelContextWindows - model,
                                    )
                                }
                            },
                            onCommitWindow = { windowTokens ->
                                onUpdate {
                                    it.copy(
                                        modelContextWindows = if (windowTokens == null) {
                                            it.modelContextWindows - model
                                        } else {
                                            it.modelContextWindows + (model to windowTokens)
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
                TruncatedListHint(hiddenCount = window.hiddenCount, isFiltered = window.isFiltered)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = manualModel,
                    onValueChange = { manualModel = it },
                    singleLine = true,
                    label = { Text("手动添加模型 ID") },
                    supportingText = { Text(if (manualModel.isBlank()) "请输入服务提供的完整模型 ID" else if (manualModel.trim() in provider.models) "此模型已在列表中" else "添加后可在模型选择器中使用") },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    shape = Corner.Control,
                    colors = quietTextFieldColors(),
                )
                TextButton(
                    onClick = {
                        val model = manualModel.trim()
                        if (model.isNotEmpty()) {
                            onUpdate { it.copy(models = it.models + model) }
                            manualModel = ""
                        }
                    },
                    enabled = manualModel.isNotBlank() && manualModel.trim() !in provider.models,
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                ) {
                    Text("添加")
                }
            }

            if (provider.usesGoogleOAuth) {
                // 禁用必须说明原因，而不是点了没反应。
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
                ) {
                    Text("从服务获取模型列表")
                }
                MetaLine("不可用：Google Vertex 的 openapi 端点没有 /models，请用上方的「手动添加模型 ID」。")
            } else {
                RefreshModelsButton(
                    isRefreshingThis = isRefreshingThis,
                    isRefreshingOther = isRefreshingOther,
                    idleLabel = "从服务获取模型列表",
                    onRefresh = onRefresh,
                )
            }
        }
    }
}

private const val MAX_PROJECT_ID_LENGTH = 30

@Composable
private fun ProviderModelRow(
    model: String,
    isSelected: Boolean,
    configuredWindow: Long?,
    onRemove: () -> Unit,
    onCommitWindow: (Long?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = model,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isSelected) {
                Text(
                    text = "使用中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = Spacing.Small),
                )
            }
            TextButton(
                onClick = onRemove,
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) {
                Text("移除")
            }
        }

    }
}

/** 刷新按钮。全局串行刷新时，别的入口必须说明「为什么现在点不动」。 */
@Composable
internal fun RefreshModelsButton(
    isRefreshingThis: Boolean,
    isRefreshingOther: Boolean,
    idleLabel: String,
    onRefresh: () -> Unit,
) {
    OutlinedButton(
        onClick = onRefresh,
        enabled = !isRefreshingThis && !isRefreshingOther,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
    ) {
        if (isRefreshingThis) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(16.dp)
                    .padding(end = 2.dp),
            )
        }
        Text(
            text = when {
                isRefreshingThis -> "正在获取…"
                isRefreshingOther -> "其他列表刷新中，请稍候"
                else -> idleLabel
            },
            modifier = Modifier.padding(start = if (isRefreshingThis) Spacing.Small else 0.dp),
        )
    }
}

internal const val MAX_PINNED_MODELS = 20
internal const val VISIBLE_WITHOUT_SEARCH = ModelListWindow.MAX_RENDERED_OTHERS

@Suppress("unused")
private fun unusedRefreshLabelReference() = describeRefreshAction(false, false)

/**
 * RP 辅助请求的模型覆盖。
 *
 * RP 每轮正文之外还会再发一次请求，用来补写内心独白、剧情推进建议和状态块。
 * 那次请求只要求稳定的 JSON，不需要正文那档推理能力，却默认和正文用同一个模型，
 * 于是每轮都按贵模型的价格付了一次结构化输出的钱。这里允许单独指定供应商和模型：
 * 正文用 Vertex 这类按量计费的大模型时，可以让辅助请求固定走 DeepSeek 的 Flash。
 *
 * 模型名只在它所属的供应商下有意义：保存时会记下归属，供应商对不上就当作没填。
 */
@Composable
fun RpAuxiliaryModelSection(
    settings: ChatSettings,
    onChange: (SettingsMutation) -> Unit,
) {
    val fieldValue = rememberSyncedTextFieldValue(external = settings.rpAuxiliaryModel)
    DebouncedTextSetting(
        fieldValue = fieldValue,
        persistedValue = settings.rpAuxiliaryModel,
        onCommit = { value -> onChange { it.withRpAuxiliaryModel(value) } },
    )

    val targetsDeepSeek = settings.rpAuxiliaryTargetKey == ChatProviderId.DEEPSEEK.storageValue
    val candidates = if (targetsDeepSeek) {
        settings.deepSeekModelDirectory.visibleModelIds
    } else {
        settings.activeCustomProvider?.models.orEmpty()
    }.filter {
        // 已经选中的那个不用再列一遍；正文模型在跟随模式下选它等于不覆盖。
        it.isNotBlank() &&
            // 只排除「正在生效」的那个；旧版留下的无归属值不生效，必须还能被一键重新选上。
            it != settings.rpAuxiliaryModelOverride &&
            (settings.rpAuxiliaryDivertsToDeepSeek || it != settings.activeModel)
    }

    SettingsSection(
        title = "RP 辅助请求",
        description = "只影响 RP 的剧情推进建议、元数据补写与状态兜底，正文始终用你选定的主模型。",
    ) {
        RpAuxiliaryProviderChoices(settings = settings, onChange = onChange)
        if (settings.rpAuxiliaryDivertsToDeepSeek) {
            MetaLine(
                text = "改道后，这些请求会把最近一轮对话、角色卡与状态定义发给 DeepSeek，" +
                    "使用你保存的 DeepSeek API Key，费用记在 DeepSeek 账户。",
            )
            val directory = settings.deepSeekModelDirectory
            if (directory.isFromServerCache && DeepSeekModels.FLASH !in directory.cachedModelIds) {
                MetaLine(
                    text = "提示：最近一次获取的 DeepSeek 模型列表里没有「${DeepSeekModels.FLASH}」，" +
                        "请求可能被拒绝；可在下方手动指定可用的模型。",
                )
            }
        }
        if (settings.rpAuxiliaryModelIsStale) {
            MetaLine(
                text = "已保存的辅助模型「${settings.rpAuxiliaryModel}」属于其他供应商，" +
                    "当前不会使用，已自动改用默认模型。可在下方重新选择。",
            )
        }
        Column(
            modifier = Modifier.padding(vertical = Spacing.Small),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            OutlinedTextField(
                value = fieldValue.value,
                onValueChange = { new -> fieldValue.value = new },
                singleLine = true,
                placeholder = { Text("留空 = ${rpAuxiliaryDefaultLabel(settings)}") },
                label = { Text("模型名") },
                supportingText = {
                    Text("当前实际使用：${settings.effectiveRpAuxiliaryModel.ifBlank { "未选择" }}")
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = Corner.Control,
                colors = quietTextFieldColors(),
            )
            // 手打模型名很容易拼错，而拼错要等到下一轮 RP 补写失败才会发现。
            // 这里把当前供应商已知的模型直接列出来，点一下就填上。
            if (candidates.isNotEmpty()) {
                MetaLine(text = if (targetsDeepSeek) "DeepSeek 的可用模型：" else "当前供应商的其他可用模型：")
                candidates.take(6).forEach { modelId ->
                    OutlinedButton(
                        onClick = {
                            fieldValue.value = TextFieldValue(
                                text = modelId,
                                selection = TextRange(modelId.length),
                            )
                            onChange { it.withRpAuxiliaryModel(modelId) }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
                    ) {
                        Text(modelId, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (settings.rpAuxiliaryModel.isNotBlank()) {
                OutlinedButton(
                    onClick = {
                        fieldValue.value = TextFieldValue(text = "", selection = TextRange(0))
                        onChange { it.withRpAuxiliaryModel("") }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
                ) {
                    Text("恢复默认（${rpAuxiliaryDefaultLabel(settings)}）")
                }
            }
        }
    }
}

/** 供应商二选一：状态靠文字与对勾表达，不只靠颜色。 */
@Composable
private fun RpAuxiliaryProviderChoices(
    settings: ChatSettings,
    onChange: (SettingsMutation) -> Unit,
) {
    val deepSeekActive = settings.provider == ChatProviderId.DEEPSEEK
    Column(
        modifier = Modifier.padding(vertical = Spacing.Small),
        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
    ) {
        listOf(
            RpAuxiliaryProvider.FOLLOW_ACTIVE to "跟随正文供应商（${settings.activeProviderLabel}）",
            RpAuxiliaryProvider.DEEPSEEK to "固定走 DeepSeek（更便宜，需已保存 DeepSeek API Key）",
        ).forEach { (choice, label) ->
            val selected = settings.rpAuxiliaryProvider == choice
            OutlinedButton(
                onClick = { onChange { it.copy(rpAuxiliaryProvider = choice) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .semantics { this.selected = selected },
            ) {
                if (selected) {
                    Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Text(
                    text = if (selected) "已选 · $label" else label,
                    modifier = Modifier.padding(start = if (selected) Spacing.Small else 0.dp),
                )
            }
        }
        if (deepSeekActive) {
            MetaLine(text = "当前正文本来就是 DeepSeek，这个选项暂时没有区别。")
        }
    }
}

/** 没有单独指定模型时辅助请求用什么，给文案和占位用。 */
private fun rpAuxiliaryDefaultLabel(settings: ChatSettings): String =
    if (settings.rpAuxiliaryDivertsToDeepSeek) DeepSeekModels.FLASH else "跟随正文模型"

/** 设置分组折叠时的摘要。 */
fun rpAuxiliarySummary(settings: ChatSettings): String {
    val where = if (settings.rpAuxiliaryDivertsToDeepSeek) "DeepSeek" else settings.activeProviderLabel
    return "$where · ${settings.effectiveRpAuxiliaryModel.ifBlank { "未选择" }}"
}
