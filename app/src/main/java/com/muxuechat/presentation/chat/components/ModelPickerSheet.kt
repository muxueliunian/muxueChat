package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.DeepSeekModel

/**
 * 聊天页的模型选择面板。
 *
 * 按供应商分组列出模型：选中一个模型就同时切换供应商与模型，
 * 用户不需要先去设置页切服务再回来选模型。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    settings: ChatSettings,
    refreshingModelsProviderId: String?,
    onSelectDeepSeekModel: (DeepSeekModel) -> Unit,
    onSelectCustomProviderModel: (providerId: String, model: String) -> Unit,
    onRefreshProviderModels: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "选择模型",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            ProviderGroupLabel(text = "DeepSeek")
            DeepSeekModel.entries.forEach { model ->
                ModelRow(
                    title = model.apiName,
                    selected = settings.usesDeepSeekParameters && settings.model == model,
                    onClick = {
                        onSelectDeepSeekModel(model)
                        onDismiss()
                    },
                )
            }

            settings.customProviders.forEach { provider ->
                ProviderGroupLabel(text = provider.displayName)
                CustomProviderModels(
                    provider = provider,
                    settings = settings,
                    isRefreshing = refreshingModelsProviderId == provider.id,
                    onSelect = { model ->
                        onSelectCustomProviderModel(provider.id, model)
                        onDismiss()
                    },
                    onRefresh = { onRefreshProviderModels(provider.id) },
                )
            }

            if (settings.customProviders.isEmpty()) {
                Text(
                    text = "还没有自定义供应商。可以在设置里添加一个 OpenAI 兼容服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            TextButton(
                onClick = {
                    onDismiss()
                    onOpenSettings()
                },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text("管理供应商")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CustomProviderModels(
    provider: CustomProvider,
    settings: ChatSettings,
    isRefreshing: Boolean,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    if (provider.models.isEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (isRefreshing) "正在获取模型…" else "没有已知模型",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh, enabled = !isRefreshing) {
                Text("获取")
            }
        }
        return
    }

    provider.models.forEach { model ->
        ModelRow(
            title = model,
            selected = settings.isCustomProvider &&
                settings.activeCustomProvider?.id == provider.id &&
                provider.selectedModel == model,
            onClick = { onSelect(model) },
        )
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onRefresh, enabled = !isRefreshing) {
            Text(if (isRefreshing) "正在刷新…" else "刷新列表")
        }
    }
}

@Composable
private fun ProviderGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun ModelRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainer,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                text = "使用中",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
