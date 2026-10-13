package com.muxuechat.domain.tool

import com.muxuechat.domain.model.ProtagonistDraft
import com.muxuechat.domain.model.ProtagonistProfileDraft
import com.muxuechat.domain.model.RoleCardDraft
import com.muxuechat.domain.model.RpSettingRef
import com.muxuechat.domain.model.SupportingCharacterDraft
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
    val supportingCharacter: SupportingCharacterDraft? = null,
    /** 修改主角档案时的修改稿（[SettingsTool.UPDATE_RP_SETTING]）；新建主角仍用 [protagonist]。 */
    val protagonistProfile: ProtagonistProfileDraft? = null,
    /** 修改请求的目标（[SettingsTool.UPDATE_RP_SETTING]）；不为空时保存覆盖原设定而不是新建。 */
    val update: SettingsUpdateTarget? = null,
    /** 删除请求要删的项（[SettingsTool.DELETE_RP_SETTINGS]）；其它草稿为空。 */
    val deletion: List<SettingsDeletionTarget> = emptyList(),
    /** 本地校验时发现并修正的问题，展示给用户，让他知道模型给的值被调整过。 */
    val warnings: List<String> = emptyList(),
    val createdAtMillis: Long,
) {
    val isDeletion: Boolean get() = deletion.isNotEmpty()

    val isUpdate: Boolean get() = update != null

    val displayName: String
        get() = update?.name
            ?: roleCard?.name
            ?: worldSetting?.title
            ?: writingStyle?.name
            ?: protagonist?.name
            ?: supportingCharacter?.name
            ?: deletion.singleOrNull()?.let { "${it.ref.kind.label}「${it.name}」" }
            ?: deletion.takeIf { it.isNotEmpty() }?.let { "${it.size} 项设定" }
            ?: "未命名"

    /** 待确认提示条上的前缀，例如「角色卡草稿」「删除请求」。 */
    val pendingLabel: String
        get() = update?.let { "${it.ref.kind.label}修改请求" } ?: when (tool) {
            SettingsTool.CREATE_ROLE_CARD -> "角色卡草稿"
            SettingsTool.CREATE_WORLD_SETTING -> "世界观草稿"
            SettingsTool.CREATE_WRITING_STYLE -> "文风草稿"
            SettingsTool.CREATE_PROTAGONIST -> "主角草稿"
            SettingsTool.CREATE_SUPPORTING_CHARACTER -> "配角草稿"
            SettingsTool.LIST_RP_SETTINGS -> "设定列表"
            SettingsTool.GET_RP_SETTING -> "设定详情"
            SettingsTool.UPDATE_RP_SETTING -> "修改请求"
            SettingsTool.DELETE_RP_SETTINGS -> "删除请求"
        }
}

/**
 * 删除请求里的一项。[name] 与 [usageCount] 是生成请求时的快照，只用于展示；
 * 确认界面的详情从实时数据读取，真正删除时按 [ref] 删。
 */
data class SettingsDeletionTarget(
    val ref: RpSettingRef,
    val name: String,
    val usageCount: Int,
)
