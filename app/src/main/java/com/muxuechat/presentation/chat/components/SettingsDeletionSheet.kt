package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.RpSettingKind
import com.muxuechat.domain.model.RpSettingRef
import com.muxuechat.domain.tool.RpSettingsInventory
import com.muxuechat.domain.tool.RpSettingsListing
import com.muxuechat.domain.tool.SettingsDeletionTarget
import com.muxuechat.domain.tool.SettingsToolDraft
import com.muxuechat.presentation.common.components.EditorSheet
import com.muxuechat.presentation.common.components.rememberGuardedDismiss
import com.muxuechat.presentation.theme.Corner
import com.muxuechat.presentation.theme.Spacing

/**
 * AI 发起的删除请求的确认界面（`delete_rp_settings`）。
 *
 * 模型调用工具只生成这份请求，**用户在这里同意之前不删除任何内容**。
 * 单项显示完整详情；多项显示列表，默认全选，可逐项取消后一起删除。
 * 详情从 [inventory]（实时数据）读取，生成请求后已被删掉的项标注「已不存在」并不再勾选。
 */
@Composable
fun SettingsDeletionSheet(
    draft: SettingsToolDraft,
    inventory: RpSettingsInventory,
    isDeleting: Boolean,
    onConfirm: (draftId: String, refs: List<RpSettingRef>) -> Unit,
    onDiscard: (draftId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val targets = draft.deletion
    val exists = targets.associate { it.ref to (RpSettingsListing.detail(inventory, it.ref) != null) }
    var selected by remember(draft.id) { mutableStateOf(targets.map { it.ref }.filter { exists[it] == true }.toSet()) }
    val effective = selected.filter { exists[it] == true }

    val discardGuard = rememberGuardedDismiss(false, { onDiscard(draft.id) }, enabled = !isDeleting)
    val guardedDismiss = rememberGuardedDismiss(false, onDismiss, enabled = !isDeleting)
    EditorSheet(
        onDismiss = guardedDismiss,
        actions = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
                verticalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = discardGuard, enabled = !isDeleting) { Text("拒绝删除") }
                TextButton(onClick = guardedDismiss, enabled = !isDeleting) { Text("收起") }
                Button(
                    onClick = { onConfirm(draft.id, effective) },
                    enabled = effective.isNotEmpty() && !isDeleting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(
                        when {
                            isDeleting -> "删除中…"
                            targets.size == 1 -> "删除"
                            else -> "删除所选（${effective.size}）"
                        },
                    )
                }
            }
        },
    ) {
        Text(
            text = if (targets.size == 1) {
                val target = targets.single()
                "删除${target.ref.kind.label}「${target.name}」？"
            } else {
                "删除 ${targets.size} 项设定？"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "这是 AI 发起的删除请求，还没有删除任何内容。删除后不能恢复，请逐项核对。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        draft.warnings.forEach { warning ->
            Text(
                text = "⚠ $warning",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (targets.size == 1) {
            val target = targets.single()
            SingleTargetDetail(target, RpSettingsListing.detail(inventory, target.ref))
        } else {
            targets.forEach { target ->
                val alive = exists[target.ref] == true
                TargetRow(
                    target = target,
                    alive = alive,
                    checked = alive && target.ref in selected,
                    enabled = alive && !isDeleting,
                    onCheckedChange = { checked ->
                        selected = if (checked) selected + target.ref else selected - target.ref
                    },
                )
            }
        }
    }
}

@Composable
private fun SingleTargetDetail(target: SettingsDeletionTarget, detail: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        Text(
            text = consequenceOf(target),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (detail == null) {
            Text(
                text = "这一项已经不存在了（可能已被删除），没有可删除的内容。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Corner.Control)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(Spacing.Medium),
            )
        }
    }
}

@Composable
private fun TargetRow(
    target: SettingsDeletionTarget,
    alive: Boolean,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val background = if (checked) MaterialTheme.colorScheme.errorContainer else Color.Transparent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(Corner.Control)
            .background(background)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(horizontal = Spacing.Tight, vertical = Spacing.Small),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.Small),
        ) {
            Text(
                text = "${target.ref.kind.label}「${target.name}」",
                style = MaterialTheme.typography.titleSmall,
                color = if (checked) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (alive) consequenceOf(target) else "已不存在，不会删除",
                style = MaterialTheme.typography.bodySmall,
                color = if (checked) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 删除后会发生什么（第 4 节的转换规则），带上生成请求时的会话数。 */
private fun consequenceOf(target: SettingsDeletionTarget): String {
    val count = target.usageCount
    if (count == 0) {
        return if (target.ref.kind == RpSettingKind.SUPPORTING_CHARACTER) "没有会话勾选它。" else "没有会话在用它。"
    }
    return when (target.ref.kind) {
        RpSettingKind.ROLE_CARD ->
            "有 $count 个会话在用：这些会话会解绑角色卡，清掉角色的状态数值、关系与场景；没绑主角的会话会关闭 RP。"
        RpSettingKind.PROTAGONIST -> "有 $count 个会话在用：这些会话会解绑主角，并清掉主角的状态数值。"
        RpSettingKind.SUPPORTING_CHARACTER -> "有 $count 个会话勾选了它：这些会话会取消勾选。"
        RpSettingKind.WORLD_SETTING -> "有 $count 个会话在用：这些会话会解绑世界观。"
        RpSettingKind.WRITING_STYLE -> "有 $count 个会话在用：这些会话会解绑文风。"
    }
}
