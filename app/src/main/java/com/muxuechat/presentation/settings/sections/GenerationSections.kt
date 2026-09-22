package com.muxuechat.presentation.settings.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.DisplayScale
import com.muxuechat.domain.model.DisplaySettings
import com.muxuechat.domain.model.ReasoningEffort
import com.muxuechat.domain.model.ThinkingMode
import com.muxuechat.presentation.common.apiValueLabel
import com.muxuechat.presentation.common.components.quietTextFieldColors
import com.muxuechat.presentation.common.displayName
import com.muxuechat.presentation.settings.CustomProviderMutation
import com.muxuechat.presentation.settings.DebouncedTextSetting
import com.muxuechat.presentation.settings.SettingsMutation
import com.muxuechat.presentation.settings.quietSwitchColors
import com.muxuechat.presentation.settings.rememberSyncedTextFieldValue
import com.muxuechat.presentation.settings.components.ChoiceOption
import com.muxuechat.presentation.settings.components.ChoiceRow
import com.muxuechat.presentation.settings.components.SettingsDivider
import com.muxuechat.presentation.settings.components.SettingsRow
import com.muxuechat.presentation.settings.components.SettingsSection
import com.muxuechat.presentation.settings.components.SliderField
import com.muxuechat.presentation.theme.Corner
import com.muxuechat.presentation.theme.MinTouchTarget
import com.muxuechat.presentation.theme.Spacing

@Composable
fun SystemPromptSection(
    settings: ChatSettings,
    onChange: (SettingsMutation) -> Unit,
) {
    val fieldValue = rememberSyncedTextFieldValue(external = settings.systemPrompt)
    DebouncedTextSetting(
        fieldValue = fieldValue,
        persistedValue = settings.systemPrompt,
        onCommit = { value -> onChange { it.copy(systemPrompt = value) } },
    )
    SettingsSection(
        title = "系统提示词",
        description = "对所有普通会话生效。角色扮演模式下会与角色卡、世界观一起组装。",
    ) {
        Column(modifier = Modifier.padding(vertical = Spacing.Small)) {
            OutlinedTextField(
                value = fieldValue.value,
                onValueChange = { new -> fieldValue.value = new },
                placeholder = { Text("可选，用于设置助手的角色或风格") },
                minLines = 3,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
                shape = Corner.Control,
                colors = quietTextFieldColors(),
            )
        }
    }
}

@Composable
fun GenerationSection(
    settings: ChatSettings,
    onChange: (SettingsMutation) -> Unit,
) {
    SettingsSection(title = "输出") {
        SettingsRow(
            title = "流式输出",
            subtitle = if (settings.streamEnabled) "逐字返回模型响应" else "等待整段响应后一次性显示",
            trailing = {
                Switch(
                    checked = settings.streamEnabled,
                    onCheckedChange = { enabled -> onChange { it.copy(streamEnabled = enabled) } },
                    colors = quietSwitchColors(),
                )
            },
        )
        SettingsDivider()
        MaxTokensRow(settings = settings, onChange = onChange)
        SettingsDivider()
        SettingsRow(
            title = "设定生成工具",
            subtitle = if (settings.settingsToolsEnabled) {
                "关闭 RP 时，允许已启用工具能力的供应商创建角色卡、世界观、文风和主角草稿"
            } else {
                "已关闭：不会向任何供应商发送 tools 字段"
            },
            trailing = {
                Switch(
                    checked = settings.settingsToolsEnabled,
                    onCheckedChange = { enabled -> onChange { it.copy(settingsToolsEnabled = enabled) } },
                    colors = quietSwitchColors(),
                )
            },
        )
    }
}

@Composable
private fun MaxTokensRow(
    settings: ChatSettings,
    onChange: (SettingsMutation) -> Unit,
) {
    val fieldValue = rememberSyncedTextFieldValue(external = settings.maxTokens.toString())
    val text = fieldValue.value.text
    // 只有落在合法区间内才写回，避免把 0 或几亿这种值一路带到请求体。
    val parsed = ChatSettings.parseMaxTokensOrNull(text)
    DebouncedTextSetting(
        fieldValue = fieldValue,
        persistedValue = settings.maxTokens.toString(),
        canCommit = { value -> ChatSettings.parseMaxTokensOrNull(value) != null },
        onCommit = { value ->
            ChatSettings.parseMaxTokensOrNull(value)?.let { maxTokens ->
                onChange { it.copy(maxTokens = maxTokens) }
            }
        },
    )
    SettingsRow(
        title = "最大 Token",
        subtitle = if (parsed == null) {
            "请输入 ${ChatSettings.MIN_MAX_TOKENS}~${ChatSettings.MAX_MAX_TOKENS} 之间的数值"
        } else {
            "单次响应的上限，同时作为上下文预算里的输出预留"
        },
        trailing = {
            OutlinedTextField(
                value = fieldValue.value,
                onValueChange = { new ->
                    val filtered = new.text.filter { it.isDigit() }.take(6)
                    fieldValue.value = new.copy(text = filtered, selection = TextRange(filtered.length))
                },
                isError = parsed == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(110.dp).heightIn(min = MinTouchTarget),
                shape = Corner.Control,
                colors = quietTextFieldColors(),
            )
        },
    )
}

