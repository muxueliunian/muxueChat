package com.muxuechat.domain.model

data class RoleCard(
    val id: String,
    val name: String,
    val description: String,
    val persona: String,
    val speakingStyle: String,
    val userIdentity: String,
    val boundaries: String,
    /**
     * 文本词条与数值词条的统一存储（RP-1 起是唯一读源）。
     *
     * 旧的 `metricDefinitions` 现在是它的只读视图：prompt 组装、编辑器与工具
     * 仍按旧形状工作，等 RP-2 / RP-3 再逐步换成直接用词条。
     */
    val entries: List<RpEntry> = emptyList(),
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    val metricDefinitions: List<RpMetricDefinition>
        get() = entries.filterIsInstance<RpEntry.Numeric>().map { it.toMetricDefinition() }

    val textEntries: List<RpEntry.Text>
        get() = entries.filterIsInstance<RpEntry.Text>()
}

data class RoleCardDraft(
    val name: String,
    val description: String = "",
    val persona: String = "",
    val speakingStyle: String = "",
    val userIdentity: String = "",
    val boundaries: String = "",
    val metricDefinitions: List<RpMetricDefinition> = emptyList(),
    val textEntries: List<RpEntry.Text> = emptyList(),
) {
    /** 保存时的落库形状：文本在前、数值在后。 */
    fun toEntries(): List<RpEntry> = textEntries + metricDefinitions.map { it.toNumericEntry() }
}
