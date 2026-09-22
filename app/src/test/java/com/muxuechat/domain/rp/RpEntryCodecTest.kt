package com.muxuechat.domain.rp

import com.muxuechat.domain.model.RpEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 词条的库内格式：判别名固定、逐条容错（RP_ACTOR_PLAN §3.1 / §8.5）。 */
class RpEntryCodecTest {

    @Test
    fun roundTripKeepsBothKinds() {
        val entries = listOf(
            RpEntry.Text("t1", "年龄", "二十七"),
            RpEntry.Numeric("m1", "好感度", minValue = -5, maxValue = 5, initialValue = 1),
        )

        assertEquals(entries, RpEntryCodec.decodeEntries(RpEntryCodec.encodeEntries(entries)).entries)
    }

    @Test
    fun discriminatorNamesAreFixed() {
        // 库里的格式不能随 Kotlin 类名变化。
        val encoded = RpEntryCodec.encodeEntries(
            listOf(RpEntry.Text("t1", "a"), RpEntry.Numeric("m1", "b")),
        )

        assertTrue(encoded.contains("\"type\":\"text\""))
        assertTrue(encoded.contains("\"type\":\"numeric\""))
    }

    @Test
    fun unknownKindIsSkippedIndividually() {
        val raw = """[
            {"type":"text","id":"t1","name":"年龄","value":"27"},
            {"type":"image","id":"x","name":"立绘"},
            {"type":"numeric","id":"m1","name":"好感度"}
        ]"""

        val decoded = RpEntryCodec.decodeEntries(raw)

        assertEquals(listOf("t1", "m1"), decoded.entries.map { it.id })
        assertEquals(1, decoded.skipped)
    }

    @Test
    fun unreadableJsonDecodesToEmpty() {
        val decoded = RpEntryCodec.decodeEntries("{坏")

        assertEquals(emptyList<RpEntry>(), decoded.entries)
        assertEquals(0, decoded.skipped)
    }
}
