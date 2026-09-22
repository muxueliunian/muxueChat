package com.muxuechat.presentation.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

data class ChoiceOption<T>(
    val value: T,
    val label: String,
    /** 副文案，例如请求体里会发出去的原始值。留空则只显示 [label]。 */
    val supportingLabel: String? = null,
)

@Composable
fun <T> ChoiceRow(
    options: List<ChoiceOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option.value == selected
            val container = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer
            val content = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface
            val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
            else Color.Transparent
            Box(
                modifier = Modifier
                    .widthIn(min = 96.dp)
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(container)
                    .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled) { onSelect(option.value) }
                    .semantics {
                        this.selected = isSelected
                        contentDescription = buildString {
                            append(option.label)
                            option.supportingLabel?.let { append("（$it）") }
                            if (isSelected) append("，已选中")
                            if (!enabled) append("，当前不可用")
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clearAndSetSemantics { },
                ) {
                    Text(
                        text = if (isSelected) "✓ ${option.label}" else option.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (enabled) content else content.copy(alpha = 0.5f),
                    )
                    option.supportingLabel?.let { supporting ->
                        Text(
                            text = supporting,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
