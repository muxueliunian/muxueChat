package com.muxuechat.domain.model

import kotlinx.serialization.Serializable

data class RpStatusPanel(
    val conversationId: String,
    val metrics: List<RpMetric>,
    val relationship: String,
    val scene: String,
    val rawJson: String,
    val updatedAtMillis: Long,
)

@Serializable
data class RpMetric(
    val name: String,
    val value: Int,
    val min: Int = 0,
    val max: Int = 100,
)

@Serializable
data class RpMetricDefinition(
    val id: String,
    val name: String,
    val minValue: Int = 0,
    val maxValue: Int = 100,
    val initialValue: Int = 0,
    val description: String = "",
    val progressRule: String = "",
)
