package com.muxuechat.domain.usecase

import com.muxuechat.data.repository.ChatRepository
import com.muxuechat.domain.model.ChatMessage
import com.muxuechat.domain.model.MessageImageEdit
import kotlinx.coroutines.flow.Flow

class EditUserMessageAndRegenerateUseCase(private val repository: ChatRepository) {
    operator fun invoke(
        messageId: String,
        content: String,
        imageEdit: MessageImageEdit? = null,
    ): Flow<ChatMessage> = repository.editUserMessageAndRegenerate(messageId, content, imageEdit)
}
