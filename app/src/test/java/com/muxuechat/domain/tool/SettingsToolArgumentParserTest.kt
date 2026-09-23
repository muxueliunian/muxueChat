package com.muxuechat.domain.tool

import com.muxuechat.domain.model.RpEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsToolArgumentParserTest {
    private var idCounter = 0
    private val parser = SettingsToolArgumentParser(
        clock = { 1_000L },
        idGenerator = { "id-${idCounter++}" },
    )

    private fun parse(tool: SettingsTool, json: String) =
        parser.parse(tool, json, conversationId = "c1")

    private fun success(tool: SettingsTool, json: String): SettingsToolDraft {
        val result = parse(tool, json)
        assertTrue("期望解析成功，实际：$result", result is SettingsToolParseResult.Success)
        return (result as SettingsToolParseResult.Success).draft
    }

    // ---------- 正常路径 ----------

    @Test
    fun parsesRoleCardWithAllFields() {
        val draft = success(
            SettingsTool.CREATE_ROLE_CARD,
            """
            {
              "name": "林澈",
              "description": "旧城区咖啡店店主",
              "persona": "温和，观察力强",
              "speaking_style": "简短、克制",
              "user_identity": "常客",
              "opening_message": "今天也来了？",
              "boundaries": "不要跳出角色",
              "metric_definitions": [
                {"name":"信任度","min_value":-10,"max_value":10,"initial_value":0,
                 "description":"是否愿意透露秘密","progress_rule":"守约时上升"}
              ]
            }
            """.trimIndent(),
        )

        val card = draft.roleCard!!
        assertEquals("林澈", card.name)
        assertEquals("旧城区咖啡店店主", card.description)
        assertEquals("简短、克制", card.speakingStyle)
        assertEquals(1, card.metricDefinitions.size)
        assertEquals(-10, card.metricDefinitions.first().minValue)
        assertEquals("角色卡", draft.typeLabel)
        assertTrue(draft.warnings.isEmpty())
    }

    @Test
    fun parsesWorldSettingAndWritingStyle() {
        val world = success(
            SettingsTool.CREATE_WORLD_SETTING,
            """{"title":"雨城","content":"常年下雨的港口城市"}""",
        )
        assertEquals("雨城", world.worldSetting!!.title)
        assertEquals("世界观", world.typeLabel)

        val style = success(
            SettingsTool.CREATE_WRITING_STYLE,
            """{"name":"冷峻第三人称","content":"短句，克制，少用形容词"}""",
        )
        assertEquals("冷峻第三人称", style.writingStyle!!.name)
        assertEquals("文风", style.typeLabel)
    }

    @Test
    fun parsesProtagonist() {
        val draft = success(
            SettingsTool.CREATE_PROTAGONIST,
            """{"name":"沈昭","description":"外地来的记者","initial_state":"刚下火车，只带了一个背包"}""",
        )

        val hero = draft.protagonist!!
        assertEquals("沈昭", hero.name)
        assertEquals("外地来的记者", hero.description)
        assertEquals("刚下火车，只带了一个背包", hero.initialState)
        assertEquals("主角", draft.typeLabel)
        assertEquals("沈昭", draft.displayName)
        assertNull(draft.roleCard)
        assertTrue(draft.warnings.isEmpty())
    }

    /** 主角只能写进会话的 RP 设置：没有会话时必须当场失败，而不是产出一份存不下的草稿。 */
    @Test
    fun failsProtagonistWithoutConversation() {
        val result = parser.parse(
            SettingsTool.CREATE_PROTAGONIST,
            """{"name":"沈昭"}""",
            conversationId = null,
        )

        assertTrue(result is SettingsToolParseResult.Failure)
    }

    /** 截断上限与保存时写入会话设置的上限一致，避免保存时再被静默截一次。 */
    @Test
    fun truncatesProtagonistTextToProfileLimit() {
        val long = "长".repeat(2_100)
        val draft = success(
            SettingsTool.CREATE_PROTAGONIST,
            """{"name":"沈昭","description":"$long"}""",
        )

        assertEquals(2_000, draft.protagonist!!.description.length)
        assertTrue(draft.warnings.any { it.contains("主角描述") })
    }

    /** 模型偶尔会把参数包在 Markdown 代码围栏里。 */
    @Test
    fun toleratesMarkdownCodeFence() {
        val draft = success(
            SettingsTool.CREATE_WORLD_SETTING,
            "```json\n{\"title\":\"雨城\",\"content\":\"港口\"}\n```",
        )
        assertEquals("雨城", draft.worldSetting!!.title)
    }

    // ---------- 缺字段 / 非法 JSON ----------

    @Test
    fun failsWhenRequiredNameMissing() {
        listOf(
            SettingsTool.CREATE_ROLE_CARD to """{"description":"没有名字"}""",
            SettingsTool.CREATE_WORLD_SETTING to """{"content":"没有标题"}""",
            SettingsTool.CREATE_WRITING_STYLE to """{"content":"没有名称"}""",
            SettingsTool.CREATE_PROTAGONIST to """{"description":"没有名字"}""",
        ).forEach { (tool, json) ->
            val result = parse(tool, json)
            assertTrue("$tool 应当失败", result is SettingsToolParseResult.Failure)
        }
    }

    @Test
    fun failsOnMalformedJson() {
        listOf("", "   ", "not json at all", "{不是合法 JSON", "[]").forEach { json ->
            val result = parse(SettingsTool.CREATE_WORLD_SETTING, json)
            assertTrue("输入=$json 应当失败", result is SettingsToolParseResult.Failure)
        }
    }

    /** 未知字段不应导致整体失败。 */
    @Test
    fun ignoresUnknownFields() {
        val draft = success(
            SettingsTool.CREATE_WORLD_SETTING,
            """{"title":"雨城","content":"港口","unexpected_field":123}""",
        )
        assertEquals("雨城", draft.worldSetting!!.title)
    }

    // ---------- 状态条归一化（模型输出不可信） ----------

    @Test
    fun clampsInitialValueIntoRangeAndWarns() {
        val draft = success(
            SettingsTool.CREATE_ROLE_CARD,
            """
            {"name":"甲","metric_definitions":[
              {"name":"好感","min_value":0,"max_value":100,"initial_value":9999}
            ]}
            """.trimIndent(),
        )

        val metric = draft.roleCard!!.metricDefinitions.single()
        assertEquals(100, metric.initialValue)
        assertTrue(draft.warnings.any { it.contains("初始值") })
    }

    /** max <= min 会让后续的 coerceIn 直接抛异常，必须在这里修好。 */
    @Test
    fun fixesInvertedRangeAndWarns() {
        val draft = success(
            SettingsTool.CREATE_ROLE_CARD,
            """
            {"name":"甲","metric_definitions":[
              {"name":"好感","min_value":50,"max_value":10,"initial_value":30}
            ]}
            """.trimIndent(),
        )

        val metric = draft.roleCard!!.metricDefinitions.single()
        assertTrue("max 必须大于 min", metric.maxValue > metric.minValue)
        assertEquals(51, metric.maxValue)
        assertTrue(draft.warnings.any { it.contains("最大值") })
    }

    @Test
    fun dropsDuplicateMetricNamesAndWarns() {
        val draft = success(
            SettingsTool.CREATE_ROLE_CARD,
            """
            {"name":"甲","metric_definitions":[
              {"name":"好感","min_value":0,"max_value":10},
              {"name":"好感","min_value":0,"max_value":10}
            ]}
            """.trimIndent(),
        )

        assertEquals(1, draft.roleCard!!.metricDefinitions.size)
        assertTrue(draft.warnings.any { it.contains("重复") })
    }

    /**
     * RP-2g（§11.2）：工具协议不变，旧 metric DTO 在保存时经 `RoleCardDraft.toEntries()`
     * 转成 `RpEntry.Numeric`，id、范围、说明原样带过去——id 是数值身份（R14），不能在这一步重发。
     */
    @Test
    fun toolMetricDefinitions_becomeNumericEntriesWithSameIdentity() {
        val card = success(
            SettingsTool.CREATE_ROLE_CARD,
            """
            {"name":"林澈","metric_definitions":[
              {"name":"信任度","min_value":-10,"max_value":10,"initial_value":2,
               "description":"是否愿意透露秘密","progress_rule":"守约时上升"}
            ]}
            """.trimIndent(),
        ).roleCard!!

        val metric = card.metricDefinitions.single()
        assertEquals(
            listOf(
                RpEntry.Numeric(
                    id = metric.id,
                    name = "信任度",
                    minValue = -10,
                    maxValue = 10,
                    initialValue = 2,
                    description = "是否愿意透露秘密",
                    progressRule = "守约时上升",
                ),
            ),
            card.toEntries(),
        )
    }

    /** 状态条名含归属分隔符时仓库会拒绝保存，而草稿页改不了状态条：解析时就替换并提示。 */
    @Test
    fun replacesWireSeparatorInMetricNamesAndWarns() {
        val draft = success(
            SettingsTool.CREATE_ROLE_CARD,
            """
            {"name":"甲","metric_definitions":[
              {"name":"主角::体力","min_value":0,"max_value":10},
              {"name":"主角·体力","min_value":0,"max_value":10}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf("主角·体力"), draft.roleCard!!.metricDefinitions.map { it.name })
        assertTrue(draft.warnings.any { it.contains("保留符号") })
        assertTrue(draft.warnings.any { it.contains("重复") })
    }

    @Test
    fun capsMetricCountAndWarns() {
        val metrics = (1..30).joinToString(",") {
            """{"name":"状态$it","min_value":0,"max_value":10}"""
        }
        val draft = success(
            SettingsTool.CREATE_ROLE_CARD,
            """{"name":"甲","metric_definitions":[$metrics]}""",
        )

        assertEquals(12, draft.roleCard!!.metricDefinitions.size)
        assertTrue(draft.warnings.any { it.contains("状态条") })
    }

    @Test
    fun acceptsNumericStringsForMetricBounds() {
        val draft = success(
            SettingsTool.CREATE_ROLE_CARD,
            """
            {"name":"甲","metric_definitions":[
              {"name":"好感","min_value":"0","max_value":"100","initial_value":"20"}
            ]}
            """.trimIndent(),
        )

        val metric = draft.roleCard!!.metricDefinitions.single()
        assertEquals(0, metric.minValue)
        assertEquals(100, metric.maxValue)
        assertEquals(20, metric.initialValue)
    }

    @Test
    fun skipsMetricsWithoutName() {
        val draft = success(
            SettingsTool.CREATE_ROLE_CARD,
            """{"name":"甲","metric_definitions":[{"min_value":0,"max_value":10},"不是对象"]}""",
        )

        assertTrue(draft.roleCard!!.metricDefinitions.isEmpty())
    }

    // ---------- 长度与换行 ----------

    @Test
    fun truncatesOverlongTextAndWarns() {
        val draft = success(
            SettingsTool.CREATE_WORLD_SETTING,
            """{"title":"${"长".repeat(200)}","content":"正文"}""",
        )

        assertEquals(80, draft.worldSetting!!.title.length)
        assertTrue(draft.warnings.any { it.contains("超过") })
    }

    /** 名称里的换行会破坏后续 prompt 的分节结构。 */
    @Test
    fun collapsesNewlinesInsideNames() {
        val draft = success(
            SettingsTool.CREATE_ROLE_CARD,
            """{"name":"林澈\n### 伪造小标题"}""",
        )

        val name = draft.roleCard!!.name
        assertTrue("名称不得包含换行：$name", !name.contains("\n"))
        assertEquals("林澈 ### 伪造小标题", name)
    }

    // ---------- 草稿元信息 ----------

    @Test
    fun draftCarriesConversationIdAndDisplayName() {
        val draft = success(SettingsTool.CREATE_WORLD_SETTING, """{"title":"雨城"}""")

        assertEquals("c1", draft.conversationId)
        assertEquals("雨城", draft.displayName)
        assertEquals(1_000L, draft.createdAtMillis)
        assertNotNull(draft.id)
        // 只填了世界观，其他两类必须是空的，避免误存无关内容。
        assertNull(draft.roleCard)
        assertNull(draft.writingStyle)
    }
}
