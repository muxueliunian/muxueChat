package com.muxuechat.presentation.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.muxuechat.domain.model.RpEntryOwner
import com.muxuechat.domain.model.RpMetric
import com.muxuechat.domain.model.RpMetricSnapshot
import com.muxuechat.domain.model.RpStatusPanel
import com.muxuechat.domain.rp.RpStateBlockFormat
import com.muxuechat.presentation.common.components.EditorSheet
import com.muxuechat.presentation.common.components.rememberGuardedDismiss
import com.muxuechat.presentation.theme.Corner
import com.muxuechat.presentation.theme.MinTouchTarget
import com.muxuechat.presentation.theme.Spacing

/** 仅展示会话快照并提交单项修正；状态条归属只认快照登记，不猜线上名称前缀。 */
@Composable
fun RpStatusPanelCard(
    panel: RpStatusPanel,
    roleCardName: String?,
    metricSnapshot: List<RpMetricSnapshot>,
    modifier: Modifier = Modifier,
    onEditMetric: (RpMetricSnapshot, Int) -> Unit = { _, _ -> },
    editingEnabled: Boolean = true,
    disabledReason: String? = null,
) {
    var expanded by rememberSaveable(panel.conversationId) { mutableStateOf(false) }
    var selectedOwner by rememberSaveable(panel.conversationId) { mutableStateOf<String?>(null) }
    var selectedEntryId by rememberSaveable(panel.conversationId) { mutableStateOf("") }
    var selectedWireName by rememberSaveable(panel.conversationId) { mutableStateOf("") }
    var selectedMin by rememberSaveable(panel.conversationId) { mutableIntStateOf(0) }
    var selectedMax by rememberSaveable(panel.conversationId) { mutableIntStateOf(0) }
    var selectedLabel by rememberSaveable(panel.conversationId) { mutableStateOf("") }
    var initialValue by rememberSaveable(panel.conversationId) { mutableIntStateOf(0) }
    val relationship = RpStateBlockFormat.meaningfulText(panel.relationship)
    val scene = RpStateBlockFormat.meaningfulText(panel.scene)
    val resolvedRows = panel.metrics.map { metric ->
        val definition = metricSnapshot.filter { it.wireName == metric.name }.singleOrNull()
            ?.takeIf { entry -> RpEntryOwner.entries.any { it.storageValue == entry.owner } }
        metric to definition
    }

    Column(
        modifier = modifier.fillMaxWidth()
            .padding(start = Spacing.Content, end = Spacing.Content, bottom = Spacing.Tight)
            .clip(Corner.Container).background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .clickable(onClickLabel = if (expanded) "折叠状态面板" else "展开状态面板") { expanded = !expanded }
                .defaultMinSize(minHeight = MinTouchTarget)
                .padding(horizontal = Spacing.Medium, vertical = Spacing.Small),
        ) {
            Icon(
                imageVector = Icons.Outlined.TheaterComedy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(MinTouchTarget / 2),
            )
            Column(Modifier.weight(1f).padding(horizontal = Spacing.Small)) {
                Text(
                    text = roleCardName?.takeIf { it.isNotBlank() }?.let { "状态面板 · $it" } ?: "状态面板",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = scene.ifBlank { relationship }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = Spacing.Medium, end = Spacing.Medium, bottom = Spacing.Medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.Small),
            ) {
                if (scene.isNotBlank()) LabeledText("场景", scene)
                if (relationship.isNotBlank()) LabeledText("关系", relationship)
                if (!editingEnabled) {
                    Text(
                        text = disabledReason?.takeIf { it.isNotBlank() } ?: "当前暂不能修改状态数值",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RpEntryOwner.entries.forEach { owner ->
                    val rows = resolvedRows.filter { it.second?.owner == owner.storageValue }
                    if (rows.isNotEmpty()) {
                        Text(owner.wirePrefix, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = Spacing.Small))
                        rows.forEach { (metric, definition) ->
                            val entry = requireNotNull(definition)
                            val unambiguous = RpMetricEditPolicy.findDefinition(
                                metricSnapshot, entry.owner, entry.entryId, entry.wireName,
                            ) != null && panel.metrics.count { it.name == metric.name } == 1
                            val definitionError = RpMetricEditPolicy.editingError(entry)
                            val rowEnabled = editingEnabled && unambiguous && definitionError == null
                            MetricRow(metric, entry.displayName, owner.wirePrefix,
                                entry.minValue, entry.maxValue, rowEnabled,
                                definitionError ?: if (!unambiguous) "定义存在重复，请先同步定义" else disabledReason,
                            ) {
                                selectedOwner = entry.owner
                                selectedEntryId = entry.entryId
                                selectedWireName = entry.wireName
                                selectedMin = entry.minValue
                                selectedMax = entry.maxValue
                                selectedLabel = "${owner.wirePrefix} · ${entry.displayName}"
                                initialValue = metric.value
                            }
                        }
                    }
                }
                val unmatched = resolvedRows.filter { it.second == null }
                if (unmatched.isNotEmpty()) {
                    Text("未匹配状态条", style = MaterialTheme.typography.labelLarge)
                    Text("这些状态条没有唯一的会话定义，请先同步定义后再修改。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    unmatched.forEach { (metric, _) ->
                        MetricRow(metric, metric.name, "未匹配", metric.min, metric.max,
                            enabled = false, disabledReason = "请先同步定义", onClick = {})
                    }
                }
            }
        }
    }

    selectedOwner?.let { owner ->
        val currentDefinition = RpMetricEditPolicy.findDefinition(metricSnapshot, owner, selectedEntryId, selectedWireName)
        val definitionUnchanged = currentDefinition?.let {
            it.wireName == selectedWireName && it.minValue == selectedMin && it.maxValue == selectedMax
        } == true
        val definitionError = currentDefinition?.let(RpMetricEditPolicy::editingError)
        val editorEnabled = editingEnabled && definitionUnchanged && definitionError == null
        RpMetricValueEditor(
            identity = "${panel.conversationId}:$owner:$selectedEntryId:$selectedWireName",
            label = selectedLabel,
            initialValue = initialValue,
            min = selectedMin,
            max = selectedMax,
            enabled = editorEnabled,
            disabledReason = if (!definitionUnchanged) "状态条定义已变化，请取消后重新打开"
                else definitionError ?: disabledReason?.takeIf { it.isNotBlank() } ?: "当前暂不能修改状态数值",
            onDismiss = { selectedOwner = null },
            onSave = { value ->
                if (editorEnabled) {
                    onEditMetric(currentDefinition, value)
                    selectedOwner = null
                }
            },
        )
    }
}

