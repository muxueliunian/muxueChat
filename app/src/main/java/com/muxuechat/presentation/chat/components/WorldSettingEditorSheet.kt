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
import com.muxuechat.domain.model.WorldSettingDraft

/**
 * 世界观编辑底部 Sheet。
 *
 * 标题必填；空时禁用保存。内容可滚动，避免被键盘遮挡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldSettingEditorSheet(
    isEditing: Boolean,
    initialDraft: WorldSettingDraft,
    onSave: (WorldSettingDraft) -> Unit,
    onDismiss: () -> Unit,
) {

    var title by remember(initialDraft) { mutableStateOf(initialDraft.title) }
    var content by remember(initialDraft) { mutableStateOf(initialDraft.content) }

    val canSave = title.trim().isNotEmpty()

    val guardedDismiss = rememberGuardedDismiss(title != initialDraft.title || content != initialDraft.content, onDismiss)
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
                            WorldSettingDraft(
                                title = title.trim(),
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
                text = if (isEditing) "编辑世界观" else "新建世界观",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "世界观标题",
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
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    placeholder = { Text("例如：赛博港 2099") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(min = 56.dp),
                )
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "世界观内容",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    minLines = 6,
                    maxLines = 20,
                    placeholder = { Text("描述时代、地理、势力、规则、道具……") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(min = 160.dp),
                )
            }


    }
}
