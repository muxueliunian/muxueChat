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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.ImageInputSpec
import com.muxuechat.domain.context.ContextCapacitySource
import com.muxuechat.domain.context.ContextCompressionMode
import com.muxuechat.domain.context.ContextCompressionPolicy
import com.muxuechat.domain.context.ContextSectionKind
import com.muxuechat.domain.context.ContextWindowPlan
import com.muxuechat.domain.context.ModelContextCatalog
import com.muxuechat.domain.context.ModelContextSpec
import com.muxuechat.domain.model.ConversationTokenUsage
import com.muxuechat.presentation.common.CONTEXT_WARNING_USAGE_PERCENT
import com.muxuechat.presentation.common.formatCompactTokens
import com.muxuechat.presentation.common.formatConversationTime
import com.muxuechat.presentation.common.formatExactTokens
import kotlin.math.roundToInt

/**
 * 上下文视窗。
 *
 * 目的是让「这一轮到底会发出去多少、花在哪、丢了什么」变成可见的，而不是让用户凭感觉猜。
 * 口径已经从字符预算改为**模型真实 token 窗口**：进度条、压缩阈值和实际发送的请求
 * 全部来自同一份 [ContextWindowPlan]，不再各算各的。
 *
 * 「预计」两个字必须一直留着——发送前的数字来自本地启发式估算，只有下面的
 * 「上次 API 实际用量」才是服务端给的真实值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextWindowSheet(
    plan: ContextWindowPlan?,
    isCompacting: Boolean,
    onCompactNow: () -> Unit,
    onModeChange: (ContextCompressionMode) -> Unit,
    onTriggerPercentChange: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {

    com.muxuechat.presentation.common.components.EditorSheet(
        onDismiss = onDismiss,
        actions = { TextButton(onClick = onDismiss) { Text("关闭") } },
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
                CapacitySection(plan, onOpenSettings)
                BreakdownSection(plan)
                ActualUsageSection(plan)
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

@Composable
private fun UsageSection(plan: ContextWindowPlan) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(
                    text = "预计输入",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${formatCompactTokens(plan.estimatedInputTokens)} / " +
                        "${formatCompactTokens(plan.inputBudgetTokens)} tokens",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = "${plan.usagePercent}%",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (plan.usagePercent >= CONTEXT_WARNING_USAGE_PERCENT) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }

        UsageBar(plan)

        Text(
            // 发送前只能估算：各家分词器不同，这个数字不能拿去跟账单对数。
            text = "发送前的本地估算，含安全余量；真实用量以下方 API 返回值为准。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        plan.overflowError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
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
                    when {
                        plan.usagePercent >= CONTEXT_WARNING_USAGE_PERCENT -> MaterialTheme.colorScheme.error
                        plan.usagePercent >= plan.policy.autoTriggerPercent -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
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

/**
 * 把「1M 的模型为什么只有 945.9K 可用」说清楚。
 *
 * 不写出来的话，用户只会觉得 App 在无缘无故克扣窗口。
 */
@Composable
private fun CapacitySection(plan: ContextWindowPlan, onOpenSettings: () -> Unit) {
    val spec = plan.spec
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("容量")
        BreakdownRow("当前模型", "${spec.providerLabel} / ${spec.modelId}")
        BreakdownRow("模型窗口", "${formatCompactTokens(spec.contextWindowTokens)} tokens")
        BreakdownRow(
            label = "${ModelContextSpec.SAFETY_WINDOW_PERCENT}% 安全窗口",
            value = "${formatCompactTokens(spec.effectiveContextTokens)} tokens",
        )
        BreakdownRow("输出预留", "${formatExactTokens(spec.maxOutputTokens)} tokens")
        BreakdownRow("可用输入预算", "${formatExactTokens(plan.inputBudgetTokens)} tokens")
        if (spec.isCostCapped) {
            Text(
                text = "当前生效的是成本上限（${formatExactTokens(spec.costCapTokens ?: 0L)} tokens），" +
                    "低于模型本身能装下的 ${formatExactTokens(spec.modelInputBudgetTokens)} tokens。" +
                    "可在设置中调高或关闭。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        capacitySourceNotice(spec)?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                // 回退值是猜的，不能让它看起来像供应商给出的真实能力。
                color = MaterialTheme.colorScheme.error,
            )
            if (spec.isFallback) {
                TextButton(onClick = onOpenSettings) {
                    Text("去设置中填写上下文长度")
                }
            }
        }
    }
}

