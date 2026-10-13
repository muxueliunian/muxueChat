package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.context.ContextWindowPlan
import com.muxuechat.domain.model.SupportingCharacter
import com.muxuechat.domain.prompt.SupportingCharacterPrompt
import com.muxuechat.presentation.common.formatCompactTokens
import com.muxuechat.presentation.theme.Corner
import com.muxuechat.presentation.theme.MinIconTouchTarget
import com.muxuechat.presentation.theme.MinTouchTarget
import com.muxuechat.presentation.theme.Spacing

/** 已选数量达到这个数时给辅助提醒（§6.2 建议值）；只是提醒，不阻止保存（R12）。 */
internal const val SUPPORTING_CHARACTER_COUNT_HINT = 6

/**
 * RP 设置里的配角段（RP-3c）：多选、按勾选顺序排序、预计占用。
 *
 * 已选的配角按 [selectedIds] 的顺序排在前面并可上下移动，未选的排在后面。
 * 主警告看 [draftPlan]（按整份草稿规划的结果），数量阈值只是辅助提醒（§6.2）。
 */
@Composable
internal fun RpSupportingCharacterSection(
    characters: List<SupportingCharacter>,
    usage: Map<String, Int>,
    selectedIds: List<String>,
    isRpEnabled: Boolean,
    draftPlan: ContextWindowPlan?,
    onSelectedIdsChange: (List<String>) -> Unit,
    onCreate: () -> Unit,
    onEdit: (SupportingCharacter) -> Unit,
) {
    val byId = characters.associateBy { it.id }
    // 草稿里可能留着刚被删掉的 id，展示与计数都只认还存在的。
    val selected = selectedIds.mapNotNull { byId[it] }
    val unselected = characters.filter { it.id !in selectedIds }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        Text(
            text = "配角常驻写入正文 prompt，按下面的顺序出现；不进状态栏，也不参与内心独白与剧情建议。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SupportingBudgetSummary(
            selected = selected,
            isRpEnabled = isRpEnabled,
            draftPlan = draftPlan,
        )

        selected.forEachIndexed { index, character ->
            SupportingCharacterRow(
                character = character,
                usageCount = usage[character.id] ?: 0,
                checked = true,
                enabled = isRpEnabled,
                position = index + 1,
                canMoveUp = index > 0,
                canMoveDown = index < selected.lastIndex,
                onCheckedChange = { onSelectedIdsChange(selected.map { it.id } - character.id) },
                onMoveUp = { onSelectedIdsChange(selected.map { it.id }.swapped(index, index - 1)) },
                onMoveDown = { onSelectedIdsChange(selected.map { it.id }.swapped(index, index + 1)) },
                onEdit = { onEdit(character) },
            )
        }
        unselected.forEach { character ->
            SupportingCharacterRow(
                character = character,
                usageCount = usage[character.id] ?: 0,
                checked = false,
                enabled = isRpEnabled,
                position = null,
                canMoveUp = false,
                canMoveDown = false,
                onCheckedChange = { onSelectedIdsChange(selected.map { it.id } + character.id) },
                onMoveUp = {},
                onMoveDown = {},
                onEdit = { onEdit(character) },
            )
        }

        OutlinedButton(
            onClick = onCreate,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = MinTouchTarget),
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(text = "新建配角", modifier = Modifier.padding(start = Spacing.Small))
        }
    }
}

@Composable
private fun SupportingBudgetSummary(
    selected: List<SupportingCharacter>,
    isRpEnabled: Boolean,
    draftPlan: ContextWindowPlan?,
) {
    if (!isRpEnabled) return
    val selectedTokens = selected.sumOf(SupportingCharacterPrompt::estimateTokens)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        if (selected.isNotEmpty()) {
            Text(
                text = "已选 ${selected.size} 个配角，预计每轮占用约 ${formatCompactTokens(selectedTokens)} tokens",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (selected.size >= SUPPORTING_CHARACTER_COUNT_HINT) {
            Text(
                text = "⚠ 配角较多：每一个都会随每轮请求发送。这只是提醒，仍可保存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        val overflow = draftPlan?.overflowError
        when {
            draftPlan == null -> Unit
            overflow != null -> Text(
                text = "⚠ 按当前选择，固定内容已超出模型的输入预算，发送会失败：$overflow",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Text(
                text = "按当前选择，下一轮预计输入 ${formatCompactTokens(draftPlan.estimatedInputTokens)} / " +
                    "${formatCompactTokens(draftPlan.inputBudgetTokens)}（${draftPlan.usagePercent}%）。" +
                    "数字为预计值，超出模型预算时仍会发送失败。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SupportingCharacterRow(
    character: SupportingCharacter,
    usageCount: Int,
    checked: Boolean,
    enabled: Boolean,
    position: Int?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
) {
    val name = character.name.ifBlank { "未命名配角" }
    val tokens = SupportingCharacterPrompt.estimateTokens(character)
    val subtitle = listOfNotNull(
        position?.let { "第 $it 位" },
        "约 ${formatCompactTokens(tokens)} tokens",
        "$usageCount 个会话在用".takeIf { usageCount > 0 },
        character.description.ifBlank { null },
    ).joinToString(" · ")
    val background = if (checked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(Corner.Control)
            .background(background)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(start = Spacing.Tight, end = Spacing.Tight, top = Spacing.Small, bottom = Spacing.Small),
    ) {
        // 整行可点，复选框本身不再单独响应，免得读屏把一行读成两个控件。
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.Small),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (checked) {
            IconButton(
                onClick = onMoveUp,
                enabled = enabled && canMoveUp,
                modifier = Modifier.size(MinIconTouchTarget),
            ) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "把配角「$name」上移")
            }
            IconButton(
                onClick = onMoveDown,
                enabled = enabled && canMoveDown,
                modifier = Modifier.size(MinIconTouchTarget),
            ) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "把配角「$name」下移")
            }
        }
        IconButton(
            onClick = onEdit,
            modifier = Modifier.size(MinIconTouchTarget),
        ) {
            Icon(
                imageVector = Icons.Outlined.EditNote,
                contentDescription = "编辑配角「$name」",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun <T> List<T>.swapped(a: Int, b: Int): List<T> =
    toMutableList().also { list ->
        val item = list[a]
        list[a] = list[b]
        list[b] = item
    }
