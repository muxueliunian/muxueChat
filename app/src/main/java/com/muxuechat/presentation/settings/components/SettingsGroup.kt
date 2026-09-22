package com.muxuechat.presentation.settings.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muxuechat.presentation.theme.Corner
import com.muxuechat.presentation.theme.MinTouchTarget
import com.muxuechat.presentation.theme.Spacing

/**
 * 设置页的一级分组。
 *
 * 设置项按用户任务分组后仍然很长，所以低频分组默认折叠：折叠时其内容根本不进入组合，
 * 既省掉一屏又一屏的滚动，也顺带避免「不可见的输入框仍在参与重组」。
 *
 * [summary] 是折叠状态下唯一能看到的信息，必须写当前值而不是功能介绍——
 * 否则用户为了确认一个开关状态还得先展开。
 */
@Composable
fun SettingsGroup(
    title: String,
    summary: String,
    initiallyExpanded: Boolean = false,
    stateKey: String = title,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(initiallyExpanded) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        // 动效只用来解释「这块展开了」，短且线性，不做弹跳。
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "settings-group-chevron",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Medium)
                .clip(Corner.Container)
                .clickable { expanded = !expanded }
                .semantics {
                    contentDescription = if (expanded) {
                        "$title，已展开，$summary"
                    } else {
                        "$title，已折叠，$summary"
                    }
                }
                .defaultMinSize(minHeight = MinTouchTarget)
                .padding(horizontal = Spacing.Tight, vertical = Spacing.Medium),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics { },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(chevronRotation),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.Small),
            ) {
                content()
            }
        }
    }
}
