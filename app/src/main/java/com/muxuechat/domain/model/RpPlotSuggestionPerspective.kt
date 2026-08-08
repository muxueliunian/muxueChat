package com.muxuechat.domain.model

enum class RpPlotSuggestionPerspective(val storageValue: String) {
    PROTAGONIST("PROTAGONIST"),
    ROLE_CARD("ROLE_CARD"),
    ;

    companion object {
        val DEFAULT = PROTAGONIST

        fun fromStorageValue(value: String?): RpPlotSuggestionPerspective =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}
