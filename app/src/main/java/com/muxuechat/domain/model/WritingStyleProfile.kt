package com.muxuechat.domain.model

/**
 * 文风设定。
 *
 * 与角色卡的 [RoleCard.speakingStyle] 是两件事，不要混用：
 * - `speakingStyle` 描述**这个角色怎么说话**（用词、语气、口头禅）。
 * - 文风描述**整段正文怎么写**（叙事视角、时态、节奏、描写密度、对白比例、禁用表达）。
 *
 * 它也不能写进 [ChatSettings.systemPrompt]——那是全局普通聊天设置，
 * 会影响所有其他会话，而文风只应作用于绑定了它的 RP 会话。
 */
data class WritingStyleProfile(
    val id: String,
    val name: String,
    val content: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

data class WritingStyleProfileDraft(
    val name: String,
    val content: String = "",
)
