package com.muxuechat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUsageNormalizerTest {

    @Test
    fun completeUsage_passesThrough() {
        val usage = TokenUsageNormalizer.normalize(promptTokens = 13_102, completionTokens = 816, totalTokens = 13_918)

        assertEquals(13_102, usage?.promptTokens)
        assertEquals(816, usage?.completionTokens)
        assertEquals(13_918, usage?.totalTokens)
    }

    /** 上游不返回 usage 是常态。返回 null 让调用方保留上一份有效快照，而不是写入一行空数据。 */
    @Test
    fun allMissing_returnsNull() {
        assertNull(TokenUsageNormalizer.normalize(null, null, null))
    }

    /** total 与 completion 都在时可以安全反推输入。 */
    @Test
    fun missingPrompt_isDerivedFromTotalAndCompletion() {
        val usage = TokenUsageNormalizer.normalize(promptTokens = null, completionTokens = 816, totalTokens = 13_918)

        assertEquals(13_102, usage?.promptTokens)
    }

    /**
     * 只有 total 时绝不能拿它冒充输入 token——那会把输出部分也算进上下文占用，
     * 让用户以为自己离窗口上限比实际更近。
     */
    @Test
    fun totalAlone_neverImpersonatesPromptTokens() {
        val usage = TokenUsageNormalizer.normalize(promptTokens = null, completionTokens = null, totalTokens = 13_918)

        assertNull(usage?.promptTokens)
        assertEquals(13_918, usage?.totalTokens)
    }

    @Test
    fun missingTotal_isSummedWhenBothHalvesAreKnown() {
        val usage = TokenUsageNormalizer.normalize(promptTokens = 100, completionTokens = 20, totalTokens = null)

        assertEquals(120, usage?.totalTokens)
    }

    @Test
    fun missingTotal_staysNullWhenAHalfIsMissing() {
        val usage = TokenUsageNormalizer.normalize(promptTokens = 100, completionTokens = null, totalTokens = null)

        assertEquals(100, usage?.promptTokens)
        assertNull(usage?.totalTokens)
    }

    /** 个别代理会回 -1 表示「不支持」，不能把它当成真实数值展示。 */
    @Test
    fun negativeValues_areTreatedAsMissing() {
        assertNull(TokenUsageNormalizer.normalize(-1, -1, -1))

        val usage = TokenUsageNormalizer.normalize(promptTokens = -1, completionTokens = 20, totalTokens = 120)
        assertEquals(100, usage?.promptTokens)
    }

    /** completion 大于 total 是脏数据，反推出的负输入必须丢弃而不是照单全收。 */
    @Test
    fun inconsistentTotals_doNotProduceNegativePrompt() {
        val usage = TokenUsageNormalizer.normalize(promptTokens = null, completionTokens = 200, totalTokens = 100)

        assertNull(usage?.promptTokens)
        assertEquals(100, usage?.totalTokens)
    }

    @Test
    fun usage_knowsWhetherItBelongsToTheCurrentModel() {
        val usage = ConversationTokenUsage(
            conversationId = "c",
            providerKey = "DEEPSEEK",
            model = "deepseek-v4-flash",
            promptTokens = 100,
            completionTokens = 20,
            totalTokens = 120,
            effectiveContextTokens = 950_000L,
            capturedAtMillis = 1L,
        )

        assertTrue(usage.hasAnyValue)
        assertTrue(usage.matches(providerKey = "DEEPSEEK", model = "deepseek-v4-flash"))
        assertFalse(usage.matches(providerKey = "DEEPSEEK", model = "deepseek-v4-pro"))
        // 两个不同端点即使模型同名也不该混为一谈。
        assertFalse(usage.matches(providerKey = "provider-1", model = "deepseek-v4-flash"))
    }

    @Test
    fun usageWithoutAnyValue_isReportedAsEmpty() {
        val usage = ConversationTokenUsage(
            conversationId = "c",
            providerKey = "DEEPSEEK",
            model = "m",
            promptTokens = null,
            completionTokens = null,
            totalTokens = null,
            effectiveContextTokens = 1L,
            capturedAtMillis = 1L,
        )

        assertFalse(usage.hasAnyValue)
    }
}
