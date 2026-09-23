package com.muxuechat.domain.model

/**
 * 主角档案：从 v9 的三个字符串（姓名 / 描述 / 初始状态）升格而来的独立实体。
 *
 * 与角色卡的区别见 RP_ACTOR_PLAN §3.2：主角不需要 `persona`、`speakingStyle`、
 * `userIdentity`、`openingMessage`、`boundaries`——这些要么是角色卡视角的概念，
 * 要么用文本词条表达即可。
 */
data class ProtagonistProfile(
    val id: String,
    val name: String,
    val description: String = "",
    val entries: List<RpEntry> = emptyList(),
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    val metricDefinitions: List<RpMetricDefinition>
        get() = entries.filterIsInstance<RpEntry.Numeric>().map { it.toMetricDefinition() }

    val textEntries: List<RpEntry.Text>
        get() = entries.filterIsInstance<RpEntry.Text>()

    /** RP 设置里「初始状态」输入框对应的文本词条；同名多条时取第一条。 */
    val initialStateText: String
        get() = textEntries.firstOrNull { it.name == INITIAL_STATE_ENTRY_NAME }?.value.orEmpty()

    companion object {
        /** v9「初始状态」字段转成的文本词条名；迁移、设定生成工具与 RP 设置共用。 */
        const val INITIAL_STATE_ENTRY_NAME = "初始状态"
    }
}

data class ProtagonistProfileDraft(
    val name: String,
    val description: String = "",
    val entries: List<RpEntry> = emptyList(),
)

/**
 * 配角：只进正文 prompt，不进状态块也不进元数据。
 *
 * [entries] 的类型写死为 [RpEntry.Text]，让「配角不能有数值」成为**类型约束**
 * 而不是运行时校验。
 */
data class SupportingCharacter(
    val id: String,
    val name: String,
    val description: String = "",
    val entries: List<RpEntry.Text> = emptyList(),
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

data class SupportingCharacterDraft(
    val name: String,
    val description: String = "",
    val entries: List<RpEntry.Text> = emptyList(),
)
