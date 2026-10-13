package com.muxuechat.domain.tool

import com.muxuechat.domain.model.ProtagonistProfile
import com.muxuechat.domain.model.ProtagonistProfileDraft
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RoleCardDraft
import com.muxuechat.domain.model.RpEntry
import com.muxuechat.domain.model.RpSettingKind
import com.muxuechat.domain.model.RpSettingRef
import com.muxuechat.domain.model.SupportingCharacter
import com.muxuechat.domain.model.SupportingCharacterDraft
import com.muxuechat.domain.model.WorldSetting
import com.muxuechat.domain.model.WorldSettingDraft
import com.muxuechat.domain.model.WritingStyleProfile
import com.muxuechat.domain.model.WritingStyleProfileDraft
import com.muxuechat.domain.model.toMetricDefinition
import com.muxuechat.domain.rp.RpStateBlockFormat
import java.util.UUID

/**
 * `update_rp_setting` 的参数：**只描述要改的部分**，没给的字段保持原样。
 *
 * [fields] 的键是工具协议里的字段名（name、persona、content……），值是该字段的完整新文本。
 * 词条按名称定位：[textEntries] 与 [metrics] 同名则改、不同名则加，[removeEntries] 按名删除。
 */
data class RpSettingPatch(
    val fields: Map<String, String> = emptyMap(),
    val textEntries: List<Pair<String, String>> = emptyList(),
    val metrics: List<MetricPatch> = emptyList(),
    val removeEntries: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = fields.isEmpty() && textEntries.isEmpty() && metrics.isEmpty() && removeEntries.isEmpty()
}

/** 一个状态条的修改；为 null 的数字表示沿用原值（新建时用默认值）。 */
data class MetricPatch(
    val name: String,
    val minValue: Int? = null,
    val maxValue: Int? = null,
    val initialValue: Int? = null,
    val description: String? = null,
    val progressRule: String? = null,
)

/** 被修改的那一项此刻在库里的样子。 */
sealed interface RpSettingCurrent {
    val ref: RpSettingRef
    val displayName: String
    val updatedAtMillis: Long

    data class OfRoleCard(val value: RoleCard) : RpSettingCurrent {
        override val ref get() = RpSettingRef(RpSettingKind.ROLE_CARD, value.id)
        override val displayName get() = value.name
        override val updatedAtMillis get() = value.updatedAtMillis
    }

    data class OfSupportingCharacter(val value: SupportingCharacter) : RpSettingCurrent {
        override val ref get() = RpSettingRef(RpSettingKind.SUPPORTING_CHARACTER, value.id)
        override val displayName get() = value.name
        override val updatedAtMillis get() = value.updatedAtMillis
    }

    data class OfProtagonist(val value: ProtagonistProfile) : RpSettingCurrent {
        override val ref get() = RpSettingRef(RpSettingKind.PROTAGONIST, value.id)
        override val displayName get() = value.name.ifBlank { "未命名主角" }
        override val updatedAtMillis get() = value.updatedAtMillis
    }

    data class OfWorldSetting(val value: WorldSetting) : RpSettingCurrent {
        override val ref get() = RpSettingRef(RpSettingKind.WORLD_SETTING, value.id)
        override val displayName get() = value.title
        override val updatedAtMillis get() = value.updatedAtMillis
    }

    data class OfWritingStyle(val value: WritingStyleProfile) : RpSettingCurrent {
        override val ref get() = RpSettingRef(RpSettingKind.WRITING_STYLE, value.id)
        override val displayName get() = value.name
        override val updatedAtMillis get() = value.updatedAtMillis
    }
}

/** 修改草稿的目标与说明。保存时按 [baseUpdatedAtMillis] 检查这期间有没有被别处改过。 */
data class SettingsUpdateTarget(
    val ref: RpSettingRef,
    val name: String,
    val usageCount: Int,
    val baseUpdatedAtMillis: Long,
    /** 给用户看的变更清单，例如「人设」「状态条「好感度」：修改」。 */
    val changes: List<String>,
    /** 定义被改动的状态条名：所有使用这项设定的会话里，它们会回到新的初始值。 */
    val resetMetricNames: List<String> = emptyList(),
)

