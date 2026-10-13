package com.muxuechat.domain.rp

import com.muxuechat.domain.model.RpEntry
import com.muxuechat.domain.model.RpEntryOwner
import com.muxuechat.domain.model.RpMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpMetricStateUpdatesTest {
    private val role = snapshot(RpEntryOwner.ROLE_CARD, "shared-id", "好感度")
    private val protagonist = snapshot(RpEntryOwner.PROTAGONIST, "shared-id", "体力")

    @Test
    fun syncOneGroup_preservesOtherGroupAndHandlesOwnerLocalIds() {
        val renamed = role.copy(wireName = "角色::亲密度", displayName = "亲密度")
        val changedOther = protagonist.copy(maxValue = 30)
        val untouched = RpMetric(protagonist.wireName, 88, 0, 100)
        assertEquals(
            listOf(RpMetric(renamed.wireName, 77, 0, 100), untouched),
            RpMetricStateUpdates.resync(
                listOf(RpMetric(role.wireName, 77, 0, 100), untouched),
                listOf(role, protagonist),
                listOf(renamed, changedOther),
                setOf(RpEntryOwner.ROLE_CARD),
            ),
        )
    }

    @Test
    fun deletedThenAddedSameName_doesNotReuseOldValue() {
        val replacement = role.copy(entryId = "new-id", initialValue = 12)
        assertEquals(
            listOf(RpMetric(role.wireName, 12, 0, 100)),
            RpMetricStateUpdates.resync(
                listOf(RpMetric(role.wireName, 77, 0, 100)),
                listOf(role), listOf(replacement), setOf(RpEntryOwner.ROLE_CARD),
            ),
        )
    }

    @Test
    fun syncBothGroups_withSameIds_keepsSeparateValuesAndClampsOnlyChangedRange() {
        val smaller = role.copy(maxValue = 50)
        val result = RpMetricStateUpdates.resync(
            listOf(RpMetric(role.wireName, 77, 0, 100), RpMetric(protagonist.wireName, 88, 0, 100)),
            listOf(role, protagonist), listOf(smaller, protagonist), RpEntryOwner.entries.toSet(),
        )
        assertEquals(listOf(RpMetric(role.wireName, 50, 0, 50), RpMetric(protagonist.wireName, 88, 0, 100)), result)
    }

    @Test
    fun correction_changesOnlyTarget_andRejectsOutOfRange() {
        val other = RpMetric(protagonist.wireName, 88, -5, 120)
        val metrics = listOf(RpMetric(role.wireName, 77, 0, 100), other)
        assertEquals(listOf(RpMetric(role.wireName, 3, 0, 100), other), RpMetricStateUpdates.correct(metrics, role, 3))
        assertTrue(runCatching { RpMetricStateUpdates.correct(metrics, role, -1) }.isFailure)
        assertTrue(runCatching { RpMetricStateUpdates.correct(metrics, role, 101) }.isFailure)
        assertEquals(77, metrics.first().value)
    }

    @Test
    fun correction_acceptsNegativeValues_andRejectsDuplicateTarget() {
        val negativeRange = role.copy(minValue = -100)
        assertEquals(listOf(RpMetric(role.wireName, -20, -100, 100)), RpMetricStateUpdates.correct(emptyList(), negativeRange, -20))
        val duplicate = listOf(RpMetric(role.wireName, 3, 0, 100), RpMetric(role.wireName, 4, 0, 100))
        assertTrue(runCatching { RpMetricStateUpdates.correct(duplicate, role, 5) }.isFailure)
    }

    @Test
    fun diff_renameMatchesIdentity_andOnlyRequestedOwnerIsCompared() {
        val renamed = role.copy(displayName = "亲密度", wireName = "角色::亲密度")
        val diff = RpMetricDefinitionDiff.between(listOf(role, protagonist), listOf(renamed, protagonist.copy(maxValue = 50)), RpEntryOwner.ROLE_CARD)
        assertTrue(diff.hasChanges)
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
        assertEquals(listOf(RpMetricDefinitionChange(role, renamed)), diff.changed)
    }

    @Test
    fun diff_detectsAddedRemovedAndOrdering_withoutCountingOtherOwnerChanges() {
        val extra = snapshot(RpEntryOwner.ROLE_CARD, "extra", "信任")
        val added = RpMetricDefinitionDiff.between(listOf(role), listOf(role, extra), RpEntryOwner.ROLE_CARD)
        assertEquals(listOf(extra), added.added)
        val removed = RpMetricDefinitionDiff.between(listOf(role, extra), listOf(extra), RpEntryOwner.ROLE_CARD)
        assertEquals(listOf(role), removed.removed)
        assertTrue(RpMetricDefinitionDiff.between(listOf(role, extra), listOf(extra, role), RpEntryOwner.ROLE_CARD).orderChanged)
        assertFalse(RpMetricDefinitionDiff.between(listOf(role, protagonist), listOf(role, protagonist.copy(maxValue = 50)), RpEntryOwner.ROLE_CARD).hasChanges)
    }

    private fun snapshot(owner: RpEntryOwner, id: String, name: String) =
        RpEffectiveDefinitions.snapshotOf(owner, listOf(RpEntry.Numeric(id, name))).single()
}