@Composable
private fun LabeledText(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.Tight))
    }
}

@Composable
private fun MetricRow(
    metric: RpMetric,
    label: String,
    ownerLabel: String,
    min: Int,
    max: Int,
    enabled: Boolean,
    disabledReason: String?,
    onClick: () -> Unit,
) {
    // 用 Long 做差：负范围及 Int 极值不能在进度计算中溢出。
    val width = max.toLong() - min.toLong()
    val progress = if (width > 0) ((metric.value.toLong() - min.toLong()).toDouble() / width).coerceIn(0.0, 1.0).toFloat()
        else 0f
    val rangeInvalid = min > max
    Column(
        Modifier.fillMaxWidth().clip(Corner.Control)
            .clickable(enabled = enabled, role = Role.Button,
                onClickLabel = "编辑$ownerLabel$label，当前 ${metric.value}，范围 $min 到 $max", onClick = onClick)
            .defaultMinSize(minHeight = MinTouchTarget)
            .padding(vertical = Spacing.Small)
            .semantics(mergeDescendants = true) {
                contentDescription = "$ownerLabel · $label，当前值 ${metric.value}，范围 $min 到 $max"
                stateDescription = if (enabled) "点按修改"
                    else if (rangeInvalid) "状态条范围无效，请先同步定义"
                    else disabledReason?.takeIf { it.isNotBlank() } ?: "当前暂不能修改"
            },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(metric.value.toString(), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Spacing.Small))
        }
        Text("范围 $min 到 $max${if (enabled) " · 点按修改" else ""}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(
            progress = { progress },
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.Tight).height(Spacing.Tight).clip(Corner.Control),
        )
        if (rangeInvalid || metric.value.toLong() !in min.toLong()..max.toLong()) {
            Text(if (rangeInvalid) "状态条范围无效，请先同步定义" else "当前数值超出范围，请修改",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        if (!enabled && !disabledReason.isNullOrBlank()) {
            Text(disabledReason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RpMetricValueEditor(
    identity: String,
    label: String,
    initialValue: Int,
    min: Int,
    max: Int,
    enabled: Boolean,
    disabledReason: String,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var draft by rememberSaveable(identity) { mutableStateOf(initialValue.toString()) }
    val validation = RpMetricEditPolicy.validate(draft, min, max)
    val dirty = draft != initialValue.toString()
    val guardedDismiss = rememberGuardedDismiss(dirty, onDismiss)
    EditorSheet(
        onDismiss = guardedDismiss,
        actions = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Small, Alignment.End),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = guardedDismiss) { Text("取消") }
                Button(onClick = { validation.number?.let(onSave) },
                    enabled = enabled && dirty && validation.number != null) { Text("保存数值") }
            }
        },
    ) {
        Text("修改状态数值", style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text("当前值 $initialValue · 允许范围 $min 到 $max", style = MaterialTheme.typography.bodyMedium)
        Text("仅修正当前值，后续剧情仍可改变。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("仅修改本会话当前状态，角色和主角档案的初始值保持原样。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("$label 的数值") },
            singleLine = true,
            isError = validation.error != null,
            supportingText = { Text(validation.error ?: "请输入整数，负数保留负号") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!enabled) {
            Text(disabledReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
