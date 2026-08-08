package com.muxuechat.domain.model

/**
 * 当前聊天请求应使用的**协议**，而不是具体服务商。
 *
 * 自定义供应商有多个，但它们共用同一套 OpenAI 兼容请求链路，
 * 具体是哪一个由 [ChatSettings.activeCustomProviderId] 表达。
 *
 * 此枚举只描述路由目标，不包含 API Key、OAuth Token 或其他凭据。
 */
enum class ChatProviderId(val storageValue: String) {
    DEEPSEEK("deepseek"),
    CUSTOM_OPENAI("custom_openai"),
    ;

    companion object {
        /** 一般化改造前写入的旧值，升级后必须继续落到 OpenAI 兼容链路。 */
        private const val LEGACY_CLI_PROXY_API = "cli_proxy_api"

        /** 未知或旧版本未写入的值一律回退到既有的 DeepSeek 行为。 */
        fun fromStorage(value: String?): ChatProviderId = when (value) {
            LEGACY_CLI_PROXY_API -> CUSTOM_OPENAI
            else -> entries.firstOrNull { it.storageValue == value } ?: DEEPSEEK
        }
    }
}
