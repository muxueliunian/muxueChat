package com.muxuechat.domain.rp

import com.muxuechat.domain.model.RpEntry
import com.muxuechat.domain.model.RpEntryOwner
import org.junit.Assert.assertEquals
import org.junit.Test

/** 状态面板的显示名还原（RP-2a）。 */
class RpEffectiveDefinitionsTest {
    private val snapshot = RpEffectiveDefinitions.snapshotOf(
        RpEntryOwner.ROLE_CARD,
        listOf(RpEntry.Numeric(id = "r1", name = "好感度"), RpEntry.Numeric(id = "r2", name = "体力")),
    ) + RpEffectiveDefinitions.snapshotOf(
        RpEntryOwner.PROTAGONIST,
        listOf(RpEntry.Numeric(id = "p1", name = "体力"), RpEntry.Numeric(id = "p2", name = "金钱")),
    )

    @Test
    fun registeredNames_showDisplayNameWithoutPrefix() {
        assertEquals(
            listOf("好感度", "金钱"),
            RpEffectiveDefinitions.displayLabelsOf(listOf("角色::好感度", "主角::金钱"), snapshot),
        )
    }

    /** 角色卡和主角都有「体力」时，只显示名字就分不清，撞名时才加归属。 */
    @Test
    fun collidingDisplayNames_areQualifiedWithOwner() {
        assertEquals(
            listOf("好感度", "角色·体力", "主角·体力"),
            RpEffectiveDefinitions.displayLabelsOf(listOf("角色::好感度", "角色::体力", "主角::体力"), snapshot),
        )
    }

    /** 快照里没登记的名字原样显示，不按前缀形状猜（R16）。 */
    @Test
    fun unregisteredNames_areShownAsIs() {
        assertEquals(
            listOf("角色::旧数值", "心情"),
            RpEffectiveDefinitions.displayLabelsOf(listOf("角色::旧数值", "心情"), snapshot),
        )
    }
}
