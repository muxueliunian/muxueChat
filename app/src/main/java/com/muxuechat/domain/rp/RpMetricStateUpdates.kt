package com.muxuechat.domain.rp

import com.muxuechat.domain.model.RpEntryOwner
import com.muxuechat.domain.model.RpMetric
import com.muxuechat.domain.model.RpMetricSnapshot

object RpMetricStateUpdates {
    /** 每组分别按 entryId 对齐，避免不同归属恰有相同 id 时串值；未选择的组逐项原样保留。 */
    fun resync(
        metrics: List<RpMetric>,
        before: List<RpMetricSnapshot>,
        after: List<RpMetricSnapshot>,
        owners: Set<RpEntryOwner>,
    ): List<RpMetric> {
        if (owners.isEmpty()) return metrics
        val oldOwnerByName = before.associate { it.wireName to it.ownerType }
        val selectedNames = after.filter { it.ownerType in owners }.mapTo(mutableSetOf()) { it.wireName }
        val untouched = metrics.filterNot {
            // 旧实现允许无定义角色卡保存模型自报数值；与绑定转换一样，这些未登记项属于角色侧遗留值。
            (oldOwnerByName[it.name] ?: RpEntryOwner.ROLE_CARD) in owners || it.name in selectedNames
        }
        val synced = RpEntryOwner.entries.filter { it in owners }.flatMap { owner ->
            val oldGroup = before.filter { it.ownerType == owner }
            val previousNames = oldGroup.filter { it.entryId.isNotBlank() }.associate { it.entryId to it.wireName }
            RpStateMerger.alignToDefinitions(
                definitions = RpEffectiveDefinitions.of(after.filter { it.ownerType == owner }),
                metrics = metrics.filter { oldOwnerByName[it.name] == owner },
                previousNames = previousNames,
            )
        }
        val newOwnerByName = after.associate { it.wireName to it.ownerType }
        return (untouched + synced).sortedBy { newOwnerByName[it.name]?.ordinal ?: Int.MAX_VALUE }
    }

    /** 手输不收敛，且只修改指定一项；整张面板的其余字段由事务内的最新值继续使用。 */
    fun correct(metrics: List<RpMetric>, definition: RpMetricSnapshot, value: Int): List<RpMetric> {
        require(value in definition.minValue..definition.maxValue) {
            "数值必须在 ${definition.minValue} 到 ${definition.maxValue} 之间"
        }
        require(metrics.count { it.name == definition.wireName } <= 1) { "状态条数据重复，请先同步定义" }
        val corrected = RpMetric(definition.wireName, value, definition.minValue, definition.maxValue)
        return if (metrics.any { it.name == definition.wireName }) {
            metrics.map { if (it.name == definition.wireName) corrected else it }
        } else {
            metrics + corrected
        }
    }
}
