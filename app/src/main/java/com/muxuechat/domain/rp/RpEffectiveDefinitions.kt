package com.muxuechat.domain.rp

import com.muxuechat.domain.model.ConversationRpSettings
import com.muxuechat.domain.model.RpEntry
import com.muxuechat.domain.model.RpEntryOwner
import com.muxuechat.domain.model.RpMetricDefinition
import com.muxuechat.domain.model.RpMetricSnapshot

/**
 * 「本会话有效数值定义」的唯一入口（RP_ACTOR_PLAN §3.4）。
 *
 * 正文解析、metadata 解析、兜底请求、历史回滚、面板重建、prompt 组装全部从这里取定义，
 * **不再读实时档案**——改档案不影响已绑定的会话（R6），靠的就是这一点。
 *
 * 返回的 [RpMetricDefinition.name] 是 **wireName**（如「角色::好感度」），`id` 是 entryId。
 * 这样下游按名字匹配的代码（合并、对齐、prompt 取当前值）不需要知道前缀的存在，
 * 就自然和库里、状态块里的名字一致。界面显示用 [displayNameOf] 还原。
 */
object RpEffectiveDefinitions {

    fun of(settings: ConversationRpSettings?): List<RpMetricDefinition> =
        settings?.metricSnapshot.orEmpty().map { it.toDefinition() }

    fun of(snapshot: List<RpMetricSnapshot>): List<RpMetricDefinition> =
        snapshot.map { it.toDefinition() }

    /**
     * 为某个归属方的档案拍快照。wireName 由显示名加前缀得到，entryId 原样取自词条。
     */
    fun snapshotOf(owner: RpEntryOwner, entries: List<RpEntry>): List<RpMetricSnapshot> =
        entries.filterIsInstance<RpEntry.Numeric>().map { entry ->
            RpMetricSnapshot(
                owner = owner.storageValue,
                entryId = entry.id,
                wireName = RpStateBlockFormat.wireNameOf(owner, entry.name),
                displayName = entry.name,
                minValue = entry.minValue,
                maxValue = entry.maxValue,
                initialValue = entry.initialValue,
                description = entry.description,
                progressRule = entry.progressRule,
            )
        }

    /**
     * 把 [current] 中属于 [owner] 的一组替换为 [replacement]，其它归属方原样保留。
     * 换角色卡只重拍角色组、换主角只重拍主角组（§4），靠的就是这个。
     */
    fun replaceGroup(
        current: List<RpMetricSnapshot>,
        owner: RpEntryOwner,
        replacement: List<RpMetricSnapshot>,
    ): List<RpMetricSnapshot> {
        val kept = current.filterNot { it.ownerType == owner }
        // 顺序固定为角色组在前、主角组在后，prompt 与面板的展示因此稳定。
        return (kept + replacement).sortedBy { it.ownerType.ordinal }
    }

    /**
     * wireName → 显示名。只认快照里登记过的名字，**不按前缀形状猜**（R16）：
     * 快照里查不到时原样返回。
     */
    fun displayNameOf(wireName: String, snapshot: List<RpMetricSnapshot>): String =
        snapshot.firstOrNull { it.wireName == wireName }?.displayName ?: wireName

    /**
     * 状态面板上一组名字的显示文本，与 [wireNames] 一一对应。
     *
     * 默认用 [displayNameOf]；角色卡和主角可以各有一个同名数值（如都叫「体力」），
     * 这时只显示名字就分不清谁是谁，于是**仅在撞名时**加上归属，显示为「主角·体力」。
     * 快照里查不到的名字原样显示，同样不按前缀形状猜。
     */
    fun displayLabelsOf(wireNames: List<String>, snapshot: List<RpMetricSnapshot>): List<String> {
        val entries = wireNames.map { name -> snapshot.firstOrNull { it.wireName == name } }
        val labels = wireNames.mapIndexed { index, name -> entries[index]?.displayName ?: name }
        val collided = labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return labels.mapIndexed { index, label ->
            val entry = entries[index]
            if (entry != null && label in collided) "${entry.ownerType.wirePrefix}·$label" else label
        }
    }

    private fun RpMetricSnapshot.toDefinition() = RpMetricDefinition(
        id = entryId,
        name = wireName,
        minValue = minValue,
        maxValue = maxValue,
        initialValue = initialValue,
        description = description,
        progressRule = progressRule,
    )
}
