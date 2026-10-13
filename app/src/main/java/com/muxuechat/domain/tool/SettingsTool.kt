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

    /** 主角档案保存后必须绑定到发起调用的会话，见 [com.muxuechat.domain.model.ProtagonistDraft]。 */
    CREATE_PROTAGONIST("create_protagonist"),
    CREATE_SUPPORTING_CHARACTER("create_supporting_character"),

    /** 只读：列出已有设定的完整内容与 id，供模型定位要删除的项。不产生草稿。 */
    LIST_RP_SETTINGS("list_rp_settings"),

    /** 只读：按 type + id 取单项完整内容。不产生草稿。 */
    GET_RP_SETTING("get_rp_setting"),

    /** 只产生「修改请求」草稿（只含要改的部分，与原内容合并），用户确认后才覆盖原设定。 */
    UPDATE_RP_SETTING("update_rp_setting"),

    /** 只产生「删除请求」草稿，用户确认后才删除。 */
    DELETE_RP_SETTINGS("delete_rp_settings"),
    ;

    companion object {
        fun fromApiName(value: String?): SettingsTool? =
            entries.firstOrNull { it.apiName == value }
    }
}
