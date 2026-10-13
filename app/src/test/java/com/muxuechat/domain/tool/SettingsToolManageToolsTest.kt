package com.muxuechat.domain.tool

import com.muxuechat.domain.model.ProtagonistProfile
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RpEntry
import com.muxuechat.domain.model.RpSettingKind
import com.muxuechat.domain.model.RpSettingRef
import com.muxuechat.domain.model.SupportingCharacter
import com.muxuechat.domain.model.WorldSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 配角创建、设定列表、删除请求三个工具的参数解析与列表格式。 */
class SettingsToolManageToolsTest {
    private var idCounter = 0
    private val parser = SettingsToolArgumentParser(
        clock = { 1_000L },
        idGenerator = { "id-${idCounter++}" },
    )

    private fun parse(tool: SettingsTool, json: String) = parser.parse(tool, json, conversationId = "c1")

    // ---------- create_supporting_character ----------

    @Test
    fun parsesSupportingCharacterWithTextEntriesOnly() {
        val result = parse(
            SettingsTool.CREATE_SUPPORTING_CHARACTER,
            """{"name":"老周","description":"咖啡店熟客","entries":[
                {"name":"职业","value":"退休警察"},
                {"name":" ","value":"无名"},
                {"name":"口头禅","value":" "},
                "不是对象"
            ]}""",
        ) as SettingsToolParseResult.Success

        val character = result.draft.supportingCharacter!!
        assertEquals("老周", character.name)
        assertEquals("咖啡店熟客", character.description)
        assertEquals(listOf(RpEntry.Text("id-1", "职业", "退休警察")), character.entries)
        assertEquals("配角草稿", result.draft.pendingLabel)
        assertEquals("老周", result.draft.displayName)
        assertNull(result.draft.roleCard)
    }

    @Test
    fun supportingCharacterWithoutNameFails() {
        assertTrue(parse(SettingsTool.CREATE_SUPPORTING_CHARACTER, """{"description":"x"}""") is SettingsToolParseResult.Failure)
    }

    // ---------- list_rp_settings ----------

    @Test
    fun listRequest_acceptsBlankArgumentsAndTypeFilter() {
        assertEquals(SettingsToolParseResult.ListRequest(emptySet()), parse(SettingsTool.LIST_RP_SETTINGS, ""))
        assertEquals(SettingsToolParseResult.ListRequest(emptySet()), parse(SettingsTool.LIST_RP_SETTINGS, "{}"))
        assertEquals(
            SettingsToolParseResult.ListRequest(setOf(RpSettingKind.ROLE_CARD, RpSettingKind.SUPPORTING_CHARACTER)),
            parse(SettingsTool.LIST_RP_SETTINGS, """{"types":["role_card","supporting_character"]}"""),
        )
    }

    @Test
    fun listRequest_unknownTypeFails() {
        val result = parse(SettingsTool.LIST_RP_SETTINGS, """{"types":["world_tree"]}""")
        assertTrue(result is SettingsToolParseResult.Failure)
        assertTrue((result as SettingsToolParseResult.Failure).reason.contains("world_tree"))
    }

    // ---------- delete_rp_settings ----------

    @Test
    fun deleteRequest_parsesAndMergesDuplicates() {
        val result = parse(
            SettingsTool.DELETE_RP_SETTINGS,
            """{"targets":[{"type":"role_card","id":"r1"},{"type":"protagonist","id":" p1 "},{"type":"role_card","id":"r1"}]}""",
        ) as SettingsToolParseResult.DeleteRequest

        assertEquals(
            listOf(RpSettingRef(RpSettingKind.ROLE_CARD, "r1"), RpSettingRef(RpSettingKind.PROTAGONIST, "p1")),
            result.refs,
        )
        assertEquals(1, result.warnings.size)
    }

