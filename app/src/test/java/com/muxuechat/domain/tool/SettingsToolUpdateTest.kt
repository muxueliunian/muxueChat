package com.muxuechat.domain.tool

import com.muxuechat.domain.model.ProtagonistProfile
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RpEntry
import com.muxuechat.domain.model.RpSettingKind
import com.muxuechat.domain.model.RpSettingRef
import com.muxuechat.domain.model.WorldSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `get_rp_setting` 与 `update_rp_setting` 的参数解析，以及修改合并规则。 */
class SettingsToolUpdateTest {
    private val parser = SettingsToolArgumentParser(clock = { 1_000L }, idGenerator = { "gen" })
    private var idCounter = 0
    private val newId = { "new-${idCounter++}" }

    private fun parseUpdate(json: String) =
        parser.parse(SettingsTool.UPDATE_RP_SETTING, json, conversationId = "c1")

    private val card = RoleCard(
        id = "card1",
        name = "林夏",
        description = "咖啡店店主",
        persona = "温和",
        speakingStyle = "",
        userIdentity = "",
                boundaries = "",
        entries = listOf(
            RpEntry.Text("t1", "年龄", "25"),
            RpEntry.Numeric("m1", "好感度", 0, 100, 10),
            RpEntry.Numeric("m2", "信任度", 0, 100, 0),
        ),
        createdAtMillis = 1,
        updatedAtMillis = 2,
    )

    @Test
    fun parsesGetRequest() {
        val result = parser.parse(SettingsTool.GET_RP_SETTING, """{"type":"role_card","id":" card1 "}""", "c1")
        assertEquals(
            SettingsToolParseResult.GetRequest(RpSettingRef(RpSettingKind.ROLE_CARD, "card1")),
            result,
        )
        assertTrue(parser.parse(SettingsTool.GET_RP_SETTING, """{"type":"x","id":"a"}""", "c1") is SettingsToolParseResult.Failure)
    }

    @Test
    fun updateWithoutChangesFails() {
        assertTrue(parseUpdate("""{"type":"role_card","id":"card1"}""") is SettingsToolParseResult.Failure)
    }

