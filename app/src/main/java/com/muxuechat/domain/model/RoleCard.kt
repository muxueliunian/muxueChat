package com.muxuechat.domain.model

data class RoleCard(
    val id: String,
    val name: String,
    val description: String,
    val persona: String,
    val speakingStyle: String,
    val userIdentity: String,
    val openingMessage: String,
    val boundaries: String,
    val metricDefinitions: List<RpMetricDefinition> = emptyList(),
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

data class RoleCardDraft(
    val name: String,
    val description: String = "",
    val persona: String = "",
    val speakingStyle: String = "",
    val userIdentity: String = "",
    val openingMessage: String = "",
    val boundaries: String = "",
    val metricDefinitions: List<RpMetricDefinition> = emptyList(),
)