/** 把修改套到现有设定上的结果：与新建共用同一套草稿形状，界面与保存都复用。 */
data class RpSettingPatchResult(
    val roleCard: RoleCardDraft? = null,
    val supportingCharacter: SupportingCharacterDraft? = null,
    val protagonistProfile: ProtagonistProfileDraft? = null,
    val worldSetting: WorldSettingDraft? = null,
    val writingStyle: WritingStyleProfileDraft? = null,
    val changes: List<String>,
    val resetMetricNames: List<String>,
    val warnings: List<String>,
)

/**
 * 纯函数：现有设定 + 修改 → 修改后的完整草稿。
 *
 * 词条的 id 原样保留（R14），状态值靠它找回；新加的词条才生成新 id。
 * 修改是否「真的改了什么」按修改前后逐项比较，而不是按模型给了哪些字段。
 */
object RpSettingPatcher {

    /** 每类允许修改的普通字段（工具协议名 → 显示名）。 */
    fun allowedFields(kind: RpSettingKind): Map<String, String> = when (kind) {
        RpSettingKind.ROLE_CARD -> linkedMapOf(
            "name" to "名称",
            "description" to "简介",
            "persona" to "人设",
            "speaking_style" to "说话风格",
            "user_identity" to "用户身份",
            "boundaries" to "禁止事项与边界",
        )
        RpSettingKind.SUPPORTING_CHARACTER, RpSettingKind.PROTAGONIST -> linkedMapOf(
            "name" to "名称",
            "description" to "简介",
        )
        RpSettingKind.WORLD_SETTING -> linkedMapOf("title" to "标题", "content" to "正文")
        RpSettingKind.WRITING_STYLE -> linkedMapOf("name" to "名称", "content" to "要求")
    }

    fun supportsMetrics(kind: RpSettingKind): Boolean =
        kind == RpSettingKind.ROLE_CARD || kind == RpSettingKind.PROTAGONIST

    fun supportsEntries(kind: RpSettingKind): Boolean =
        kind == RpSettingKind.ROLE_CARD || kind == RpSettingKind.PROTAGONIST ||
            kind == RpSettingKind.SUPPORTING_CHARACTER

