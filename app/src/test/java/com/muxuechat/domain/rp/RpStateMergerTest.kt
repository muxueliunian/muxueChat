package com.muxuechat.domain.rp

import com.muxuechat.domain.model.RpMetric
import com.muxuechat.domain.model.RpMetricDefinition
import com.muxuechat.domain.model.RpStatusPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 「按本轮定义 → 匹配 → 合并」的语义（RP_ACTOR_PLAN R15 / 3.6）。 */
class RpStateMergerTest {
    private val definitions = listOf(
        RpMetricDefinition("affection", "好感度", minValue = 0, maxValue = 100, initialValue = 10),
        RpMetricDefinition("trust", "信任", minValue = -10, maxValue = 10, initialValue = 0),
    )
    private val current = RpStatusPanel(
        conversationId = CONVERSATION_ID,
        metrics = listOf(RpMetric("好感度", 62, 0, 100), RpMetric("信任", 4, -10, 10)),
        relationship = "熟人",
        scene = "吧台",
        rawJson = "",
        updatedAtMillis = 1,
    )

    private fun merge(vararg metrics: RpMetricDraft, relationship: String = "", scene: String = "") =
        RpStateMerger.merge(
            conversationId = CONVERSATION_ID,
            definitions = definitions,
            current = current,
            draft = RpStatusPanelDraft(metrics.toList(), relationship, scene, rawJson = "{}"),
            now = 99,
        )

    private fun RpStateMergeResult.valueOf(name: String) = panel?.metrics?.single { it.name == name }?.value

    /** 模型照抄示例占位文字时不能覆盖真实的关系与场景。 */
    @Test
    fun copiedPlaceholderText_keepsCurrentRelationshipAndScene() {
        val result = merge(
            RpMetricDraft("好感度", 70, 0, 100),
            relationship = "当前关系",
            scene = RpStateBlockFormat.SCENE_HINT,
        )

        assertEquals("熟人", result.panel?.relationship)
        assertEquals("吧台", result.panel?.scene)
    }

    @Test
    fun copiedPlaceholderTextOnly_isNotNewText() {
        val result = merge(relationship = "当前关系", scene = "当前场景")

        assertNull(result.panel)
        assertNull(result.textOnlyPanel)
    }

    /** 旧实现在这里回落初始值：模型漏写一项，玩家的数值就当场归零。 */
    @Test
    fun missingMetric_keepsCurrentValueInsteadOfInitial() {
        val result = merge(RpMetricDraft("好感度", 70, 0, 100))

        assertEquals(70, result.valueOf("好感度"))
        assertEquals(4, result.valueOf("信任"))
    }

    @Test
    fun missingMetricWithoutCurrentPanel_fallsBackToInitial() {
        val result = RpStateMerger.merge(
            conversationId = CONVERSATION_ID,
            definitions = definitions,
            current = null,
            draft = RpStatusPanelDraft(listOf(RpMetricDraft("好感度", 70, 0, 100)), "", "", "{}"),
            now = 99,
        )

        assertEquals(0, result.valueOf("信任"))
    }

    @Test
    fun unreadableValue_keepsCurrentValue() {
        val result = merge(RpMetricDraft("好感度", 70, null, null), RpMetricDraft("信任", null, null, null))

        assertEquals(4, result.valueOf("信任"))
    }

    /** 同名出现两次时不知道该信哪个，拒绝这一项并保留当前值，而不是静默取第一个。 */
    @Test
    fun duplicateName_isRejectedAndReported() {
        val result = merge(
            RpMetricDraft("好感度", 70, 0, 100),
            RpMetricDraft("信任", 9, -10, 10),
            RpMetricDraft("信任", -9, -10, 10),
        )

        assertEquals(4, result.valueOf("信任"))
        assertEquals(1, result.duplicateCount)
    }

    @Test
    fun unknownName_isDroppedAndCounted() {
        val result = merge(RpMetricDraft("好感度", 70, 0, 100), RpMetricDraft("体力", 50, 0, 100))

        assertEquals(listOf("好感度", "信任"), result.panel?.metrics?.map { it.name })
        assertEquals(1, result.unknownCount)
    }

