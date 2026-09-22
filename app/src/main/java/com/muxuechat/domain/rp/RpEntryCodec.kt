package com.muxuechat.domain.rp

import com.muxuechat.domain.model.RpEntry
import com.muxuechat.domain.model.RpMetricSnapshot
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * 词条与定义快照的唯一序列化入口。
 *
 * 逐条解码而不是整体解码：一条坏 entry 不能清掉整张卡（RP_ACTOR_PLAN §8.5）。
 * 未知的判别名、缺字段、类型不对的项都只跳过自己，并计入 [Decoded.skipped]，
 * 供迁移与日志报告条数——**不输出正文内容**。
 */
object RpEntryCodec {

    /** [entries] 是解出来的部分，[skipped] 是被跳过的条数。 */
    data class Decoded<T>(val entries: List<T>, val skipped: Int)

    private val json = Json {
        ignoreUnknownKeys = true
        // 判别名固定为 type，与 RpEntry 上的 @SerialName 一起构成稳定的库内格式。
        classDiscriminator = "type"
    }

    fun decodeEntries(raw: String): Decoded<RpEntry> = decodeEach(raw) {
        json.decodeFromJsonElement(RpEntry.serializer(), it)
    }

    fun encodeEntries(entries: List<RpEntry>): String =
        json.encodeToString(ListSerializer(RpEntry.serializer()), entries)

    fun decodeSnapshot(raw: String): Decoded<RpMetricSnapshot> = decodeEach(raw) {
        json.decodeFromJsonElement(RpMetricSnapshot.serializer(), it)
    }

    fun encodeSnapshot(snapshot: List<RpMetricSnapshot>): String =
        json.encodeToString(
            ListSerializer(RpMetricSnapshot.serializer()),
            snapshot,
        )

    private fun <T> decodeEach(
        raw: String,
        decodeOne: (JsonElement) -> T,
    ): Decoded<T> {
        if (raw.isBlank()) return Decoded(emptyList(), 0)
        val array = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            // 整个 JSON 都读不出来时无法知道原本有几条，只能报 0——原文仍留在库里。
            ?: return Decoded(emptyList(), 0)

        var skipped = 0
        val decoded = array.mapNotNull { element ->
            runCatching { decodeOne(element) }.getOrElse {
                skipped++
                null
            }
        }
        return Decoded(decoded, skipped)
    }
}