    /** 失败时返回原因（给模型看），成功返回结果。 */
    fun apply(
        current: RpSettingCurrent,
        patch: RpSettingPatch,
        idGenerator: () -> String = { UUID.randomUUID().toString() },
    ): Result<RpSettingPatchResult> = runCatching {
        val kind = current.ref.kind
        val allowed = allowedFields(kind)
        val unknown = patch.fields.keys - allowed.keys
        require(unknown.isEmpty()) {
            "${kind.label}没有这些字段：${unknown.joinToString("、")}。可改的字段：${allowed.keys.joinToString("、")}。"
        }
        require(supportsEntries(kind) || (patch.textEntries.isEmpty() && patch.removeEntries.isEmpty())) {
            "${kind.label}没有词条，不能使用 set_text_entries 或 remove_entries。"
        }
        require(supportsMetrics(kind) || patch.metrics.isEmpty()) {
            "${kind.label}没有状态条，不能使用 set_metrics。"
        }
        val warnings = mutableListOf<String>()
        fun field(key: String, old: String): String = patch.fields[key] ?: old

        val result = when (current) {
            is RpSettingCurrent.OfRoleCard -> {
                val card = current.value
                val entries = patchEntries(card.entries, patch, warnings, idGenerator)
                RpSettingPatchResult(
                    roleCard = RoleCardDraft(
                        name = field("name", card.name),
                        description = field("description", card.description),
                        persona = field("persona", card.persona),
                        speakingStyle = field("speaking_style", card.speakingStyle),
                        userIdentity = field("user_identity", card.userIdentity),
                        boundaries = field("boundaries", card.boundaries),
                        metricDefinitions = entries.filterIsInstance<RpEntry.Numeric>().map { it.toMetricDefinition() },
                        textEntries = entries.filterIsInstance<RpEntry.Text>(),
                    ),
                    changes = fieldChanges(
                        allowed,
                        mapOf(
                            "name" to (card.name to field("name", card.name)),
                            "description" to (card.description to field("description", card.description)),
                            "persona" to (card.persona to field("persona", card.persona)),
                            "speaking_style" to (card.speakingStyle to field("speaking_style", card.speakingStyle)),
                            "user_identity" to (card.userIdentity to field("user_identity", card.userIdentity)),
                            "boundaries" to (card.boundaries to field("boundaries", card.boundaries)),
                        ),
                    ) + entryChanges(card.entries, entries),
                    resetMetricNames = changedMetricNames(card.entries, entries),
                    warnings = warnings,
                )
            }

            is RpSettingCurrent.OfProtagonist -> {
                val profile = current.value
                val entries = patchEntries(profile.entries, patch, warnings, idGenerator)
                RpSettingPatchResult(
                    protagonistProfile = ProtagonistProfileDraft(
                        name = field("name", profile.name),
                        description = field("description", profile.description),
                        entries = entries,
                    ),
                    changes = fieldChanges(
                        allowed,
                        mapOf(
                            "name" to (profile.name to field("name", profile.name)),
                            "description" to (profile.description to field("description", profile.description)),
                        ),
                    ) + entryChanges(profile.entries, entries),
                    resetMetricNames = changedMetricNames(profile.entries, entries),
                    warnings = warnings,
                )
            }

            is RpSettingCurrent.OfSupportingCharacter -> {
                val character = current.value
                val entries = patchEntries(character.entries, patch, warnings, idGenerator)
                    .filterIsInstance<RpEntry.Text>()
                RpSettingPatchResult(
                    supportingCharacter = SupportingCharacterDraft(
                        name = field("name", character.name),
                        description = field("description", character.description),
                        entries = entries,
                    ),
                    changes = fieldChanges(
                        allowed,
                        mapOf(
                            "name" to (character.name to field("name", character.name)),
                            "description" to (character.description to field("description", character.description)),
                        ),
                    ) + entryChanges(character.entries, entries),
                    resetMetricNames = emptyList(),
                    warnings = warnings,
                )
            }

            is RpSettingCurrent.OfWorldSetting -> {
                val world = current.value
                RpSettingPatchResult(
                    worldSetting = WorldSettingDraft(
                        title = field("title", world.title),
                        content = field("content", world.content),
                    ),
                    changes = fieldChanges(
                        allowed,
                        mapOf(
                            "title" to (world.title to field("title", world.title)),
                            "content" to (world.content to field("content", world.content)),
                        ),
                    ),
                    resetMetricNames = emptyList(),
                    warnings = warnings,
                )
            }

            is RpSettingCurrent.OfWritingStyle -> {
                val style = current.value
                RpSettingPatchResult(
                    writingStyle = WritingStyleProfileDraft(
                        name = field("name", style.name),
                        content = field("content", style.content),
                    ),
                    changes = fieldChanges(
                        allowed,
                        mapOf(
                            "name" to (style.name to field("name", style.name)),
                            "content" to (style.content to field("content", style.content)),
                        ),
                    ),
                    resetMetricNames = emptyList(),
                    warnings = warnings,
                )
            }
        }
        val nameKey = if (kind == RpSettingKind.WORLD_SETTING) "title" else "name"
        require(patch.fields[nameKey]?.isBlank() != true || kind == RpSettingKind.PROTAGONIST) {
            "${allowed[nameKey]}不能改成空。"
        }
        require(result.changes.isNotEmpty()) { "修改后的内容与现有设定完全相同，没有需要修改的地方。" }
        result
    }