    /** 范围一律以定义为准，不先按模型自报的 min/max 裁剪。 */
    @Test
    fun outOfRangeValue_isClampedToDefinitionNotSelfReportedRange() {
        val result = merge(RpMetricDraft("好感度", 150, 0, 120), RpMetricDraft("信任", 8, 0, 5))

        assertEquals(100, result.valueOf("好感度"))
        assertEquals(8, result.valueOf("信任"))
        assertEquals(-10, result.panel?.metrics?.single { it.name == "信任" }?.min)
    }

    /** 一项都对不上的状态块不是有效状态：调用方据此继续走兜底，而不是拿它覆盖面板。 */
    @Test
    fun blockMatchingNothing_isNotAValidPanel() {
        val result = merge(RpMetricDraft("角色::好感度", 70, 0, 100), relationship = "恋人")

        assertNull(result.panel)
        assertEquals(1, result.unknownCount)
    }

    /** 建议 5：数值一个没对上，关系与场景仍然保留，数值维持当前值。 */
    @Test
    fun blockMatchingNothing_keepsRelationshipAndSceneWithCurrentValues() {
        val result = merge(RpMetricDraft("角色::好感度", 70, 0, 100), relationship = "恋人")

        assertNull(result.panel)
        val textOnly = requireNotNull(result.textOnlyPanel)
        assertEquals("恋人", textOnly.relationship)
        assertEquals("吧台", textOnly.scene)
        assertEquals(listOf(62, 4), textOnly.metrics.map { it.value })
        assertEquals(99L, textOnly.updatedAtMillis)
    }

    @Test
    fun blockMatchingNothing_withoutRelationshipOrScene_hasNoTextOnlyPanel() {
        val result = merge(RpMetricDraft("角色::好感度", 70, 0, 100))

        assertNull(result.panel)
        assertNull(result.textOnlyPanel)
    }

    /** 数值对上了就走正常面板，textOnlyPanel 只是整块对不上时的退路。 */
    @Test
    fun matchedBlock_hasNoTextOnlyPanel() {
        val result = merge(RpMetricDraft("好感度", 70, 0, 100), relationship = "恋人")

        assertNotNull(result.panel)
        assertNull(result.textOnlyPanel)
    }

    @Test
    fun blankRelationshipAndScene_keepCurrent() {
        val result = merge(RpMetricDraft("好感度", 70, 0, 100), scene = "雨巷")

        assertEquals("熟人", result.panel?.relationship)
        assertEquals("雨巷", result.panel?.scene)
    }

    @Test
    fun withoutDefinitions_keepsSelfDescribedMetrics() {
        val result = RpStateMerger.merge(
            conversationId = CONVERSATION_ID,
            definitions = emptyList(),
            current = null,
            draft = RpStatusPanelDraft(
                metrics = listOf(RpMetricDraft("心情", 12, 0, 10), RpMetricDraft("无值", null, null, null)),
                relationship = "试探",
                scene = "",
                rawJson = "{}",
            ),
            now = 99,
        )

        assertNotNull(result.panel)
        assertEquals(listOf(RpMetric("心情", 10, 0, 10)), result.panel?.metrics)
        assertEquals("试探", result.panel?.relationship)
    }

    @Test
    fun alignToDefinitions_findsRenamedMetricById() {
        val renamed = listOf(definitions[0].copy(name = "亲密度"), definitions[1])

        val aligned = RpStateMerger.alignToDefinitions(
            definitions = renamed,
            metrics = current.metrics,
            previousNames = mapOf("affection" to "好感度", "trust" to "信任"),
        )

        assertEquals(listOf(RpMetric("亲密度", 62, 0, 100), RpMetric("信任", 4, -10, 10)), aligned)
    }

