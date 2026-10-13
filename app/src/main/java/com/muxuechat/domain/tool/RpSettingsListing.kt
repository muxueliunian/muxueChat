package com.muxuechat.domain.tool

import com.muxuechat.domain.model.ProtagonistProfile
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RpEntry
import com.muxuechat.domain.model.RpSettingKind
import com.muxuechat.domain.model.RpSettingRef
import com.muxuechat.domain.model.SupportingCharacter
import com.muxuechat.domain.model.WorldSetting
import com.muxuechat.domain.model.WritingStyleProfile

/** [RpSettingsListing] 的输入：库里的全部设定与各自被几个会话使用。 */
data class RpSettingsInventory(
    val roleCards: List<RoleCard> = emptyList(),
    val supportingCharacters: List<SupportingCharacter> = emptyList(),
    val protagonists: List<ProtagonistProfile> = emptyList(),
    val worldSettings: List<WorldSetting> = emptyList(),
    val writingStyles: List<WritingStyleProfile> = emptyList(),
    val usage: Map<RpSettingRef, Int> = emptyMap(),
)

/**
 * `list_rp_settings` 的结果文本（用户选定：给完整内容，不给摘要）。
 *
 * 结果会进入本轮上下文，因此总长有上限 [maxChars]：放不下的项**整项不列**并写明还有几项，
 * 不截断单项内容——半截人设比没有更容易让模型误判。模型可以用 types 缩小范围再查。
 */
object RpSettingsListing {
    const val DEFAULT_MAX_CHARS = 40_000

    fun format(
        inventory: RpSettingsInventory,
        kinds: Set<RpSettingKind>,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): String {
        val wanted = kinds.ifEmpty { RpSettingKind.entries.toSet() }
        val blocks = RpSettingKind.entries.filter { it in wanted }.flatMap { kind -> blocksOf(inventory, kind) }
        if (blocks.isEmpty()) {
            return "没有找到任何${wanted.joinToString("、") { it.label }}。"
        }

        val listed = mutableListOf<String>()
        var used = 0
        for (block in blocks) {
            if (used + block.length > maxChars && listed.isNotEmpty()) break
            listed += block
            used += block.length
        }
        return buildString {
            append("共 ${blocks.size} 项设定")
            if (listed.size < blocks.size) {
                append("，内容过长，本次只列出前 ${listed.size} 项，还有 ${blocks.size - listed.size} 项未列出；")
                append("请用 types 只查需要的类型")
            }
            appendLine("。修改或删除时使用每项的 type 与 id。")
            listed.forEach { block ->
                appendLine()
                append(block)
            }
        }.trimEnd()
    }

    /** 删除确认界面的单项详情：与列表同一份内容，只是不带 id 行。已不存在时返回 null。 */
    fun detail(inventory: RpSettingsInventory, ref: RpSettingRef): String? =
        bodiesOf(inventory, ref.kind).firstOrNull { it.first == ref.id }?.second?.trimEnd()

    /** `get_rp_setting` 的结果：与列表里的单项同一格式（含 id 行）。已不存在时返回 null。 */
    fun item(inventory: RpSettingsInventory, ref: RpSettingRef): String? =
        blocksOf(inventory, ref.kind, onlyId = ref.id).firstOrNull()?.trimEnd()

    private fun blocksOf(inventory: RpSettingsInventory, kind: RpSettingKind, onlyId: String? = null): List<String> =
        bodiesOf(inventory, kind).filter { onlyId == null || it.first == onlyId }.map { (id, body) ->
            val count = inventory.usage[RpSettingRef(kind, id)] ?: 0
            val usageLabel = if (kind == RpSettingKind.SUPPORTING_CHARACTER) "被几个会话勾选" else "被几个会话使用"
            "[${kind.apiName}] id=$id（${kind.label}，$usageLabel：$count）\n" + body
        }

    /** 每项的 id 与正文（不含 id 行）。 */
    private fun bodiesOf(inventory: RpSettingsInventory, kind: RpSettingKind): List<Pair<String, String>> =
        when (kind) {
            RpSettingKind.ROLE_CARD -> inventory.roleCards.map { card ->
                card.id to buildString {
                    field("名称", card.name)
                    field("简介", card.description)
                    field("人设", card.persona)
                    field("说话风格", card.speakingStyle)
                    field("用户身份", card.userIdentity)
                    field("禁止事项与边界", card.boundaries)
                    entries(card.entries)
                }
            }
            RpSettingKind.SUPPORTING_CHARACTER -> inventory.supportingCharacters.map { character ->
                character.id to buildString {
                    field("名称", character.name)
                    field("简介", character.description)
                    entries(character.entries)
                }
            }
            RpSettingKind.PROTAGONIST -> inventory.protagonists.map { profile ->
                profile.id to buildString {
                    field("名称", profile.name.ifBlank { "（未命名，代表玩家本人）" })
                    field("简介", profile.description)
                    entries(profile.entries)
                }
            }
            RpSettingKind.WORLD_SETTING -> inventory.worldSettings.map { world ->
                world.id to buildString {
                    field("标题", world.title)
                    field("正文", world.content)
                }
            }
            RpSettingKind.WRITING_STYLE -> inventory.writingStyles.map { style ->
                style.id to buildString {
                    field("名称", style.name)
                    field("要求", style.content)
                }
            }
        }

    private fun StringBuilder.field(label: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isNotEmpty()) appendLine("$label：$trimmed")
    }

    private fun StringBuilder.entries(entries: List<RpEntry>) {
        val texts = entries.filterIsInstance<RpEntry.Text>().filter { it.value.isNotBlank() }
        val numerics = entries.filterIsInstance<RpEntry.Numeric>()
        if (texts.isNotEmpty()) {
            appendLine("文本词条：")
            texts.forEach { appendLine("- ${it.name.trim()}：${it.value.trim()}") }
        }
        if (numerics.isNotEmpty()) {
            appendLine("状态条：")
            numerics.forEach { metric ->
                append("- ${metric.name}（${metric.minValue}～${metric.maxValue}，初始 ${metric.initialValue}）")
                listOf(metric.description, metric.progressRule).map(String::trim).filter(String::isNotEmpty)
                    .takeIf { it.isNotEmpty() }
                    ?.let { append("：" + it.joinToString("；")) }
                appendLine()
            }
        }
    }
}
