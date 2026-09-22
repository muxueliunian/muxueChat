package com.muxuechat.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 角色卡、主角、配角共享的「词条」。
 *
 * 文本词条是静态档案信息（年龄、身份、与主角的关系……），只进 prompt；
 * 数值词条进状态面板、进状态块，会被模型每轮更新。
 * 配角只能有文本词条——这是类型约束，不是运行时校验（见 RP_ACTOR_PLAN §3.2）。
 *
 * [id] 是身份的唯一依据（R14）：改名后仍然靠它找回原值，
 * 因此**任何转换都必须原样保留它**，不能重新生成。
 *
 * 判别名 `text` / `numeric` 固定写死，与类名解耦：
 * 以后重命名 Kotlin 类不会让库里已有的 JSON 解不出来。
 */
@Serializable
sealed interface RpEntry {
    val id: String
    val name: String

    @Serializable
    @SerialName("text")
    data class Text(
        override val id: String,
        override val name: String,
        val value: String = "",
    ) : RpEntry

    @Serializable
    @SerialName("numeric")
    data class Numeric(
        override val id: String,
        override val name: String,
        val minValue: Int = 0,
        val maxValue: Int = 100,
        val initialValue: Int = 0,
        val description: String = "",
        val progressRule: String = "",
    ) : RpEntry
}

/**
 * 数值词条 ↔ 旧的 [RpMetricDefinition] 互转。
 *
 * 过渡期用：存储已经是词条，但 prompt 组装、编辑器与工具仍按旧形状工作，
 * 由 RP-2 / RP-3 逐步换掉。**`id` 两个方向都原样保留**，它是身份。
 */
fun RpEntry.Numeric.toMetricDefinition(): RpMetricDefinition = RpMetricDefinition(
    id = id,
    name = name,
    minValue = minValue,
    maxValue = maxValue,
    initialValue = initialValue,
    description = description,
    progressRule = progressRule,
)

fun RpMetricDefinition.toNumericEntry(): RpEntry.Numeric = RpEntry.Numeric(
    id = id,
    name = name,
    minValue = minValue,
    maxValue = maxValue,
    initialValue = initialValue,
    description = description,
    progressRule = progressRule,
)

/** 词条的归属方，决定状态块里的 wireName 前缀。 */
enum class RpEntryOwner(val storageValue: String, val wirePrefix: String) {
    ROLE_CARD("ROLE_CARD", "角色"),
    PROTAGONIST("PROTAGONIST", "主角"),
    ;

    companion object {
        fun fromStorageValue(value: String): RpEntryOwner =
            entries.firstOrNull { it.storageValue == value } ?: ROLE_CARD
    }
}
