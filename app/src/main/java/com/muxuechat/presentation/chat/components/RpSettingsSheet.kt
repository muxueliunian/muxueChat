package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.muxuechat.presentation.common.components.EditorSheet
import com.muxuechat.presentation.common.components.rememberGuardedDismiss
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.ProtagonistProfile
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.SupportingCharacter
import com.muxuechat.domain.context.ContextWindowPlan
import com.muxuechat.domain.model.RpMarkerMode
import com.muxuechat.domain.model.RpNarrativePerspective
import com.muxuechat.domain.model.RpPlotSuggestionPerspective
import com.muxuechat.domain.model.WorldSetting
import com.muxuechat.domain.model.WritingStyleProfile

/**
 * RP 主设置底部 Sheet。
 *
 * 展示当前会话 RP 开关、角色卡和世界观的选择列表，以及每项的编辑/删除入口。
 *
 * 编辑模式：草稿由上层传入，本组件只负责展示和上报变更。只有点击底部的“保存”
 * 按钮才会通过 onSave 一次性上抛；点击“取消”或关闭 Sheet 时由上层丢弃草稿。
 *
 * 角色卡/世界观本身的增删改仍走原有即时回调——它们是全局对象，不参与草稿。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RpSettingsSheet(
    initialRpEnabled: Boolean,
    initialRoleCardId: String?,
    initialWorldSettingId: String?,
    initialWritingStyleId: String?,
    initialProtagonistProfileId: String?,
    initialSupportingCharacterIds: List<String>,
    initialNarrativeMinChars: Int,
    initialNarrativePerspective: RpNarrativePerspective,
    initialPlotSuggestionPerspective: RpPlotSuggestionPerspective,
    initialRpMarkerMode: RpMarkerMode,
    draftRpEnabled: Boolean,
    draftRoleCardId: String?,
    draftWorldSettingId: String?,
    draftWritingStyleId: String?,
    draftProtagonistProfileId: String?,
    draftSupportingCharacterIds: List<String>,
    draftNarrativeMinCharsText: String,
    draftNarrativePerspective: RpNarrativePerspective,
    draftPlotSuggestionPerspective: RpPlotSuggestionPerspective,
    draftRpMarkerMode: RpMarkerMode,
    showRpMarkerOption: Boolean,
    isRpMarkerAvailable: Boolean,
    rpMarkerUnavailableReason: String?,
    roleCards: List<RoleCard>,
    worldSettings: List<WorldSetting>,
    writingStyles: List<WritingStyleProfile>,
    protagonistProfiles: List<ProtagonistProfile>,
    /** 档案 id → 绑定它的会话数。 */
    protagonistProfileUsage: Map<String, Int>,
    supportingCharacters: List<SupportingCharacter>,
    supportingCharacterUsage: Map<String, Int>,
    /** 按整份草稿规划的上下文；null 表示还没算出来。 */
    draftContextPlan: ContextWindowPlan?,
    onDraftRpEnabledChange: (Boolean) -> Unit,
    onDraftRoleCardIdChange: (String?) -> Unit,
    onDraftWorldSettingIdChange: (String?) -> Unit,
    onDraftWritingStyleIdChange: (String?) -> Unit,
    onDraftProtagonistProfileIdChange: (String?) -> Unit,
    onDraftSupportingCharacterIdsChange: (List<String>) -> Unit,
    onDraftNarrativeMinCharsTextChange: (String) -> Unit,
    onDraftNarrativePerspectiveChange: (RpNarrativePerspective) -> Unit,
    onDraftPlotSuggestionPerspectiveChange: (RpPlotSuggestionPerspective) -> Unit,
    onDraftRpMarkerModeChange: (RpMarkerMode) -> Unit,
    onSave: (
        isRpEnabled: Boolean,
        roleCardId: String?,
        worldSettingId: String?,
        writingStyleId: String?,
        protagonistProfileId: String?,
        // 勾选没变时为 null，保存时不动关联表。
        supportingCharacterIds: List<String>?,
        narrativeMinChars: Int,
        narrativePerspective: RpNarrativePerspective,
        plotSuggestionPerspective: RpPlotSuggestionPerspective,
        rpMarkerMode: RpMarkerMode,
    ) -> Unit,
    onCreateRoleCard: () -> Unit,
    onEditRoleCard: (RoleCard) -> Unit,
    onDeleteRoleCard: (RoleCard) -> Unit,
    onCreateProtagonist: () -> Unit,
    onEditProtagonist: (ProtagonistProfile) -> Unit,
    onDeleteProtagonist: (ProtagonistProfile) -> Unit,
    onCreateSupportingCharacter: () -> Unit,
    onEditSupportingCharacter: (SupportingCharacter) -> Unit,
    onCreateWorldSetting: () -> Unit,
    onEditWorldSetting: (WorldSetting) -> Unit,
    onDeleteWorldSetting: (WorldSetting) -> Unit,
    onCreateWritingStyle: () -> Unit,
    onEditWritingStyle: (WritingStyleProfile) -> Unit,
    onDeleteWritingStyle: (WritingStyleProfile) -> Unit,
    onDismiss: () -> Unit,
    metricSyncContent: @Composable () -> Unit = {},
) {
    val effectiveDraftRpMarkerMode = if (showRpMarkerOption && isRpMarkerAvailable) {
        draftRpMarkerMode
    } else {
        RpMarkerMode.DISABLED
    }

    val hasChanges = draftRpEnabled != initialRpEnabled ||
        draftRoleCardId != initialRoleCardId ||
        draftWorldSettingId != initialWorldSettingId ||
        draftWritingStyleId != initialWritingStyleId ||
        draftProtagonistProfileId != initialProtagonistProfileId ||
        draftSupportingCharacterIds != initialSupportingCharacterIds ||
        draftNarrativeMinCharsText.toNarrativeMinChars() != initialNarrativeMinChars ||
        draftNarrativePerspective != initialNarrativePerspective ||
        draftPlotSuggestionPerspective != initialPlotSuggestionPerspective ||
        effectiveDraftRpMarkerMode != initialRpMarkerMode

    val guardedDismiss = rememberGuardedDismiss(hasChanges, onDismiss)
    EditorSheet(
        onDismiss = guardedDismiss,
        actions = {
            SheetActions(
                canSave = hasChanges,
                onCancel = guardedDismiss,
                onSave = {
                    onSave(
                        draftRpEnabled,
                        draftRoleCardId,
                        draftWorldSettingId,
                        draftWritingStyleId,
                        draftProtagonistProfileId,
                        draftSupportingCharacterIds.takeIf { it != initialSupportingCharacterIds },
                        draftNarrativeMinCharsText.toNarrativeMinChars(),
                        draftNarrativePerspective,
                        draftPlotSuggestionPerspective,
                        effectiveDraftRpMarkerMode,
                    )
                },
            )

        },
    ) {
            SheetHeader()

            RpToggleRow(
                isRpEnabled = draftRpEnabled,
                onToggleRp = onDraftRpEnabledChange,
            )

            SectionTitle(
                icon = Icons.Outlined.TheaterComedy,
                title = "角色卡",
            )
            RoleCardSection(
                roleCards = roleCards,
                selectedId = draftRoleCardId,
                isRpEnabled = draftRpEnabled,
                onSelect = onDraftRoleCardIdChange,
                onCreate = onCreateRoleCard,
                onEdit = onEditRoleCard,
                onDelete = onDeleteRoleCard,
            )

            SectionTitle(
                icon = Icons.Outlined.Person,
                title = "主角档案",
            )
            ProtagonistProfileSection(
                profiles = protagonistProfiles,
                usage = protagonistProfileUsage,
                selectedId = draftProtagonistProfileId,
                isRpEnabled = draftRpEnabled,
                onSelect = onDraftProtagonistProfileIdChange,
                onCreate = onCreateProtagonist,
                onEdit = onEditProtagonist,
                onDelete = onDeleteProtagonist,
            )
            metricSyncContent()

            SectionTitle(
                icon = Icons.Outlined.Groups,
                title = "配角",
            )
            RpSupportingCharacterSection(
                characters = supportingCharacters,
                usage = supportingCharacterUsage,
                selectedIds = draftSupportingCharacterIds,
                isRpEnabled = draftRpEnabled,
                draftPlan = draftContextPlan,
                onSelectedIdsChange = onDraftSupportingCharacterIdsChange,
                onCreate = onCreateSupportingCharacter,
                onEdit = onEditSupportingCharacter,
            )

            SectionTitle(
                icon = Icons.Outlined.EditNote,
                title = "剧情正文",
            )
            NarrativeRequirementSection(
                minCharsText = draftNarrativeMinCharsText,
                onMinCharsTextChange = onDraftNarrativeMinCharsTextChange,
            )

            SectionTitle(
                icon = Icons.Outlined.EditNote,
                title = "正文叙事视角",
            )
            NarrativePerspectiveSection(
                selected = draftNarrativePerspective,
                onSelect = onDraftNarrativePerspectiveChange,
            )

            SectionTitle(
                icon = Icons.Outlined.TheaterComedy,
                title = "剧情推进建议",
            )
            PlotSuggestionPerspectiveSection(
                selected = draftPlotSuggestionPerspective,
                onSelect = onDraftPlotSuggestionPerspectiveChange,
            )

            if (showRpMarkerOption) {
                SectionTitle(
                    icon = Icons.Outlined.Public,
                    title = "DeepSeek Marker",
                )
                RpMarkerModeSection(
                    selected = effectiveDraftRpMarkerMode,
                    isMarkerAvailable = isRpMarkerAvailable,
                    unavailableReason = rpMarkerUnavailableReason,
                    onSelect = onDraftRpMarkerModeChange,
                )
            }

            SectionTitle(
                icon = Icons.Outlined.Public,
                title = "世界观",
            )
            WorldSettingSection(
                worldSettings = worldSettings,
                selectedId = draftWorldSettingId,
                isRpEnabled = draftRpEnabled,
                onSelect = onDraftWorldSettingIdChange,
                onCreate = onCreateWorldSetting,
                onEdit = onEditWorldSetting,
                onDelete = onDeleteWorldSetting,
            )

            SectionTitle(
                icon = Icons.Outlined.EditNote,
                title = "文风",
            )
            WritingStyleSection(
                writingStyles = writingStyles,
                selectedId = draftWritingStyleId,
                isRpEnabled = draftRpEnabled,
                onSelect = onDraftWritingStyleIdChange,
                onCreate = onCreateWritingStyle,
                onEdit = onEditWritingStyle,
                onDelete = onDeleteWritingStyle,
            )


    }
}

