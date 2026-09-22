package com.muxuechat.domain.model

/**
 * 主角草稿。
 *
 * 主角**目前不是独立实体**：它就是 [ConversationRpSettings] 里的三段文本，属于某个会话。
 * 因此这份草稿只能保存到一个具体会话，不像角色卡 / 世界观 / 文风那样可以先入库再随时绑定。
 *
 * `docs/RP_ACTOR_PLAN.md` 的 RP-1 会把主角升格为带词条与数值的独立实体，
 * 届时这个模型、对应的工具 schema 与保存路径都要跟着改。
 */
data class ProtagonistDraft(
    val name: String,
    val description: String = "",
    val initialState: String = "",
)
