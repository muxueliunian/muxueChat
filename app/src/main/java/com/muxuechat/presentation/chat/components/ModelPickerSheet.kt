package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.presentation.common.ModelListWindow
import com.muxuechat.presentation.common.ModelNote
import com.muxuechat.presentation.common.ModelNotes
import com.muxuechat.presentation.common.components.MetaLine
import com.muxuechat.presentation.common.components.ModelNoteList
import com.muxuechat.presentation.common.components.ModelSearchField
import com.muxuechat.presentation.common.components.TruncatedListHint
import com.muxuechat.presentation.common.describeDirectorySource
import com.muxuechat.presentation.common.describeLastRefreshed
import com.muxuechat.presentation.common.describeRefreshAction
import com.muxuechat.presentation.theme.Corner
import com.muxuechat.presentation.theme.MinTouchTarget
import com.muxuechat.presentation.theme.Spacing

/**
 * 聊天页的模型选择面板。
 *
 * 目录最多可能有两千项，所以整个面板是一个 [LazyColumn] + 常驻搜索框：
 * 当前选中项与「服务器未返回但仍在使用」的项会被置顶，其余按需渲染。
 * 选中一个模型就同时切换供应商与模型，用户不需要先去设置页切服务再回来选模型。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    settings: ChatSettings,
    refreshingModelsProviderId: String?,
    onSelectDeepSeekModel: (String) -> Unit,
    onSelectCustomProviderModel: (providerId: String, model: String) -> Unit,
    onRefreshProviderModels: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    refreshErrors: Map<String, String> = emptyMap(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val directory = settings.deepSeekModelDirectory
    // 刷新在 ViewModel 层全局串行：任一供应商刷新期间，其他入口点了会被静默忽略。
    // 这里把这个事实显式反映到每个按钮上，而不是让用户点了没反应。
    val deepSeekProviderId = ChatProviderId.DEEPSEEK.storageValue

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.9f)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Content),
                verticalArrangement = Arrangement.spacedBy(Spacing.Small),
            ) {
                Text(
                    text = "选择模型",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "选中即同时切换供应商与模型。设置在下一次发送时生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ModelSearchField(
                    value = query,
                    onValueChange = { query = it },
                    label = "搜索模型 ID",
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = Spacing.Content),
                verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = Spacing.Medium,
                    bottom = Spacing.Section,
                ),
            ) {
                val deepSeekWindow = ModelListWindow.of(
                    all = directory.visibleModelIds,
                    pinnedIds = listOfNotNull(
                        directory.selectedModelId.takeIf { settings.usesDeepSeekParameters },
                    ),
                    query = query,
                        maxRenderedOthers = Int.MAX_VALUE,
                )
                providerGroup(
                    key = "deepseek",
                    title = "DeepSeek",
                    meta = describeDirectorySource(directory),
                    refreshError = refreshErrors[deepSeekProviderId],
                    window = deepSeekWindow,
                    emptyText = if (query.isBlank()) {
                        "目录为空。请刷新，或到设置页确认 Base URL。"
                    } else {
                        "没有匹配「$query」的 DeepSeek 模型。"
                    },
                    isRefreshingThis = refreshingModelsProviderId == deepSeekProviderId,
                    isRefreshingOther = refreshingModelsProviderId != null &&
                        refreshingModelsProviderId != deepSeekProviderId,
                    onRefresh = { onRefreshProviderModels(deepSeekProviderId) },
                    notesFor = { modelId ->
                        ModelNotes.deepSeekNotes(
                            modelId = modelId,
                            directory = directory,
                            configuredWindowTokens = settings.deepSeekModelContextWindows[modelId],
                        )
                    },
                    isSelected = { modelId ->
                        settings.usesDeepSeekParameters && settings.model == modelId
                    },
                    onSelect = { modelId ->
                        onSelectDeepSeekModel(modelId)
                        onDismiss()
                    },
                )

                settings.customProviders.forEach { provider ->
                    val isActiveProvider = settings.isCustomProvider &&
                        settings.activeCustomProvider?.id == provider.id
                    val providerWindow = ModelListWindow.of(
                        all = provider.visibleModelIds(),
                        pinnedIds = listOfNotNull(
                            provider.selectedModel.takeIf { it.isNotBlank() && isActiveProvider },
                        ),
                        query = query,
                        maxRenderedOthers = Int.MAX_VALUE,
                    )
                    providerGroup(
                        key = "provider-${provider.id}",
                        title = provider.displayName,
                        refreshError = refreshErrors[provider.id],
                        meta = buildString {
                            append("共 ${provider.models.size} 个已知模型 · ")
                            append(describeLastRefreshed(provider.modelsUpdatedAtMillis))
                        },
                        window = providerWindow,
                        emptyText = if (query.isBlank()) {
                            "还没有模型。可以刷新，或在设置里手动添加模型名。"
                        } else {
                            "没有匹配「$query」的模型。"
                        },
                        isRefreshingThis = refreshingModelsProviderId == provider.id,
                        isRefreshingOther = refreshingModelsProviderId != null &&
                            refreshingModelsProviderId != provider.id,
                        onRefresh = { onRefreshProviderModels(provider.id) },
                        notesFor = { model ->
                            val notes = mutableListOf<ModelNote>()
                            if (isActiveProvider &&
                                model == provider.selectedModel &&
                                model !in provider.models
                            ) {
                                notes += ModelNote(
                                    "该模型不在最近一次拉取的列表里，仍保留当前选择",
                                    com.muxuechat.presentation.common.ModelNoteTone.CAUTION,
                                )
                            }
                            notes += ModelNotes.customProviderNotes(
                                configuredWindowTokens = provider.modelContextWindows[model],
                            )
                            notes
                        },
                        isSelected = { model ->
                            isActiveProvider && provider.selectedModel == model
                        },
                        onSelect = { model ->
                            onSelectCustomProviderModel(provider.id, model)
                            onDismiss()
                        },
                    )
                }

                if (settings.customProviders.isEmpty()) {
                    item(key = "no-custom-provider") {
                        Text(
                            text = "还没有自定义供应商。可以在设置里添加一个 OpenAI 兼容服务。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Spacing.Small),
                        )
                    }
                }
            }

            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Small, vertical = Spacing.Tight),
            ) {
                TextButton(
                    onClick = {
                        onDismiss()
                        onOpenSettings()
                    },
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                ) {
                    Text("管理供应商与模型")
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            Spacer(modifier = Modifier.height(Spacing.Small))
        }
    }
}

/** 选中的模型未必在最近一次拉取的列表里；它必须仍然出现，否则用户会以为自己的选择丢了。 */
private fun CustomProvider.visibleModelIds(): List<String> {
    val selected = selectedModel.trim()
    return if (selected.isNotEmpty() && selected !in models) models + selected else models
}