/**
 * 思考与采样。
 *
 * 两条容易误导用户的规则在这里必须说清楚：
 * 1. 思考关闭时，DeepSeek 请求根本不会带 `reasoning_effort`，所以思考强度不显示为「生效中」；
 * 2. 思考开启时，DeepSeek 会静默忽略 temperature / top_p，App 也不再发送这两个字段。
 *
 * 自定义供应商不走这套 DeepSeek 协议：它的思考等级是自由文本，采样是否发送由用户逐项决定。
 */
@Composable
fun ThinkingAndSamplingSection(
    settings: ChatSettings,
    onChange: (SettingsMutation) -> Unit,
    onUpdateProvider: (providerId: String, transform: CustomProviderMutation) -> Unit,
) {
    if (settings.usesDeepSeekParameters) {
        DeepSeekThinkingSection(settings = settings, onChange = onChange)
    } else {
        settings.activeCustomProvider?.let { provider ->
            ProviderReasoningSection(
                provider = provider,
                onUpdate = { transform -> onUpdateProvider(provider.id, transform) },
            )
        }
    }
    SamplingSection(
        settings = settings,
        onChange = onChange,
        onUpdateProvider = onUpdateProvider,
    )
}

@Composable
private fun DeepSeekThinkingSection(
    settings: ChatSettings,
    onChange: (SettingsMutation) -> Unit,
) {
    val thinkingEnabled = settings.thinkingMode == ThinkingMode.ENABLED
    SettingsSection(
        title = "思考模式",
        description = "开启后先思考再回复；关闭后思考强度不生效。",
    ) {
        SettingsRow(
            title = "开启思考",
            subtitle = if (thinkingEnabled) {
                "模型会先生成推理过程，回复中可展开查看"
            } else {
                "直接输出回复，不产生推理过程"
            },
            trailing = {
                Switch(
                    checked = thinkingEnabled,
                    onCheckedChange = { enabled ->
                        onChange { current ->
                            current.copy(
                                thinkingMode = if (enabled) ThinkingMode.ENABLED else ThinkingMode.DISABLED,
                            )
                        }
                    },
                    colors = quietSwitchColors(),
                )
            },
        )
        SettingsDivider()
        SettingsRow(
            title = "思考强度",
            subtitle = if (thinkingEnabled) {
                "对应请求体的 reasoning_effort 字段"
            } else {
                "思考已关闭，此设置当前不生效，请求中也不会出现 reasoning_effort"
            },
        )
        if (thinkingEnabled) {
            ChoiceRow(
                options = listOf(
                    ChoiceOption(
                        ReasoningEffort.LOW,
                        ReasoningEffort.LOW.displayName,
                        ReasoningEffort.LOW.apiValueLabel,
                    ),
                    ChoiceOption(
                        ReasoningEffort.HIGH,
                        ReasoningEffort.HIGH.displayName,
                        ReasoningEffort.HIGH.apiValueLabel,
                    ),
                    ChoiceOption(
                        ReasoningEffort.MAX,
                        ReasoningEffort.MAX.displayName,
                        ReasoningEffort.MAX.apiValueLabel,
                    ),
                ),
                selected = settings.reasoningEffort,
                onSelect = { effort -> onChange { it.copy(reasoningEffort = effort) } },
            )
        }
    }
}

/**
 * 自定义供应商的思考等级。
 *
 * 取值不做白名单校验——各家支持的档位并不一致（还有 minimal、none、xhigh 等），
 * 预设按钮只是快捷填充。留空表示完全不发送该字段，这是默认行为：
 * 不支持思考的模型收到未知字段可能直接 400。
 */