    /** 任何一项不合法都整体拒绝，不能「删一部分」——模型要么给对，要么重来。 */
    @Test
    fun deleteRequest_rejectsEmptyOrInvalidTargets() {
        assertTrue(parse(SettingsTool.DELETE_RP_SETTINGS, """{"targets":[]}""") is SettingsToolParseResult.Failure)
        assertTrue(parse(SettingsTool.DELETE_RP_SETTINGS, """{}""") is SettingsToolParseResult.Failure)
        assertTrue(
            parse(
                SettingsTool.DELETE_RP_SETTINGS,
                """{"targets":[{"type":"role_card","id":"r1"},{"type":"world_tree","id":"w1"}]}""",
            ) is SettingsToolParseResult.Failure,
        )
        assertTrue(
            parse(SettingsTool.DELETE_RP_SETTINGS, """{"targets":[{"type":"role_card","id":" "}]}""")
                is SettingsToolParseResult.Failure,
        )
    }

    // ---------- 列表格式 ----------

    private val inventory = RpSettingsInventory(
        roleCards = listOf(
            RoleCard(
                id = "r1", name = "林澈", description = "店主", persona = "温和", speakingStyle = "",
                userIdentity = "", boundaries = "不跳戏",
                entries = listOf(
                    RpEntry.Text("t1", "年龄", "27"),
                    RpEntry.Numeric("m1", "好感度", 0, 100, 10, "亲近程度", ""),
                ),
                createdAtMillis = 1, updatedAtMillis = 1,
            ),
        ),
        supportingCharacters = listOf(SupportingCharacter("s1", "老周", "熟客", createdAtMillis = 1, updatedAtMillis = 1)),
        protagonists = listOf(ProtagonistProfile("p1", "", "外地记者", createdAtMillis = 1, updatedAtMillis = 1)),
        worldSettings = listOf(WorldSetting("w1", "雨城", "常年下雨", 1, 1)),
        usage = mapOf(RpSettingRef(RpSettingKind.ROLE_CARD, "r1") to 2),
    )

    /** 列表给完整内容与 id（用户选定），空字段不输出。 */
    @Test
    fun listing_containsFullContentIdsAndUsage() {
        val text = RpSettingsListing.format(inventory, emptySet())

        assertTrue(text.startsWith("共 4 项设定。"))
        assertTrue(text.contains("[role_card] id=r1（角色卡，被几个会话使用：2）"))
        assertTrue(text.contains("人设：温和"))
        assertTrue(text.contains("禁止事项与边界：不跳戏"))
        assertTrue(text.contains("- 年龄：27"))
        assertTrue(text.contains("- 好感度（0～100，初始 10）：亲近程度"))
        assertTrue(text.contains("[supporting_character] id=s1（配角，被几个会话勾选：0）"))
        assertTrue(text.contains("名称：（未命名，代表玩家本人）"))
        assertFalse(text.contains("说话风格："))
    }

    @Test
    fun listing_filtersByKind() {
        val text = RpSettingsListing.format(inventory, setOf(RpSettingKind.WORLD_SETTING))
        assertTrue(text.startsWith("共 1 项设定。"))
        assertTrue(text.contains("[world_setting] id=w1"))
        assertFalse(text.contains("林澈"))

        assertEquals("没有找到任何文风。", RpSettingsListing.format(inventory, setOf(RpSettingKind.WRITING_STYLE)))
    }

    /** 超出总长时整项不列并写明还有几项，不截断单项内容。 */
    @Test
    fun listing_dropsWholeItemsWhenOverBudget() {
        val text = RpSettingsListing.format(inventory, emptySet(), maxChars = 200)

        assertTrue(text, text.contains("还有"))
        assertTrue(text.contains("人设：温和"))
        assertFalse(text.contains("[world_setting]"))
    }

    @Test
    fun detail_omitsIdLineAndReportsMissing() {
        val detail = RpSettingsListing.detail(inventory, RpSettingRef(RpSettingKind.SUPPORTING_CHARACTER, "s1"))
        assertEquals("名称：老周\n简介：熟客", detail)
        assertNull(RpSettingsListing.detail(inventory, RpSettingRef(RpSettingKind.ROLE_CARD, "gone")))
    }
}
