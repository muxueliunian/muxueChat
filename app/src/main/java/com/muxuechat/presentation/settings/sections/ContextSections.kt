package com.muxuechat.presentation.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.context.ModelContextCatalog
import com.muxuechat.domain.context.ModelContextSpec
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.DeepSeekModels
import com.muxuechat.presentation.common.ModelListWindow
import com.muxuechat.presentation.common.components.MetaLine
import com.muxuechat.presentation.common.components.ModelSearchField
import com.muxuechat.presentation.common.components.TruncatedListHint
import com.muxuechat.presentation.common.components.quietTextFieldColors
import com.muxuechat.presentation.common.describeDirectorySource
import com.muxuechat.presentation.common.formatExactTokens
import com.muxuechat.presentation.settings.DebouncedTextSetting
import com.muxuechat.presentation.settings.SettingsMutation
import com.muxuechat.presentation.settings.quietSwitchColors
import com.muxuechat.presentation.settings.rememberSyncedTextFieldValue
import com.muxuechat.presentation.settings.components.SettingsDivider
import com.muxuechat.presentation.settings.components.SettingsRow
import com.muxuechat.presentation.settings.components.SettingsSection
import com.muxuechat.presentation.theme.Corner
import com.muxuechat.presentation.theme.MinTouchTarget
import com.muxuechat.presentation.theme.Spacing

/**
 * 逐模型的上下文长度输入。
 *
 * 留空表示「没有配置」，不是「0」——清空必须能真正删掉覆盖值，回到内置或回退值。
 * 三种状态（内置已知 / 用户覆盖 / 未知 32K 回退）必须给出三句不同的话，
 * 尤其不能把回退值说得像供应商的真实能力。
 */
