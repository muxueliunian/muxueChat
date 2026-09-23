package com.muxuechat.presentation.chat.components

import com.muxuechat.domain.model.RpEntry
import com.muxuechat.domain.model.RpMetricDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpEntryDraftsTest {

    /** 空串与单独一个负号必须能停留在输入框里，否则清不掉、输不了负数（§7.2）。 */
    @Test
    fun numberInput_acceptsIntermediateShapesOnly() {
        listOf("", "-", "0", "-12", "123456789").forEach { assertTrue(it, RpEntryDrafts.acceptsNumberInput(it)) }
        listOf("1.5", "--1", "1-", "abc", " 1", "1234567890").forEach {
            assertFalse(it, RpEntryDrafts.acceptsNumberInput(it))
        }
    }

    @Test
    fun numericIssues_validDraftHasNone() {
        val issues = RpEntryDrafts.numericIssues(listOf(numeric("a", "好感度", "-10", "10", "0")))
        assertFalse(issues.single().hasAny)
    }

    @Test
    fun numericIssues_rejectsSeparatorInName() {
        val issue = RpEntryDrafts.numericIssues(listOf(numeric("a", "主角::体力"))).single()
        assertNotNull(issue.name)
        assertTrue(issue.name!!.contains("::"))
    }

    /** 判重先归一化：只差首尾空格或换行的两条，Repository 会视为同名，这里也必须报错。 */
    @Test
    fun numericIssues_flagsDuplicatesAfterNormalization() {
        val issues = RpEntryDrafts.numericIssues(
            listOf(numeric("a", "好感度"), numeric("b", " 好感度\n"), numeric("c", "信任度")),
        )
        assertNotNull(issues[0].name)
        assertNotNull(issues[1].name)
        assertNull(issues[2].name)
    }

    @Test
    fun numericIssues_blankNameIsAnError() {
        assertNotNull(RpEntryDrafts.numericIssues(listOf(numeric("a", "  "))).single().name)
    }

    /** 用户手输越界要明确报错，不静默收敛（§7.2）。 */
    @Test
    fun numericIssues_reportsRangeProblemsWithoutCoercing() {
        val maxNotAboveMin = RpEntryDrafts.numericIssues(listOf(numeric("a", "x", "10", "10", "10"))).single()
        assertNotNull(maxNotAboveMin.max)
        // 范围本身无效时不再追报初始值，免得一处错误出两条提示。
        assertNull(maxNotAboveMin.initial)

        val initialOutside = RpEntryDrafts.numericIssues(listOf(numeric("a", "x", "0", "100", "101"))).single()
        assertNull(initialOutside.max)
        assertEquals("初始值须在 0～100 之间", initialOutside.initial)
    }

    @Test
    fun numericIssues_blankOrLoneMinusIsNotANumber() {
        val issue = RpEntryDrafts.numericIssues(listOf(numeric("a", "x", "", "-", "0"))).single()
        assertEquals("请填写整数", issue.min)
        assertEquals("请输入整数", issue.max)
    }

    @Test
    fun textNameIssue_onlyWhenValueWithoutName() {
        assertNotNull(RpEntryDrafts.textNameIssue(TextEntryDraft("t", name = " ", value = "二十七")))
        assertNull(RpEntryDrafts.textNameIssue(TextEntryDraft("t", name = "", value = "")))
        // 文本词条不进状态命名空间：「::」不受限。
        assertNull(RpEntryDrafts.textNameIssue(TextEntryDraft("t", name = "a::b", value = "c")))
    }

    @Test
    fun issueCount_sumsTextAndNumericProblems() {
        val count = RpEntryDrafts.issueCount(
            texts = listOf(TextEntryDraft("t1", "", "有内容"), TextEntryDraft("t2", "年龄", "二十七")),
            numerics = listOf(numeric("a", "好感度"), numeric("b", "好感度", "0", "100", "200")),
        )
        // t1 一处；a 重名一处；b 重名 + 初始值越界算同一条的问题，只计一处。
        assertEquals(3, count)
    }

    /** 转换保留 id（R14 的身份依据）、trim 内容、丢弃全空的文本条。 */
    @Test
    fun conversions_keepIdsAndDropEmptyTextEntries() {
        val texts = RpEntryDrafts.toTextEntries(
            listOf(TextEntryDraft("t1", " 年龄 ", " 二十七 "), TextEntryDraft("t2", " ", " ")),
        )
        assertEquals(listOf(RpEntry.Text("t1", "年龄", "二十七")), texts)

        val definition = RpMetricDefinition("m1", "信任度", -10, 10, 3, "含义", "规则")
        val roundTrip = RpEntryDrafts.toMetricDefinitions(listOf(RpEntryDrafts.numericFrom(definition)))
        assertEquals(listOf(definition), roundTrip)

        val numeric = RpEntryDrafts.toNumericEntries(listOf(numeric("m2", " 体力 ", " -5 ", "5", " 0")))
        assertEquals(listOf(RpEntry.Numeric("m2", "体力", -5, 5, 0)), numeric)
    }

    private fun numeric(
        id: String,
        name: String,
        min: String = "0",
        max: String = "100",
        initial: String = "0",
    ) = NumericEntryDraft(id = id, name = name, minText = min, maxText = max, initialText = initial)
}
