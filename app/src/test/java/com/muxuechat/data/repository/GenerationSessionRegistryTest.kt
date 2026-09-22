package com.muxuechat.data.repository

import com.muxuechat.domain.model.ChatProviderId
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GenerationSessionRegistryTest {
    @Test
    fun currentForConversation_onlyReturnsOwningSession() {
        val registry = GenerationSessionRegistry()
        val session = session(conversationId = "conversation-a")
        registry.start(session)

        assertSame(session, registry.currentForConversation("conversation-a"))
        assertNull(registry.currentForConversation("conversation-b"))
        assertSame(session, registry.currentForConversation(null))
    }

    @Test
    fun staleSessionCannotAttachAssistantOrFinishNewSession() {
        val registry = GenerationSessionRegistry()
        val oldSession = session(sessionId = "old", conversationId = "conversation-a")
        val newSession = session(sessionId = "new", conversationId = "conversation-b")

        registry.start(oldSession)
        assertSame(oldSession, registry.start(newSession))

        assertNull(
            registry.attachAssistant(
                sessionId = oldSession.sessionId,
                assistantMessageId = "old-assistant",
                isHistoryReplacement = false,
            ),
        )
        assertNull(registry.finish(oldSession.sessionId))
        assertSame(newSession, registry.current())

        val attached = registry.attachAssistant(
            sessionId = newSession.sessionId,
            assistantMessageId = "new-assistant",
            isHistoryReplacement = true,
        )
        assertEquals("new-assistant", attached?.assistantMessageId)
        assertEquals(GenerationSessionStatus.STREAMING, attached?.status)
        assertEquals(true, attached?.isHistoryReplacement)
        assertEquals(attached, registry.finish(newSession.sessionId))
        assertNull(registry.current())
    }

    private fun session(
        sessionId: String = "session",
        conversationId: String,
    ) = GenerationSession(
        sessionId = sessionId,
        conversationId = conversationId,
        assistantMessageId = null,
        job = Job(),
        providerSnapshot = GenerationProviderSnapshot(
            provider = ChatProviderId.DEEPSEEK,
            baseUrl = "https://example.test/v1",
            model = "test-model",
        ),
        status = GenerationSessionStatus.STARTING,
        isHistoryReplacement = false,
    )
}
