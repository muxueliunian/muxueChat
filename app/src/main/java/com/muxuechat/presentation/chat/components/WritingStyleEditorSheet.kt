package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.muxuechat.presentation.common.components.EditorSheet
import com.muxuechat.presentation.common.components.rememberGuardedDismiss
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.WritingStyleProfileDraft

/**
 * 文风编辑底部 Sheet。
 *
 * 在此之前文风只能由 AI 工具生成，用户没有手动入口。
 * 名称必填；空时禁用保存。结构与 [WorldSettingEditorSheet] 保持一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WritingStyleEditorSheet(
    isEditing: Boolean,
    initialDraft: WritingStyleProfileDraft,
    onSave: (WritingStyleProfileDraft) -> Unit,
    onDismiss: () -> Unit,
) {

    var name by remember(initialDraft) { mutableStateOf(initialDraft.name) }
    var content by remember(initialDraft) { mutableStateOf(initialDraft.content) }

    val canSave = name.trim().isNotEmpty()

    val guardedDismiss = rememberGuardedDismiss(name != initialDraft.name || content != initialDraft.content, onDismiss)
    EditorSheet(
        onDismiss = guardedDismiss,
        actions = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = guardedDismiss) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        onSave(
                            WritingStyleProfileDraft(
                                name = name.trim(),
                                content = content.trim(),
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
                text = if (isEditing) "编辑文风" else "新建文风",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )

            // 文风和角色卡的「说话风格」很容易混淆，这里直接写清楚区别。
            Text(
                text = "文风约束整段正文怎么写：叙事视角、时态、节奏、描写密度、对白比例、禁用表达。" +
                    "角色怎么说话请写在角色卡的「说话风格」里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "文风名称",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = " *",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("例如：冷硬克制的短句") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(min = 56.dp),
                )
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "文风内容",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    minLines = 6,
                    maxLines = 20,
                    placeholder = { Text("句子偏短，少用比喻；情绪靠动作和留白传达，不直接点破……") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(min = 160.dp),
                )
            }


    }
}
