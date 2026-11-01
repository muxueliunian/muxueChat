package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.ReasoningEffort
import com.muxuechat.domain.model.ReasoningEffortCatalog
import com.muxuechat.domain.model.ThinkingMode
import com.muxuechat.presentation.theme.MinTouchTarget
import com.muxuechat.presentation.theme.Spacing

/** 输入栏「思考」菜单里的一项选择。 */
sealed interface ThinkingChoice {
    /** DeepSeek：显式关闭思考（请求里发 `thinking: disabled`）。 */
    data object DeepSeekOff : ThinkingChoice

    data class DeepSeekEffort(val effort: ReasoningEffort) : ThinkingChoice

    /** 自定义供应商的 `reasoning_effort`；空串表示不发送该字段。 */
    data class ProviderEffort(val value: String) : ThinkingChoice
}

data class ThinkingOption(val label: String, val choice: ThinkingChoice)

data class ThinkingControlState(
    val options: List<ThinkingOption>,
    val selected: ThinkingChoice,
    /** 用户显式设了思考档位（DeepSeek 开启 / 自定义供应商会发送字段）。 */
    val isActive: Boolean,
    val chipLabel: String,
    /** 自定义供应商允许手填任意字符串；DeepSeek 只有固定档位。 */
    val allowsCustom: Boolean,
)

/**
 * 当前供应商与模型下的思考选项。
 *
 * DeepSeek 走专属的 thinking 开关 + 三档强度；自定义供应商只管 `reasoning_effort`：
 * 模型在 [ReasoningEffortCatalog] 里就列出已核实的取值，否则给通用预设，另可手填。
 * 当前值不在列表里时（手填过的）也照样列出，不让用户的设置凭空消失。
 */
fun ChatSettings.thinkingControlState(): ThinkingControlState? {
    if (usesDeepSeekParameters) {
        val enabled = thinkingMode == ThinkingMode.ENABLED
        val selected = if (enabled) ThinkingChoice.DeepSeekEffort(reasoningEffort) else ThinkingChoice.DeepSeekOff
        return ThinkingControlState(
            options = listOf(ThinkingOption("关闭", ThinkingChoice.DeepSeekOff)) +
                ReasoningEffort.entries.map { ThinkingOption(effortLabel(it.apiName), ThinkingChoice.DeepSeekEffort(it)) },
            selected = selected,
            isActive = enabled,
            chipLabel = if (enabled) "思考 · ${effortLabel(reasoningEffort.apiName)}" else "思考",
            allowsCustom = false,
        )
    }
    val provider = activeCustomProvider ?: return null
    val current = provider.reasoningEffort.trim()
    val values = ReasoningEffortCatalog.optionsFor(provider.selectedModel) ?: CustomProvider.REASONING_EFFORT_PRESETS
    val listed = if (current.isNotEmpty() && current !in values) values + current else values
    return ThinkingControlState(
        options = listOf(ThinkingOption("默认", ThinkingChoice.ProviderEffort(""))) +
            listed.map { ThinkingOption(effortLabel(it), ThinkingChoice.ProviderEffort(it)) },
        selected = ThinkingChoice.ProviderEffort(current),
        isActive = current.isNotEmpty(),
        chipLabel = if (current.isEmpty()) "思考" else "思考 · ${effortLabel(current)}",
        allowsCustom = true,
    )
}

/** 常见档位给中文名；不认识的原样显示（自填值）。 */
internal fun effortLabel(value: String): String = when (value) {
    "none" -> "不思考"
    "minimal" -> "最少"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "超高"
    "max" -> "最高"
    else -> value
}

/** 输入栏的思考档位按钮：点开菜单直接选档，设置在下一次发送时生效。 */
@Composable
fun ThinkingSelector(
    state: ThinkingControlState,
    onSelect: (ThinkingChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var customDialogVisible by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            onClick = { expanded = true },
            shape = MaterialTheme.shapes.extraLarge,
            color = if (state.isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (state.isActive) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .heightIn(min = 36.dp)
                .semantics { contentDescription = "${state.chipLabel}，点按选择思考档位" },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = Spacing.Medium, vertical = Spacing.Tight),
            ) {
                Icon(Icons.Outlined.Lightbulb, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = state.chipLabel,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = Spacing.Tight),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.options.forEach { option ->
                val selected = option.choice == state.selected
                DropdownMenuItem(
                    text = { Text(option.label) },
                    trailingIcon = if (selected) {
                        { Icon(Icons.Outlined.Check, contentDescription = "已选") }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelect(option.choice)
                    },
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                )
            }
            if (state.allowsCustom) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("自定义…") },
                    onClick = {
                        expanded = false
                        customDialogVisible = true
                    },
                )
            }
        }
    }
    if (customDialogVisible) {
        CustomEffortDialog(
            initial = (state.selected as? ThinkingChoice.ProviderEffort)?.value.orEmpty(),
            onConfirm = { value ->
                customDialogVisible = false
                onSelect(ThinkingChoice.ProviderEffort(value))
            },
            onDismiss = { customDialogVisible = false },
        )
    }
}

@Composable
private fun CustomEffortDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("reasoning_effort") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(CustomProvider.MAX_REASONING_EFFORT_LENGTH) },
                singleLine = true,
                placeholder = { Text("留空则不发送") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
