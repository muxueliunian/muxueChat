package com.muxuechat.domain.context

import com.muxuechat.domain.prompt.PromptMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowPlannerTest {
    private val planner = ContextWindowPlanner()

    private fun user(content: String) = PromptMessage(role = "user", content = content)
    private fun assistant(content: String) = PromptMessage(role = "assistant", content = content)

    /** 交替的 user/assistant 历史，每条 [charsEach] 个字符。 */
    private fun history(turns: Int, charsEach: Int): List<PromptMessage> =
        (0 until turns).flatMap { index ->
            listOf(
                user("u$index".padEnd(charsEach, 'x')),
                assistant("a$index".padEnd(charsEach, 'y')),
            )
        }

    private fun policy(
        mode: ContextCompressionMode = ContextCompressionMode.AUTO,
        budget: Int = 4_000,
        trigger: Int = 75,
        target: Int = 45,
    ) = ContextCompressionPolicy(
        mode = mode,
        autoTriggerPercent = trigger,
        targetPercent = target,
        budgetChars = budget,
    )

    // ---------- 预算与裁剪 ----------

    @Test
    fun withinBudget_keepsEverything() {
        val messages = history(turns = 3, charsEach = 100)
        val plan = planner.plan(messages, policy = policy(budget = 10_000))

        assertEquals(messages, plan.messages)
        assertEquals(0, plan.hardTrimmedMessageCount)
        assertEquals(0, plan.summarizedMessageCount)
        assertEquals(600, plan.totalHistoryChars)
        assertNull(plan.summaryMessage)
        assertFalse(plan.hasUnrecoverableLoss)
    }

    @Test
    fun overBudgetWithoutSummary_hardTrimsAndFlagsUnrecoverableLoss() {
        // 预算低于 MIN_BUDGET_CHARS 会被 policy 收敛，所以这里用足够大的历史来制造超预算。
        val messages = history(turns = 20, charsEach = 500)
        val plan = planner.plan(messages, policy = policy(budget = 4_000))

        assertTrue(plan.hardTrimmedMessageCount > 0)
        assertTrue(plan.verbatimChars <= 4_000)
        assertEquals(20_000, plan.totalHistoryChars)
        assertTrue(plan.hasUnrecoverableLoss)
        // 保留的必须是原历史的后缀，顺序不能乱。
        assertEquals(messages.takeLast(plan.messages.size), plan.messages)
    }

    /** 保留窗口必须以 user 开头，否则模型会看到一条没有对应输入的回复。 */
    @Test
    fun trimmedWindow_alwaysStartsWithUserMessage() {
        val messages = history(turns = 20, charsEach = 500)
        listOf(4_000, 5_500, 8_000, 11_000, 25_000).forEach { budget ->
            val plan = planner.plan(messages, policy = policy(budget = budget))

            assertTrue("budget=$budget 结果不能为空", plan.messages.isNotEmpty())
            assertEquals("budget=$budget 必须以 user 开头", "user", plan.messages.first().role)
        }
    }

    @Test
    fun emptyHistory_isHandledWithoutCrashing() {
        val plan = planner.plan(emptyList(), policy = policy(budget = 4_000))

        assertTrue(plan.messages.isEmpty())
        assertEquals(0, plan.totalHistoryChars)
        assertEquals(0, plan.usagePercent)
    }

    @Test
    fun singleOversizedMessage_isStillKept() {
        val plan = planner.plan(listOf(user("x".repeat(50_000))), policy = policy(budget = 4_000))

        assertEquals(1, plan.messages.size)
        assertEquals("user", plan.messages.first().role)
    }

    // ---------- 摘要顶替 ----------

    @Test
    fun withSummary_coveredMessagesAreReplacedNotTrimmed() {
        val messages = history(turns = 20, charsEach = 100)
        val state = ContextSummaryState(summary = "更早的剧情摘要", coveredMessageCount = 30)
        val plan = planner.plan(messages, state, policy(budget = 4_000))

        assertEquals(30, plan.summarizedMessageCount)
        assertEquals(10, plan.messages.size)
        assertNotNull(plan.summaryMessage)
        // 被摘要覆盖的部分不算信息损失。
        assertEquals(0, plan.hardTrimmedMessageCount)
        assertFalse(plan.hasUnrecoverableLoss)
    }

    @Test
    fun summaryMessage_isSystemRoleAndMarkedAsHistoryNotInstruction() {
        val state = ContextSummaryState(summary = "他们在雨中告别。", coveredMessageCount = 10)
        val plan = planner.plan(history(turns = 20, charsEach = 100), state, policy())

        val summaryMessage = plan.summaryMessage!!
        assertEquals("system", summaryMessage.role)
        assertTrue(summaryMessage.content.contains("更早剧情摘要"))
        assertTrue(summaryMessage.content.contains("他们在雨中告别。"))
        // 必须明确告诉模型这是历史而不是本轮指令。
        assertTrue(summaryMessage.content.contains("不是用户本轮的指令"))
    }

    @Test
    fun summaryChars_countAgainstBudget() {
        val summary = "摘".repeat(1_000)
        val state = ContextSummaryState(summary = summary, coveredMessageCount = 10)
        val plan = planner.plan(history(turns = 20, charsEach = 100), state, policy(budget = 4_000))

        assertEquals(summary.length, plan.summaryChars)
        assertEquals(plan.verbatimChars + summary.length, plan.effectiveChars)
        assertTrue("逐字部分应受摘要挤压", plan.verbatimChars <= 3_000)
    }

    @Test
    fun compressionOff_ignoresExistingSummaryButStillHardTrims() {
        val messages = history(turns = 20, charsEach = 500)
        val state = ContextSummaryState(summary = "旧摘要", coveredMessageCount = 30)
        val plan = planner.plan(messages, state, policy(mode = ContextCompressionMode.OFF, budget = 4_000))

        assertNull("压缩关闭时不得使用摘要", plan.summaryMessage)
        assertEquals(0, plan.summarizedMessageCount)
        // 裁剪是保底安全网，不受压缩开关影响。
        assertTrue(plan.hardTrimmedMessageCount > 0)
    }

    /** 消息被删除（重新生成/编辑）后，旧的覆盖计数可能超过实际历史长度。 */
    @Test
    fun staleSummaryCoverage_isIgnoredInsteadOfCrashing() {
        val messages = history(turns = 2, charsEach = 100)
        val state = ContextSummaryState(summary = "旧摘要", coveredMessageCount = 999)
        val plan = planner.plan(messages, state, policy())

        assertNull(plan.summaryMessage)
        assertEquals(0, plan.summarizedMessageCount)
        assertEquals(messages, plan.messages)
    }

    // ---------- 自动触发 ----------

    @Test
    fun shouldAutoCompact_onlyWhenAutoModeAndOverTriggerThreshold() {
        val messages = history(turns = 20, charsEach = 100) // 4000 字符

        // 预算 4000，触发线 75% = 3000，占用 4000 → 超过。
        val over = planner.plan(messages, policy = policy(budget = 4_000, trigger = 75))
        assertTrue(planner.shouldAutoCompact(over))

        // 预算 40000，触发线 75% = 30000，占用 4000 → 未到。
        val under = planner.plan(messages, policy = policy(budget = 40_000, trigger = 75))
        assertFalse(planner.shouldAutoCompact(under))

        // 手动模式下永不自动触发。
        val manual = planner.plan(messages, policy = policy(mode = ContextCompressionMode.MANUAL, budget = 4_000))
        assertFalse(planner.shouldAutoCompact(manual))

        val off = planner.plan(messages, policy = policy(mode = ContextCompressionMode.OFF, budget = 4_000))
        assertFalse(planner.shouldAutoCompact(off))
    }

    @Test
    fun usagePercent_reflectsEffectiveCharsOverBudget() {
        val plan = planner.plan(history(turns = 10, charsEach = 100), policy = policy(budget = 4_000))

        // 10 轮 * 2 条 * 100 字符 = 2000，占 4000 的 50%。
        assertEquals(50, plan.usagePercent)
    }

    // ---------- 压缩规划 ----------

    @Test
    fun planCompaction_selectsOldestMessagesDownToTarget() {
        val messages = history(turns = 20, charsEach = 100)
        val plan = planner.planCompaction(messages, policy = policy(budget = 4_000, target = 45))!!

        assertTrue(plan.messagesToSummarize.isNotEmpty())
        // 选中的必须是最老的一段，且是原历史的前缀。
        assertEquals(messages.take(plan.messagesToSummarize.size), plan.messagesToSummarize)
        assertEquals(plan.messagesToSummarize.size, plan.newCoveredMessageCount)

        val remainingChars = messages.drop(plan.newCoveredMessageCount).sumOf { it.content.length }
        assertTrue("压缩后应降到目标线附近: $remainingChars", remainingChars <= 1_800)
    }

    /** 压缩后剩下的窗口同样必须以 user 开头。 */
    @Test
    fun planCompaction_leavesWindowStartingWithUserMessage() {
        val messages = history(turns = 20, charsEach = 100)
        val plan = planner.planCompaction(messages, policy = policy(budget = 4_000))!!

        val remaining = messages.drop(plan.newCoveredMessageCount)
        assertEquals("user", remaining.first().role)
    }

    @Test
    fun planCompaction_mergesWithPreviousSummary() {
        val messages = history(turns = 20, charsEach = 100)
        val state = ContextSummaryState(summary = "第一段摘要", coveredMessageCount = 10)
        val plan = planner.planCompaction(messages, state, policy(budget = 4_000))!!

        assertEquals("第一段摘要", plan.previousSummary)
        // 不能重复压缩已被覆盖的部分。
        assertEquals(messages.drop(10).take(plan.messagesToSummarize.size), plan.messagesToSummarize)
        assertTrue(plan.newCoveredMessageCount > 10)
    }

    @Test
    fun planCompaction_returnsNullWhenHistoryTooShort() {
        assertNull(planner.planCompaction(history(turns = 1, charsEach = 100), policy = policy()))
        assertNull(planner.planCompaction(emptyList(), policy = policy()))
    }

    @Test
    fun planCompaction_returnsNullWhenCompressionOff() {
        val messages = history(turns = 20, charsEach = 100)

        assertNull(planner.planCompaction(messages, policy = policy(mode = ContextCompressionMode.OFF)))
    }

    /** 手动模式仍然可以压缩——只是不自动触发。 */
    @Test
    fun planCompaction_worksInManualMode() {
        val messages = history(turns = 20, charsEach = 100)
        val plan = planner.planCompaction(
            messages,
            policy = policy(mode = ContextCompressionMode.MANUAL, budget = 4_000),
        )

        assertNotNull(plan)
    }

    @Test
    fun planCompaction_alwaysLeavesRecentMessagesVerbatim() {
        val messages = history(turns = 20, charsEach = 100)
        val plan = planner.planCompaction(messages, policy = policy(budget = 4_000, target = 10))!!

        val remainingCount = messages.size - plan.newCoveredMessageCount
        assertTrue(
            "最近至少要留 ${ContextWindowPlanner.MIN_MESSAGES_TO_KEEP_VERBATIM} 条原文，实际 $remainingCount",
            remainingCount >= ContextWindowPlanner.MIN_MESSAGES_TO_KEEP_VERBATIM,
        )
    }
}
