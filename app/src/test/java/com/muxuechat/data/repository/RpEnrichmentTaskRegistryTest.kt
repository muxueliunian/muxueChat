package com.muxuechat.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpEnrichmentTaskRegistryTest {
    @Test
    fun `lazy task cancelled before start is removed from ui state`() {
        val scope = CoroutineScope(Job())
        val registry = registry()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            error("cancelled lazy task must not start")
        }
        registry.register(CONVERSATION_ID, MESSAGE_ID, job)

        job.cancel()

        assertTrue(registry.enrichingMessageIds.value.isEmpty())
        assertFalse(job.start())
        scope.cancel()
    }

    @Test
    fun `old completion cannot clear replacement task marker`() {
        val registry = registry()
        val oldJob = Job()
        val oldTask = registry.register(CONVERSATION_ID, MESSAGE_ID, oldJob)
        val replacementJob = Job()
        val replacementTask = registry.register(CONVERSATION_ID, MESSAGE_ID, replacementJob)

        registry.unregister(oldTask)

        assertTrue(registry.enrichingMessageIds.value.contains(MESSAGE_ID))
        assertTrue(registry.isCurrent(replacementTask))

        replacementJob.complete()
        assertTrue(registry.enrichingMessageIds.value.isEmpty())
    }

    @Test
    fun `conversation removal leaves other conversation registered`() {
        val registry = registry()
        val firstJob = Job()
        val secondJob = Job()
        registry.register("first", "first-message", firstJob)
        val secondTask = registry.register("second", "second-message", secondJob)

        val removed = registry.removeConversation("first")
        removed.forEach { it.job.cancel() }

        assertEquals(setOf("second-message"), registry.enrichingMessageIds.value)
        assertTrue(registry.isCurrent(secondTask))
        secondJob.complete()
    }

    private fun registry(): RpEnrichmentTaskRegistry {
        var nextId = 0
        return RpEnrichmentTaskRegistry(newSessionId = { "session-${nextId++}" })
    }

    private companion object {
        const val CONVERSATION_ID = "conversation"
        const val MESSAGE_ID = "message"
    }
}
