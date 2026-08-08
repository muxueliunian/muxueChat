package com.muxuechat.domain.model

data class ConversationRpSettings(
    val conversationId: String,
    val isRpEnabled: Boolean = false,
    val roleCardId: String? = null,
    val worldSettingId: String? = null,
    val writingStyleId: String? = null,
    val protagonistName: String = "",
    val protagonistDescription: String = "",
    val protagonistInitialState: String = "",
    val narrativeMinChars: Int = 0,
    val narrativePerspective: RpNarrativePerspective = RpNarrativePerspective.DEFAULT,
    val plotSuggestionPerspective: RpPlotSuggestionPerspective = RpPlotSuggestionPerspective.DEFAULT,
    val rpMarkerMode: RpMarkerMode = RpMarkerMode.DEFAULT,
    val updatedAtMillis: Long,
)
