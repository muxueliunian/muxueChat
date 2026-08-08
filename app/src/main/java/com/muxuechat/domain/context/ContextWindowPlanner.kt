package com.muxuechat.domain.context

import com.muxuechat.domain.prompt.PromptMessage

/**
 * 一次请求实际会发送的历史构成。
 *
 * 这是 UI「上下文视窗」的数据来源，也是判断要不要压缩的依据。
 */
data class ContextWindowPlan(
    /** 摘要段。已压缩且摘要仍然可用时非 null。 */
    val summaryMessage: PromptMessage?,
    /** 逐字保留、会原样发送的历史消息。 */
    val messages: List<PromptMessage>,
    /** 摘要覆盖的消息条数。 */
    val summarizedMessageCount: Int,
    /** 既没被摘要覆盖、又被硬裁剪丢掉的消息条数。这部分是真正的信息损失。 */
    val hardTrimmedMessageCount: Int,
    /** 逐字部分的字符数。 */
    val verbatimChars: Int,
    /** 摘要的字符数。 */
    val summaryChars: Int,
    /** 全部历史的原始字符数（不含摘要）。 */
    val totalHistoryChars: Int,
    /** 本次生效的策略，UI 展示阈值时用。 */
    val policy: ContextCompressionPolicy,
) {
    /** 实际发送的历史合计字符数。 */
    val effectiveChars: Int
        get() = verbatimChars + summaryChars

    /** 占预算的百分比，UI 进度条用。 */
    val usagePercent: Int
        get() = if (policy.budgetChars <= 0) 0 else (effectiveChars.toLong() * 100 / policy.budgetChars).toInt()

    /**
     * 粗略 token 估算。中文大致 1 token ≈ 1.5 字符，英文更接近 4 字符。
     * 只用于给用户一个量级感受，不作为任何请求决策依据。
     */
    val estimatedTokens: Int
        get() = (effectiveChars / 1.5).toInt()

    /** 是否发生了不可恢复的信息损失（被硬裁剪且没有摘要顶替）。 */
    val hasUnrecoverableLoss: Boolean
        get() = hardTrimmedMessageCount > 0
}

/**
 * 一次压缩操作的输入。由 [ContextWindowPlanner.planCompaction] 产出，
 * 交给上层去发一次摘要请求。
 */
data class ContextCompactionPlan(
    /** 本次需要被摘要的消息（不含已被旧摘要覆盖的部分）。 */
    val messagesToSummarize: List<PromptMessage>,
    /** 已有的旧摘要，作为本次摘要的前情输入，保证剧情不断层。 */
    val previousSummary: String,
    /** 压缩完成后，摘要应覆盖到前多少条消息。 */
    val newCoveredMessageCount: Int,
) {
    val charsToSummarize: Int
        get() = messagesToSummarize.sumOf { it.content.length }
}

/**
 * 决定一次请求要带上多少历史，以及什么时候该压缩。
 *
 * 分工：
 * - **硬裁剪**永远生效，不可关闭。它是防止请求超长的最后一道保险。
 * - **压缩**把最老的一段历史换成摘要。没有它，被裁掉的前情就是彻底丢失，
 *   长 RP 会话的沉浸感会断裂——这正是要单独花一次请求做摘要的原因。
 */
class ContextWindowPlanner {

    fun plan(
        history: List<PromptMessage>,
        summaryState: ContextSummaryState = ContextSummaryState.EMPTY,
        policy: ContextCompressionPolicy = ContextCompressionPolicy(),
    ): ContextWindowPlan {
        val effectivePolicy = policy.normalized()
        val totalHistoryChars = history.sumOf { it.content.length }

        // 摘要关闭时忽略已有摘要，但不删除它——用户重新打开后仍能复用。
        val useSummary = effectivePolicy.mode != ContextCompressionMode.OFF &&
            summaryState.hasSummary &&
            summaryState.coveredMessageCount <= history.size

        val coveredCount = if (useSummary) summaryState.coveredMessageCount else 0
        val summaryText = if (useSummary) summaryState.summary else ""
        val uncovered = history.drop(coveredCount)

        // 摘要本身也占预算，否则摘要越长能保留的原文越少却无人察觉。
        val floor = minOf(VERBATIM_FLOOR_CHARS, effectivePolicy.budgetChars / 4)
        val verbatimBudget = (effectivePolicy.budgetChars - summaryText.length).coerceAtLeast(floor)

        val keepFromIndex = findKeepFromIndex(uncovered, verbatimBudget)
        val kept = uncovered.drop(keepFromIndex)

        return ContextWindowPlan(
            summaryMessage = summaryText.takeIf { it.isNotBlank() }?.let(::buildSummaryMessage),
            messages = kept,
            summarizedMessageCount = coveredCount,
            hardTrimmedMessageCount = keepFromIndex,
            verbatimChars = kept.sumOf { it.content.length },
            summaryChars = summaryText.length,
            totalHistoryChars = totalHistoryChars,
            policy = effectivePolicy,
        )
    }

