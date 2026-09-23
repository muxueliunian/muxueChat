package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.muxuechat.domain.model.ProtagonistProfile
import com.muxuechat.domain.model.ProtagonistProfileDraft
import com.muxuechat.domain.model.RpEntry
import com.muxuechat.presentation.common.components.EditorSheet
import com.muxuechat.presentation.common.components.rememberGuardedDismiss
import com.muxuechat.presentation.theme.MinTouchTarget
import com.muxuechat.presentation.theme.Spacing
import java.util.UUID

/** 名字可以为空（表示玩家本人），列表与对话框里需要一个能看的名字（§9 RP-2 第 1 条）。 */
fun ProtagonistProfile.displayName(): String = name.ifBlank { "未命名主角" }

/**
 * 主角档案编辑（RP-3b）：名称、简介、文本词条、状态条。
 *
 * [sharedConversationCount] 是绑定这份档案的会话数，用来说明修改的影响范围。
 * [isBoundToCurrentConversation] 为 true 时保存会同步本会话的状态栏定义；
 * 此时若还有别的会话共用，提供「另存为新档案，只给本会话用」，走 [onFork] 而不是 [onSave]。
 */
@Composable
fun ProtagonistEditorSheet(
    isEditing: Boolean,
    initialDraft: ProtagonistProfileDraft,
    sharedConversationCount: Int,
    isBoundToCurrentConversation: Boolean,
    onSave: (ProtagonistProfileDraft) -> Unit,
    onFork: (ProtagonistProfileDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialDraft) { mutableStateOf(initialDraft.name) }
    var description by remember(initialDraft) { mutableStateOf(initialDraft.description) }
    val initialTextEntries = remember(initialDraft) {
        initialDraft.entries.filterIsInstance<RpEntry.Text>().map(RpEntryDrafts::textFrom)
    }
    val initialNumericEntries = remember(initialDraft) {
        initialDraft.entries.filterIsInstance<RpEntry.Numeric>().map(RpEntryDrafts::numericFrom)
    }
    var textEntries by remember(initialDraft) { mutableStateOf(initialTextEntries) }
    var numericEntries by remember(initialDraft) { mutableStateOf(initialNumericEntries) }

    val issueCount = RpEntryDrafts.issueCount(textEntries, numericEntries)
    val builtTexts = RpEntryDrafts.toTextEntries(textEntries)
    // 与 Repository 的规则一致：整份档案不能什么都没有，否则进不了 prompt。
    val isEmpty = name.isBlank() && description.isBlank() &&
        builtTexts.none { it.value.isNotBlank() } && numericEntries.isEmpty()
    val saveBlockedReason = when {
        issueCount > 0 -> "还有 $issueCount 处需要修正"
        isEmpty -> "名称、简介和词条至少填一项"
        else -> null
    }
    val isDirty = name != initialDraft.name ||
        description != initialDraft.description ||
        textEntries != initialTextEntries ||
        numericEntries != initialNumericEntries

    fun buildDraft() = ProtagonistProfileDraft(
        name = name.trim(),
        description = description.trim(),
        entries = builtTexts + RpEntryDrafts.toNumericEntries(numericEntries),
    )

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
                    onClick = { onSave(buildDraft()) },
                    enabled = saveBlockedReason == null,
                ) {
                    Text("保存")
                }
            }
        },
    ) {
        Text(
            text = if (isEditing) "编辑主角档案" else "新建主角档案",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Spacing.Content, bottom = Spacing.Tight),
        )
        Text(
            text = "主角是玩家控制的人物，会写入 RP prompt，并作为剧情推进建议的依据。名称留空表示玩家本人。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val othersUseIt = if (isBoundToCurrentConversation) sharedConversationCount > 1 else sharedConversationCount > 0
        if (isEditing && othersUseIt) {
            SharedProfileNotice(
                sharedConversationCount = sharedConversationCount,
                isBoundToCurrentConversation = isBoundToCurrentConversation,
                forkEnabled = saveBlockedReason == null,
                onFork = { onFork(buildDraft()) },
            )
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("主角名称") },
            placeholder = { Text("例如：牧雪") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("主角简介") },
            placeholder = { Text("玩家控制的主角是谁，和角色是什么关系") },
            minLines = 2,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )

        TextEntriesEditor(
            entries = textEntries,
            onChange = { textEntries = it },
            description = "补充静态设定，例如初始状态、身份、随身物品。只写进 prompt，不进状态栏。",
        )

        NumericEntriesEditor(
            entries = numericEntries,
            onChange = { numericEntries = it },
            newEntry = {
                NumericEntryDraft(
                    id = UUID.randomUUID().toString(),
                    name = "体力",
                    initialText = "100",
                    description = "主角的体力与精神余量。",
                    progressRule = "0 表示精疲力竭，100 表示状态饱满；受伤、奔波或休息时变化。",
                )
            },
            description = "主角的数值会以「主角::」前缀进状态栏，和角色卡的数值互不串位。",
        )
    }
}

@Composable
private fun SharedProfileNotice(
    sharedConversationCount: Int,
    isBoundToCurrentConversation: Boolean,
    forkEnabled: Boolean,
    onFork: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        Text(
            text = "这份档案有 $sharedConversationCount 个会话在用。名称、简介和文本词条的修改会同时出现在这些会话里；" +
                if (isBoundToCurrentConversation) {
                    "状态栏定义只同步到当前会话，其它会话仍沿用绑定时的定义。"
                } else {
                    "状态栏定义不会改动任何会话，它们仍沿用绑定时的定义。"
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (isBoundToCurrentConversation) {
            OutlinedButton(
                onClick = onFork,
                enabled = forkEnabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            ) {
                Text("另存为新档案，只给本会话用")
            }
            Text(
                text = "另存后本会话改用新档案，主角数值保留；原档案和其它会话不受影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
