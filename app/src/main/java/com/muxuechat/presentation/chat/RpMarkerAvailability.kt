package com.muxuechat.presentation.chat

import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.DeepSeekModels
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

    val supportsRpMarker = DeepSeekModels.supportsRpMarker(model)
    val isThinkingEnabled = thinkingMode == ThinkingMode.ENABLED

    return when {
        supportsRpMarker && isThinkingEnabled -> RpMarkerAvailability(
            isAvailable = true,
            unavailableReason = null,
        )
        // 视觉实验旧名没有被验证过 Marker，不能因此说「当前模型不是 V4」。
        // 未知的新 ID 也一样：App 不按名字猜测能力，只如实说明它不在已验证名单里。
        !supportsRpMarker -> RpMarkerAvailability(
            isAvailable = false,
            unavailableReason = "当前模型「$model」未验证 DeepSeek Marker，暂不支持。" +
                "已验证的是 ${DeepSeekModels.FLASH} 与 ${DeepSeekModels.V4_PRO}，且需要开启思考模式。",
        )
        else -> RpMarkerAvailability(
            isAvailable = false,
            unavailableReason = "当前模型支持 DeepSeek Marker，但需要开启思考模式。" +
                "请先在输入框的「思考」里选择一个档位。",
        )
    }
}
