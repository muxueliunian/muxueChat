package com.muxuechat.domain.model

data class WorldSetting(
    val id: String,
    val title: String,
    val content: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

data class WorldSettingDraft(
    val title: String,
    val content: String = "",
)
