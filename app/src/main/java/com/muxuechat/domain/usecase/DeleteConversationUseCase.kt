package com.muxuechat.domain.usecase

import com.muxuechat.data.repository.ChatRepository

class DeleteConversationUseCase(private val repository: ChatRepository) {
    suspend operator fun invoke(conversationId: String) {
        repository.deleteConversation(conversationId)
    }
}
