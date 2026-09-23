package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.rp.RpEntryRules
import com.muxuechat.presentation.theme.MinIconTouchTarget
import com.muxuechat.presentation.theme.MinTouchTarget
import com.muxuechat.presentation.theme.Spacing

/**
 * 文本词条编辑（RP-3a）：名称 + 内容，角色卡、主角、配角共用。
 * 文本词条只进 prompt，不进状态栏，因此名字不限制「::」也不判重。
 */
@Composable
fun TextEntriesEditor(
    entries: List<TextEntryDraft>,
    onChange: (List<TextEntryDraft>) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
    ) {
        EntrySectionHeader(title = "文本词条", description = description)

        entries.forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            EntryRowHeader(
                title = "词条 ${index + 1}",
                deleteDescription = "删除词条 ${index + 1}",
                onDelete = { onChange(entries.toMutableList().also { it.removeAt(index) }) },
            )
            EntryField(
                label = "名称",
                value = entry.name,
                onValueChange = { name -> onChange(entries.replaced(index, entry.copy(name = name))) },
                error = RpEntryDrafts.textNameIssue(entry),
                singleLine = true,
                placeholder = "例如：年龄、身份、与主角的关系",
            )
            EntryField(
                label = "内容",
                value = entry.value,
                onValueChange = { value -> onChange(entries.replaced(index, entry.copy(value = value))) },
                minLines = 1,
                maxLines = 6,
            )
        }

        AddEntryButton(
            text = "添加文本词条",
            onClick = { onChange(entries + RpEntryDrafts.newText()) },
        )
    }
}

/**
 * 数值词条（状态条）编辑（RP-3a）：角色卡与主角共用；配角没有数值，不使用本组件。
 *
 * 数字保留字符串草稿，错误内联显示；是否允许保存由调用方按 [RpEntryDrafts.issueCount] 决定。
 * 达到 [RpEntryRules.MAX_NUMERIC_ENTRIES] 时禁用添加并写明原因——超出的条 Repository 会丢弃。
 */
@Composable
fun NumericEntriesEditor(
    entries: List<NumericEntryDraft>,
    onChange: (List<NumericEntryDraft>) -> Unit,
    newEntry: () -> NumericEntryDraft,
    description: String,
    modifier: Modifier = Modifier,
) {
    val issues = RpEntryDrafts.numericIssues(entries)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
    ) {
        EntrySectionHeader(title = "状态栏定义", description = description)

        entries.forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NumericEntryEditor(
                index = index,
                entry = entry,
                issues = issues[index],
                onChange = { onChange(entries.replaced(index, it)) },
                onDelete = { onChange(entries.toMutableList().also { it.removeAt(index) }) },
            )
        }

        val atLimit = entries.size >= RpEntryRules.MAX_NUMERIC_ENTRIES
        AddEntryButton(
            text = "添加状态条",
            enabled = !atLimit,
            onClick = { onChange(entries + newEntry()) },
        )
        if (atLimit) {
            Text(
                text = "每份档案最多 ${RpEntryRules.MAX_NUMERIC_ENTRIES} 条状态条，删除一条后才能继续添加。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NumericEntryEditor(
    index: Int,
    entry: NumericEntryDraft,
    issues: NumericEntryIssues,
    onChange: (NumericEntryDraft) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
    ) {
        EntryRowHeader(
            title = "状态条 ${index + 1}",
            deleteDescription = "删除状态条 ${index + 1}",
            onDelete = onDelete,
        )
        EntryField(
            label = "状态名称",
            value = entry.name,
            onValueChange = { onChange(entry.copy(name = it)) },
            error = issues.name,
            singleLine = true,
            placeholder = "例如：好感度、信任度、体力",
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
            modifier = Modifier.fillMaxWidth(),
        ) {
            NumberDraftField(
                label = "最小值",
                value = entry.minText,
                isError = issues.min != null,
                onValueChange = { onChange(entry.copy(minText = it)) },
                modifier = Modifier.weight(1f),
            )
            NumberDraftField(
                label = "最大值",
                value = entry.maxText,
                isError = issues.max != null,
                onValueChange = { onChange(entry.copy(maxText = it)) },
                modifier = Modifier.weight(1f),
            )
            NumberDraftField(
                label = "初始值",
                value = entry.initialText,
                isError = issues.initial != null,
                onValueChange = { onChange(entry.copy(initialText = it)) },
                modifier = Modifier.weight(1f),
            )
        }
        // 三个数字框很窄，错误合并成一行写在下方，而不是挤进各自的 supportingText。
        val numberIssues = listOfNotNull(
            issues.min?.let { "最小值：$it" },
            issues.max?.let { "最大值：$it" },
            issues.initial?.let { "初始值：$it" },
        )
        if (numberIssues.isNotEmpty()) {
            Text(
                text = numberIssues.joinToString("；"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        EntryField(
            label = "状态含义",
            value = entry.description,
            onValueChange = { onChange(entry.copy(description = it)) },
            minLines = 2,
            maxLines = 5,
            placeholder = "这个数值代表什么心理、关系或身体状态",
        )
        EntryField(
            label = "变化规则",
            value = entry.progressRule,
            onValueChange = { onChange(entry.copy(progressRule = it)) },
            minLines = 2,
            maxLines = 5,
            placeholder = "例如：0 表示陌生，50 表示信任，100 表示强烈依恋；只有在明确互动后才变化",
        )
    }
}

@Composable
private fun EntrySectionHeader(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EntryRowHeader(
    title: String,
    deleteDescription: String,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(MinIconTouchTarget),
        ) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = deleteDescription,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AddEntryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget),
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = text,
            modifier = Modifier.padding(start = Spacing.Small),
        )
    }
}

/** 只接受「可选负号 + 数字」的中间形状；空串与单独的负号都允许停留，保存时再校验。 */
@Composable
private fun NumberDraftField(
    label: String,
    value: String,
    isError: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value = value,
            onValueChange = { text ->
                if (RpEntryDrafts.acceptsNumberInput(text)) onValueChange(text)
            },
            isError = isError,
            singleLine = true,
            // 用 Text 而不是 Number：不少厂商的数字键盘没有负号键。
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.Tight)
                .heightIn(min = MinTouchTarget),
        )
    }
}

@Composable
private fun EntryField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    placeholder: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            maxLines = if (singleLine) 1 else maxLines,
            placeholder = placeholder?.let { { Text(it) } },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.Tight)
                .heightIn(min = MinTouchTarget),
        )
    }
}

private fun <T> List<T>.replaced(index: Int, item: T): List<T> =
    toMutableList().also { it[index] = item }
