package com.muxuechat.data.repository

import com.muxuechat.domain.model.ChatProviderId
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicReference

/**
 * 一次正文生成的进程内归属信息。
 *
 * Provider 快照只保留路由字段；API Key 仍只存在请求局部变量中，绝不进入状态或日志。
 */
internal data class GenerationSession(
    val sessionId: String,
    val conversationId: String,
    val assistantMessageId: String?,
    val job: Job,
    val providerSnapshot: GenerationProviderSnapshot,
    val status: GenerationSessionStatus,
    val isHistoryReplacement: Boolean,
)

// 避免 GenerationSession.toString() 递归展开用户配置的 Base URL 与模型名。
internal class GenerationProviderSnapshot(
    val provider: ChatProviderId,
    val baseUrl: String,
    val model: String,
)

internal enum class GenerationSessionStatus {
    STARTING,
    STREAMING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

/** 单生成策略的原子注册表；旧 session 的取消由拥有 Job 的 Repository 协调。 */
internal class GenerationSessionRegistry {
    private val active = AtomicReference<GenerationSession?>(null)

    fun start(session: GenerationSession): GenerationSession? = active.getAndSet(session)

    fun current(): GenerationSession? = active.get()

    fun currentForConversation(conversationId: String?): GenerationSession? =
        active.get()?.takeIf { session ->
            conversationId == null || session.conversationId == conversationId
        }

    fun attachAssistant(
        sessionId: String,
        assistantMessageId: String,
        isHistoryReplacement: Boolean,
    ): GenerationSession? = update(sessionId) { session ->
        session.copy(
            assistantMessageId = assistantMessageId,
            status = GenerationSessionStatus.STREAMING,
            isHistoryReplacement = isHistoryReplacement,
        )
    }

    fun updateStatus(sessionId: String, status: GenerationSessionStatus): GenerationSession? =
        update(sessionId) { session -> session.copy(status = status) }

    fun finish(sessionId: String): GenerationSession? {
        while (true) {
            val current = active.get() ?: return null
            if (current.sessionId != sessionId) return null
            if (active.compareAndSet(current, null)) return current
        }
    }

    private fun update(
        sessionId: String,
        transform: (GenerationSession) -> GenerationSession,
    ): GenerationSession? {
        while (true) {
            val current = active.get() ?: return null
            if (current.sessionId != sessionId) return null
            val updated = transform(current)
            if (active.compareAndSet(current, updated)) return updated
        }
    }
}
