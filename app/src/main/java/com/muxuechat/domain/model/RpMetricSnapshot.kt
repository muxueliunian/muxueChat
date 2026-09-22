package com.muxuechat.domain.model

import kotlinx.serialization.Serializable

/**
 * 会话绑定角色卡或主角时，冻结下来的「本会话有效数值定义」（R6 / R10）。
 *
 * 存在 `conversation_rp_settings` 的 JSON 列里，而不是面板里：面板会被删除和重建，
 * 快照跟着没了就失去了意义（见 RP_ACTOR_PLAN §3.4）。
 *
 * **只冻结数值定义**。姓名、简介、文本词条实时跟随档案——它们不参与数值匹配，
 * 不会造成串位。
 *
 * [entryId] 是匹配的第一依据，[wireName] 只在 entryId 缺失（迁移产生的遗留项）时兜底。
 */
@Serializable
data class RpMetricSnapshot(
    val owner: String,
    val entryId: String,
    /** 发给模型、也写进 metricsJson 的名字，如「角色::好感度」。 */
    val wireName: String,
    /** UI 上显示的名字，如「好感度」。 */
    val displayName: String,
    val minValue: Int = 0,
    val maxValue: Int = 100,
    val initialValue: Int = 0,
    val description: String = "",
    val progressRule: String = "",
) {
    val ownerType: RpEntryOwner get() = RpEntryOwner.fromStorageValue(owner)
}
