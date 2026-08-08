package com.muxuechat.domain.model

data class RpMessageMetadata(
    val messageId: String,
    val conversationId: String,
    val innerThought: String,
    val options: List<String>,
    val statusPanelSnapshot: RpStatusPanel?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
