package com.muxuechat.presentation.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.ChatMessage
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.MessageStatus
import com.muxuechat.presentation.theme.MinTouchTarget
import com.muxuechat.presentation.theme.Spacing
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val AutoFollowBottomThreshold = 24.dp
private const val MessageListBottomAnchorKey = "message-list-bottom-anchor"

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    retryingRpOptionsMessageId: String?,
    enrichingMessageIds: Set<String>,
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
        .lastOrNull { it.role == ChatRole.ASSISTANT }
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
    // 用户离开底部之后是否又有新内容。没有它，「回到底部」和「有新消息」会被混成同一句话。
    var hasNewContentWhileAway by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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

    // 新消息不再无条件把视图拉回底部。用户自己发的消息是明确的「我要看结果」信号，
    // 模型的新回复则只在用户本来就在底部时才跟随，否则只给一个可点的提示。
    LaunchedEffect(lastVisibleMessage?.id) {
        val message = lastVisibleMessage ?: return@LaunchedEffect
        if (message.role == ChatRole.USER && !userScrollActive) {
            autoFollowOutput = true
            hasNewContentWhileAway = false
            listState.scrollToItem(bottomAnchorIndex)
        } else if (!autoFollowOutput) {
            hasNewContentWhileAway = true
        }
    }

    LaunchedEffect(autoFollowOutput) {
        if (autoFollowOutput) hasNewContentWhileAway = false
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
        } else if (!autoFollowOutput && !userScrollActive) {
            hasNewContentWhileAway = true
        }
    }

    if (visible.isEmpty()) {
        EmptyChatState(modifier = modifier)
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = Spacing.Content,
                vertical = 20.dp,
            ),
        ) {
            items(visible, key = { it.id }) { message ->
                val isEnrichingRpOptions = message.id in enrichingMessageIds
                MessageItem(
                    message = message,
                    isGenerating = isGenerating,
                    canRegenerate = message.id == lastRegenerableAssistantId,
                    canRetryRpOptions = message.id == retryableRpOptionsAssistantId || isEnrichingRpOptions,
                    isRetryingRpOptions = message.id == retryingRpOptionsMessageId || isEnrichingRpOptions,
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

        AnimatedVisibility(
            visible = !autoFollowOutput,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = Spacing.Content),
        ) {
            BackToBottomButton(
                hasNewContent = hasNewContentWhileAway,
                onClick = {
                    scope.launch {
                        autoFollowOutput = true
                        hasNewContentWhileAway = false
                        listState.scrollToItem(bottomAnchorIndex)
                    }
                },
            )
        }
    }
}

/**
 * 回到底部 / 有新消息。
 *
 * 两种状态用不同文案而不是只换颜色：用户需要知道「下面只是我没看的旧内容」
 * 还是「刚刚又来了新内容」。
 */
@Composable
private fun BackToBottomButton(
    hasNewContent: Boolean,
    onClick: () -> Unit,
) {
    val label = if (hasNewContent) "有新内容，回到底部" else "回到底部"
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .defaultMinSize(minHeight = MinTouchTarget)
            .semantics { contentDescription = label },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Spacing.Content, vertical = Spacing.Small),
        ) {
            Icon(
                imageVector = Icons.Outlined.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = Spacing.Small),
            )
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
                text = "在下方输入框写下你的问题或正文，按发送即可。\n" +
                    "顶栏可以切换模型，也可以开启角色扮演模式。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.Small),
            )
        }
    }
}
