package com.muxuechat.domain.rp

import com.muxuechat.domain.model.RpMetric
import com.muxuechat.domain.model.RpMetricDefinition
import com.muxuechat.domain.model.RpStatusPanel

/** 模型输出的单个状态条。保持原样：解码阶段不裁剪、不补默认值，取不到的数字为 null。 */
data class RpMetricDraft(
    val name: String,
    val value: Int?,
    val min: Int?,
    val max: Int?,
)

/**
 * 一次合并的结果。
 *
 * [panel] 为 null 表示**整个状态块都对不上本轮定义**：它不是有效的本地状态，
 * 调用方必须当作「本轮没有状态」处理（例如继续走兜底请求），而不是拿它覆盖面板。
 *
 * [textOnlyPanel] 只在 [panel] 为 null 时可能非空：数值一个都没对上，但模型给了关系或场景。
 * 它的数值保持当前值，只更新关系与场景（用户 2026-09-23 选定，RP_ACTOR_PLAN 建议 5）。
 * 调用方可以把它写进面板，但**仍须当作数值没更新**、照常发兜底请求。
 *
 * 计数只用于日志，不携带任何名字或正文。
 */
data class RpStateMergeResult(
    val panel: RpStatusPanel?,
    val unknownCount: Int,
    val duplicateCount: Int,
    val textOnlyPanel: RpStatusPanel? = null,
)

/**
 * 「按本轮定义 → 匹配 → 合并」的唯一入口（RP_ACTOR_PLAN R15 / 3.6）。
 *
 * 正文状态块、元数据补写、兜底请求、历史回滚与换绑对齐全部走这里，
 * 保证同一份模型输出无论从哪条路进来，落库的结果都一样。
 *
 * 语义：
 * - 模型没输出、输出了但取不到数字、或同名出现多次的状态条：**保留当前值**，
 *   当前值也没有时才用初始值。旧实现一律回落初始值，模型写错一次名字玩家的数值就当场归零。
 * - 越界值按**定义**的范围收敛，不先按模型自报的 min/max 裁剪。
 * - 定义里没有的名字丢弃并计数。
 */
object RpStateMerger {
    fun merge(
        conversationId: String,
        definitions: List<RpMetricDefinition>,
        current: RpStatusPanel?,
        draft: RpStatusPanelDraft,
        now: Long,
    ): RpStateMergeResult {
        // 抄回来的示例占位文字等同于没给，保留上一轮的关系与场景。
        val draftRelationship = RpStateBlockFormat.meaningfulText(draft.relationship)
        val draftScene = RpStateBlockFormat.meaningfulText(draft.scene)
        val relationship = draftRelationship.ifBlank { current?.relationship.orEmpty() }
        val scene = draftScene.ifBlank { current?.scene.orEmpty() }

        if (definitions.isEmpty()) {
            // 角色卡没定义状态条时，面板只承载关系与场景；模型自带的状态条原样保留（现有行为）。
            return RpStateMergeResult(
                panel = RpStatusPanel(
                    conversationId = conversationId,
                    metrics = draft.metrics.mapNotNull { it.toSelfDescribedMetricOrNull() },
                    relationship = relationship,
                    scene = scene,
                    rawJson = draft.rawJson,
                    updatedAtMillis = now,
                ),
                unknownCount = 0,
                duplicateCount = 0,
            )
        }

        val byName = draft.metrics.groupBy { it.name }
        val duplicateNames = byName.filterValues { it.size > 1 }.keys
        val definedNames = definitions.mapTo(hashSetOf()) { it.name }
        val base = alignToDefinitions(definitions, current?.metrics.orEmpty())

        var matched = 0
        val metrics = definitions.zip(base) { definition, baseMetric ->
            val value = byName[definition.name]
                ?.singleOrNull()
                ?.value
                ?.coerceIn(definition.minValue, definition.maxValue)
            if (value != null) matched++
            baseMetric.copy(value = value ?: baseMetric.value)
        }

        val merged = RpStatusPanel(
            conversationId = conversationId,
            metrics = metrics,
            relationship = relationship,
            scene = scene,
            rawJson = draft.rawJson,
            updatedAtMillis = now,
        )
        val hasNewText = draftRelationship.isNotBlank() || draftScene.isNotBlank()

        return RpStateMergeResult(
            panel = merged.takeIf { matched > 0 },
            unknownCount = byName.keys.count { it !in definedNames },
            duplicateCount = duplicateNames.count { it in definedNames },
            // matched == 0 时 metrics 全部等于 base，即当前值（没有当前值时为初始值）。
            textOnlyPanel = merged.takeIf { matched == 0 && hasNewText },
        )
    }

    /**
     * 把已有的状态条对齐到一套定义：按定义顺序输出，范围以定义为准，
     * 找不到对应项时才用初始值。
     *
     * [previousNames] 是「定义 id → 改名前的名字」。角色卡里某个状态条改了名，
     * 面板里存的还是旧名；带上它就能按 id 找回原值，而不是因为改名把数值归零。
     *
     * 带 id 的旧定义的名字只归它自己，不再让其它定义按「同名」复用：
     * - 把「好感度」改名为「亲密度」、同时新增一个叫「好感度」的状态条：旧值只属于改名的那一条；
     * - 删掉「好感度」、同时新增一个同名的：新 id 是新的状态条，从初始值开始。
     * id 为空的旧定义（早期数据）不在 [previousNames] 里，仍按名字匹配。
     */
    fun alignToDefinitions(
        definitions: List<RpMetricDefinition>,
        metrics: List<RpMetric>,
        previousNames: Map<String, String> = emptyMap(),
    ): List<RpMetric> {
        val byName = metrics.groupBy { it.name }
        val claimedByIdNames = previousNames.values.toHashSet()
        return definitions.map { definition ->
            val lookupName = previousNames[definition.id]
                ?: definition.name.takeUnless { it in claimedByIdNames }
            val value = lookupName
                ?.let { byName[it] }
                ?.firstOrNull()
                ?.value
                ?.coerceIn(definition.minValue, definition.maxValue)
                ?: definition.initialValue
            RpMetric(
                name = definition.name,
                value = value,
                min = definition.minValue,
                max = definition.maxValue,
            )
        }
    }

    /** 没有定义可对照时，只能信模型自报的范围；取不到数字的项直接丢弃。 */
    private fun RpMetricDraft.toSelfDescribedMetricOrNull(): RpMetric? {
        val rawValue = value ?: return null
        val minValue = min ?: 0
        val maxValue = (max ?: 100).let { if (it > minValue) it else minValue + 1 }
        return RpMetric(
            name = name,
            value = rawValue.coerceIn(minValue, maxValue),
            min = minValue,
            max = maxValue,
        )
    }
}
