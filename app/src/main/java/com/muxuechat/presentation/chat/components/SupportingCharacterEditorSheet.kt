package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.muxuechat.domain.model.SupportingCharacterDraft
import com.muxuechat.presentation.common.components.EditorSheet
import com.muxuechat.presentation.common.components.rememberGuardedDismiss
import com.muxuechat.presentation.theme.MinTouchTarget
import com.muxuechat.presentation.theme.Spacing

/**
 * 配角编辑（RP-3c）：名称、简介、文本词条。配角没有数值（R2），这里不提供状态条。
 *
 * 删除入口放在编辑器里而不是列表行上：列表行已经有勾选、排序与编辑，再塞一个删除按钮在小屏上放不下。
 * [onDelete] 为 null 表示新建，不显示删除。
 */
@Composable
fun SupportingCharacterEditorSheet(
    isEditing: Boolean,
    initialDraft: SupportingCharacterDraft,
    usedByConversationCount: Int,
    onSave: (SupportingCharacterDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember(initialDraft) { mutableStateOf(initialDraft.name) }
    var description by remember(initialDraft) { mutableStateOf(initialDraft.description) }
    val initialTextEntries = remember(initialDraft) { initialDraft.entries.map(RpEntryDrafts::textFrom) }
    var textEntries by remember(initialDraft) { mutableStateOf(initialTextEntries) }

    val issueCount = RpEntryDrafts.issueCount(textEntries, emptyList())
    val saveBlockedReason = when {
        name.isBlank() -> "请填写配角名称"
        issueCount > 0 -> "还有 $issueCount 处需要修正"
        else -> null
    }
    val isDirty = name != initialDraft.name ||
        description != initialDraft.description ||
        textEntries != initialTextEntries

    val guardedDismiss = rememberGuardedDismiss(isDirty, onDismiss)
    EditorSheet(
        onDismiss = guardedDismiss,
        actions = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small, Alignment.End),
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
                            SupportingCharacterDraft(
                                name = name.trim(),
                                description = description.trim(),
                                entries = RpEntryDrafts.toTextEntries(textEntries),
                            ),
                        )
                    },
                    enabled = saveBlockedReason == null,
                ) {
                    Text("保存")
                }
            }
        },
    ) {
        Text(
            text = if (isEditing) "编辑配角" else "新建配角",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Spacing.Content, bottom = Spacing.Tight),
        )
        Text(
            text = "配角常驻写入正文 prompt，由模型按设定描写他们的言行；不进状态栏，也不参与内心独白与剧情建议。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isEditing && usedByConversationCount > 0) {
            Text(
                text = "有 $usedByConversationCount 个会话勾选了这个配角，修改会同时出现在这些会话里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("配角名称 *") },
            placeholder = { Text("例如：老周") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("简介") },
            placeholder = { Text("他是谁，和主角、角色卡人物是什么关系") },
            minLines = 2,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )

        TextEntriesEditor(
            entries = textEntries,
            onChange = { textEntries = it },
            description = "补充静态设定，例如年龄、身份、口头禅。越长越占每一轮的上下文。",
        )

        if (onDelete != null) {
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            ) {
                Text("删除这个配角", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
