package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.muxuechat.presentation.common.components.EditorSheet
import com.muxuechat.presentation.common.components.rememberGuardedDismiss
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.RoleCardDraft
import java.util.UUID

/**
 * 角色卡编辑底部 Sheet。
 *
 * initialDraft 为空表示新增，传入已有草稿代表编辑。
 * 名称为空时禁用保存。所有回调由上层 ViewModel 处理持久化。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleCardEditorSheet(
    isEditing: Boolean,
    initialDraft: RoleCardDraft,
    onSave: (RoleCardDraft) -> Unit,
    onDismiss: () -> Unit,
) {

    var name by remember(initialDraft) { mutableStateOf(initialDraft.name) }
    var description by remember(initialDraft) { mutableStateOf(initialDraft.description) }
    var persona by remember(initialDraft) { mutableStateOf(initialDraft.persona) }
    var speakingStyle by remember(initialDraft) { mutableStateOf(initialDraft.speakingStyle) }
    var userIdentity by remember(initialDraft) { mutableStateOf(initialDraft.userIdentity) }
    var boundaries by remember(initialDraft) { mutableStateOf(initialDraft.boundaries) }
    val initialTextEntries = remember(initialDraft) { initialDraft.textEntries.map(RpEntryDrafts::textFrom) }
    val initialNumericEntries = remember(initialDraft) {
        initialDraft.metricDefinitions.map(RpEntryDrafts::numericFrom)
    }
    var textEntries by remember(initialDraft) { mutableStateOf(initialTextEntries) }
    var numericEntries by remember(initialDraft) { mutableStateOf(initialNumericEntries) }

    val issueCount = RpEntryDrafts.issueCount(textEntries, numericEntries)
    val canSave = name.trim().isNotEmpty() && issueCount == 0
    val saveBlockedReason = when {
        name.trim().isEmpty() -> "请填写角色名称"
        issueCount > 0 -> "还有 $issueCount 处需要修正"
        else -> null
    }

    val isDirty = name != initialDraft.name ||
        description != initialDraft.description ||
        persona != initialDraft.persona ||
        speakingStyle != initialDraft.speakingStyle ||
        userIdentity != initialDraft.userIdentity ||
        boundaries != initialDraft.boundaries ||
        textEntries != initialTextEntries ||
        numericEntries != initialNumericEntries
    val guardedDismiss = rememberGuardedDismiss(isDirty, onDismiss)
    EditorSheet(
        onDismiss = guardedDismiss,
        actions = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                saveBlockedReason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                TextButton(onClick = guardedDismiss) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        onSave(
                            RoleCardDraft(
                                name = name.trim(),
                                description = description.trim(),
                                persona = persona.trim(),
                                speakingStyle = speakingStyle.trim(),
                                userIdentity = userIdentity.trim(),
                                boundaries = boundaries.trim(),
                                metricDefinitions = RpEntryDrafts.toMetricDefinitions(numericEntries),
                                textEntries = RpEntryDrafts.toTextEntries(textEntries),
                            ),
                        )
                    },
                    enabled = canSave,
                ) {
                    Text("保存")
                }
            }
        },
    ) {
            Text(
                text = if (isEditing) "编辑角色卡" else "新建角色卡",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )

            FormField(
                label = "角色名称",
                required = true,
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = "例如：艾琳",
            )

            FormField(
                label = "简介",
                value = description,
                onValueChange = { description = it },
                minLines = 2,
                maxLines = 4,
                placeholder = "一句话介绍，如：来自星港的领航员",
            )

            FormField(
                label = "人设描述",
                value = persona,
                onValueChange = { persona = it },
                minLines = 4,
                maxLines = 12,
                placeholder = "详细描述角色的背景、性格、动机……",
            )

            FormField(
                label = "说话风格",
                value = speakingStyle,
                onValueChange = { speakingStyle = it },
                minLines = 2,
                maxLines = 6,
                placeholder = "语气、用词偏好、常见口癖等",
            )

            FormField(
                label = "用户身份",
                value = userIdentity,
                onValueChange = { userIdentity = it },
                minLines = 2,
                maxLines = 6,
                placeholder = "角色眼中你是谁（身份、关系、昵称）",
            )

            FormField(
                label = "禁止事项 / 边界",
                value = boundaries,
                onValueChange = { boundaries = it },
                minLines = 2,
                maxLines = 8,
                placeholder = "角色拒绝谈论的内容或行为边界",
            )

            TextEntriesEditor(
                entries = textEntries,
                onChange = { textEntries = it },
                description = "补充静态设定，例如年龄、身份、口头禅、与主角的关系。只写进 prompt，不进状态栏。",
            )

            NumericEntriesEditor(
                entries = numericEntries,
                onChange = { numericEntries = it },
                newEntry = {
                    NumericEntryDraft(
                        id = UUID.randomUUID().toString(),
                        name = "好感度",
                        description = "角色对用户的亲近、信赖和情感投入程度。",
                        progressRule = "0 表示陌生或排斥，50 表示熟悉，100 表示强烈依恋。",
                    )
                },
                description = "定义这个角色需要维护的状态条；当前数值会按会话独立保存。" +
                    "修改后，已绑定这张卡的其它会话仍沿用绑定时的定义。",
            )
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    required: Boolean = false,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    placeholder: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (required) {
                Text(
                    text = " *",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            maxLines = if (singleLine) 1 else maxLines,
            placeholder = placeholder?.let { { Text(it) } },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .heightIn(min = 56.dp),
        )
    }
}
