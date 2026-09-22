package com.muxuechat.data.repository

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

internal data class RpEnrichmentTask(
    val sessionId: String,
    val conversationId: String,
    val messageId: String,
    val job: Job,
    val predecessor: Job?,
)

/**
 * 同一把锁维护补写任务索引与 UI 可见集合，避免注册、取消和完成之间出现幽灵“补写中”。
 * 完成回调在任务尚未启动就被取消时也会执行，因此清理不依赖协程体内的 finally。
 */
internal class RpEnrichmentTaskRegistry(
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val byMessageId = mutableMapOf<String, RpEnrichmentTask>()
    private val bySessionId = mutableMapOf<String, RpEnrichmentTask>()
    private val tailsByConversation = mutableMapOf<String, RpEnrichmentTask>()
    private val _enrichingMessageIds = MutableStateFlow<Set<String>>(emptySet())

    val enrichingMessageIds: StateFlow<Set<String>> = _enrichingMessageIds.asStateFlow()

    fun register(conversationId: String, messageId: String, job: Job): RpEnrichmentTask = synchronized(lock) {
        byMessageId[messageId]?.job?.cancel()
        val task = RpEnrichmentTask(
            sessionId = newSessionId(),
            conversationId = conversationId,
            messageId = messageId,
            job = job,
            predecessor = tailsByConversation[conversationId]?.job,
        )
        byMessageId[messageId] = task
        bySessionId[task.sessionId] = task
        tailsByConversation[conversationId] = task
        _enrichingMessageIds.update { it + messageId }
        job.invokeOnCompletion { unregister(task) }
        task
    }

    fun unregister(task: RpEnrichmentTask) {
        synchronized(lock) {
            byMessageId.remove(task.messageId, task)
            bySessionId.remove(task.sessionId, task)
            if (tailsByConversation[task.conversationId] == task) {
                tailsByConversation.remove(task.conversationId)
            }
            if (!byMessageId.containsKey(task.messageId)) {
                _enrichingMessageIds.update { it - task.messageId }
            }
        }
    }

    fun removeConversation(conversationId: String): Set<RpEnrichmentTask> = synchronized(lock) {
        val selected = bySessionId.values
            .filterTo(linkedSetOf()) { it.conversationId == conversationId }
        removeLocked(selected)
        selected
    }

    fun removeAll(): Set<RpEnrichmentTask> = synchronized(lock) {
        val selected = bySessionId.values.toCollection(linkedSetOf())
        removeLocked(selected)
        selected
    }

    fun isCurrent(task: RpEnrichmentTask): Boolean = synchronized(lock) {
        byMessageId[task.messageId] == task && task.job.isActive
    }

    private fun removeLocked(tasks: Set<RpEnrichmentTask>) {
        tasks.forEach { task ->
            byMessageId.remove(task.messageId, task)
            bySessionId.remove(task.sessionId, task)
            if (tailsByConversation[task.conversationId] == task) {
                tailsByConversation.remove(task.conversationId)
            }
        }
        val removedMessageIds = tasks
            .asSequence()
            .map { it.messageId }
            .filterNot { byMessageId.containsKey(it) }
            .toSet()
        if (removedMessageIds.isNotEmpty()) {
            _enrichingMessageIds.update { it - removedMessageIds }
        }
    }
}
