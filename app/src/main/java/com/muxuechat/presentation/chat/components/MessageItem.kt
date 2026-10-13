package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.foundation.layout.offset
import com.muxuechat.presentation.theme.MinIconTouchTarget
import com.muxuechat.presentation.theme.Spacing
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.ChatMessage
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.MessageStatus
import com.muxuechat.domain.rp.RpStateBlockFormat

@Composable
fun MessageItem(
    message: ChatMessage,
    isGenerating: Boolean,
    canRegenerate: Boolean,
    canRetryRpOptions: Boolean,
    isRetryingRpOptions: Boolean,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onRetryRpOptions: () -> Unit,
    onSendOption: (String) -> Unit,
    onEditOption: (String) -> Unit,
    onEditUserMessage: (ChatMessage) -> Unit,
    onEditAssistantMessage: (ChatMessage) -> Unit,
) {
    when (message.role) {
        ChatRole.USER -> UserMessage(
            message = message,
            editable = !isGenerating && message.status == MessageStatus.COMPLETED,
            onEditUserMessage = onEditUserMessage,
        )
        ChatRole.ASSISTANT -> AssistantMessage(
            message = message,
            isGenerating = isGenerating,
            canRegenerate = canRegenerate,
            canRetryRpOptions = canRetryRpOptions,
            isRetryingRpOptions = isRetryingRpOptions,
            onCopy = onCopy,
            onRegenerate = onRegenerate,
            onRetryRpOptions = onRetryRpOptions,
            onSendOption = onSendOption,
            onEditOption = onEditOption,
            onEditAssistantMessage = { onEditAssistantMessage(message) },
        )
        ChatRole.SYSTEM -> Unit
    }
}

@Composable
private fun UserMessage(
    message: ChatMessage,
    editable: Boolean,
    onEditUserMessage: (ChatMessage) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 左侧固定留白：手机上长消息也保持气泡形态，和整宽的回复正文区分开。
            .padding(start = Spacing.Section * 2),
        horizontalArrangement = Arrangement.End,
    ) {
        val bubbleShape = com.muxuechat.presentation.theme.Corner.Bubble
        Box(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .clip(bubbleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .then(
                    if (editable) {
                        Modifier.clickable(onClickLabel = "编辑并重新发送这条消息") { onEditUserMessage(message) }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = Spacing.Content, vertical = Spacing.Medium),
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun AssistantMessage(
    message: ChatMessage,
    isGenerating: Boolean,
    canRegenerate: Boolean,
    canRetryRpOptions: Boolean,
    isRetryingRpOptions: Boolean,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onRetryRpOptions: () -> Unit,
    onSendOption: (String) -> Unit,
    onEditOption: (String) -> Unit,
    onEditAssistantMessage: () -> Unit,
) {
    val streaming = message.status == MessageStatus.STREAMING || message.status == MessageStatus.PENDING
    // RP 状态块在回复完成时才从正文剥离；流式期间和被中途停止的回复里，显示层先把它藏掉。
    val content = remember(message.content, streaming) {
        RpStateBlockFormat.stripForDisplay(message.content, isStreaming = streaming)
    }
    val metadata = message.rpMetadata
    val innerThought = metadata?.innerThought?.takeIf { it.isNotBlank() }
    val options = metadata?.options?.filter { it.isNotBlank() }.orEmpty()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
    ) {
        if (!message.reasoningContent.isNullOrBlank()) {
            ReasoningSection(
                reasoning = message.reasoningContent,
                messageKey = message.id,
            )
        }
        if (content.isNotBlank()) {
            SelectionContainer {
                MarkdownText(
                    content = content,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else if (streaming) {
            TypingIndicator(modifier = Modifier.padding(vertical = 4.dp))
        }
        if (streaming && content.isNotBlank()) {
            TypingIndicator(modifier = Modifier.padding(top = 2.dp))
        }
        if (innerThought != null) {
            InnerThoughtBlock(text = innerThought)
        }
        if (options.isNotEmpty()) {
            OptionsBlock(
                options = options,
                enabled = !isGenerating,
                onSendOption = onSendOption,
                onEditOption = onEditOption,
            )
        }
        if (message.status == MessageStatus.FAILED && !message.errorMessage.isNullOrBlank()) {
            Text(
                text = "生成失败：${message.errorMessage}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (message.status == MessageStatus.COMPLETED && !message.errorMessage.isNullOrBlank()) {
            // COMPLETED 上的 errorMessage 是历史派生状态失效/状态栏未更新等中性提示，
            // 不能套用红色“生成失败”，但也不能静默隐藏。
            Text(
                text = "提示：${message.errorMessage}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (message.status == MessageStatus.CANCELLED) {
            Text(
                text = "已停止生成",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!streaming && canRegenerate && (message.status == MessageStatus.FAILED || message.status == MessageStatus.CANCELLED)) {
            TextButton(onClick = onRegenerate, enabled = !isGenerating) { Text("重试这条回复") }
        }
        if (!streaming && !isGenerating && content.isNotBlank()) {
            MessageActionsRow(
                onCopy = { onCopy(content) },
                onEdit = onEditAssistantMessage,
                onRegenerate = if (canRegenerate) onRegenerate else null,
                onRetryRpOptions = if (canRetryRpOptions) onRetryRpOptions else null,
                isRetryingRpOptions = isRetryingRpOptions,
            )
        }
    }
}

@Composable
private fun InnerThoughtBlock(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = "内心",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun OptionsBlock(
    options: List<String>,
    enabled: Boolean,
    onSendOption: (String) -> Unit,
    onEditOption: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "剧情推进建议",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        options.forEach { option ->
            OptionRow(
                text = option,
                enabled = enabled,
                onSend = { onSendOption(option) },
                onEdit = { onEditOption(option) },
            )
        }
    }
}

@Composable
private fun OptionRow(
    text: String,
    enabled: Boolean,
    onSend: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            ),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 44.dp)
                .clickable(enabled = enabled, onClick = onSend)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onEdit,
            enabled = enabled,
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = "编辑此建议",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun MessageActionsRow(
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: (() -> Unit)?,
    onRetryRpOptions: (() -> Unit)?,
    isRetryingRpOptions: Boolean,
) {
    // 图标按钮自带内边距，整行左移同样的量，让第一个图标与正文左缘对齐。
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.offset(x = -Spacing.Medium),
    ) {
        ActionIcon(Icons.Outlined.ContentCopy, "复制", onClick = onCopy)
        ActionIcon(Icons.Outlined.Edit, "编辑正文", onClick = onEdit)
        onRegenerate?.let { ActionIcon(Icons.Outlined.Refresh, "重新生成", onClick = it) }
        onRetryRpOptions?.let {
            ActionPill(
                icon = Icons.Outlined.Lightbulb,
                label = if (isRetryingRpOptions) "建议补写中" else "重试建议",
                enabled = !isRetryingRpOptions,
                onClick = it,
            )
        }
    }
}

@Composable
private fun ActionIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(MinIconTouchTarget)) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ActionPill(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        val contentColor = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        }
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