@Composable
private fun SheetActions(
    canSave: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        TextButton(onClick = onCancel) {
            Text("取消")
        }
        Button(
            onClick = onSave,
            enabled = canSave,
        ) {
            Text("保存")
        }
    }
}

@Composable
private fun SheetHeader() {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = "角色扮演",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "为当前会话绑定角色卡、主角与世界观。点击保存后应用到当前会话，下一次发送消息时生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun RpToggleRow(
    isRpEnabled: Boolean,
    onToggleRp: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable { onToggleRp(!isRpEnabled) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "开启 RP",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (isRpEnabled) "当前会话将按照角色卡与世界观进行对话" else "当前会话按普通模式对话",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked = isRpEnabled,
            onCheckedChange = onToggleRp,
        )
    }
}

@Composable
private fun SectionTitle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun RoleCardSection(
    roleCards: List<RoleCard>,
    selectedId: String?,
    isRpEnabled: Boolean,
    onSelect: (String?) -> Unit,
    onCreate: () -> Unit,
    onEdit: (RoleCard) -> Unit,
    onDelete: (RoleCard) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NoneOption(
            label = "不绑定角色卡",
            selected = selectedId == null,
            enabled = isRpEnabled,
            onSelect = { onSelect(null) },
        )
        roleCards.forEach { card ->
            SelectableRow(
                selected = card.id == selectedId,
                enabled = isRpEnabled,
                onSelect = { onSelect(card.id) },
                title = card.name.ifBlank { "未命名角色" },
                subtitle = card.description.ifBlank { null },
                actions = {
                    IconButton(
                        onClick = { onEdit(card) },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.EditNote,
                            contentDescription = "编辑角色卡",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { onDelete(card) },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "删除角色卡",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        }
        CreateButton(text = "新建角色卡", onClick = onCreate)
    }
}

@Composable
private fun WorldSettingSection(
    worldSettings: List<WorldSetting>,
    selectedId: String?,
    isRpEnabled: Boolean,
    onSelect: (String?) -> Unit,
    onCreate: () -> Unit,
    onEdit: (WorldSetting) -> Unit,
    onDelete: (WorldSetting) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NoneOption(
            label = "不绑定世界观",
            selected = selectedId == null,
            enabled = isRpEnabled,
            onSelect = { onSelect(null) },
        )
        worldSettings.forEach { world ->
            SelectableRow(
                selected = world.id == selectedId,
                enabled = isRpEnabled,
                onSelect = { onSelect(world.id) },
                title = world.title.ifBlank { "未命名世界观" },
                subtitle = world.content.take(60).ifBlank { null },
                actions = {
                    IconButton(
                        onClick = { onEdit(world) },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.EditNote,
                            contentDescription = "编辑世界观",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { onDelete(world) },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "删除世界观",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        }
        CreateButton(text = "新建世界观", onClick = onCreate)
    }
}

@Composable
private fun WritingStyleSection(
    writingStyles: List<WritingStyleProfile>,
    selectedId: String?,
    isRpEnabled: Boolean,
    onSelect: (String?) -> Unit,
    onCreate: () -> Unit,
    onEdit: (WritingStyleProfile) -> Unit,
    onDelete: (WritingStyleProfile) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NoneOption(
            label = "不绑定文风",
            selected = selectedId == null,
            enabled = isRpEnabled,
            onSelect = { onSelect(null) },
        )
        writingStyles.forEach { style ->
            SelectableRow(
                selected = style.id == selectedId,
                enabled = isRpEnabled,
                onSelect = { onSelect(style.id) },
                title = style.name.ifBlank { "未命名文风" },
                subtitle = style.content.take(60).ifBlank { null },
                actions = {
                    IconButton(
                        onClick = { onEdit(style) },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.EditNote,
                            contentDescription = "编辑文风",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { onDelete(style) },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "删除文风",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        }
        CreateButton(text = "新建文风", onClick = onCreate)
    }
}

@Composable
private fun ProtagonistProfileSection(
    profiles: List<ProtagonistProfile>,
    usage: Map<String, Int>,
    selectedId: String?,
    isRpEnabled: Boolean,
    onSelect: (String?) -> Unit,
    onCreate: () -> Unit,
    onEdit: (ProtagonistProfile) -> Unit,
    onDelete: (ProtagonistProfile) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "玩家控制的主角，会写入 RP prompt，并作为剧情推进建议的依据。不绑定时视为玩家本人。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NoneOption(
            label = "不绑定主角",
            selected = selectedId == null,
            enabled = isRpEnabled,
            onSelect = { onSelect(null) },
        )
        profiles.forEach { profile ->
            val displayName = profile.displayName()
            val count = usage[profile.id] ?: 0
            SelectableRow(
                selected = profile.id == selectedId,
                enabled = isRpEnabled,
                onSelect = { onSelect(profile.id) },
                title = displayName,
                subtitle = listOfNotNull(
                    "$count 个会话在用".takeIf { count > 0 },
                    profile.description.ifBlank { null },
                ).joinToString(" · ").ifBlank { null },
                actions = {
                    IconButton(
                        onClick = { onEdit(profile) },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.EditNote,
                            contentDescription = "编辑主角「$displayName」",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { onDelete(profile) },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "删除主角「$displayName」",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        }
        CreateButton(text = "新建主角", onClick = onCreate)
    }
}

@Composable
private fun NarrativeRequirementSection(
    minCharsText: String,
    onMinCharsTextChange: (String) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "限制 RP 回复中剧情正文的最低字数。留空或 0 表示不限制，保存后从下一次发送消息开始生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = minCharsText,
            onValueChange = { raw ->
                onMinCharsTextChange(raw.filter(Char::isDigit).take(4))
            },
            label = { Text("正文最低字数") },
            placeholder = { Text("例如：300") },
            supportingText = { Text("上限 5000 字。正文不包含角色内心、剧情推进建议和状态栏 JSON。") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NarrativePerspectiveSection(
    selected: RpNarrativePerspective,
    onSelect: (RpNarrativePerspective) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "控制正文采用哪一侧的叙事重心，不改变用户输入作为临时剧情推进意图的规则。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectableRow(
            selected = selected == RpNarrativePerspective.PROTAGONIST_SECOND_PERSON,
            enabled = true,
            onSelect = { onSelect(RpNarrativePerspective.PROTAGONIST_SECOND_PERSON) },
            title = "主角视角",
            subtitle = "正文以第二人称“你”为中心展开，默认推荐。",
        )
        SelectableRow(
            selected = selected == RpNarrativePerspective.ROLE_CARD,
            enabled = true,
            onSelect = { onSelect(RpNarrativePerspective.ROLE_CARD) },
            title = "角色卡视角",
            subtitle = "允许贴近角色卡人物的感受、判断和必要心理描写。",
        )
    }
}

@Composable
private fun PlotSuggestionPerspectiveSection(
    selected: RpPlotSuggestionPerspective,
    onSelect: (RpPlotSuggestionPerspective) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "只影响建议倾向。用户直接发送或改写后，仍视为临时剧情推进指令。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectableRow(
            selected = selected == RpPlotSuggestionPerspective.PROTAGONIST,
            enabled = true,
            onSelect = { onSelect(RpPlotSuggestionPerspective.PROTAGONIST) },
            title = "主角推进建议",
            subtitle = "优先建议主角说什么、做什么、观察什么或如何回避。",
        )
        SelectableRow(
            selected = selected == RpPlotSuggestionPerspective.ROLE_CARD,
            enabled = true,
            onSelect = { onSelect(RpPlotSuggestionPerspective.ROLE_CARD) },
            title = "角色卡推进建议",
            subtitle = "优先建议角色卡人物如何行动、回应、隐藏情绪或推动剧情。",
        )
    }
}

@Composable
private fun RpMarkerModeSection(
    selected: RpMarkerMode,
    isMarkerAvailable: Boolean,
    unavailableReason: String?,
    onSelect: (RpMarkerMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Marker 只作为 DeepSeek V4 思考模式下的请求态辅助，不写入历史消息。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isMarkerAvailable && !unavailableReason.isNullOrBlank()) {
            Text(
                text = unavailableReason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SelectableRow(
            selected = selected == RpMarkerMode.DISABLED,
            enabled = true,
            onSelect = { onSelect(RpMarkerMode.DISABLED) },
            title = "关闭",
            subtitle = "不注入 RP Marker。",
        )
        SelectableRow(
            selected = selected == RpMarkerMode.ROLE_IMMERSION,
            enabled = isMarkerAvailable,
            onSelect = { onSelect(RpMarkerMode.ROLE_IMMERSION) },
            title = "角色沉浸",
            subtitle = "强化思考模式中的角色内心与角色决策沉浸感。",
        )
        SelectableRow(
            selected = selected == RpMarkerMode.ANALYTICAL,
            enabled = isMarkerAvailable,
            onSelect = { onSelect(RpMarkerMode.ANALYTICAL) },
            title = "纯分析",
            subtitle = "强化思考模式中的结构稳定性与剧情规划。",
        )
    }
}

@Composable
private fun NoneOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .let { if (selected) it.background(MaterialTheme.colorScheme.secondaryContainer) else it }
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            enabled = enabled,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

private fun String.toNarrativeMinChars(): Int =
    filter(Char::isDigit)
        .toIntOrNull()
        ?.coerceIn(0, MAX_NARRATIVE_MIN_CHARS)
        ?: 0

private const val MAX_NARRATIVE_MIN_CHARS = 5000

@Composable
private fun SelectableRow(
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    title: String,
    subtitle: String?,
    actions: @Composable () -> Unit = {},
) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            enabled = enabled,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            actions()
        }
    }
}

@Composable
private fun CreateButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Box(modifier = Modifier.padding(start = 8.dp)) {
            Text(text)
        }
    }
}
