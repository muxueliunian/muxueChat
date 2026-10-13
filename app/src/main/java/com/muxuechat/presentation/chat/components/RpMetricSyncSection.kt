package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.muxuechat.domain.model.ProtagonistProfile
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RpEntryOwner
import com.muxuechat.domain.model.RpMetricSnapshot
import com.muxuechat.domain.rp.RpEffectiveDefinitions
import com.muxuechat.domain.rp.RpMetricDefinitionDiff
import com.muxuechat.domain.rp.RpMetricGroupDiff
import com.muxuechat.presentation.theme.MinTouchTarget
import com.muxuechat.presentation.theme.Spacing

/**
 * 比较已保存绑定的定义快照与档案最新定义；不根据尚未保存的草稿执行同步。
 * 同步是当前会话的即时操作，回调只上报归属，由 ViewModel 决定实际提交。
 */
@Composable
internal fun RpMetricSyncSection(
    metricSnapshot: List<RpMetricSnapshot>,
    roleCard: RoleCard?,
    protagonistProfile: ProtagonistProfile?,
    onSync: (RpEntryOwner) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledReason: String? = null,
    bindingsSaved: Boolean = true,
) {
    if (roleCard == null && protagonistProfile == null) return
    val reason = when {
        !bindingsSaved -> "请先保存角色与主角的选择，再同步定义。"
        !enabled -> disabledReason?.takeIf { it.isNotBlank() } ?: "当前会话尚未准备好，请稍后再同步。"
        else -> null
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
    ) {
        Text(
            text = "状态条定义同步",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = "档案更新后，本会话继续使用原定义。同步只对当前会话的这一组立即生效，另一组与其它会话不受影响。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        roleCard?.let { card ->
            SyncGroup(
                label = "角色",
                diff = RpMetricDefinitionDiff.between(
                    metricSnapshot,
                    RpEffectiveDefinitions.snapshotOf(RpEntryOwner.ROLE_CARD, card.entries),
                    RpEntryOwner.ROLE_CARD,
                ),
                disabledReason = reason,
                onSync = { onSync(RpEntryOwner.ROLE_CARD) },
            )
        }
        protagonistProfile?.let { profile ->
            SyncGroup(
                label = "主角",
                diff = RpMetricDefinitionDiff.between(
                    metricSnapshot,
                    RpEffectiveDefinitions.snapshotOf(RpEntryOwner.PROTAGONIST, profile.entries),
                    RpEntryOwner.PROTAGONIST,
                ),
                disabledReason = reason,
                onSync = { onSync(RpEntryOwner.PROTAGONIST) },
            )
        }
    }
}

@Composable
private fun SyncGroup(
    label: String,
    diff: RpMetricGroupDiff,
    disabledReason: String?,
    onSync: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Text(
            text = if (diff.hasChanges) {
                "$label：新增 ${diff.added.size} 项，删除 ${diff.removed.size} 项，修改 ${diff.changed.size} 项" +
                    if (diff.orderChanged) "；顺序已调整" else ""
            } else {
                "$label：本会话的状态条定义已与档案一致。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (diff.hasChanges) {
            Text(
                text = "同步保留现有数值，改名仍保值；新增项使用初始值，已删除项移除。若范围缩小，现值会调整到新范围内，历史状态快照也会同步。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (disabledReason != null) {
                Text(
                    text = disabledReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onSync,
                enabled = disabledReason == null,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = MinTouchTarget),
            ) {
                Text("同步${label}定义")
            }
        }
    }
}