@Composable
private fun BreakdownSection(plan: ContextWindowPlan) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("构成")
        plan.sections
            .filter { it.tokens > 0L }
            .forEach { section ->
                BreakdownRow(
                    label = contextSectionLabel(section.kind),
                    value = buildString {
                        if (section.messageCount > 0) {
                            append(section.messageCount)
                            append(if (section.kind == ContextSectionKind.TOOL_DEFINITIONS) " 个 · " else " 条 · ")
                        }
                        append(formatExactTokens(section.tokens))
                        append(" tokens")
                    },
                )
            }

        if (plan.summarizedMessageCount > 0) {
            BreakdownRow("已压缩为摘要", "${plan.summarizedMessageCount} 条")
        }
        BreakdownRow("历史总量", "${plan.totalHistoryMessageCount} 条")
        ImageUsageRows(plan)

        if (plan.summaryInvalidated) {
            Text(
                // 编辑历史会让摘要覆盖范围不再可信，占用突然变大不是 bug。
                text = "编辑历史后原有摘要已作废，本轮按逐字历史计算。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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

/**
 * 本轮请求里的图片（MULTIMODAL_PLAN 3.6）：携带张数、因请求体积上限省略的更早图片、文件已丢失的图片。
 * 图片按每张固定的预计 token 计入「逐字历史」，与文字一样只能标「预计」。
 */
@Composable
private fun ImageUsageRows(plan: ContextWindowPlan) {
    if (plan.imageCount > 0) {
        BreakdownRow("本轮携带图片", "${plan.imageCount} 张")
    }
    if (plan.omittedImageCount > 0) {
        BreakdownRow("超出 ${ImageInputSpec.MAX_IMAGE_PAYLOAD_BYTES / (1024 * 1024)} MB 未发送", "${plan.omittedImageCount} 张")
    }
    if (plan.missingImageCount > 0) {
        BreakdownRow("文件已丢失", "${plan.missingImageCount} 张")
    }
}

@Composable
private fun ActualUsageSection(plan: ContextWindowPlan) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("上次 API 实际用量")
        val usage = plan.lastApiUsage
        if (usage == null || !usage.hasAnyValue) {
            Text(
                text = "还没有拿到服务端返回的用量。部分上游在流式下不返回用量，" +
                    "可在该供应商设置中开启「请求流式用量统计」后重试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        BreakdownRow(
            label = "输入 · 输出 · 合计",
            value = listOf(usage.promptTokens, usage.completionTokens, usage.totalTokens)
                .joinToString(" · ") { value -> value?.let { formatExactTokens(it.toLong()) } ?: "—" },
        )
        BreakdownRow(
            label = "来源",
            value = "${usage.model} · ${formatConversationTime(usage.capturedAtMillis)}",
        )
        if (isStaleUsage(usage, plan.spec)) {
            Text(
                // 旧模型的用量不参与当前进度条，否则百分比会张冠李戴。
                text = "旧模型数据：这份用量来自其他供应商或模型，不计入上方的占用比例。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip("自动", ContextCompressionMode.AUTO, plan.policy.mode, onModeChange)
            ModeChip("手动", ContextCompressionMode.MANUAL, plan.policy.mode, onModeChange)
            ModeChip("关闭", ContextCompressionMode.OFF, plan.policy.mode, onModeChange)
        }

        Text(
            text = when (plan.policy.mode) {
                ContextCompressionMode.AUTO ->
                    "预计输入达到模型输入预算的 ${plan.policy.autoTriggerPercent}%（约 " +
                        "${formatCompactTokens(plan.policy.autoTriggerTokens(plan.inputBudgetTokens))} tokens）" +
                        "时，自动把最早的剧情压成摘要，目标压回 ${plan.policy.targetPercent}%。"
                ContextCompressionMode.MANUAL ->
                    "只在你点「立即压缩」时压缩。超出输入预算的部分仍会被丢弃。"
                ContextCompressionMode.OFF ->
                    "不生成摘要。超出输入预算的更早剧情会被直接丢弃，模型将不再记得它们。"
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
            text = "上下文越长，每轮的费用和首 token 延迟都会同步上升——模型窗口装得下，不代表值得装满。" +
                "压缩本身也要额外发一次请求，并且摘要更新的那一轮会让前缀缓存失效；" +
                "换来的是更早的剧情不会凭空消失。想直接封顶花费，可在设置中开启「成本上限」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TriggerSlider(percent: Int, onChange: (Int) -> Unit) {
    Column {
        androidx.compose.foundation.layout.FlowRow(
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

internal fun contextSectionLabel(kind: ContextSectionKind): String = when (kind) {
    ContextSectionKind.SYSTEM_PROMPT -> "系统提示词"
    ContextSectionKind.RP_SETTINGS -> "RP 设定与状态"
    ContextSectionKind.SUMMARY -> "剧情摘要"
    ContextSectionKind.VERBATIM_HISTORY -> "逐字历史"
    ContextSectionKind.TOOL_DEFINITIONS -> "工具定义"
    ContextSectionKind.TOOL_EXCHANGE -> "工具调用消息"
}

/** 容量不是来自内置或用户配置时给出的警告；正常情况返回 null。 */
internal fun capacitySourceNotice(spec: ModelContextSpec): String? = when (spec.source) {
    ContextCapacitySource.BUILT_IN, ContextCapacitySource.USER_CONFIGURED -> null
    ContextCapacitySource.FALLBACK ->
        "未配置该模型的上下文长度，暂按 ${formatExactTokens(ModelContextCatalog.FALLBACK_CONTEXT_TOKENS)} " +
            "tokens 的保守回退值计算。这不是供应商的真实能力，填写后占用比例才准确。"
}

/** 用量是否来自其他供应商或模型。是的话不能拿它解释当前占用。 */
internal fun isStaleUsage(usage: ConversationTokenUsage, spec: ModelContextSpec): Boolean =
    !usage.matches(providerKey = spec.providerKey, model = spec.modelId)
