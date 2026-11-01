package com.muxuechat.domain.usecase

import com.muxuechat.data.repository.ChatRepository
import com.muxuechat.domain.model.ChatMessage
import com.muxuechat.domain.model.PendingImage
import kotlinx.coroutines.flow.Flow

class SendMessageUseCase(private val repository: ChatRepository) {
    operator fun invoke(
        conversationId: String?,
        content: String,
        images: List<PendingImage> = emptyList(),
    ): Flow<ChatMessage> = repository.sendMessage(conversationId, content, images)
}
