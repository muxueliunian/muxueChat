package com.muxuechat.domain.model

enum class RpNarrativePerspective(val storageValue: String) {
    PROTAGONIST_SECOND_PERSON("PROTAGONIST_SECOND_PERSON"),
    ROLE_CARD("ROLE_CARD"),
    ;

    companion object {
        val DEFAULT = PROTAGONIST_SECOND_PERSON

        fun fromStorageValue(value: String?): RpNarrativePerspective =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}
