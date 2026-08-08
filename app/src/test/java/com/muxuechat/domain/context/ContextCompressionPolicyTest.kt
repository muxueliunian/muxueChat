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

    @Test
    fun budgetChars_isClampedIntoAllowedRange() {
        assertEquals(
            ContextCompressionPolicy.MAX_BUDGET_CHARS,
            ContextCompressionPolicy(budgetChars = 10_000_000).normalized().budgetChars,
        )
        assertEquals(
            ContextCompressionPolicy.MIN_BUDGET_CHARS,
            ContextCompressionPolicy(budgetChars = 1).normalized().budgetChars,
        )
    }

    @Test
    fun triggerAndTargetChars_areDerivedFromBudget() {
        val policy = ContextCompressionPolicy(
            autoTriggerPercent = 75,
            targetPercent = 45,
            budgetChars = 40_000,
        ).normalized()

        assertEquals(30_000, policy.autoTriggerChars)
        assertEquals(18_000, policy.targetChars)
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
