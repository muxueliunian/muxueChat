package com.muxuechat.domain.usecase

import com.muxuechat.data.repository.ChatRepository

class EditAssistantMessageUseCase(private val repository: ChatRepository) {
    suspend operator fun invoke(messageId: String, content: String) =
        repository.editAssistantMessage(messageId, content)
}
