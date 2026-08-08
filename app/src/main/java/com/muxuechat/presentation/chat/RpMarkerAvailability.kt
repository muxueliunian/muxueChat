package com.muxuechat.presentation.chat

import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.ThinkingMode

internal data class RpMarkerAvailability(
    val isAvailable: Boolean,
    val unavailableReason: String?,
)

internal fun ChatSettings.rpMarkerAvailability(): RpMarkerAvailability {
    if (!usesDeepSeekParameters) {
        return RpMarkerAvailability(
            isAvailable = false,
            unavailableReason = "DeepSeek Marker 不适用于自定义供应商。",
        )
    }

    val isDeepSeekV4 = model.apiName.startsWith("deepseek-v4", ignoreCase = true)
    val isThinkingEnabled = thinkingMode == ThinkingMode.ENABLED

    return when {
        isDeepSeekV4 && isThinkingEnabled -> RpMarkerAvailability(
            isAvailable = true,
            unavailableReason = null,
        )
        !isDeepSeekV4 -> RpMarkerAvailability(
            isAvailable = false,
            unavailableReason = "仅 DeepSeek V4 且思考模式开启时可用。当前模型不是 DeepSeek V4。",
        )
        else -> RpMarkerAvailability(
            isAvailable = false,
            unavailableReason = "仅 DeepSeek V4 且思考模式开启时可用。请先在设置中开启思考模式。",
        )
    }
}
