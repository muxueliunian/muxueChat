package com.muxuechat.presentation.chat.components

import com.muxuechat.domain.model.RpMetricSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RpMetricEditPolicyTest {
    @Test
    fun unfinishedNegativeAndEmptyDrafts_cannotSubmit() {
        listOf("", " ", "-", "- ").forEach {
            assertNull(RpMetricEditPolicy.validate(it, -100, 100).number)
            assertNotNull(RpMetricEditPolicy.validate(it, -100, 100).error)
        }
        assertEquals(-12, RpMetricEditPolicy.validate("-12", -100, 100).number)
    }

    @Test
    fun boundsAndIntOverflow_areErrorsInsteadOfClampedValues() {
        assertEquals(-100, RpMetricEditPolicy.validate("-100", -100, 100).number)
        assertEquals(100, RpMetricEditPolicy.validate("100", -100, 100).number)
        listOf("101", "-101", "1.5", "2147483648", "-2147483649", "abc").forEach {
            assertNull(RpMetricEditPolicy.validate(it, -100, 100).number)
        }
        assertEquals(Int.MIN_VALUE, RpMetricEditPolicy.validate(Int.MIN_VALUE.toString(), Int.MIN_VALUE, Int.MAX_VALUE).number)
        assertNull(RpMetricEditPolicy.validate("0", 2, 1).number)
    }

    @Test
    fun identityUsesOwnerAndEntryId_withoutGuessingNamePrefixes() {
        val role = definition("ROLE_CARD", "same-id", "没有前缀")
        val protagonist = definition("PROTAGONIST", "same-id", "角色::误导的前缀")
        val snapshot = listOf(role, protagonist)
        assertEquals(role, RpMetricEditPolicy.findDefinition(snapshot, "ROLE_CARD", "same-id", "old-name"))
        assertEquals(protagonist, RpMetricEditPolicy.findDefinition(snapshot, "PROTAGONIST", "same-id", "old-name"))
        assertNull(RpMetricEditPolicy.findDefinition(snapshot, "PROTAGONIST", "missing-id", role.wireName))
    }

    @Test
    fun legacyFallbackAndAmbiguity_stayWithinTheSameOwner() {
        val role = definition("ROLE_CARD", "", "体力")
        val protagonist = definition("PROTAGONIST", "", "体力")
        assertEquals(role, RpMetricEditPolicy.findDefinition(listOf(role, protagonist), "ROLE_CARD", "", "体力"))
        assertNull(RpMetricEditPolicy.findDefinition(listOf(role, role), "ROLE_CARD", "", "体力"))
    }

    @Test
    fun legacyIdentity_isDisplayableButCannotBeEditedBeforeSync() {
        listOf("", " ").forEach { id ->
            val legacy = definition("ROLE_CARD", id, "主角::原名")
            assertEquals(legacy, RpMetricEditPolicy.findDefinition(listOf(legacy), legacy.owner, id, legacy.wireName))
            assertEquals("缺少状态条身份，请先同步定义", RpMetricEditPolicy.editingError(legacy))
        }
        assertNull(RpMetricEditPolicy.editingError(definition("ROLE_CARD", "new-id", "主角::原名")))
        assertNotNull(RpMetricEditPolicy.editingError(definition("unknown-owner", "id", "体力")))
        assertNotNull(RpMetricEditPolicy.editingError(definition("ROLE_CARD", "id", "体力").copy(minValue = 2, maxValue = 1)))
    }

    private fun definition(owner: String, id: String, wire: String) = RpMetricSnapshot(
        owner = owner, entryId = id, wireName = wire, displayName = "体力",
    )
}
