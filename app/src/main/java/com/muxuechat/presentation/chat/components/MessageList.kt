package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.ChatMessage
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.MessageStatus
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

private val AutoFollowBottomThreshold = 24.dp
private const val MessageListBottomAnchorKey = "message-list-bottom-anchor"

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    retryingRpOptionsMessageId: String?,
    canRetryMissingRpOptions: Boolean,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onRetryRpOptions: () -> Unit,
    onSendOption: (String) -> Unit,
    onEditOption: (String) -> Unit,
    onEditUserMessage: (ChatMessage) -> Unit,
    onEditAssistantMessage: (ChatMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val bottomThresholdPx = with(LocalDensity.current) { AutoFollowBottomThreshold.roundToPx() }
    val visible = messages.filter { it.role != ChatRole.SYSTEM }
    val bottomAnchorIndex = visible.size
    val lastVisibleMessage = visible.lastOrNull()
    val lastRegenerableAssistantId = visible
        .lastOrNull { it.role == ChatRole.ASSISTANT && it.status == MessageStatus.COMPLETED && it.content.isNotBlank() }
        ?.id
    val lastConversationMessageId = visible
        .lastOrNull { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
        ?.id
    val retryableRpOptionsAssistantId = visible
        .lastOrNull { it.role == ChatRole.ASSISTANT && it.status == MessageStatus.COMPLETED && it.content.isNotBlank() }
        ?.takeIf { message ->
            canRetryMissingRpOptions &&
                message.id == lastConversationMessageId &&
                (message.rpMetadata?.options?.any { option -> option.isNotBlank() } != true)
        }
        ?.id
    var autoFollowOutput by remember { mutableStateOf(true) }
    var userScrollActive by remember { mutableStateOf(false) }

    LaunchedEffect(listState.interactionSource) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    userScrollActive = true
                    autoFollowOutput = false
                }
                is DragInteraction.Stop,
                is DragInteraction.Cancel -> Unit
            }
        }
    }

    LaunchedEffect(listState, bottomAnchorIndex, bottomThresholdPx) {
        snapshotFlow {
            AutoFollowScrollSnapshot(
                userScrollActive = userScrollActive,
                isScrollInProgress = listState.isScrollInProgress,
                isAtBottom = listState.isNearBottom(
                    bottomAnchorIndex = bottomAnchorIndex,
                    thresholdPx = bottomThresholdPx,
                ),
            )
        }
            .distinctUntilChanged()
            .collect { snapshot ->
                if (snapshot.userScrollActive && !snapshot.isScrollInProgress) {
                    if (snapshot.isAtBottom) {
                        autoFollowOutput = true
                    }
                    userScrollActive = false
                }
            }
    }

    LaunchedEffect(lastVisibleMessage?.id) {
        if (lastVisibleMessage != null && !userScrollActive) {
            autoFollowOutput = true
            listState.scrollToItem(bottomAnchorIndex)
        }
    }

    LaunchedEffect(
        autoFollowOutput,
        userScrollActive,
        bottomAnchorIndex,
        lastVisibleMessage?.id,
        lastVisibleMessage?.content?.length,
        lastVisibleMessage?.reasoningContent?.length,
        lastVisibleMessage?.status,
        lastVisibleMessage?.errorMessage?.length,
        lastVisibleMessage?.updatedAtMillis,
        lastVisibleMessage?.rpMetadata?.updatedAtMillis,
    ) {
        if (autoFollowOutput && !userScrollActive && visible.isNotEmpty()) {
            listState.scrollToItem(bottomAnchorIndex)
        }
    }

    if (visible.isEmpty()) {
        EmptyChatState(modifier = modifier)
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp,
            vertical = 20.dp,
        ),
    ) {
        items(visible, key = { it.id }) { message ->
            MessageItem(
                message = message,
                isGenerating = isGenerating,
                canRegenerate = message.id == lastRegenerableAssistantId,
                canRetryRpOptions = message.id == retryableRpOptionsAssistantId,
                isRetryingRpOptions = message.id == retryingRpOptionsMessageId,
                onCopy = onCopy,
                onRegenerate = onRegenerate,
                onRetryRpOptions = onRetryRpOptions,
                onSendOption = onSendOption,
                onEditOption = onEditOption,
                onEditUserMessage = onEditUserMessage,
                onEditAssistantMessage = onEditAssistantMessage,
            )
        }
        item(key = MessageListBottomAnchorKey) {
            Spacer(modifier = Modifier.height(1.dp))
        }
    }
}

private data class AutoFollowScrollSnapshot(
    val userScrollActive: Boolean,
    val isScrollInProgress: Boolean,
    val isAtBottom: Boolean,
)

private fun LazyListState.isNearBottom(
    bottomAnchorIndex: Int,
    thresholdPx: Int,
): Boolean {
    if (!canScrollForward) {
        return true
    }
    val anchor = layoutInfo.visibleItemsInfo.firstOrNull { item ->
        item.index == bottomAnchorIndex
    } ?: return false
    return anchor.offset + anchor.size <= layoutInfo.viewportEndOffset + thresholdPx
}

@Composable
private fun EmptyChatState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                text = "开始一次对话",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "在下方输入你的问题，按发送即可与模型交流。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
