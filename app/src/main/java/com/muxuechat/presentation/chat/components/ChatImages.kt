package com.muxuechat.presentation.chat.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.ImageInputSpec
import com.muxuechat.domain.model.MessageAttachment
import com.muxuechat.domain.model.PendingImage
import com.muxuechat.presentation.theme.Corner
import com.muxuechat.presentation.theme.MinIconTouchTarget
import com.muxuechat.presentation.theme.Spacing

/** 界面要显示的一张图片：已发送的附件，或还在待发送区的图片。只带 id，不带路径。 */
sealed interface ChatImageRef {
    data class Attachment(val id: String) : ChatImageRef
    data class Pending(val id: String) : ChatImageRef
}

/** 缩略图来源。由 ViewModel 提供；UI 不直接读文件（AGENT.md §3.5）。 */
fun interface ChatImageThumbnailSource {
    suspend fun load(ref: ChatImageRef, maxEdgePx: Int): Bitmap?
}

/** 聊天页内共用的缩略图来源，省得把加载函数一层层传给每个气泡。默认不加载（预览与测试）。 */
val LocalChatImageThumbnails = staticCompositionLocalOf { ChatImageThumbnailSource { _, _ -> null } }

/** 气泡里单张缩略图的边长；多张时按两列排。 */
private val MessageThumbnailSize = 96.dp
private val SingleMessageThumbnailSize = 160.dp
private val PendingThumbnailSize = 64.dp

/**
 * 一张降采样缩略图。解码在后台进行，完成前显示浅色占位；文件丢失或解码失败时显示「图片不可用」图标。
 */
@Composable
fun ChatImageThumbnail(
    ref: ChatImageRef,
    size: Dp,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val source = LocalChatImageThumbnails.current
    val maxEdgePx = with(LocalDensity.current) { size.roundToPx() }
    val state by produceState<ThumbnailState>(ThumbnailState.Loading, ref, maxEdgePx) {
        value = source.load(ref, maxEdgePx)?.let { ThumbnailState.Ready(it) } ?: ThumbnailState.Unavailable
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(Corner.Control)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { this.contentDescription = contentDescription },
    ) {
        when (val current = state) {
            is ThumbnailState.Ready -> Image(
                bitmap = current.bitmap.asImageBitmap(),
                // 语义描述已挂在外层 Box 上，这里不再重复朗读。
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
            ThumbnailState.Unavailable -> Icon(
                imageVector = Icons.Outlined.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ThumbnailState.Loading -> Unit
        }
    }
}

private sealed interface ThumbnailState {
    data object Loading : ThumbnailState
    data object Unavailable : ThumbnailState
    data class Ready(val bitmap: Bitmap) : ThumbnailState
}

/** 用户消息气泡里的图片：一张时稍大，多张时两列网格（最多 4 张）。 */
@Composable
fun MessageImageGrid(
    attachments: List<MessageAttachment>,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    val sorted = attachments.sortedBy { it.orderIndex }
    if (sorted.size == 1) {
        ChatImageThumbnail(
            ref = ChatImageRef.Attachment(sorted.single().id),
            size = SingleMessageThumbnailSize,
            contentDescription = "消息图片，共 1 张",
            modifier = modifier,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight), modifier = modifier) {
        sorted.chunked(2).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                row.forEachIndexed { columnIndex, attachment ->
                    val position = rowIndex * 2 + columnIndex + 1
                    ChatImageThumbnail(
                        ref = ChatImageRef.Attachment(attachment.id),
                        size = MessageThumbnailSize,
                        contentDescription = "消息图片，第 $position 张，共 ${sorted.size} 张",
                    )
                }
            }
        }
    }
}

/** 预览条里的一项：要么是已有附件（编辑时保留的原图），要么是新选的待发送图片。 */
data class ImageStripItem(val key: String, val ref: ChatImageRef)

fun PendingImage.toStripItem() = ImageStripItem(key = "pending:$id", ref = ChatImageRef.Pending(id))

fun MessageAttachment.toStripItem() = ImageStripItem(key = "attachment:$id", ref = ChatImageRef.Attachment(id))

/**
 * 输入框上方 / 编辑弹窗里的图片预览条：缩略图、删除按钮与「2/4」计数。
 * 删除按钮保持 48dp 触控目标；处理中时给出文字说明，而不是只靠禁用色。
 */
@Composable
fun ImagePreviewStrip(
    items: List<ImageStripItem>,
    isImporting: Boolean,
    enabled: Boolean,
    onRemove: (ImageStripItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty() && !isImporting) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            items.forEachIndexed { index, item ->
                Box {
                    ChatImageThumbnail(
                        ref = item.ref,
                        size = PendingThumbnailSize,
                        contentDescription = "待发送图片，第 ${index + 1} 张",
                        modifier = Modifier.padding(top = Spacing.Small, end = Spacing.Small),
                    )
                    IconButton(
                        onClick = { onRemove(item) },
                        enabled = enabled,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(MinIconTouchTarget)
                            .padding(start = Spacing.Content, bottom = Spacing.Content),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = if (enabled) {
                                "移除第 ${index + 1} 张图片"
                            } else {
                                "移除第 ${index + 1} 张图片，当前不可用"
                            },
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        )
                    }
                }
            }
        }
        Text(
            text = buildString {
                append("图片 ${items.size}/${ImageInputSpec.MAX_IMAGES_PER_MESSAGE}")
                if (isImporting) append(" · 正在处理图片，完成后即可发送…")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
