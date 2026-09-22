package com.muxuechat.domain.usecase

import com.muxuechat.data.repository.ChatRepository

class StopGenerationUseCase(private val repository: ChatRepository) {
    suspend operator fun invoke(conversationId: String? = null) {
        repository.stopGeneration(conversationId)
    }
}
