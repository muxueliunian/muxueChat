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
import com.muxuechat.presentation.common.components.EditorSheet
import com.muxuechat.presentation.common.components.rememberGuardedDismiss
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.ProtagonistDraft
import com.muxuechat.domain.model.ProtagonistProfileDraft
import com.muxuechat.domain.model.RpEntry
import com.muxuechat.domain.model.RpMetricDefinition
import com.muxuechat.domain.model.toMetricDefinition
import com.muxuechat.domain.model.RoleCardDraft
import com.muxuechat.domain.model.SupportingCharacterDraft
import com.muxuechat.domain.model.WorldSettingDraft
import com.muxuechat.domain.model.WritingStyleProfileDraft
import com.muxuechat.domain.tool.SettingsToolDraft
import com.muxuechat.domain.tool.SettingsUpdateTarget

/** 用户在预览界面确认时提交的最终内容。 */
data class SettingsDraftConfirmation(
    val draftId: String,
    val roleCard: RoleCardDraft?,
    val worldSetting: WorldSettingDraft?,
    val writingStyle: WritingStyleProfileDraft?,
    val protagonist: ProtagonistDraft?,
    val supportingCharacter: SupportingCharacterDraft? = null,
    val bindToConversation: Boolean,
    /** 只在修改主角档案时出现（`update_rp_setting`）。 */
    val protagonistProfile: ProtagonistProfileDraft? = null,
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

    var roleCard by remember(draft.id) { mutableStateOf(draft.roleCard) }
    var worldSetting by remember(draft.id) { mutableStateOf(draft.worldSetting) }
    var writingStyle by remember(draft.id) { mutableStateOf(draft.writingStyle) }
    var protagonist by remember(draft.id) { mutableStateOf(draft.protagonist) }
    var supporting by remember(draft.id) { mutableStateOf(draft.supportingCharacter) }
    val initialSupportingEntries = remember(draft.id) {
        draft.supportingCharacter?.entries.orEmpty().map(RpEntryDrafts::textFrom)
    }
    var supportingEntries by remember(draft.id) { mutableStateOf(initialSupportingEntries) }
    // 修改请求里角色卡与主角档案的文本词条也要能改；状态条只读，保存时原样带回（id 不变）。
    val initialRoleCardEntries = remember(draft.id) {
        draft.roleCard?.textEntries.orEmpty().map(RpEntryDrafts::textFrom)
    }
    var roleCardEntries by remember(draft.id) { mutableStateOf(initialRoleCardEntries) }
    var profile by remember(draft.id) { mutableStateOf(draft.protagonistProfile) }
    val initialProfileEntries = remember(draft.id) {
        draft.protagonistProfile?.entries.orEmpty().filterIsInstance<RpEntry.Text>().map(RpEntryDrafts::textFrom)
    }
    var profileEntries by remember(draft.id) { mutableStateOf(initialProfileEntries) }
    val update = draft.update
    // 默认不绑定：保存和绑定是两件事，不该替用户决定。
    // 主角例外：它只存在于会话设置里，不绑定就没有落点，因此强制绑定且不给取消。
    val bindingRequired = draft.protagonist != null && update == null
    var bindToConversation by remember(draft.id) { mutableStateOf(bindingRequired) }

    val canSave = roleCard?.name?.isNotBlank() == true ||
        worldSetting?.title?.isNotBlank() == true ||
        writingStyle?.name?.isNotBlank() == true ||
        (protagonist?.name?.isNotBlank() == true && draft.conversationId != null) ||
        (supporting?.name?.isNotBlank() == true && RpEntryDrafts.issueCount(supportingEntries, emptyList()) == 0) ||
        profile != null
    val entriesValid = RpEntryDrafts.issueCount(roleCardEntries + profileEntries, emptyList()) == 0

    val discardGuard = rememberGuardedDismiss(true, { onDiscard(draft.id) }, enabled = !isSaving)
    val guardedDismiss = rememberGuardedDismiss(roleCard != draft.roleCard || worldSetting != draft.worldSetting || writingStyle != draft.writingStyle || protagonist != draft.protagonist || supporting != draft.supportingCharacter || supportingEntries != initialSupportingEntries || roleCardEntries != initialRoleCardEntries || profile != draft.protagonistProfile || profileEntries != initialProfileEntries || bindToConversation != bindingRequired, onDismiss, enabled = !isSaving)
    EditorSheet(
        onDismiss = guardedDismiss,
        actions = {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = discardGuard,
                    enabled = !isSaving,
                ) {
                    Text("丢弃")
                }
                TextButton(onClick = guardedDismiss, enabled = !isSaving) { Text("收起") }
                Button(
                    onClick = {
                        onConfirm(
                            SettingsDraftConfirmation(
                                draftId = draft.id,
                                roleCard = roleCard?.let { card ->
                                    if (update == null) card else card.copy(textEntries = RpEntryDrafts.toTextEntries(roleCardEntries))
                                },
                                worldSetting = worldSetting,
                                writingStyle = writingStyle,
                                protagonist = protagonist,
                                supportingCharacter = supporting?.copy(
                                    entries = RpEntryDrafts.toTextEntries(supportingEntries),
                                ),
                                bindToConversation = bindToConversation && update == null,
                                protagonistProfile = profile?.let { hero ->
                                    // 文本词条在前、状态条在后，与保存时的落库顺序一致。
                                    hero.copy(
                                        entries = RpEntryDrafts.toTextEntries(profileEntries) +
                                            hero.entries.filterIsInstance<RpEntry.Numeric>(),
                                    )
                                },
                            ),
                        )
                    },
                    enabled = canSave && entriesValid && !isSaving,
                ) {
                    Text(
                        when {
                            isSaving -> "保存中…"
                            update != null -> "确认修改"
                            else -> "保存"
                        },
                    )
                }
            }
        },
    ) {
            Text(
                text = draft.pendingLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (update != null) {
                    "这是 AI 对「${update.name}」提出的修改，还没有生效。下面是修改后的完整内容，" +
                        "你可以继续调整，确认后会覆盖原设定。"
                } else {
                    "这是 AI 生成的草稿，还没有保存。你可以直接修改，确认后才会写入。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            update?.let { UpdateSummary(it) }

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
                DraftField("禁止事项与边界", card.boundaries, minLines = 2) {
                    roleCard = card.copy(boundaries = it)
                }
                if (update != null) {
                    TextEntriesEditor(
                        entries = roleCardEntries,
                        onChange = { roleCardEntries = it },
                        description = "角色卡的文本词条。",
                    )
                }
                if (card.metricDefinitions.isNotEmpty()) {
                    MetricSummary(card.metricDefinitions)
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

            protagonist?.let { hero ->
                DraftField("主角名字", hero.name) { protagonist = hero.copy(name = it) }
                DraftField("主角描述", hero.description, minLines = 4) {
                    protagonist = hero.copy(description = it)
                }
                DraftField("初始状态", hero.initialState, minLines = 3) {
                    protagonist = hero.copy(initialState = it)
                }
                if (draft.conversationId == null) {
                    Text(
                        text = "当前没有会话，主角设定无处保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            profile?.let { hero ->
                DraftField("主角名字", hero.name) { profile = hero.copy(name = it) }
                DraftField("主角简介", hero.description, minLines = 4) { profile = hero.copy(description = it) }
                TextEntriesEditor(
                    entries = profileEntries,
                    onChange = { profileEntries = it },
                    description = "主角档案的文本词条。",
                )
                val metrics = hero.entries.filterIsInstance<RpEntry.Numeric>().map { it.toMetricDefinition() }
                if (metrics.isNotEmpty()) MetricSummary(metrics)
            }

            supporting?.let { character ->
                DraftField("配角名称", character.name) { supporting = character.copy(name = it) }
                DraftField("配角简介", character.description, minLines = 3) {
                    supporting = character.copy(description = it)
                }
                TextEntriesEditor(
                    entries = supportingEntries,
                    onChange = { supportingEntries = it },
                    description = "配角只有文本词条，没有状态数值。",
                )
            }

            if (update == null) Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = bindToConversation,
                    onCheckedChange = { bindToConversation = it },
                    enabled = draft.conversationId != null && !bindingRequired,
                )
                Column {
                    Text(
                        text = if (supporting != null) "保存后在当前会话勾选这个配角" else "保存后绑定到当前会话",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = if (bindingRequired) {
                            "主角是会话设置的一部分，只能保存到当前会话。绑定不会自动开启 RP 模式。"
                        } else {
                            "绑定不会自动开启 RP 模式。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
private fun UpdateSummary(update: SettingsUpdateTarget) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "本次修改：",
            style = MaterialTheme.typography.labelLarge,
        )
        update.changes.forEach { change ->
            Text(text = "· $change", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = if (update.usageCount > 0) {
                "这项设定被 ${update.usageCount} 个会话使用，确认后这些会话都会用上新内容。"
            } else {
                "目前没有会话使用这项设定。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (update.resetMetricNames.isNotEmpty()) {
            Text(
                text = "状态条「${update.resetMetricNames.joinToString("」「")}」的定义被修改：" +
                    "所有使用这项设定的会话里，它们会回到新的初始值，历史回合里的旧数值也会清除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MetricSummary(metricDefinitions: List<RpMetricDefinition>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "状态条（${metricDefinitions.size} 个）",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        metricDefinitions.forEach { definition ->
            Text(
                text = "· ${definition.name}：${definition.minValue}~${definition.maxValue}，" +
                    "初始 ${definition.initialValue}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "状态条在这里只读，保存后可在编辑器里进一步调整。",
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
