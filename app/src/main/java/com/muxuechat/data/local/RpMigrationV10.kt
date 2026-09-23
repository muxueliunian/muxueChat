package com.muxuechat.data.local

import com.muxuechat.domain.model.RpEntry
import com.muxuechat.domain.model.RpEntryOwner
import com.muxuechat.domain.model.RpMetricDefinition
import com.muxuechat.domain.rp.RpEffectiveDefinitions
import com.muxuechat.domain.rp.RpEntryCodec
import com.muxuechat.domain.rp.RpStateBlockFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/**
 * v9 → v10 迁移里所有需要读写 JSON 的转换。
 *
 * 抽成纯函数是为了能用 JVM 测试直接验证——迁移最容易出事的恰恰是这些字符串转换，
 * 而它们不需要数据库就能测。SQL 部分留在 [ChatDatabase] 的 MIGRATION_9_10 里。
 *
 * 共同的约定：**解不出来就原样返回**。库里的原文是最后的恢复机会，
 * 把它覆盖成空串等于永久丢失（RP_ACTOR_PLAN §8.5）。
 */
internal object RpMigrationV10 {

    /** 主角初始状态转成的文本词条名。 */
    const val PROTAGONIST_INITIAL_STATE_ENTRY = "初始状态"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 角色卡：`metricDefinitionsJson` → `entriesJson`（§8.2）。
     *
     * 逐条转换，一条坏 entry 不清掉整张卡。**`id` 原样保留**——它是 R14 的身份依据，
     * 重新生成会让面板里已有的数值再也对不回来。
     */
    fun metricDefinitionsToEntriesJson(raw: String): Converted {
        val array = parseArray(raw) ?: return Converted("[]", skipped = 0, parsed = false)

        var skipped = 0
        val entries = array.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(RpMetricDefinition.serializer(), element) }
                .getOrElse {
                    skipped++
                    null
                }
        }
        val numeric: List<RpEntry> = entries.map { definition ->
            RpEntry.Numeric(
                id = definition.id.ifBlank { UUID.randomUUID().toString() },
                name = definition.name,
                minValue = definition.minValue,
                maxValue = definition.maxValue,
                initialValue = definition.initialValue,
                description = definition.description,
                progressRule = definition.progressRule,
            )
        }
        return Converted(RpEntryCodec.encodeEntries(numeric), skipped = skipped, parsed = true)
    }

    data class Converted(val json: String, val skipped: Int, val parsed: Boolean)

    /**
     * 状态名迁移（§8.3）：v9 的每个名字一律归到角色侧，改写为 `角色::<原名>`。
     *
     * **包括原名本身就含 `::` 的情况**——v9 的编辑器不限制命名，用户完全可以
     * 把数值叫成「主角::体力」，但它当时仍然是角色卡的数值，不能被解释成主角的。
     * 判断新旧格式一律靠数据版本，绝不靠名字里有没有分隔符（R16）。
     *
     * 只改 `name`，其余字段原样保留；解不出来就返回原文。
     */
    fun prefixLegacyMetricNames(raw: String): String {
        val array = parseArray(raw) ?: return raw

        val rewritten = buildJsonArray {
            array.forEach { element ->
                val obj = element as? JsonObject
                val name = (obj?.get("name") as? JsonPrimitive)?.contentOrNull
                if (obj == null || name == null) {
                    add(element)
                } else {
                    add(
                        JsonObject(
                            obj + ("name" to JsonPrimitive(legacyWireName(name))),
                        ),
                    )
                }
            }
        }
        return rewritten.toString()
    }

    /** v9 遗留名字对应的 wireName。 */
    fun legacyWireName(legacyName: String): String =
        RpStateBlockFormat.wireNameOf(RpEntryOwner.ROLE_CARD, legacyName)

    /**
     * 回填定义快照（§8.4）。
     *
     * 只能冻结**升级当刻**的角色卡定义，**不是**还原当初绑定时的定义——
     * v9 没有存过历史定义，这个信息已经不存在了。
     */
    fun roleCardSnapshotJson(roleCardEntriesJson: String): String {
        val entries = RpEntryCodec.decodeEntries(roleCardEntriesJson).entries
        return RpEntryCodec.encodeSnapshot(RpEffectiveDefinitions.snapshotOf(RpEntryOwner.ROLE_CARD, entries))
    }

    /**
     * 主角三字段 → 档案的词条（§8.1）。
     *
     * `protagonistInitialState` 成为一条名为「初始状态」的文本词条；为空时不生成词条。
     * **不做任何截断**：迁移不得因新档案的长度限制静默丢掉用户原有的正文。
     */
    fun protagonistEntriesJson(initialState: String, idGenerator: () -> String = { UUID.randomUUID().toString() }): String {
        if (initialState.isBlank()) return "[]"
        val entries: List<RpEntry> = listOf(
            RpEntry.Text(
                id = idGenerator(),
                name = PROTAGONIST_INITIAL_STATE_ENTRY,
                value = initialState,
            ),
        )
        return RpEntryCodec.encodeEntries(entries)
    }

    /** 三个字段全空的会话不生成档案。 */
    fun hasProtagonistContent(name: String, description: String, initialState: String): Boolean =
        name.isNotBlank() || description.isNotBlank() || initialState.isNotBlank()

    private fun parseArray(raw: String): JsonArray? {
        if (raw.isBlank()) return null
        return runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
    }
}
