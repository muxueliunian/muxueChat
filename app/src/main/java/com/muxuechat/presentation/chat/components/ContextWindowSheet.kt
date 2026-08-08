package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.context.ContextCompressionMode
import com.muxuechat.domain.context.ContextCompressionPolicy
import com.muxuechat.domain.context.ContextWindowPlan
import kotlin.math.roundToInt

/**
 * 上下文视窗。
 *
 * 目的是让"这一轮到底发了什么、花了多少、丢了什么"变成可见的，
 * 而不是让用户凭感觉猜。压缩的代价（前缀缓存失效）也直接写在界面上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextWindowSheet(
    plan: ContextWindowPlan?,
    isCompacting: Boolean,
    onCompactNow: () -> Unit,
    onModeChange: (ContextCompressionMode) -> Unit,
    onTriggerPercentChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "上下文",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (plan == null) {
                Text(
                    text = "当前没有会话，或上下文还在计算中。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                UsageSection(plan)
                BreakdownSection(plan)
                CompressionSection(
                    plan = plan,
                    isCompacting = isCompacting,
                    onCompactNow = onCompactNow,
                    onModeChange = onModeChange,
                    onTriggerPercentChange = onTriggerPercentChange,
                )
            }
        }
    }
}

@Composable
private fun UsageSection(plan: ContextWindowPlan) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "${plan.usagePercent}%",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (plan.usagePercent >= 100) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = "${plan.effectiveChars} / ${plan.policy.budgetChars} 字符",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        UsageBar(plan)

        Text(
            // 明确标注是估算，避免用户拿它跟账单对数。
            text = "约 ${plan.estimatedTokens} tokens（粗略估算，仅供参考）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 进度条，同时用一道竖线标出自动压缩的触发位置。 */
@Composable
private fun UsageBar(plan: ContextWindowPlan) {
    val fraction = (plan.usagePercent / 100f).coerceIn(0f, 1f)
    val triggerFraction = (plan.policy.autoTriggerPercent / 100f).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(10.dp)
                .background(
                    if (plan.usagePercent >= plan.policy.autoTriggerPercent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    },
                ),
        )
        if (plan.policy.mode == ContextCompressionMode.AUTO) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(triggerFraction)
                    .height(10.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(10.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
    }
}

@Composable
private fun BreakdownSection(plan: ContextWindowPlan) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("构成")
        BreakdownRow("逐字历史", "${plan.messages.size} 条 · ${plan.verbatimChars} 字符")
        if (plan.summarizedMessageCount > 0) {
            BreakdownRow(
                label = "已压缩为摘要",
                value = "${plan.summarizedMessageCount} 条 → ${plan.summaryChars} 字符",
            )
        }
        BreakdownRow("历史总量", "${plan.totalHistoryChars} 字符")

        if (plan.hasUnrecoverableLoss) {
            // 硬裁剪是真丢失，必须显眼地告诉用户，而不是埋在日志里。
            Text(
                text = "有 ${plan.hardTrimmedMessageCount} 条更早的消息因超出预算被丢弃，且没有摘要顶替。" +
                    "开启压缩可以避免这种情况。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CompressionSection(
    plan: ContextWindowPlan,
    isCompacting: Boolean,
    onCompactNow: () -> Unit,
    onModeChange: (ContextCompressionMode) -> Unit,
    onTriggerPercentChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("压缩")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip("自动", ContextCompressionMode.AUTO, plan.policy.mode, onModeChange)
            ModeChip("手动", ContextCompressionMode.MANUAL, plan.policy.mode, onModeChange)
            ModeChip("关闭", ContextCompressionMode.OFF, plan.policy.mode, onModeChange)
        }

        Text(
            text = when (plan.policy.mode) {
                ContextCompressionMode.AUTO ->
                    "占用达到 ${plan.policy.autoTriggerPercent}% 时自动把最早的剧情压成摘要。"
                ContextCompressionMode.MANUAL ->
                    "只在你点「立即压缩」时压缩。超出预算的部分仍会被丢弃。"
                ContextCompressionMode.OFF ->
                    "不生成摘要。超出预算的更早剧情会被直接丢弃，模型将不再记得它们。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (plan.policy.mode == ContextCompressionMode.AUTO) {
            TriggerSlider(
                percent = plan.policy.autoTriggerPercent,
                onChange = onTriggerPercentChange,
            )
        }

        if (plan.policy.mode != ContextCompressionMode.OFF) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onCompactNow,
                    enabled = !isCompacting,
                ) {
                    Text(if (isCompacting) "压缩中…" else "立即压缩")
                }
                if (isCompacting) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                }
            }
        }

        // 把代价直接写出来，而不是让用户事后自己发现变慢变贵了。
        Text(
            text = "压缩需要额外发一次请求，并且摘要更新的那一轮会让前缀缓存失效。" +
                "换来的是更早的剧情不会凭空消失。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TriggerSlider(percent: Int, onChange: (Int) -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "自动触发阈值",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(
            value = percent.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = ContextCompressionPolicy.MIN_AUTO_TRIGGER_PERCENT.toFloat()..
                ContextCompressionPolicy.MAX_AUTO_TRIGGER_PERCENT.toFloat(),
        )
        Text(
            text = "推荐 ${ContextCompressionPolicy.RECOMMENDED_MIN_AUTO_TRIGGER_PERCENT}%~" +
                "${ContextCompressionPolicy.RECOMMENDED_MAX_AUTO_TRIGGER_PERCENT}%。" +
                "调低会更早压缩、更省 token，但摘要次数变多；调高则相反。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModeChip(
    label: String,
    mode: ContextCompressionMode,
    selected: ContextCompressionMode,
    onSelect: (ContextCompressionMode) -> Unit,
) {
    FilterChip(
        selected = mode == selected,
        onClick = { onSelect(mode) },
        label = { Text(label) },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BreakdownRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
