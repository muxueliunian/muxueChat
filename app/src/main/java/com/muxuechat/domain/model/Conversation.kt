package com.muxuechat.domain.model

data class Conversation(
    val id: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
