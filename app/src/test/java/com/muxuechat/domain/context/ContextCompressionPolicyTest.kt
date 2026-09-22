package com.muxuechat.domain.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressionPolicyTest {

    @Test
    fun autoTriggerPercent_isClampedIntoAllowedRange() {
        assertEquals(
            ContextCompressionPolicy.MAX_AUTO_TRIGGER_PERCENT,
            ContextCompressionPolicy(autoTriggerPercent = 500).normalized().autoTriggerPercent,
        )
        assertEquals(
            ContextCompressionPolicy.MIN_AUTO_TRIGGER_PERCENT,
            ContextCompressionPolicy(autoTriggerPercent = -10).normalized().autoTriggerPercent,
        )
    }

    /** 推荐区间 50%~90% 必须原样保留，不能被收敛掉。 */
    @Test
    fun recommendedRange_passesThroughUnchanged() {
        (ContextCompressionPolicy.RECOMMENDED_MIN_AUTO_TRIGGER_PERCENT..
            ContextCompressionPolicy.RECOMMENDED_MAX_AUTO_TRIGGER_PERCENT).forEach { percent ->
            assertEquals(
                percent,
                ContextCompressionPolicy(autoTriggerPercent = percent).normalized().autoTriggerPercent,
            )
        }
    }

    /**
     * 目标线必须明显低于触发线。否则压缩完立刻又达标，会每轮都发一次摘要请求。
     */
    @Test
    fun targetPercent_isForcedBelowTriggerPercent() {
        val policy = ContextCompressionPolicy(
            autoTriggerPercent = 60,
            targetPercent = 95,
        ).normalized()

        assertTrue(
            "target=${policy.targetPercent} trigger=${policy.autoTriggerPercent}",
            policy.targetPercent <= policy.autoTriggerPercent - ContextCompressionPolicy.MIN_TRIGGER_TARGET_GAP,
        )
    }

    @Test
    fun targetPercent_neverDropsBelowFloorEvenWithLowTrigger() {
        val policy = ContextCompressionPolicy(
            autoTriggerPercent = ContextCompressionPolicy.MIN_AUTO_TRIGGER_PERCENT,
            targetPercent = 5,
        ).normalized()

        assertEquals(ContextCompressionPolicy.MIN_TARGET_PERCENT, policy.targetPercent)
    }

    /**
     * 阈值现在是相对**模型输入 token 预算**的百分比，不再有任何写死的字符预算。
     * 用 DeepSeek 默认的 945,904 做一次端到端核对。
     */
    @Test
    fun triggerAndTargetTokens_areDerivedFromInputBudget() {
        val policy = ContextCompressionPolicy(
            autoTriggerPercent = 75,
            targetPercent = 45,
        ).normalized()

        val budget = 945_904L
        assertEquals(709_428L, policy.autoTriggerTokens(budget))
        assertEquals(425_656L, policy.targetTokens(budget))
    }

    /** 预算非法时不能除出负数或抛异常，直接退化成 0。 */
    @Test
    fun triggerAndTargetTokens_areZeroForNonPositiveBudget() {
        val policy = ContextCompressionPolicy().normalized()

        assertEquals(0L, policy.autoTriggerTokens(0L))
        assertEquals(0L, policy.targetTokens(-1L))
    }

    /** 百万级预算下不能因为先乘后除溢出。 */
    @Test
    fun triggerTokens_doesNotOverflowForHugeBudgets() {
        val policy = ContextCompressionPolicy(autoTriggerPercent = 90, targetPercent = 45).normalized()

        assertEquals(9_000_000_000L, policy.autoTriggerTokens(10_000_000_000L))
    }

    @Test
    fun modeStorage_roundTripsAndFallsBackToDefault() {
        ContextCompressionMode.entries.forEach { mode ->
            assertEquals(mode, ContextCompressionMode.fromStorage(mode.storageValue))
        }
        assertEquals(ContextCompressionMode.DEFAULT, ContextCompressionMode.fromStorage(null))
        assertEquals(ContextCompressionMode.DEFAULT, ContextCompressionMode.fromStorage("不认识的值"))
    }
}
