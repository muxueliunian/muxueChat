package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.RoleCardDraft
import com.muxuechat.domain.model.WorldSettingDraft
import com.muxuechat.domain.model.WritingStyleProfileDraft
import com.muxuechat.domain.tool.SettingsToolDraft

/** 用户在预览界面确认时提交的最终内容。 */
data class SettingsDraftConfirmation(
    val draftId: String,
    val roleCard: RoleCardDraft?,
    val worldSetting: WorldSettingDraft?,
    val writingStyle: WritingStyleProfileDraft?,
    val bindToConversation: Boolean,
)

/**
 * 工具草稿的预览与编辑。
 *
 * 这是安全契约里最关键的一环：模型调用工具**只生成草稿**，
 * 必须经过这个界面由用户确认才会写入数据库。取消则整份丢弃。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDraftSheet(
    draft: SettingsToolDraft,
    isSaving: Boolean,
    onConfirm: (SettingsDraftConfirmation) -> Unit,
    onDiscard: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var roleCard by remember(draft.id) { mutableStateOf(draft.roleCard) }
    var worldSetting by remember(draft.id) { mutableStateOf(draft.worldSetting) }
    var writingStyle by remember(draft.id) { mutableStateOf(draft.writingStyle) }
    // 默认不绑定：保存和绑定是两件事，不该替用户决定。
    var bindToConversation by remember(draft.id) { mutableStateOf(false) }

    val canSave = roleCard?.name?.isNotBlank() == true ||
        worldSetting?.title?.isNotBlank() == true ||
        writingStyle?.name?.isNotBlank() == true

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // AI 角色卡草稿是长表单。滚动到边界时不能把剩余拖动交给外层 Sheet，
        // 否则会与 verticalScroll 竞争并造成上下回弹。
        sheetGesturesEnabled = false,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "${draft.typeLabel}草稿",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "这是 AI 生成的草稿，还没有保存。你可以直接修改，确认后才会写入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (draft.warnings.isNotEmpty()) {
                WarningBlock(draft.warnings)
            }

            roleCard?.let { card ->
                DraftField("角色名称", card.name) { roleCard = card.copy(name = it) }
                DraftField("角色简介", card.description, minLines = 2) {
                    roleCard = card.copy(description = it)
                }
                DraftField("人设描述", card.persona, minLines = 4) { roleCard = card.copy(persona = it) }
                DraftField("说话风格", card.speakingStyle, minLines = 2) {
                    roleCard = card.copy(speakingStyle = it)
                }
                DraftField("用户身份", card.userIdentity) { roleCard = card.copy(userIdentity = it) }
                DraftField("开场白", card.openingMessage, minLines = 2) {
                    roleCard = card.copy(openingMessage = it)
                }
                DraftField("禁止事项与边界", card.boundaries, minLines = 2) {
                    roleCard = card.copy(boundaries = it)
                }
                if (card.metricDefinitions.isNotEmpty()) {
                    MetricSummary(card)
                }
            }

            worldSetting?.let { world ->
                DraftField("世界观标题", world.title) { worldSetting = world.copy(title = it) }
                DraftField("世界观正文", world.content, minLines = 8) {
                    worldSetting = world.copy(content = it)
                }
            }

            writingStyle?.let { style ->
                DraftField("文风名称", style.name) { writingStyle = style.copy(name = it) }
                DraftField("文风要求", style.content, minLines = 8) {
                    writingStyle = style.copy(content = it)
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = bindToConversation,
                    onCheckedChange = { bindToConversation = it },
                    enabled = draft.conversationId != null,
                )
                Column {
                    Text(text = "保存后绑定到当前会话", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "绑定不会自动开启 RP 模式。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = { onDiscard(draft.id) },
                    enabled = !isSaving,
                ) {
                    Text("丢弃")
                }
                Button(
                    onClick = {
                        onConfirm(
                            SettingsDraftConfirmation(
                                draftId = draft.id,
                                roleCard = roleCard,
                                worldSetting = worldSetting,
                                writingStyle = writingStyle,
                                bindToConversation = bindToConversation,
                            ),
                        )
                    },
                    enabled = canSave && !isSaving,
                ) {
                    Text(if (isSaving) "保存中…" else "保存")
                }
            }
        }
    }
}

@Composable
private fun WarningBlock(warnings: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "以下内容已被本地规则修正：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        warnings.forEach { warning ->
            Text(
                text = "· $warning",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MetricSummary(card: RoleCardDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "状态条（${card.metricDefinitions.size} 个）",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        card.metricDefinitions.forEach { definition ->
            Text(
                text = "· ${definition.name}：${definition.minValue}~${definition.maxValue}，" +
                    "初始 ${definition.initialValue}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "保存后可在角色卡编辑器里进一步调整。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DraftField(
    label: String,
    value: String,
    minLines: Int = 1,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    )
}
