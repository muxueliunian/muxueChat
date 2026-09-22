package com.muxuechat.data.remote

import com.muxuechat.domain.context.ContextToolDefinition
import com.muxuechat.domain.tool.SettingsTool
import com.muxuechat.domain.tool.SettingsToolSchemas

/** 把 domain 层的工具定义翻译成各协议共用的 OpenAI function tools 请求字段。 */
object SettingsToolCatalog {

    fun tools(): List<DeepSeekToolDto> = SettingsTool.entries.map { tool ->
        DeepSeekToolDto(
            function = DeepSeekFunctionDto(
                name = tool.apiName,
                description = SettingsToolSchemas.descriptionOf(tool),
                parameters = SettingsToolSchemas.parametersOf(tool),
            ),
        )
    }

    fun openAiCompatibleTools(): List<OpenAiCompatibleToolDto> = SettingsTool.entries.map { tool ->
        OpenAiCompatibleToolDto(
            function = OpenAiCompatibleFunctionDto(
                name = tool.apiName,
                description = SettingsToolSchemas.descriptionOf(tool),
                parameters = SettingsToolSchemas.parametersOf(tool),
            ),
        )
    }

    /**
     * 供上下文规划器估算用的纯 domain 形态。
     *
     * 工具 schema 是实打实发出去的字节，旧实现完全没把它计入预算——非 RP 模式下等于凭空少算
     * 几百 token。这里与上面两个 wire 形态同源，不会出现「估算的工具」和「发送的工具」不一致。
     */
    fun contextDefinitions(): List<ContextToolDefinition> = SettingsTool.entries.map { tool ->
        ContextToolDefinition(
            name = tool.apiName,
            description = SettingsToolSchemas.descriptionOf(tool),
            parametersJson = SettingsToolSchemas.parametersOf(tool).toString(),
        )
    }

    const val TOOL_CHOICE_AUTO = "auto"
}
