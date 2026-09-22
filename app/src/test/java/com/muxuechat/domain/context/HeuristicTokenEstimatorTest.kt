package com.muxuechat.domain.context

import com.muxuechat.domain.prompt.PromptMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicTokenEstimatorTest {

    @Test
    fun emptyText_costsNothing() {
        assertEquals(0L, HeuristicTokenEstimator.estimateText(""))
    }

    /** 连续 ASCII 单词按 4 字符 1 token 折算，不足 4 也算 1。 */
    @Test
    fun asciiWords_areFoldedByFourCharacters() {
        assertEquals(1L, HeuristicTokenEstimator.estimateText("hi"))
        assertEquals(1L, HeuristicTokenEstimator.estimateText("abcd"))
        assertEquals(2L, HeuristicTokenEstimator.estimateText("abcde"))
    }

    /** 中文按 code point 计，不能沿用英文的 4 字符系数——那会低估到只有 1/4。 */
    @Test
    fun chineseCharacters_countOneTokenEach() {
        assertEquals(4L, HeuristicTokenEstimator.estimateText("你好世界"))
    }

    /** emoji 是代理对，按 char 遍历会算成 2 个 token。 */
    @Test
    fun emoji_countsAsSingleCodePoint() {
        assertEquals(1L, HeuristicTokenEstimator.estimateText("🙂"))
        assertEquals(2L, HeuristicTokenEstimator.estimateText("🙂🎉"))
    }

    /** 标点很少和邻词合并成一个 token，单独计一个更接近真实分词。 */
    @Test
    fun asciiPunctuation_countsIndividually() {
        // "abcd" -> 1, "," -> 1, "efgh" -> 1
        assertEquals(3L, HeuristicTokenEstimator.estimateText("abcd,efgh"))
    }

    /** 中英文混排不能因为切换字符类别而漏算任何一段。 */
    @Test
    fun mixedText_countsBothSegments() {
        // "你好" -> 2, " world" 是一段连续 ASCII（含空格）共 6 字符 -> 2
        assertEquals(4L, HeuristicTokenEstimator.estimateText("你好 world"))
    }

    @Test
    fun message_addsProtocolOverhead() {
        val text = HeuristicTokenEstimator.estimateText("hello")
        val role = HeuristicTokenEstimator.estimateText("user")

        assertEquals(
            text + role + HeuristicTokenEstimator.PER_MESSAGE_OVERHEAD_TOKENS,
            HeuristicTokenEstimator.estimateMessage("user", "hello"),
        )
    }

    @Test
    fun messages_sumIndividualEstimates() {
        val messages = listOf(
            PromptMessage(role = "user", content = "你好"),
            PromptMessage(role = "assistant", content = "hello"),
        )

        assertEquals(
            messages.sumOf { HeuristicTokenEstimator.estimateMessage(it) },
            HeuristicTokenEstimator.estimateMessages(messages),
        )
    }

    /** 工具 schema 是实打实发出去的字节，名称、描述、参数 JSON 都必须计入。 */
    @Test
    fun toolDefinition_countsNameDescriptionAndSchema() {
        val name = "create_role_card"
        val description = "创建一张角色卡"
        val schema = """{"type":"object","properties":{"name":{"type":"string"}}}"""

        val expected = HeuristicTokenEstimator.estimateText(name) +
            HeuristicTokenEstimator.estimateText(description) +
            HeuristicTokenEstimator.estimateText(schema) +
            HeuristicTokenEstimator.PER_TOOL_OVERHEAD_TOKENS

        assertEquals(
            expected,
            HeuristicTokenEstimator.estimateToolDefinition(name, description, schema),
        )
        assertTrue(HeuristicTokenEstimator.estimateToolDefinition(name, description, schema) > 20L)
    }

    /** 宁可高估：低估会让请求真的超限，而高估只是早一点触发压缩。 */
    @Test
    fun safetyMargin_addsTenPercent() {
        assertEquals(1_100L, HeuristicTokenEstimator.withSafetyMargin(1_000L))
        assertEquals(0L, HeuristicTokenEstimator.withSafetyMargin(0L))
        assertEquals(0L, HeuristicTokenEstimator.withSafetyMargin(-5L))
    }

    /** 文本变长时估算必须单调不减，否则裁剪循环可能永远收敛不了。 */
    @Test
    fun estimate_isMonotonicAsTextGrows() {
        var previous = 0L
        var text = ""
        repeat(50) { index ->
            text += if (index % 2 == 0) "剧情推进" else "narrative "
            val current = HeuristicTokenEstimator.estimateText(text)
            assertTrue("index=$index previous=$previous current=$current", current >= previous)
            previous = current
        }
    }
}
