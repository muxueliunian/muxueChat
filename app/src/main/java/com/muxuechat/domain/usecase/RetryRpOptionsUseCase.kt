package com.muxuechat.domain.usecase

import com.muxuechat.data.repository.ChatRepository
import com.muxuechat.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow

class RetryRpOptionsUseCase(private val repository: ChatRepository) {
    operator fun invoke(conversationId: String): Flow<ChatMessage> =
        repository.retryLatestRpOptions(conversationId)
}
