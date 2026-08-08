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
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        val bubbleShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
        Box(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .clip(bubbleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .then(
                    if (editable) {
                        Modifier.clickable { onEditUserMessage(message) }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
    val metadata = message.rpMetadata
    val innerThought = metadata?.innerThought?.takeIf { it.isNotBlank() }
    val options = metadata?.options?.filter { it.isNotBlank() }.orEmpty()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!message.reasoningContent.isNullOrBlank()) {
            ReasoningSection(
                reasoning = message.reasoningContent,
                messageKey = message.id,
            )
        }
        if (message.content.isNotBlank()) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (streaming) {
            TypingIndicator(modifier = Modifier.padding(vertical = 4.dp))
        }
        if (streaming && message.content.isNotBlank()) {
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
        } else if (message.status == MessageStatus.CANCELLED) {
            Text(
                text = "已停止生成",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!streaming && !isGenerating && message.content.isNotBlank()) {
            MessageActionsRow(
                onCopy = { onCopy(message.content) },
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 2.dp),
    ) {
        ActionPill(
            icon = Icons.Outlined.ContentCopy,
            label = "复制",
            onClick = onCopy,
        )
        ActionPill(
            icon = Icons.Outlined.Edit,
            label = "编辑",
            onClick = onEdit,
        )
        if (onRetryRpOptions != null) {
            ActionPill(
                icon = Icons.Outlined.Refresh,
                label = if (isRetryingRpOptions) "重试中" else "重试建议",
                enabled = !isRetryingRpOptions,
                onClick = onRetryRpOptions,
            )
        }
        if (onRegenerate != null) {
            ActionPill(
                icon = Icons.Outlined.Refresh,
                label = "重新生成",
                onClick = onRegenerate,
            )
        }
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
