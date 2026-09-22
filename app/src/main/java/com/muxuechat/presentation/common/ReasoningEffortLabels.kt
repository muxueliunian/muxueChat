package com.muxuechat.presentation.common

import com.muxuechat.domain.model.ReasoningEffort

/**
 * DeepSeek 思考等级的中文名。
 *
 * 界面上先给中文，API 原值放在副文案里——用户读得懂档位，排查请求时也能对上字段值。
 * 只适用于 DeepSeek：自定义供应商的 `reasoning_effort` 是自由文本，不能套这套枚举。
 */
val ReasoningEffort.displayName: String
    get() = when (this) {
        ReasoningEffort.LOW -> "轻度思考"
        ReasoningEffort.HIGH -> "深度思考"
        ReasoningEffort.MAX -> "最大强度"
    }

/** 副文案：直接写请求体里会发出去的值。 */
val ReasoningEffort.apiValueLabel: String
    get() = apiName
