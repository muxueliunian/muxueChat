package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.TheaterComedy
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
 * 只占一条标准顶栏高度，标题区分两行：
 * - 第一行是**这段对话是什么**（会话标题），不会被模型名挤掉；
 * - 第二行是**这段对话正发给谁**（供应商 · 模型），整个标题区是一个可点的模型选择器。
 *
 * 之前第二行是标题栏下方独立的一整行卡片，加上 RP 状态面板后，顶部固定区域能吃掉小屏
 * 三分之一的高度。现在长模型名只在第二行内部省略；低频的「设置」入口移到了会话抽屉底部，
 * 把宽度留给标题区。
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
    contextUsagePercent: Int? = null,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        title = {
            TitleAndModel(
                conversationTitle = conversationTitle,
                providerLabel = providerLabel,
                modelLabel = modelLabel,
                isRpActive = isRpActive,
                onClick = onSelectModel,
            )
        },
        navigationIcon = {
            IconButton(onClick = onMenu) {
                Icon(
                    imageVector = Icons.Outlined.Menu,
                    contentDescription = "会话列表与设置",
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
        },
    )
}

@Composable
private fun TitleAndModel(
    conversationTitle: String,
    providerLabel: String,
    modelLabel: String,
    isRpActive: Boolean,
    onClick: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(Corner.Control)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "$conversationTitle。当前服务 $providerLabel，模型 $modelLabel" +
                    "${if (isRpActive) "，角色扮演进行中" else ""}。点按切换模型"
            }
            .defaultMinSize(minHeight = MinIconTouchTarget)
            .padding(horizontal = Spacing.Tight),
    ) {
        Text(
            text = conversationTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clearAndSetSemantics { },
        ) {
            if (isRpActive) {
                // RP 会改变正文语义，只靠顶栏图标变色不够，这里补一个文字标记。
                Text(
                    text = "RP",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(end = Spacing.Tight)
                        .border(1.dp, MaterialTheme.colorScheme.primary, Corner.Control)
                        .padding(horizontal = Spacing.Tight),
                )
            }
            Text(
                text = "$providerLabel · $modelLabel",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                // 模型 ID 的区分信息常在末尾（版本、日期），省略中间而不是末尾。
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
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