@Composable
internal fun ModelContextWindowField(
    configured: Long?,
    builtInWindowTokens: Long?,
    onCommit: (Long?) -> Unit,
) {
    val persisted = configured?.toString().orEmpty()
    val fieldValue = rememberSyncedTextFieldValue(external = persisted)
    val text = fieldValue.value.text
    val parsed = ModelContextCatalog.parseContextWindowOrNull(text)
    val isBlank = text.isBlank()
    val isValid = isBlank || parsed != null

    DebouncedTextSetting(
        fieldValue = fieldValue,
        persistedValue = persisted,
        canCommit = { value -> value.isBlank() || ModelContextCatalog.parseContextWindowOrNull(value) != null },
        onCommit = { value -> onCommit(ModelContextCatalog.parseContextWindowOrNull(value)) },
    )

    val supportingText = when {
        !isValid -> "请输入 ${formatExactTokens(ModelContextCatalog.MIN_CONFIGURABLE_CONTEXT_TOKENS)}~" +
            "${formatExactTokens(ModelContextCatalog.MAX_CONFIGURABLE_CONTEXT_TOKENS)} 之间的数值"
        !isBlank -> "你的覆盖值：按 ${formatExactTokens(parsed ?: 0L)} tokens 计算占用"
        builtInWindowTokens != null ->
            "未填写：使用 App 内置的 ${formatExactTokens(builtInWindowTokens)} tokens"
        else -> "未填写：App 不知道此模型的窗口，暂按 " +
            "${formatExactTokens(ModelContextCatalog.FALLBACK_CONTEXT_TOKENS)} tokens 的保守回退值计算，" +
            "这不是该模型的真实能力"
    }

    OutlinedTextField(
        value = fieldValue.value,
        onValueChange = { new ->
            val filtered = new.text.filter { it.isDigit() }.take(8)
            fieldValue.value = new.copy(text = filtered, selection = TextRange(filtered.length))
        },
        isError = !isValid,
        singleLine = true,
        label = { Text("上下文窗口（tokens）") },
        placeholder = { Text("留空使用默认值") },
        supportingText = { Text(supportingText) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
        shape = Corner.Control,
        colors = quietTextFieldColors(),
    )
}

/**
 * 上下文容量与成本上限。
 *
 * DeepSeek 目录最多可能有两千项。以前这里对目录做 `forEach` 直接铺开输入框，
 * 一次组合两千个 TextField 会把设置页拖死；现在只渲染
 * 「当前模型 + 已配置覆盖 + 搜索命中的前若干个」。
 */
@Composable
fun ContextCapacitySection(
    settings: ChatSettings,
    isRefreshingThis: Boolean,
    isRefreshingOther: Boolean,
    onRefreshDeepSeekModels: () -> Unit,
    onChange: (SettingsMutation) -> Unit,
) {
    SettingsSection(
        title = "预算计算方式",
        description = "上下文占用按模型真实 token 窗口计算：先取窗口的 " +
            "${ModelContextSpec.SAFETY_WINDOW_PERCENT}% 作为安全窗口，" +
            "再扣掉「最大 Token」的输出预留，剩下的才是可用输入预算。",
    ) {
        SettingsRow(
            title = "成本上限",
            // 百万级窗口下，真正的约束不是装不装得下，而是每轮重发全量历史有多贵。
            subtitle = "为单轮输入额外设一个 token 上限。关闭时只受模型窗口约束，" +
                "长会话的费用与首 token 延迟会随历史增长。",
            trailing = {
                Switch(
                    checked = settings.contextCostCapEnabled,
                    onCheckedChange = { enabled -> onChange { it.copy(contextCostCapEnabled = enabled) } },
                    colors = quietSwitchColors(),
                )
            },
        )
        if (settings.contextCostCapEnabled) {
            SettingsDivider()
            CostCapRow(settings = settings, onChange = onChange)
        }
    }

    if (settings.usesDeepSeekParameters) {
        DeepSeekContextWindowsSection(
            settings = settings,
            isRefreshingThis = isRefreshingThis,
            isRefreshingOther = isRefreshingOther,
            onRefreshDeepSeekModels = onRefreshDeepSeekModels,
            onChange = onChange,
        )
    } else {
        settings.activeCustomProvider?.let { provider ->
            key(provider.id) {
                CustomContextWindows(provider) { model, tokens ->
                    onChange { current ->
                        current.copy(customProviders = current.customProviders.map {
                            if (it.id != provider.id) it else it.copy(modelContextWindows = if (tokens == null) it.modelContextWindows - model else it.modelContextWindows + (model to tokens))
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun DeepSeekContextWindowsSection(
    settings: ChatSettings,
    isRefreshingThis: Boolean,
    isRefreshingOther: Boolean,
    onRefreshDeepSeekModels: () -> Unit,
    onChange: (SettingsMutation) -> Unit,
) {
    val directory = settings.deepSeekModelDirectory
    var onlyOverrides by rememberSaveable { mutableStateOf(false) }
    var showOther by rememberSaveable { mutableStateOf(false) }
    val editorIds = DeepSeekModels.contextWindowEditorIds(settings).filter { !onlyOverrides || it in settings.deepSeekModelContextWindows || it == directory.selectedModelId }
    var query by rememberSaveable { mutableStateOf("") }
    val window = ModelListWindow.of(
        all = if (showOther) editorIds else listOf(directory.selectedModelId),
        pinnedIds = listOf(directory.selectedModelId), query = query, maxRenderedOthers = 8,
    )

    SettingsSection(
        title = "DeepSeek 逐模型窗口",
        description = "只有需要覆盖内置值时才填。上游调整窗口时不必等一次发版。",
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
            modifier = Modifier.padding(vertical = Spacing.Small),
        ) {
            MetaLine(text = describeDirectorySource(directory))
            MetaLine(text = "目录共 ${editorIds.size} 个模型，下面按需展示。")
            TextButton(onClick = { showOther = !showOther; query = "" }) { Text(if (showOther) "只显示当前模型" else "查找其他模型与已有覆盖") }
            if (showOther) {
                FilterChip(selected = onlyOverrides, onClick = { onlyOverrides = !onlyOverrides }, label = { Text("只看已有覆盖（${settings.deepSeekModelContextWindows.size}）") })
                ModelSearchField(
                    value = query,
                    onValueChange = { query = it },
                    label = "搜索模型 ID",
                )
            }

            if (window.isEmpty) {
                Text(
                    text = "没有匹配「$query」的模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                (window.pinned + window.others).forEach { modelId ->
                    key(modelId) {
                        DeepSeekModelWindowRow(
                            modelId = modelId,
                            isCurrent = modelId == directory.selectedModelId,
                            isMissingFromCatalog = directory.selectedMissingFromCatalog &&
                                modelId == directory.selectedModelId,
                            configured = settings.deepSeekModelContextWindows[modelId],
                            onCommit = { windowTokens ->
                                onChange { current ->
                                    current.copy(
                                        deepSeekModelContextWindows = if (windowTokens == null) {
                                            current.deepSeekModelContextWindows - modelId
                                        } else {
                                            current.deepSeekModelContextWindows + (modelId to windowTokens)
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
                TruncatedListHint(hiddenCount = window.hiddenCount, isFiltered = window.isFiltered)
            }

            RefreshModelsButton(
                isRefreshingThis = isRefreshingThis,
                isRefreshingOther = isRefreshingOther,
                idleLabel = "刷新 DeepSeek 模型列表",
                onRefresh = onRefreshDeepSeekModels,
            )
        }
    }
}

@Composable
private fun DeepSeekModelWindowRow(
    modelId: String,
    isCurrent: Boolean,
    isMissingFromCatalog: Boolean,
    configured: Long?,
    onCommit: (Long?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = modelId,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isCurrent) {
                Text(
                    text = "当前模型",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (isMissingFromCatalog) {
            Text(
                text = "注意：服务器目录未返回该模型，仍保留当前选择。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        com.muxuechat.presentation.common.ModelNotes.deepSeekCapabilityNotes(modelId).forEach { note ->
            Text(note.text, style = MaterialTheme.typography.bodySmall)
        }
        ModelContextWindowField(
            configured = configured,
            builtInWindowTokens = ModelContextCatalog.builtInContextWindow(
                ChatProviderId.DEEPSEEK,
                modelId,
            ),
            onCommit = onCommit,
        )
    }
}

@Composable
private fun CostCapRow(
    settings: ChatSettings,
    onChange: (SettingsMutation) -> Unit,
) {
    val persisted = settings.contextCostCapTokens.toString()
    val fieldValue = rememberSyncedTextFieldValue(external = persisted)
    val text = fieldValue.value.text
    val parsed = ModelContextCatalog.parseCostCapOrNull(text)

    DebouncedTextSetting(
        fieldValue = fieldValue,
        persistedValue = persisted,
        canCommit = { value -> ModelContextCatalog.parseCostCapOrNull(value) != null },
        onCommit = { value ->
            ModelContextCatalog.parseCostCapOrNull(value)?.let { tokens ->
                onChange { it.copy(contextCostCapTokens = tokens) }
            }
        },
    )

    SettingsRow(
        title = "上限值",
        subtitle = if (parsed == null) {
            "请输入 ${formatExactTokens(ModelContextCatalog.MIN_COST_CAP_TOKENS)}~" +
                "${formatExactTokens(ModelContextCatalog.MAX_COST_CAP_TOKENS)} 之间的数值"
        } else {
            "实际预算取「模型窗口」与本值中的较小者"
        },
        trailing = {
            OutlinedTextField(
                value = fieldValue.value,
                onValueChange = { new ->
                    val filtered = new.text.filter { it.isDigit() }.take(8)
                    fieldValue.value = new.copy(text = filtered, selection = TextRange(filtered.length))
                },
                isError = parsed == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(120.dp).heightIn(min = MinTouchTarget),
                shape = Corner.Control,
                colors = quietTextFieldColors(),
            )
        },
    )
}

@Composable
private fun CustomContextWindows(
    provider: com.muxuechat.domain.model.CustomProvider,
    onCommit: (String, Long?) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var onlyOverrides by rememberSaveable { mutableStateOf(false) }
    val all = (listOf(provider.selectedModel) + provider.modelContextWindows.keys + provider.models)
        .filter { it.isNotBlank() }.distinct()
        .filter { !onlyOverrides || it in provider.modelContextWindows || it == provider.selectedModel }
    val window = ModelListWindow.of(
        all = if (expanded) all else listOf(provider.selectedModel).filter { it.isNotBlank() },
        pinnedIds = listOf(provider.selectedModel).filter { it.isNotBlank() },
        query = query, maxRenderedOthers = 8,
    )
    SettingsSection("逐模型窗口", "标准模型目录不提供可靠的窗口信息；未知时按 32K 保守回退，不代表模型实际能力。") {
        Column(Modifier.padding(Spacing.Small), verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
            TextButton(onClick = { expanded = !expanded; query = "" }) { Text(if (expanded) "只显示当前模型" else "查找其他模型与已有覆盖") }
            if (expanded) {
                FilterChip(selected = onlyOverrides, onClick = { onlyOverrides = !onlyOverrides }, label = { Text("只看已有覆盖（${provider.modelContextWindows.size}）") })
                ModelSearchField(query, { query = it }, "搜索模型 ID")
            }
            (window.pinned + window.others).forEach { model ->
                key(model) {
                    Text(if (model == provider.selectedModel) "当前模型：$model" else model)
                    ModelContextWindowField(provider.modelContextWindows[model], null) { onCommit(model, it) }
                }
            }
            if (window.isEmpty) Text("没有匹配的模型")
            TruncatedListHint(window.hiddenCount, window.isFiltered)
        }
    }
}
