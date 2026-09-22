package com.muxuechat.domain.tool

import com.muxuechat.domain.model.ProtagonistDraft
import com.muxuechat.domain.model.RoleCardDraft
import com.muxuechat.domain.model.WorldSettingDraft
import com.muxuechat.domain.model.WritingStyleProfileDraft

/**
 * 工具调用产出的**草稿**，只存在于内存中。
 *
 * 这是安全契约的核心：模型调用工具不等于写数据库。草稿必须先回到 UI
 * 让用户预览、修改、确认，用户点保存才落库。取消则整份丢弃，数据库无痕。
 */
data class SettingsToolDraft(
    val id: String,
    val tool: SettingsTool,
    /** 发起这次调用的会话，用于保存后可选绑定。 */
    val conversationId: String?,
    val roleCard: RoleCardDraft? = null,
    val worldSetting: WorldSettingDraft? = null,
    val writingStyle: WritingStyleProfileDraft? = null,
    /** 主角只能存进会话的 RP 设置，因此它不为空时 [conversationId] 必须存在才能保存。 */
    val protagonist: ProtagonistDraft? = null,
    /** 本地校验时发现并修正的问题，展示给用户，让他知道模型给的值被调整过。 */
    val warnings: List<String> = emptyList(),
    val createdAtMillis: Long,
) {
    val displayName: String
        get() = roleCard?.name
            ?: worldSetting?.title
            ?: writingStyle?.name
            ?: protagonist?.name
            ?: "未命名"

    val typeLabel: String
        get() = when (tool) {
            SettingsTool.CREATE_ROLE_CARD -> "角色卡"
            SettingsTool.CREATE_WORLD_SETTING -> "世界观"
            SettingsTool.CREATE_WRITING_STYLE -> "文风"
            SettingsTool.CREATE_PROTAGONIST -> "主角"
        }
}
