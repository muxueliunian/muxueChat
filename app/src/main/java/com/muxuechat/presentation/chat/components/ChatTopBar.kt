package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muxuechat.presentation.common.CONTEXT_WARNING_USAGE_PERCENT
import com.muxuechat.presentation.theme.Corner
import com.muxuechat.presentation.theme.MinIconTouchTarget
import com.muxuechat.presentation.theme.Spacing

/**
 * 聊天页顶栏。
 *
 * 分成两行是刻意的：
 * - 第一行是**这段对话是什么**（会话标题）与全局操作，标题永远不会被模型名挤掉；
 * - 第二行是**这段对话正发给谁**（供应商 + 模型），整块是一个可点的选择器。
 *
 * 之前把模型名放在居中标题里，遇到长模型 ID 会把左右两侧的按钮一起压窄；
 * 现在长名字只在第二行内部省略，不影响任何导航或操作入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    conversationTitle: String,
    providerLabel: String,
    modelLabel: String,
    isRpActive: Boolean,
    onSelectModel: () -> Unit,
    onMenu: () -> Unit,
    onOpenRp: () -> Unit,
    onOpenContext: () -> Unit,
    onOpenSettings: () -> Unit,
    contextUsagePercent: Int? = null,
) {
    Column {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            title = {
                Text(
                    text = conversationTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onMenu) {
                    Icon(
                        imageVector = Icons.Outlined.Menu,
                        contentDescription = "会话列表",
                    )
                }
            },
            actions = {
                ContextUsageAction(
                    usagePercent = contextUsagePercent,
                    onClick = onOpenContext,
                )
                IconButton(onClick = onOpenRp) {
                    Icon(
                        imageVector = Icons.Outlined.TheaterComedy,
                        contentDescription = if (isRpActive) "角色扮演，已开启" else "角色扮演，未开启",
                        tint = if (isRpActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = "设置",
                    )
                }
            },
        )
        ModelSelectorChip(
            providerLabel = providerLabel,
            modelLabel = modelLabel,
            isRpActive = isRpActive,
            onClick = onSelectModel,
        )
    }
}

@Composable
private fun ModelSelectorChip(
    providerLabel: String,
    modelLabel: String,
    isRpActive: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.Medium, end = Spacing.Medium, bottom = Spacing.Small),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f, fill = false)
                .clip(Corner.Control)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Corner.Control)
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = "当前服务 $providerLabel，模型 $modelLabel。点按切换模型"
                }
                .defaultMinSize(minHeight = MinIconTouchTarget)
                .padding(start = Spacing.Medium, end = Spacing.Small, top = 6.dp, bottom = 6.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clearAndSetSemantics { },
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = providerLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = modelLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = Spacing.Tight)
                    .size(18.dp),
            )
        }
        if (isRpActive) {
            // RP 是一种会改变正文语义的模式，光靠顶栏图标变色不够，这里补一个文字标记。
            Text(
                text = "RP 进行中",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = Spacing.Small),
            )
        }
    }
}

/**
 * 上下文占用入口。
 *
 * 已知百分比时直接把数字写出来：只让图标变红等于把状态压进颜色这一个通道，
 * 深色模式和色觉差异下都读不出来。
 */
@Composable
private fun ContextUsageAction(
    usagePercent: Int?,
    onClick: () -> Unit,
) {
    val isWarning = usagePercent != null && usagePercent >= CONTEXT_WARNING_USAGE_PERCENT
    val contentColor = if (isWarning) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = Spacing.Tight)
            .clip(Corner.Control)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = usagePercent
                    ?.let { "上下文占用 $it%${if (isWarning) "，已接近上限" else ""}" }
                    ?: "上下文占用"
            }
            .defaultMinSize(minHeight = MinIconTouchTarget, minWidth = MinIconTouchTarget)
            .padding(horizontal = Spacing.Small),
    ) {
        Icon(
            imageVector = Icons.Outlined.DataUsage,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        if (usagePercent != null) {
            Text(
                text = "$usagePercent%",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                modifier = Modifier
                    .padding(start = Spacing.Tight)
                    .clearAndSetSemantics { },
            )
        }
    }
}
