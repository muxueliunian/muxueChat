package com.muxuechat.domain.model

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: ChatRole,
    val content: String,
    val reasoningContent: String?,
    val status: MessageStatus,
    val errorMessage: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val rpMetadata: RpMessageMetadata? = null,
    /** 只有用户消息会带图片，按 [MessageAttachment.orderIndex] 排好序。 */
    val attachments: List<MessageAttachment> = emptyList(),
)
