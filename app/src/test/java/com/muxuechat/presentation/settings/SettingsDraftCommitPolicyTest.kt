package com.muxuechat.presentation.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDraftCommitPolicyTest {
    @Test
    fun newDraft_needsCommit() {
        assertTrue(needsTextCommit(value = "B", persistedValue = "A", lastSubmittedValue = null))
    }

    @Test
    fun alreadySubmittedValue_doesNotSubmitRepeatedly() {
        assertFalse(needsTextCommit(value = "B", persistedValue = "A", lastSubmittedValue = "B"))
    }

    @Test
    fun revertingWhileDifferentValueIsInFlight_submitsCorrection() {
        assertTrue(needsTextCommit(value = "A", persistedValue = "A", lastSubmittedValue = "B"))
    }

    @Test
    fun correctionAlreadySubmitted_waitsForItsAck() {
        assertFalse(needsTextCommit(value = "A", persistedValue = "B", lastSubmittedValue = "A"))
    }

    @Test
    fun persistedValueWithoutInFlightWrite_isClean() {
        assertFalse(needsTextCommit(value = "A", persistedValue = "A", lastSubmittedValue = null))
    }

    @Test
    fun flushRetriesDraftWhenPreviousSubmissionNeverReachedPersistence() {
        assertTrue(needsTextFlush(value = "B", persistedValue = "A"))
    }

    @Test
    fun flushSkipsValueAlreadyAcknowledgedByPersistence() {
        assertFalse(needsTextFlush(value = "A", persistedValue = "A"))
    }
}
