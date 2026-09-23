package com.muxuechat.presentation.chat.components

import com.muxuechat.domain.model.RpEntry
import com.muxuechat.domain.model.RpMetricDefinition
import com.muxuechat.domain.model.toMetricDefinition
import com.muxuechat.domain.model.toNumericEntry
import com.muxuechat.domain.rp.RpEntryRules
import com.muxuechat.domain.rp.RpStateBlockFormat
import java.util.UUID

/**
 * 词条编辑器的草稿形状（RP-3a），角色卡、主角、配角共用。
 *
 * 数值词条的三个数字**保留字符串草稿**，保存时才解析（§7.2）：
 * 直接存 Int 会让空串与单独一个负号无法成为中间状态，等于清不掉、输不了负数。
 */
data class TextEntryDraft(
    val id: String,
    val name: String = "",
    val value: String = "",
)

data class NumericEntryDraft(
    val id: String,
    val name: String = "",
    val minText: String = "0",
    val maxText: String = "100",
    val initialText: String = "0",
    val description: String = "",
    val progressRule: String = "",
)

/** 一条数值词条的内联错误；全为 null 表示这条可以保存。 */
data class NumericEntryIssues(
    val name: String? = null,
    val min: String? = null,
    val max: String? = null,
    val initial: String? = null,
) {
    val hasAny: Boolean get() = name != null || min != null || max != null || initial != null
}

object RpEntryDrafts {
    /** 输入过程中允许的形状：可选负号 + 至多 9 位数字，保证解析时不会溢出 Int。 */
    private val NUMBER_INPUT = Regex("-?\\d{0,9}")

    fun acceptsNumberInput(text: String): Boolean = NUMBER_INPUT.matches(text)

    fun textFrom(entry: RpEntry.Text): TextEntryDraft =
        TextEntryDraft(id = entry.id, name = entry.name, value = entry.value)

    fun numericFrom(definition: RpMetricDefinition): NumericEntryDraft = NumericEntryDraft(
        id = definition.id,
        name = definition.name,
        minText = definition.minValue.toString(),
        maxText = definition.maxValue.toString(),
        initialText = definition.initialValue.toString(),
        description = definition.description,
        progressRule = definition.progressRule,
    )

    fun numericFrom(entry: RpEntry.Numeric): NumericEntryDraft = numericFrom(entry.toMetricDefinition())

    fun newText(): TextEntryDraft = TextEntryDraft(id = UUID.randomUUID().toString())

    /**
     * 文本词条只有一条规则：写了内容就得有名字。名字与内容都空的整条在保存时丢弃，不算错。
     * 文本词条不进状态命名空间，因此不限制「::」也不判重（§3.3）。
     */
    fun textNameIssue(entry: TextEntryDraft): String? =
        if (entry.name.isBlank() && entry.value.isNotBlank()) "请填写词条名称" else null

    /**
     * 按 Repository 的口径逐条校验：名字先归一化再判「::」与重名，
     * 否则只差首尾空格的两条在这里不报错、保存时却会被拒绝。
     */
    fun numericIssues(entries: List<NumericEntryDraft>): List<NumericEntryIssues> {
        val nameCounts = entries
            .map { RpEntryRules.normalizeName(it.name) }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
        return entries.map { entry ->
            val name = RpEntryRules.normalizeName(entry.name)
            val nameIssue = when {
                name.isBlank() -> "请填写状态名称，或删除这一条"
                name.contains(RpStateBlockFormat.WIRE_NAME_SEPARATOR) ->
                    "状态名称不能包含「${RpStateBlockFormat.WIRE_NAME_SEPARATOR}」，它是状态栏的归属分隔符"
                (nameCounts[name] ?: 0) > 1 -> "与其它状态条重名，状态栏只能更新其中一条"
                else -> null
            }
            val min = entry.minText.trim().toIntOrNull()
            val max = entry.maxText.trim().toIntOrNull()
            val initial = entry.initialText.trim().toIntOrNull()
            val minIssue = if (min == null) numberIssue(entry.minText) else null
            val maxIssue = when {
                max == null -> numberIssue(entry.maxText)
                min != null && max <= min -> "最大值必须大于最小值"
                else -> null
            }
            val initialIssue = when {
                initial == null -> numberIssue(entry.initialText)
                min != null && max != null && max > min && initial !in min..max ->
                    "初始值须在 $min～$max 之间"
                else -> null
            }
            NumericEntryIssues(name = nameIssue, min = minIssue, max = maxIssue, initial = initialIssue)
        }
    }

    private fun numberIssue(text: String): String =
        if (text.isBlank()) "请填写整数" else "请输入整数"

    /** 有内容的文本词条；名字与内容都空的整条丢弃。调用前应已通过 [textNameIssue]。 */
    fun toTextEntries(entries: List<TextEntryDraft>): List<RpEntry.Text> = entries
        .filter { it.name.isNotBlank() || it.value.isNotBlank() }
        .map { RpEntry.Text(id = it.id, name = it.name.trim(), value = it.value.trim()) }

    /** 调用前应已通过 [numericIssues]；这里不再兜底收敛，解析不了就抛异常。 */
    fun toMetricDefinitions(entries: List<NumericEntryDraft>): List<RpMetricDefinition> = entries.map {
        RpMetricDefinition(
            id = it.id,
            name = it.name.trim(),
            minValue = it.minText.trim().toInt(),
            maxValue = it.maxText.trim().toInt(),
            initialValue = it.initialText.trim().toInt(),
            description = it.description.trim(),
            progressRule = it.progressRule.trim(),
        )
    }

    fun toNumericEntries(entries: List<NumericEntryDraft>): List<RpEntry.Numeric> =
        toMetricDefinitions(entries).map { it.toNumericEntry() }

    /** 编辑器里有几处需要修正；为 0 才允许保存。 */
    fun issueCount(texts: List<TextEntryDraft>, numerics: List<NumericEntryDraft>): Int =
        texts.count { textNameIssue(it) != null } + numericIssues(numerics).count { it.hasAny }
}
