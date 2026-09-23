package com.muxuechat.data.local

import com.muxuechat.domain.model.RpEntry
import com.muxuechat.domain.model.RpEntryOwner
import com.muxuechat.domain.rp.RpEntryCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v9 → v10 迁移中的 JSON 转换（RP_ACTOR_PLAN §8）。SQL 部分由 instrumented 迁移测试覆盖。 */
class RpMigrationV10Test {

    // ---- §8.2 角色卡数值 → 词条 ----

    @Test
    fun metricDefinitionsKeepTheirIds() {
        val raw = """[
            {"id":"m-affection","name":"好感度","minValue":0,"maxValue":100,"initialValue":10,
             "description":"对你的好感","progressRule":"送礼 +5"},
            {"id":"m-trust","name":"信任","minValue":-10,"maxValue":10,"initialValue":0}
        ]"""

        val converted = RpMigrationV10.metricDefinitionsToEntriesJson(raw)
        val entries = RpEntryCodec.decodeEntries(converted.json).entries

        assertTrue(converted.parsed)
        assertEquals(0, converted.skipped)
        assertEquals(
            listOf(
                RpEntry.Numeric("m-affection", "好感度", 0, 100, 10, "对你的好感", "送礼 +5"),
                RpEntry.Numeric("m-trust", "信任", -10, 10, 0),
            ),
            entries,
        )
    }

    @Test
    fun oneBadDefinitionDoesNotDropTheWholeCard() {
        // 第二项缺必填的 name，第三项类型不对。
        val raw = """[
            {"id":"a","name":"好感度"},
            {"id":"b","minValue":0},
            "不是对象",
            {"id":"c","name":"体力"}
        ]"""

        val converted = RpMigrationV10.metricDefinitionsToEntriesJson(raw)
        val names = RpEntryCodec.decodeEntries(converted.json).entries.map { it.name }

        assertEquals(listOf("好感度", "体力"), names)
        assertEquals(2, converted.skipped)
    }

    @Test
    fun unreadableDefinitionsJsonIsReportedNotSilentlyEmptied() {
        val converted = RpMigrationV10.metricDefinitionsToEntriesJson("{坏掉的")

        // 新列只能写空，但调用方据 parsed=false 知道原文仍留在旧列，不是「本来就没有状态条」。
        assertFalse(converted.parsed)
        assertEquals("[]", converted.json)
    }

    @Test
    fun legacyDefinitionWithoutIdGetsOne() {
        val converted = RpMigrationV10.metricDefinitionsToEntriesJson("""[{"id":"","name":"好感度"}]""")
        val entry = RpEntryCodec.decodeEntries(converted.json).entries.single()

        assertTrue(entry.id.isNotBlank())
    }

    // ---- §8.3 状态名迁移 ----

    @Test
    fun legacyMetricNamesArePrefixedAsRoleCardOwned() {
        val raw = """[{"name":"好感度","value":62,"min":0,"max":100}]"""

        val metric = parse(RpMigrationV10.prefixLegacyMetricNames(raw)).single() as JsonObject

        assertEquals("角色::好感度", metric["name"]!!.jsonPrimitive.content)
        // 其余字段原样保留。
        assertEquals(62, metric["value"]!!.jsonPrimitive.content.toInt())
        assertEquals(100, metric["max"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun nameThatAlreadyLooksPrefixedIsStillRoleCardOwned() {
        // R16：v9 用户完全可以把角色卡数值命名为「主角::体力」，它当时仍是角色卡的数值。
        val raw = """[{"name":"主角::体力","value":80,"min":0,"max":100}]"""

        val metric = parse(RpMigrationV10.prefixLegacyMetricNames(raw)).single() as JsonObject

        assertEquals("角色::主角::体力", metric["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun unreadableMetricsJsonIsReturnedUntouched() {
        val raw = "{坏掉的"
        assertEquals(raw, RpMigrationV10.prefixLegacyMetricNames(raw))
    }

    @Test
    fun metricItemsWithoutNameAreKeptAsIs() {
        val raw = """[{"value":1},{"name":"信任","value":2}]"""

        val items = parse(RpMigrationV10.prefixLegacyMetricNames(raw))

        assertEquals(2, items.size)
        assertFalse((items[0] as JsonObject).containsKey("name"))
        assertEquals("角色::信任", (items[1] as JsonObject)["name"]!!.jsonPrimitive.content)
    }

    // ---- §8.4 回填快照 ----

    @Test
    fun snapshotFreezesOnlyNumericEntriesWithIdentity() {
        val entriesJson = RpEntryCodec.encodeEntries(
            listOf(
                RpEntry.Text("t1", "年龄", "二十七"),
                RpEntry.Numeric("m1", "好感度", minValue = 0, maxValue = 100, initialValue = 10),
            ),
        )

        val snapshot = RpEntryCodec.decodeSnapshot(RpMigrationV10.roleCardSnapshotJson(entriesJson)).entries

        val item = snapshot.single()
        assertEquals(RpEntryOwner.ROLE_CARD, item.ownerType)
        assertEquals("m1", item.entryId)
        assertEquals("角色::好感度", item.wireName)
        assertEquals("好感度", item.displayName)
        assertEquals(10, item.initialValue)
    }

    @Test
    fun snapshotWireNameMatchesMigratedMetricName() {
        // 面板迁移与快照回填必须产出同一个 wireName，否则升级后第一轮就对不上。
        val entriesJson = RpEntryCodec.encodeEntries(listOf(RpEntry.Numeric("m1", "主角::体力")))
        val snapshotWire = RpEntryCodec.decodeSnapshot(RpMigrationV10.roleCardSnapshotJson(entriesJson))
            .entries.single().wireName
        val panelWire = (parse(
            RpMigrationV10.prefixLegacyMetricNames("""[{"name":"主角::体力","value":1}]"""),
        ).single() as JsonObject)["name"]!!.jsonPrimitive.content

        assertEquals(snapshotWire, panelWire)
    }

    // ---- §8.1 主角三字段 → 档案 ----

    @Test
    fun initialStateBecomesTextEntryWithoutTruncation() {
        val longState = "长".repeat(5_000)

        val entries = RpEntryCodec.decodeEntries(
            RpMigrationV10.protagonistEntriesJson(longState) { "e1" },
        ).entries

        assertEquals(listOf(RpEntry.Text("e1", "初始状态", longState)), entries)
    }

    @Test
    fun blankInitialStateProducesNoEntry() {
        assertEquals("[]", RpMigrationV10.protagonistEntriesJson("  "))
    }

    @Test
    fun protagonistContentDetection() {
        assertFalse(RpMigrationV10.hasProtagonistContent("", " ", ""))
        assertTrue(RpMigrationV10.hasProtagonistContent("", "", "受伤"))
    }

    private fun parse(raw: String): JsonArray = Json.parseToJsonElement(raw) as JsonArray
}