    private fun patchEntries(
        original: List<RpEntry>,
        patch: RpSettingPatch,
        warnings: MutableList<String>,
        idGenerator: () -> String,
    ): List<RpEntry> {
        val entries = original.toMutableList()
        patch.removeEntries.forEach { name ->
            if (!entries.removeAll { it.name == name }) warnings += "要删除的词条「$name」不存在，已忽略。"
        }
        patch.textEntries.forEach { (name, value) ->
            val index = entries.indexOfFirst { it is RpEntry.Text && it.name == name }
            if (index >= 0) {
                entries[index] = (entries[index] as RpEntry.Text).copy(value = value)
            } else if (entries.count { it is RpEntry.Text } >= MAX_TEXT_ENTRIES) {
                warnings += "文本词条已达 $MAX_TEXT_ENTRIES 条上限，「$name」未添加。"
            } else {
                // 新文本词条排在数值词条前面，与保存时的落库顺序一致。
                val insertAt = entries.indexOfFirst { it is RpEntry.Numeric }.takeIf { it >= 0 } ?: entries.size
                entries.add(insertAt, RpEntry.Text(id = idGenerator(), name = name, value = value))
            }
        }
        patch.metrics.forEach { metric ->
            val name = metric.name.replace(RpStateBlockFormat.WIRE_NAME_SEPARATOR, "·")
            if (name != metric.name) {
                warnings += "状态条「${metric.name}」含保留符号「${RpStateBlockFormat.WIRE_NAME_SEPARATOR}」，已改为「$name」。"
            }
            val index = entries.indexOfFirst { it is RpEntry.Numeric && it.name == name }
            val base = if (index >= 0) {
                entries[index] as RpEntry.Numeric
            } else {
                if (entries.count { it is RpEntry.Numeric } >= MAX_METRICS) {
                    warnings += "状态条已达 $MAX_METRICS 个上限，「$name」未添加。"
                    return@forEach
                }
                RpEntry.Numeric(id = idGenerator(), name = name, minValue = 0, maxValue = 100, initialValue = 0)
            }
            val min = metric.minValue ?: base.minValue
            val max = (metric.maxValue ?: base.maxValue).let { candidate ->
                if (candidate > min) candidate else (min + 1).also {
                    warnings += "状态条「$name」的最大值不大于最小值，已调整为 $it。"
                }
            }
            val rawInitial = metric.initialValue ?: if (index >= 0) base.initialValue else min
            val initial = rawInitial.coerceIn(min, max)
            if (initial != rawInitial) warnings += "状态条「$name」的初始值 $rawInitial 超出范围，已调整为 $initial。"
            val updated = base.copy(
                minValue = min,
                maxValue = max,
                initialValue = initial,
                description = metric.description ?: base.description,
                progressRule = metric.progressRule ?: base.progressRule,
            )
            if (index >= 0) entries[index] = updated else entries += updated
        }
        return entries
    }

    private fun fieldChanges(
        labels: Map<String, String>,
        values: Map<String, Pair<String, String>>,
    ): List<String> = labels.mapNotNull { (key, label) ->
        val (old, new) = values[key] ?: return@mapNotNull null
        if (old.trim() == new.trim()) null else label
    }

    private fun entryChanges(before: List<RpEntry>, after: List<RpEntry>): List<String> {
        val beforeById = before.associateBy { it.id }
        val afterIds = after.map { it.id }.toSet()
        fun label(entry: RpEntry) = if (entry is RpEntry.Numeric) "状态条「${entry.name}」" else "词条「${entry.name}」"
        return after.mapNotNull { entry ->
            when (val old = beforeById[entry.id]) {
                null -> "${label(entry)}：新增"
                entry -> null
                else -> "${label(entry)}：修改"
            }
        } + before.filter { it.id !in afterIds }.map { "${label(it)}：删除" }
    }

    private fun changedMetricNames(before: List<RpEntry>, after: List<RpEntry>): List<String> {
        val beforeById = before.filterIsInstance<RpEntry.Numeric>().associateBy { it.id }
        return after.filterIsInstance<RpEntry.Numeric>()
            .filter { entry -> beforeById[entry.id]?.let { it != entry } == true }
            .map { it.name }
    }

    const val MAX_TEXT_ENTRIES = 30
    const val MAX_METRICS = 12
}