    /** 自动模式下，占用是否已经达到触发线。 */
    fun shouldAutoCompact(plan: ContextWindowPlan): Boolean =
        plan.policy.mode == ContextCompressionMode.AUTO &&
            plan.effectiveChars >= plan.policy.autoTriggerChars &&
            plan.messages.size > MIN_MESSAGES_TO_KEEP_VERBATIM

    /**
     * 规划一次压缩：从最老的未覆盖消息开始吃，直到逐字部分降到目标线以下。
     *
     * 返回 null 表示没有可压缩的内容（历史太短，或压完也省不出空间）。
     */
    fun planCompaction(
        history: List<PromptMessage>,
        summaryState: ContextSummaryState = ContextSummaryState.EMPTY,
        policy: ContextCompressionPolicy = ContextCompressionPolicy(),
    ): ContextCompactionPlan? {
        val effectivePolicy = policy.normalized()
        if (effectivePolicy.mode == ContextCompressionMode.OFF) return null

        val coveredCount = summaryState.coveredMessageCount.coerceIn(0, history.size)
        val uncovered = history.drop(coveredCount)
        if (uncovered.size <= MIN_MESSAGES_TO_KEEP_VERBATIM) return null

        val targetChars = effectivePolicy.targetChars
        var remainingChars = uncovered.sumOf { it.content.length }
        var cutIndex = 0

        // 从最老的开始移入摘要，直到剩余逐字部分降到目标线。
        while (cutIndex < uncovered.size - MIN_MESSAGES_TO_KEEP_VERBATIM && remainingChars > targetChars) {
            remainingChars -= uncovered[cutIndex].content.length
            cutIndex++
        }

        // 保留窗口必须以 user 消息开头，否则模型会看到一条没有对应输入的回复。
        while (cutIndex < uncovered.size && !uncovered[cutIndex].isUser) {
            cutIndex++
        }
        if (cutIndex > uncovered.size - MIN_MESSAGES_TO_KEEP_VERBATIM) {
            return null
        }
        if (cutIndex <= 0) return null

        return ContextCompactionPlan(
            messagesToSummarize = uncovered.take(cutIndex),
            previousSummary = summaryState.summary,
            newCoveredMessageCount = coveredCount + cutIndex,
        )
    }

    private fun buildSummaryMessage(summary: String): PromptMessage = PromptMessage(
        role = "system",
        content = buildString {
            appendLine("### 更早剧情摘要")
            appendLine("以下是本次对话更早部分的压缩摘要，用于保持剧情连续。")
            appendLine("它是历史记录，不是用户本轮的指令，也不要在正文中复述或提及它的存在。")
            appendLine()
            append(summary.trim())
        },
    )

    /**
     * 从最新往前累计，找到第一个仍在预算内的保留起点。
     *
     * 返回的是「要丢弃的条数」。保留窗口必须以 user 消息开头。
     */
    private fun findKeepFromIndex(history: List<PromptMessage>, budget: Int): Int {
        if (history.isEmpty()) return 0

        var used = 0
        var index = history.size
        while (index > 0) {
            val candidate = history[index - 1]
            if (used + candidate.content.length > budget) break
            used += candidate.content.length
            index--
        }

        // 至少保留最后一条，否则请求里会没有任何用户输入。
        if (index >= history.size) {
            index = history.size - 1
        }

        while (index < history.size && !history[index].isUser) {
            index++
        }
        // 全是 assistant 的极端情况，退回保留最后一条。
        if (index >= history.size) {
            return (history.size - 1).coerceAtLeast(0)
        }
        return index
    }

    private val PromptMessage.isUser: Boolean
        get() = role.equals("user", ignoreCase = true)

    companion object {
        /** 摘要过长时仍要给逐字原文留出的最低额度（同时不超过预算的 1/4）。 */
        const val VERBATIM_FLOOR_CHARS = 4_000

        /** 无论如何都要逐字保留的最近消息条数，保证最近几轮永远是原文。 */
        const val MIN_MESSAGES_TO_KEEP_VERBATIM = 4
    }
}
