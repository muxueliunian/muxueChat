package com.muxuechat.data.remote

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

    const val TOOL_CHOICE_AUTO = "auto"
}
