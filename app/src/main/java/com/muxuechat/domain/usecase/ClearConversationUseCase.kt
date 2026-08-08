package com.muxuechat.domain.usecase

import com.muxuechat.data.repository.ChatRepository

class ClearConversationUseCase(private val repository: ChatRepository) {
    suspend operator fun invoke(conversationId: String) {
        repository.clearConversation(conversationId)
    }
}