@Composable
private fun ProviderReasoningSection(
    provider: CustomProvider,
    onUpdate: (CustomProviderMutation) -> Unit,
) {
    val effortField = rememberSyncedTextFieldValue(external = provider.reasoningEffort)
    val updateEffort: (String) -> Unit = { value ->
        effortField.value = TextFieldValue(text = value, selection = TextRange(value.length))
        onUpdate { it.copy(reasoningEffort = value) }
    }
    DebouncedTextSetting(
        fieldValue = effortField,
        persistedValue = provider.reasoningEffort,
        onCommit = { value -> onUpdate { it.copy(reasoningEffort = value) } },
    )

    SettingsSection(
        title = "思考等级（自由文本）",
        description = "对应 OpenAI 兼容协议顶层的 reasoning_effort 字段，取值由你的服务决定，" +
            "App 不做枚举校验。留空则完全不发送该字段。仅用于正文对话，" +
            "RP 补写与上下文摘要不会发送。",
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
            modifier = Modifier.padding(vertical = Spacing.Small),
        ) {
            OutlinedTextField(
                value = effortField.value,
                onValueChange = { new ->
                    effortField.value = new.copy(
                        text = new.text.take(CustomProvider.MAX_REASONING_EFFORT_LENGTH),
                    )
                },
                singleLine = true,
                label = { Text("reasoning_effort") },
                placeholder = { Text("留空表示不发送") },
                supportingText = {
                    Text(
                        if (effortField.value.text.isBlank()) {
                            "当前不会发送该字段"
                        } else {
                            "当前会发送：${effortField.value.text}"
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = Corner.Control,
                colors = quietTextFieldColors(),
            )
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                CustomProvider.REASONING_EFFORT_PRESETS.forEach { preset ->
                    val selected = effortField.value.text == preset
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minHeight = MinTouchTarget)
                            .clip(Corner.Control)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer,
                            )
                            .clickable { updateEffort(preset) }
                            .padding(horizontal = Spacing.Medium, vertical = Spacing.Small),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (selected) "✓ $preset" else preset,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                TextButton(
                    onClick = { updateEffort("") },
                    enabled = effortField.value.text.isNotBlank(),
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                ) {
                    Text("不发送")
                }
            }
        }
    }
}

@Composable
private fun SamplingSection(
    settings: ChatSettings,
    onChange: (SettingsMutation) -> Unit,
    onUpdateProvider: (providerId: String, transform: CustomProviderMutation) -> Unit,
) {
    val effective = settings.samplingParametersEffective
    val provider = settings.activeCustomProvider.takeIf { settings.isCustomProvider }
    val temperatureEnabled = effective && (provider?.sendTemperature != false)
    val topPEnabled = effective && (provider?.sendTopP != false)
    val ineffectiveReason = "思考模式开启时 DeepSeek 会忽略这两个参数，App 也不会发送它们。"

    SettingsSection(
        title = "采样参数",
        description = when {
            !effective -> ineffectiveReason
            provider != null -> "不同供应商和模型接受的参数范围不同。关闭某项后，请求体将完全不包含该字段。"
            else -> null
        },
    ) {
        provider?.let { customProvider ->
            SettingsRow(
                title = "发送 Temperature",
                subtitle = if (customProvider.sendTemperature) {
                    "使用下方设置值"
                } else {
                    "不发送，由供应商使用默认值"
                },
                trailing = {
                    Switch(
                        checked = customProvider.sendTemperature,
                        onCheckedChange = { enabled ->
                            onUpdateProvider(customProvider.id) { it.copy(sendTemperature = enabled) }
                        },
                        colors = quietSwitchColors(),
                    )
                },
            )
        }
        SliderField(
            title = "Temperature",
            value = settings.temperature,
            onValueChange = { value -> onChange { it.copy(temperature = value) } },
            range = 0f..2f,
            enabled = temperatureEnabled,
            supportingText = when {
                !effective -> ineffectiveReason
                provider?.sendTemperature == false -> "当前设置为不发送该字段。"
                else -> null
            },
        )
        provider?.let { customProvider ->
            SettingsRow(
                title = "发送 Top P",
                subtitle = if (customProvider.sendTopP) {
                    "使用下方设置值"
                } else {
                    "不发送，由供应商使用默认值"
                },
                trailing = {
                    Switch(
                        checked = customProvider.sendTopP,
                        onCheckedChange = { enabled ->
                            onUpdateProvider(customProvider.id) { it.copy(sendTopP = enabled) }
                        },
                        colors = quietSwitchColors(),
                    )
                },
            )
        }
        SliderField(
            title = "Top P",
            value = settings.topP,
            onValueChange = { value -> onChange { it.copy(topP = value) } },
            range = 0f..1f,
            enabled = topPEnabled,
            supportingText = when {
                !effective -> ineffectiveReason
                provider?.sendTopP == false -> "当前设置为不发送该字段。"
                else -> null
            },
        )
        // Presence / Frequency Penalty 已被 DeepSeek 废弃且永不生效，故整体移除。
    }
}

@Composable
fun DisplaySection(
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
