package com.muxuechat.domain.usecase

import com.muxuechat.data.repository.ChatRepository

class RenameConversationUseCase(private val repository: ChatRepository) {
    suspend operator fun invoke(conversationId: String, title: String) {
        repository.renameConversation(conversationId, title)
    }
}
