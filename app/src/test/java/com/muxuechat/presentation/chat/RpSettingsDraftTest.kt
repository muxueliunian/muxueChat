package com.muxuechat.presentation.chat

import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.ConversationRpSettings
import com.muxuechat.domain.model.ProtagonistProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RpSettingsDraftTest {
    private val profile = ProtagonistProfile("p", "主角", createdAtMillis = 0, updatedAtMillis = 0)
    private val initial = ChatUiState(
        settings = ChatSettings(defaultProtagonistProfileId = "p"),
        protagonistProfiles = listOf(profile),
    )

    @Test
    fun bothNewConversationPaths_preselectOnlyInDraftAndKeepRpOff() {
        listOf(null, "already-created").forEach { conversationId ->
            val state = initial.copy(activeConversationId = conversationId)
            val draft = RpSettingsDraft.from(state)
            assertEquals("p", draft.protagonistProfileId)
            assertFalse(draft.isRpEnabled)
            assertNull(state.activeRpSettings)
        }
    }

    @Test
    fun existingExplicitUnbinding_isNeverReplacedByDefault() {
        val state = initial.copy(activeRpSettings = ConversationRpSettings(
            conversationId = "existing", protagonistProfileId = null, updatedAtMillis = 0,
        ))
        assertNull(RpSettingsDraft.from(state).protagonistProfileId)
    }

    @Test
    fun deletedDefaultProfile_isNotPreselected() {
        assertNull(RpSettingsDraft.from(initial.copy(protagonistProfiles = emptyList())).protagonistProfileId)
    }

    @Test
    fun existingProfileRemainsSelectedRegardlessOfDefault() {
        val state = initial.copy(activeRpSettings = ConversationRpSettings(
            conversationId = "existing", protagonistProfileId = "other", isRpEnabled = true, updatedAtMillis = 0,
        ))
        assertEquals("other", RpSettingsDraft.from(state).protagonistProfileId)
    }
}
