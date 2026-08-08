package com.muxuechat.domain.model

enum class RpMarkerMode(val storageValue: String) {
    DISABLED("DISABLED"),
    ROLE_IMMERSION("ROLE_IMMERSION"),
    ANALYTICAL("ANALYTICAL"),
    ;

    companion object {
        val DEFAULT = DISABLED

        fun fromStorageValue(value: String?): RpMarkerMode =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}
