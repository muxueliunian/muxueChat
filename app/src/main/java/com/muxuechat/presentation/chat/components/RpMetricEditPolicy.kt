package com.muxuechat.presentation.chat.components

import com.muxuechat.domain.model.RpMetricSnapshot
import com.muxuechat.domain.model.RpEntryOwner

/** 输入中的空串和负号都是草稿；只有完整且在范围内的 Int 才能提交。 */
internal object RpMetricEditPolicy {
    data class Value(val number: Int?, val error: String?)

    fun validate(input: String, min: Int, max: Int): Value {
        if (min > max) return Value(null, "状态条范围无效，请先同步定义")
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed == "-") return Value(null, "请输入完整整数")
        val number = trimmed.toIntOrNull() ?: return Value(null, "请输入有效整数，不能包含小数或超出整数范围")
        if (number !in min..max) return Value(null, "数值必须在 $min 到 $max 之间")
        return Value(number, null)
    }

    /** 遗留名称兜底用于定位和展示；写入必须与仓库一样具有完整身份。 */
    fun editingError(definition: RpMetricSnapshot): String? = when {
        definition.entryId.isBlank() -> "缺少状态条身份，请先同步定义"
        RpEntryOwner.entries.none { it.storageValue == definition.owner } -> "状态条归属无效，请先同步定义"
        definition.minValue > definition.maxValue -> "状态条范围无效，请先同步定义"
        else -> null
    }

    /** 名称变化不影响有 ID 的身份；迁移遗留项只能在同一归属下按完整登记名兜底。 */
    fun findDefinition(
        snapshot: List<RpMetricSnapshot>,
        owner: String,
        entryId: String,
        wireName: String,
    ): RpMetricSnapshot? = snapshot.filter {
        it.owner == owner && if (entryId.isNotBlank()) it.entryId == entryId
        else it.entryId.isBlank() && it.wireName == wireName
    }.singleOrNull()
}
