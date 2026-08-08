package com.muxuechat.domain.tool

/**
 * 模型在**非 RP 模式**下可以调用的工具。
 *
 * 只在 RP 关闭时挂载：RP 进行中给工具会让模型在剧情中途跑去建卡，打断沉浸。
 */
enum class SettingsTool(val apiName: String) {
    CREATE_ROLE_CARD("create_role_card"),
    CREATE_WORLD_SETTING("create_world_setting"),
    CREATE_WRITING_STYLE("create_writing_style"),
    ;

    companion object {
        fun fromApiName(value: String?): SettingsTool? =
            entries.firstOrNull { it.apiName == value }
    }
}
