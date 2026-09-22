package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.RoleCardDraft
import com.muxuechat.domain.model.RpMetricDefinition
import java.util.UUID

/**
 * 角色卡编辑底部 Sheet。
 *
 * initialDraft 为空表示新增，传入已有草稿代表编辑。
 * 名称为空时禁用保存。所有回调由上层 ViewModel 处理持久化。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleCardEditorSheet(
    isEditing: Boolean,
    initialDraft: RoleCardDraft,
    onSave: (RoleCardDraft) -> Unit,
    onDismiss: () -> Unit,
) {

    var name by remember(initialDraft) { mutableStateOf(initialDraft.name) }
    var description by remember(initialDraft) { mutableStateOf(initialDraft.description) }
    var persona by remember(initialDraft) { mutableStateOf(initialDraft.persona) }
    var speakingStyle by remember(initialDraft) { mutableStateOf(initialDraft.speakingStyle) }
    var userIdentity by remember(initialDraft) { mutableStateOf(initialDraft.userIdentity) }
    var openingMessage by remember(initialDraft) { mutableStateOf(initialDraft.openingMessage) }
    var boundaries by remember(initialDraft) { mutableStateOf(initialDraft.boundaries) }
    var metricDefinitions by remember(initialDraft) { mutableStateOf(initialDraft.metricDefinitions) }

    val canSave = name.trim().isNotEmpty()

    fun updateMetric(index: Int, metric: RpMetricDefinition) {
        metricDefinitions = metricDefinitions.toMutableList().also { it[index] = metric }
    }

    val guardedDismiss = rememberGuardedDismiss(name != initialDraft.name || description != initialDraft.description || persona != initialDraft.persona || speakingStyle != initialDraft.speakingStyle || userIdentity != initialDraft.userIdentity || openingMessage != initialDraft.openingMessage || boundaries != initialDraft.boundaries || metricDefinitions != initialDraft.metricDefinitions, onDismiss)
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
                            RoleCardDraft(
                                name = name.trim(),
                                description = description.trim(),
                                persona = persona.trim(),
                                speakingStyle = speakingStyle.trim(),
                                userIdentity = userIdentity.trim(),
                                openingMessage = openingMessage.trim(),
                                boundaries = boundaries.trim(),
                                metricDefinitions = metricDefinitions,
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
                text = if (isEditing) "编辑角色卡" else "新建角色卡",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )

            FormField(
                label = "角色名称",
                required = true,
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = "例如：艾琳",
            )

            FormField(
                label = "简介",
                value = description,
                onValueChange = { description = it },
                minLines = 2,
                maxLines = 4,
                placeholder = "一句话介绍，如：来自星港的领航员",
            )

            FormField(
                label = "人设描述",
                value = persona,
                onValueChange = { persona = it },
                minLines = 4,
                maxLines = 12,
                placeholder = "详细描述角色的背景、性格、动机……",
            )

            FormField(
                label = "说话风格",
                value = speakingStyle,
                onValueChange = { speakingStyle = it },
                minLines = 2,
                maxLines = 6,
                placeholder = "语气、用词偏好、常见口癖等",
            )

            FormField(
                label = "用户身份",
                value = userIdentity,
                onValueChange = { userIdentity = it },
                minLines = 2,
                maxLines = 6,
                placeholder = "角色眼中你是谁（身份、关系、昵称）",
            )

            FormField(
                label = "开场白",
                value = openingMessage,
                onValueChange = { openingMessage = it },
                minLines = 2,
                maxLines = 8,
                placeholder = "角色第一次出场的话",
            )

            FormField(
                label = "禁止事项 / 边界",
                value = boundaries,
                onValueChange = { boundaries = it },
                minLines = 2,
                maxLines = 8,
                placeholder = "角色拒绝谈论的内容或行为边界",
            )

            MetricDefinitionsSection(
                metrics = metricDefinitions,
                onAdd = {
                    metricDefinitions = metricDefinitions + RpMetricDefinition(
                        id = UUID.randomUUID().toString(),
                        name = "好感度",
                        minValue = 0,
                        maxValue = 100,
                        initialValue = 0,
                        description = "角色对用户的亲近、信赖和情感投入程度。",
                        progressRule = "0 表示陌生或排斥，50 表示熟悉，100 表示强烈依恋。",
                    )
                },
                onUpdate = ::updateMetric,
                onDelete = { index ->
                    metricDefinitions = metricDefinitions.toMutableList().also { it.removeAt(index) }
                },
            )


    }
}

@Composable
private fun MetricDefinitionsSection(
    metrics: List<RpMetricDefinition>,
    onAdd: () -> Unit,
    onUpdate: (Int, RpMetricDefinition) -> Unit,
    onDelete: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "状态栏定义",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "定义这个角色需要维护的状态条；当前数值会按会话独立保存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        metrics.forEachIndexed { index, metric ->
            MetricDefinitionEditor(
                index = index,
                metric = metric,
                onChange = { onUpdate(index, it) },
                onDelete = { onDelete(index) },
            )
        }

        OutlinedButton(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "添加状态条",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun MetricDefinitionEditor(
    index: Int,
    metric: RpMetricDefinition,
    onChange: (RpMetricDefinition) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "状态条 ${index + 1}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "删除状态条",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        FormField(
            label = "状态名称",
            value = metric.name,
            onValueChange = { onChange(metric.copy(name = it)) },
            singleLine = true,
            placeholder = "例如：好感度、信任度、警戒心",
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            NumberField(
                label = "最小值",
                value = metric.minValue,
                onValueChange = { value ->
                    val safeMax = if (metric.maxValue > value) metric.maxValue else value + 1
                    onChange(
                        metric.copy(
                            minValue = value,
                            maxValue = safeMax,
                            initialValue = metric.initialValue.coerceIn(value, safeMax),
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label = "最大值",
                value = metric.maxValue,
                onValueChange = { value ->
                    val safeMax = if (value > metric.minValue) value else metric.minValue + 1
                    onChange(
                        metric.copy(
                            maxValue = safeMax,
                            initialValue = metric.initialValue.coerceIn(metric.minValue, safeMax),
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label = "初始值",
                value = metric.initialValue,
                onValueChange = { value ->
                    onChange(metric.copy(initialValue = value.coerceIn(metric.minValue, metric.maxValue)))
                },
                modifier = Modifier.weight(1f),
            )
        }

        FormField(
            label = "状态含义",
            value = metric.description,
            onValueChange = { onChange(metric.copy(description = it)) },
            minLines = 2,
            maxLines = 5,
            placeholder = "这个数值代表什么心理、关系或世界状态",
        )

        FormField(
            label = "变化规则",
            value = metric.progressRule,
            onValueChange = { onChange(metric.copy(progressRule = it)) },
            minLines = 2,
            maxLines = 5,
            placeholder = "例如：0 表示陌生，50 表示信任，100 表示强烈依恋；只有在明确互动后才变化",
        )
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value = value.toString(),
            onValueChange = { text ->
                text.toIntOrNull()?.let(onValueChange)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .heightIn(min = 56.dp),
        )
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    required: Boolean = false,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    placeholder: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (required) {
                Text(
                    text = " *",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            maxLines = if (singleLine) 1 else maxLines,
            placeholder = placeholder?.let { { Text(it) } },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .heightIn(min = 56.dp),
        )
    }
}