    /** 改名的同时新增一个叫旧名的状态条：旧值只属于改名的那一条，新增的从初始值开始。 */
    @Test
    fun alignToDefinitions_renameWithNewMetricTakingOldName_doesNotShareOldValue() {
        val edited = listOf(
            definitions[0].copy(name = "亲密度"),
            RpMetricDefinition("new", "好感度", minValue = 0, maxValue = 100, initialValue = 0),
        )

        val aligned = RpStateMerger.alignToDefinitions(
            definitions = edited,
            metrics = current.metrics,
            previousNames = mapOf("affection" to "好感度", "trust" to "信任"),
        )

        assertEquals(listOf(RpMetric("亲密度", 62, 0, 100), RpMetric("好感度", 0, 0, 100)), aligned)
    }

    @Test
    fun alignToDefinitions_swappedNames_followIds() {
        val swapped = listOf(
            definitions[0].copy(name = "信任", minValue = -10, maxValue = 100),
            definitions[1].copy(name = "好感度", minValue = -10, maxValue = 100),
        )

        val aligned = RpStateMerger.alignToDefinitions(
            definitions = swapped,
            metrics = current.metrics,
            previousNames = mapOf("affection" to "好感度", "trust" to "信任"),
        )

        assertEquals(listOf(62, 4), aligned.map { it.value })
    }

    /** A 改名为 B、同时 B 改名为 C：每一条都按自己的 id 找回原值。 */
    @Test
    fun alignToDefinitions_chainedRename_followsIds() {
        val chained = listOf(
            definitions[0].copy(name = "信任"),
            definitions[1].copy(name = "默契"),
        )

        val aligned = RpStateMerger.alignToDefinitions(
            definitions = chained,
            metrics = current.metrics,
            previousNames = mapOf("affection" to "好感度", "trust" to "信任"),
        )

        assertEquals(listOf("信任" to 62, "默契" to 4), aligned.map { it.name to it.value })
    }

    /** 删掉「好感度」、同时新增一个同名的：新 id 是新的状态条，从初始值开始。 */
    @Test
    fun alignToDefinitions_deletedMetricReplacedBySameName_startsFromInitial() {
        val edited = listOf(
            RpMetricDefinition("recreated", "好感度", minValue = 0, maxValue = 100, initialValue = 5),
            definitions[1],
        )

        val aligned = RpStateMerger.alignToDefinitions(
            definitions = edited,
            metrics = current.metrics,
            previousNames = mapOf("affection" to "好感度", "trust" to "信任"),
        )

        assertEquals(listOf(5, 4), aligned.map { it.value })
    }

    /** 早期数据的定义没有 id，不在 previousNames 里：仍按名字匹配，不能因此归零。 */
    @Test
    fun alignToDefinitions_legacyDefinitionWithoutId_stillMatchesByName() {
        val aligned = RpStateMerger.alignToDefinitions(
            definitions = listOf(definitions[0].copy(id = "assigned-on-save")),
            metrics = current.metrics,
            previousNames = emptyMap(),
        )

        assertEquals(62, aligned.single().value)
    }

    /** 唯一能对上的项同名重复：等于一项都没对上，不是有效状态。 */
    @Test
    fun onlyDuplicatedMatches_isNotAValidPanel() {
        val result = merge(RpMetricDraft("信任", 9, -10, 10), RpMetricDraft("信任", -9, -10, 10))

        assertNull(result.panel)
        assertEquals(1, result.duplicateCount)
    }

    /** 正文、元数据、兜底三条路的解析结果必须一致：它们现在共用同一个解码器。 */
    @Test
    fun contentAndMetadataPaths_decodeTheSameState() {
        val state = """{"metrics":[{"name":"好感度","value":"70点","min":0,"max":100},{"name":"信任"}],"relationship":"熟人","scene":"吧台"}"""

        val fromContent = RpStateBlockParser().extract("正文<muxue_state>$state</muxue_state>").panel
        val fromMetadata = RpMessageMetadataParser().parse("""{"inner_thought":"","options":[],"state":$state}""")
            ?.statusPanel

        assertEquals(fromContent?.metrics, fromMetadata?.metrics)
        assertEquals(listOf(RpMetricDraft("好感度", 70, 0, 100), RpMetricDraft("信任", null, null, null)), fromContent?.metrics)
    }

    private companion object {
        const val CONVERSATION_ID = "conversation"
    }
}