    @Test
    fun parsesUpdateFieldsAndEntryOperations() {
        val result = parseUpdate(
            """{"type":"role_card","id":"card1","persona":"冷淡",
                "set_text_entries":[{"name":"年龄","value":"26"},{"name":"空","value":" "}],
                "set_metrics":[{"name":"好感度","max_value":"200"}],
                "remove_entries":["信任度"," "]}""",
        ) as SettingsToolParseResult.UpdateRequest

        assertEquals(RpSettingRef(RpSettingKind.ROLE_CARD, "card1"), result.ref)
        assertEquals(mapOf("persona" to "冷淡"), result.patch.fields)
        assertEquals(listOf("年龄" to "26"), result.patch.textEntries)
        assertEquals(listOf(MetricPatch(name = "好感度", maxValue = 200)), result.patch.metrics)
        assertEquals(listOf("信任度"), result.patch.removeEntries)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun patchKeepsUntouchedFieldsAndEntryIds() {
        val patch = RpSettingPatch(
            fields = mapOf("persona" to "冷淡"),
            textEntries = listOf("年龄" to "26", "职业" to "店主"),
            metrics = listOf(MetricPatch(name = "好感度", maxValue = 200), MetricPatch(name = "体力")),
            removeEntries = listOf("信任度"),
        )
        val result = RpSettingPatcher.apply(RpSettingCurrent.OfRoleCard(card), patch, newId).getOrThrow()
        val draft = result.roleCard!!

        assertEquals("林夏", draft.name)
        assertEquals("咖啡店店主", draft.description)
        assertEquals("冷淡", draft.persona)
        assertEquals(
            listOf(RpEntry.Text("t1", "年龄", "26"), RpEntry.Text("new-0", "职业", "店主")),
            draft.textEntries,
        )
        assertEquals(listOf("m1", "new-1"), draft.metricDefinitions.map { it.id })
        assertEquals(200, draft.metricDefinitions[0].maxValue)
        assertEquals(10, draft.metricDefinitions[0].initialValue)
        // 只有「定义被改动」的已有状态条需要重置；新加的本来就从初始值开始。
        assertEquals(listOf("好感度"), result.resetMetricNames)
        assertEquals(
            listOf("人设", "词条「年龄」：修改", "词条「职业」：新增", "状态条「好感度」：修改", "状态条「体力」：新增", "状态条「信任度」：删除"),
            result.changes,
        )
    }

    @Test
    fun metricPatchClampsInvalidRange() {
        val patch = RpSettingPatch(metrics = listOf(MetricPatch(name = "好感度", minValue = 50, maxValue = 20)))
        val result = RpSettingPatcher.apply(RpSettingCurrent.OfRoleCard(card), patch, newId).getOrThrow()
        val metric = result.roleCard!!.metricDefinitions.first()
        assertEquals(50, metric.minValue)
        assertEquals(51, metric.maxValue)
        assertEquals(50, metric.initialValue)
        assertEquals(2, result.warnings.size)
    }

    @Test
    fun rejectsFieldsThatDoNotBelongToKind() {
        val world = WorldSetting("w1", "大陆", "正文", 1, 2)
        val wrongField = RpSettingPatcher.apply(
            RpSettingCurrent.OfWorldSetting(world),
            RpSettingPatch(fields = mapOf("persona" to "x")),
        )
        assertTrue(wrongField.isFailure)
        val metricsOnWorld = RpSettingPatcher.apply(
            RpSettingCurrent.OfWorldSetting(world),
            RpSettingPatch(metrics = listOf(MetricPatch("好感度"))),
        )
        assertTrue(metricsOnWorld.isFailure)
        val ok = RpSettingPatcher.apply(
            RpSettingCurrent.OfWorldSetting(world),
            RpSettingPatch(fields = mapOf("content" to "新正文")),
        ).getOrThrow()
        assertEquals("新正文", ok.worldSetting!!.content)
        assertEquals(listOf("正文"), ok.changes)
    }

    @Test
    fun rejectsNoOpAndBlankName() {
        val same = RpSettingPatcher.apply(
            RpSettingCurrent.OfRoleCard(card),
            RpSettingPatch(fields = mapOf("persona" to "温和")),
        )
        assertTrue(same.isFailure)
        val blankName = RpSettingPatcher.apply(
            RpSettingCurrent.OfRoleCard(card),
            RpSettingPatch(fields = mapOf("name" to "")),
        )
        assertTrue(blankName.isFailure)
    }

    @Test
    fun protagonistPatchKeepsNumericEntries() {
        val profile = ProtagonistProfile(
            id = "p1",
            name = "阿明",
            entries = listOf(RpEntry.Numeric("hp", "体力", 0, 100, 100)),
            createdAtMillis = 1,
            updatedAtMillis = 2,
        )
        val result = RpSettingPatcher.apply(
            RpSettingCurrent.OfProtagonist(profile),
            RpSettingPatch(textEntries = listOf("职业" to "侦探")),
            newId,
        ).getOrThrow()
        val entries = result.protagonistProfile!!.entries
        assertEquals(listOf("new-0", "hp"), entries.map { it.id })
        assertTrue(result.resetMetricNames.isEmpty())
    }

    @Test
    fun getItemIncludesIdLine() {
        val inventory = RpSettingsInventory(roleCards = listOf(card))
        val text = RpSettingsListing.item(inventory, RpSettingRef(RpSettingKind.ROLE_CARD, "card1"))
        assertNotNull(text)
        assertTrue(text!!.startsWith("[role_card] id=card1"))
        assertTrue(text.contains("好感度"))
        assertEquals(null, RpSettingsListing.item(inventory, RpSettingRef(RpSettingKind.ROLE_CARD, "none")))
    }
}
