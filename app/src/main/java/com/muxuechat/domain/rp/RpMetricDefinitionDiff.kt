package com.muxuechat.domain.rp

import com.muxuechat.domain.model.RpEntryOwner
import com.muxuechat.domain.model.RpMetricSnapshot

data class RpMetricDefinitionChange(
    val before: RpMetricSnapshot,
    val after: RpMetricSnapshot,
)

/** 定义比较以归属与词条身份为准，改名不会变成删除后新增。 */
data class RpMetricGroupDiff(
    val owner: RpEntryOwner,
    val added: List<RpMetricSnapshot>,
    val removed: List<RpMetricSnapshot>,
    val changed: List<RpMetricDefinitionChange>,
    val orderChanged: Boolean,
) {
    val hasChanges: Boolean get() = added.isNotEmpty() || removed.isNotEmpty() || changed.isNotEmpty() || orderChanged
}

object RpMetricDefinitionDiff {
    fun between(
        current: List<RpMetricSnapshot>,
        latest: List<RpMetricSnapshot>,
        owner: RpEntryOwner,
    ): RpMetricGroupDiff {
        val previous = current.filter { it.ownerType == owner }
        val next = latest.filter { it.ownerType == owner }
        // 无 id 的迁移遗留项只能按已登记的线上名匹配，不能根据名字形状推断归属。
        fun key(entry: RpMetricSnapshot): Pair<Boolean, String> =
            if (entry.entryId.isNotBlank()) true to entry.entryId else false to entry.wireName
        val previousById = previous.associateBy(::key)
        val nextById = next.associateBy(::key)
        return RpMetricGroupDiff(
            owner = owner,
            added = next.filter { key(it) !in previousById },
            removed = previous.filter { key(it) !in nextById },
            changed = next.mapNotNull { after ->
                previousById[key(after)]?.takeIf { it != after }?.let { RpMetricDefinitionChange(it, after) }
            },
            orderChanged = previous.map(::key).filter { it in nextById } !=
                next.map(::key).filter { it in previousById },
        )
    }
}
