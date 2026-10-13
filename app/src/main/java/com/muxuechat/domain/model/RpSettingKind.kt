package com.muxuechat.domain.model

/**
 * 可被设定工具列出与删除的五类 RP 设定。
 *
 * [apiName] 是工具协议里的类型名，固定写死，与枚举名解耦——改 Kotlin 名字不应让模型的调用失效。
 */
enum class RpSettingKind(val apiName: String, val label: String) {
    ROLE_CARD("role_card", "角色卡"),
    SUPPORTING_CHARACTER("supporting_character", "配角"),
    PROTAGONIST("protagonist", "主角"),
    WORLD_SETTING("world_setting", "世界观"),
    WRITING_STYLE("writing_style", "文风"),
    ;

    companion object {
        fun fromApiName(value: String?): RpSettingKind? = entries.firstOrNull { it.apiName == value?.trim() }
    }
}

/** 一条设定的引用：类型 + id。 */
data class RpSettingRef(
    val kind: RpSettingKind,
    val id: String,
)
