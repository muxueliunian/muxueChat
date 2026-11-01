package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.ImageInputSpec
import com.muxuechat.domain.model.PendingImage
import com.muxuechat.presentation.chat.PendingAttachment
import com.muxuechat.presentation.theme.MinTouchTarget
import com.muxuechat.presentation.theme.Spacing

@Composable
fun MessageInputBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    attachment: PendingAttachment? = null,
    isImportingAttachment: Boolean = false,
    /** 文本文档附件只给设定生成工具用；RP 进行中或工具未开启时不可用（图片不受影响）。 */
    canAttachDocument: Boolean = false,
    onAttachDocument: () -> Unit = {},
    onRemoveAttachment: () -> Unit = {},
    pendingImages: List<PendingImage> = emptyList(),
    isImportingImages: Boolean = false,
    onPickImages: () -> Unit = {},
    onRemovePendingImage: (String) -> Unit = {},
    /** 当前供应商与模型下的思考档位；null 时不显示（例如还没有可用的自定义供应商）。 */
    thinking: ThinkingControlState? = null,
    onSelectThinking: (ThinkingChoice) -> Unit = {},
) {
    // 带附件或图片时即使正文为空也允许发送——它们本身就是内容（D6）。
    val hasContent = value.text.isNotBlank() || attachment != null || pendingImages.isNotEmpty()
    val isImporting = isImportingAttachment || isImportingImages
    val canSend = hasContent && !isGenerating && !isImporting
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 键盘弹出时 IME 高度已经覆盖导航栏；两个 padding 分别加会多出一段空白，
                // 收起时又只剩导航栏。union 让两种状态都恰好留出应有的高度。
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(horizontal = Spacing.Medium, vertical = Spacing.Small),
        ) {
            if (isImportingAttachment) {
                Text("正在提取文档文字，完成后即可发送…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
            }
            attachment?.let { pending ->
                AttachmentChip(attachment = pending, onRemove = onRemoveAttachment)
            }
            ImagePreviewStrip(
                items = pendingImages.map { it.toStripItem() },
                isImporting = isImportingImages,
                enabled = !isGenerating,
                onRemove = { item -> (item.ref as? ChatImageRef.Pending)?.let { onRemovePendingImage(it.id) } },
                modifier = Modifier.padding(bottom = Spacing.Small),
            )
            // 主流聊天应用的输入框：上面整宽写字，下面一排工具（附件、思考档位），发送键在右下。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(com.muxuechat.presentation.theme.Corner.Container)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = com.muxuechat.presentation.theme.Corner.Container,
                    )
                    .padding(Spacing.Tight),
            ) {
                MessageTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.Small)
                        .semantics { contentDescription = "消息正文" }
                        .heightIn(min = 40.dp, max = 180.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // 图片入口始终可用，不按当前模型禁用（D8）；普通聊天与 RP 共用这一个菜单。
                    AttachmentMenuButton(
                        enabled = !isGenerating && !isImporting,
                        disabledReason = when {
                            isImportingImages -> "正在处理图片"
                            isImportingAttachment -> "正在提取文档文字"
                            isGenerating -> "生成期间不可用"
                            else -> null
                        },
                        remainingImageSlots = ImageInputSpec.MAX_IMAGES_PER_MESSAGE - pendingImages.size,
                        canAttachDocument = canAttachDocument,
                        onPickImages = onPickImages,
                        onAttachDocument = onAttachDocument,
                    )
                    thinking?.let { ThinkingSelector(state = it, onSelect = onSelectThinking) }
                    Spacer(modifier = Modifier.weight(1f))
                    SendOrStopButton(
                        isGenerating = isGenerating,
                        canSend = canSend,
                        isImportingAttachment = isImporting,
                        hasContent = hasContent,
                        onSend = onSend,
                        onStop = onStop,
                    )
                }
            }
        }
    }
}

/**
 * 附件菜单：「图片」「文本文档」。不可用的项照样列出并写明原因，而不是悄悄消失。
 */
@Composable
private fun AttachmentMenuButton(
    enabled: Boolean,
    disabledReason: String?,
    remainingImageSlots: Int,
    canAttachDocument: Boolean,
    onPickImages: () -> Unit,
    onAttachDocument: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.size(MinTouchTarget),
        ) {
            Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = disabledReason?.let { "添加附件，当前不可用：$it" } ?: "添加附件：图片或文本文档",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val canPickImages = remainingImageSlots > 0
            DropdownMenuItem(
                text = {
                    Column {
                        Text("图片")
                        // 只在不可用时写明原因。
                        if (!canPickImages) {
                            Text(
                                text = "已达 ${ImageInputSpec.MAX_IMAGES_PER_MESSAGE} 张上限",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                enabled = canPickImages,
                onClick = {
                    expanded = false
                    onPickImages()
                },
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text("文本文档")
                        if (!canAttachDocument) {
                            Text(
                                text = "需开启设定生成工具且不在 RP 中",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                enabled = canAttachDocument,
                onClick = {
                    expanded = false
                    onAttachDocument()
                },
            )
        }
    }
}

/** 已附加资料的提示条。字数直接显示出来，让用户对将要发送的量有感知。 */
@Composable
private fun AttachmentChip(
    attachment: PendingAttachment,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Description,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Text(
                text = attachment.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${attachment.charCount} 字 · 将随本条消息发送",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "移除附件",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyle: TextStyle = LocalTextStyle.current.merge(
        MaterialTheme.typography.bodyLarge,
    ).copy(color = MaterialTheme.colorScheme.onSurface)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = textStyle,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Default,
        ),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.padding(vertical = Spacing.Medium),
            ) {
                if (value.text.isEmpty()) {
                    Text(
                        text = "发送消息……",
                        style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun SendOrStopButton(
    isGenerating: Boolean,
    canSend: Boolean,
    isImportingAttachment: Boolean,
    hasContent: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    if (isGenerating) {
        IconButton(
            onClick = onStop,
            modifier = Modifier
                .size(com.muxuechat.presentation.theme.MinTouchTarget)
                .clip(CircleShape),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Icon(
                imageVector = Icons.Outlined.Stop,
                contentDescription = "停止生成",
            )
        }
    } else {
        IconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier
                .size(com.muxuechat.presentation.theme.MinTouchTarget)
                .clip(CircleShape),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Send,
                // 禁用时也要说明原因，否则 TalkBack 只会读出一个读不出所以然的「发送」。
                contentDescription = if (isImportingAttachment) "发送不可用，正在处理附件" else if (hasContent) "发送" else "发送，当前不可用：还没有输入内容",
            )
        }
    }
}