/**
 * 一个供应商分组。
 *
 * 每组的结构完全一致：组名 → 目录来源与刷新时间 → 刷新入口 → 模型行 → 截断提示。
 * 分组之间不允许出现「DeepSeek 有刷新时间、自定义供应商没有」这种差异。
 */
private fun LazyListScope.providerGroup(
    key: String,
    title: String,
    meta: String,
    refreshError: String?,
    window: ModelListWindow,
    emptyText: String,
    isRefreshingThis: Boolean,
    isRefreshingOther: Boolean,
    onRefresh: () -> Unit,
    notesFor: (String) -> List<ModelNote>,
    isSelected: (String) -> Boolean,
    onSelect: (String) -> Unit,
) {
    item(key = "$key-header") {
        ProviderGroupHeader(
            title = title,
            meta = meta,
            isRefreshingThis = isRefreshingThis,
            isRefreshingOther = isRefreshingOther,
            onRefresh = onRefresh,
        )
    }
    if (refreshError != null) {
        item(key = "$key-error") { Text(refreshError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (window.isEmpty) {
        item(key = "$key-empty") {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.Small),
            )
        }
        return
    }
    items(
        items = window.pinned + window.others,
        key = { modelId -> "$key-$modelId" },
    ) { modelId ->
        ModelRow(
            title = modelId,
            notes = notesFor(modelId),
            selected = isSelected(modelId),
            onClick = { onSelect(modelId) },
        )
    }
    item(key = "$key-truncated") {
        TruncatedListHint(hiddenCount = window.hiddenCount, isFiltered = window.isFiltered)
    }
}

@Composable
private fun ProviderGroupHeader(
    title: String,
    meta: String,
    isRefreshingThis: Boolean,
    isRefreshingOther: Boolean,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.Medium, bottom = Spacing.Tight),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isRefreshingThis) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .size(16.dp)
                        .semantics { contentDescription = "$title 正在刷新模型列表" },
                )
                Spacer(modifier = Modifier.size(Spacing.Small))
            }
            TextButton(
                onClick = onRefresh,
                enabled = !isRefreshingThis && !isRefreshingOther,
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) {
                Text(describeRefreshAction(isRefreshingThis, isRefreshingOther))
            }
        }
        MetaLine(text = meta)
    }
}

@Composable
private fun ModelRow(
    title: String,
    notes: List<ModelNote>,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val notesDescription = notes.joinToString("；") { it.text }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(Corner.Control)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
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
                    "$title，使用中。$notesDescription"
                } else {
                    "$title。$notesDescription"
                }
            }
            .padding(horizontal = Spacing.Medium, vertical = Spacing.Medium),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics { },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ModelNoteList(notes = notes)
        }
        if (selected) {
            // 选中同时用「图标 + 文字 + 边框」表达，不依赖底色这一种信号。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = Spacing.Small),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "使用中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(start = Spacing.Tight),
                )
            }
        }
    }
}
