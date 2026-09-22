package com.muxuechat.presentation.common.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.muxuechat.presentation.common.ModelNote
import com.muxuechat.presentation.common.ModelNoteTone
import com.muxuechat.presentation.theme.Corner
import com.muxuechat.presentation.theme.MinTouchTarget
import com.muxuechat.presentation.theme.Spacing

/**
 * 模型标注文案。
 *
 * [ModelNoteTone.CAUTION] 会同时改变颜色**和**前缀符号：颜色不能是唯一的状态信号，
 * 深色模式、色觉差异和截图分享下都要能读出这是一条限制而不是普通说明。
 */
@Composable
fun ModelNoteText(
    note: ModelNote,
    modifier: Modifier = Modifier,
) {
    val prefix = when (note.tone) {
        ModelNoteTone.NEUTRAL -> ""
        ModelNoteTone.CAUTION -> "注意："
    }
    Text(
        text = prefix + note.text,
        style = MaterialTheme.typography.bodySmall,
        color = when (note.tone) {
            ModelNoteTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
            ModelNoteTone.CAUTION -> MaterialTheme.colorScheme.error
        },
        modifier = modifier,
    )
}

@Composable
fun ModelNoteList(
    notes: List<ModelNote>,
    modifier: Modifier = Modifier,
) {
    if (notes.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        notes.forEach { ModelNoteText(note = it) }
    }
}

/**
 * 目录搜索框。
 *
 * 两千个模型不可能一次全渲染，搜索是找到目标的主要手段，因此它必须常驻而不是藏在菜单里。
 */
@Composable
fun ModelSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(MinTouchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "清除搜索",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = Corner.Control,
        colors = quietTextFieldColors(),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics { contentDescription = label },
    )
}

/** 「还有 N 个没显示」的提示。没有它，截断就会变成静默丢数据。 */
@Composable
fun TruncatedListHint(
    hiddenCount: Int,
    isFiltered: Boolean,
    modifier: Modifier = Modifier,
) {
    if (hiddenCount <= 0) return
    Text(
        text = if (isFiltered) {
            "另有 $hiddenCount 个匹配项未显示，请输入更精确的关键词。"
        } else {
            "另有 $hiddenCount 个模型未显示，可用上方搜索框查找。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = Spacing.Tight),
    )
}

/** 一行「标签 + 状态」的说明条，用于目录来源、刷新时间这类元信息。 */
@Composable
fun MetaLine(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 低对比、无填充变化的输入框配色，避免设置页出现一片高对比方块。 */
@Composable
fun quietTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
