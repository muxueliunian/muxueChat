package com.muxuechat.domain.context

/** 压缩的触发方式。 */
enum class ContextCompressionMode(val storageValue: String) {
    /** 占用达到阈值时自动压缩，用户无需干预。 */
    AUTO("AUTO"),

    /** 只在用户点「立即压缩」时压缩。超预算时仍会硬裁剪，但被裁掉的部分直接丢失。 */
    MANUAL("MANUAL"),

    /** 完全不压缩。只保留硬裁剪这层安全网。 */
    OFF("OFF"),
    ;

    companion object {
        val DEFAULT = AUTO

        fun fromStorage(value: String?): ContextCompressionMode =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}

/**
 * 上下文压缩策略。
 *
 * 三层概念要分清：
 * 1. **预算** [budgetChars]：一次请求愿意携带的历史字符上限。
 * 2. **压缩**：把最老的一段历史换成一份摘要，用一次额外请求换取剧情连续性。
 *    没有它，长 RP 会话被裁掉的前情就是彻底丢失，沉浸感断裂。
 * 3. **硬裁剪**：不可关闭的安全网。即使压缩关闭或摘要请求失败，也必须保证请求不超长。
 */
data class ContextCompressionPolicy(
    val mode: ContextCompressionMode = ContextCompressionMode.DEFAULT,
    /** 占用达到预算的百分之多少时自动压缩。 */
    val autoTriggerPercent: Int = DEFAULT_AUTO_TRIGGER_PERCENT,
    /** 压缩后希望把逐字部分降到预算的百分之多少。 */
    val targetPercent: Int = DEFAULT_TARGET_PERCENT,
    val budgetChars: Int = DEFAULT_BUDGET_CHARS,
) {
    fun normalized(): ContextCompressionPolicy {
        val trigger = autoTriggerPercent.coerceIn(MIN_AUTO_TRIGGER_PERCENT, MAX_AUTO_TRIGGER_PERCENT)
        return copy(
            autoTriggerPercent = trigger,
            // 压缩目标必须明显低于触发线，否则压完立刻又达标，会反复触发摘要请求。
            targetPercent = targetPercent.coerceIn(
                MIN_TARGET_PERCENT,
                (trigger - MIN_TRIGGER_TARGET_GAP).coerceAtLeast(MIN_TARGET_PERCENT),
            ),
            budgetChars = budgetChars.coerceIn(MIN_BUDGET_CHARS, MAX_BUDGET_CHARS),
        )
    }

    /** 触发压缩的绝对字符数。 */
    val autoTriggerChars: Int
        get() = (budgetChars.toLong() * autoTriggerPercent / 100).toInt()

    /** 压缩后逐字部分的目标字符数。 */
    val targetChars: Int
        get() = (budgetChars.toLong() * targetPercent / 100).toInt()

    companion object {
        /** 推荐区间 50%~90%；允许用户自定义到更宽的范围。 */
        const val RECOMMENDED_MIN_AUTO_TRIGGER_PERCENT = 50
        const val RECOMMENDED_MAX_AUTO_TRIGGER_PERCENT = 90

        const val MIN_AUTO_TRIGGER_PERCENT = 20
        const val MAX_AUTO_TRIGGER_PERCENT = 95
        const val DEFAULT_AUTO_TRIGGER_PERCENT = 75

        const val MIN_TARGET_PERCENT = 10
        const val DEFAULT_TARGET_PERCENT = 45

        /** 触发线与目标线之间至少要拉开的差距，防止压缩后立刻再次触发。 */
        const val MIN_TRIGGER_TARGET_GAP = 10

        const val MIN_BUDGET_CHARS = 4_000
        const val MAX_BUDGET_CHARS = 200_000

        /**
         * 默认逐字历史预算。DeepSeek V4 上下文足够长，真正的约束是费用而非长度。
         * 4 万字符在中文下约合 2.5~3 万 token，够覆盖几十轮 RP 正文。
         */
        const val DEFAULT_BUDGET_CHARS = 40_000
    }
}

/** 某个会话已有的摘要状态。 */
data class ContextSummaryState(
    val summary: String = "",
    /** 摘要已覆盖到历史的前多少条消息。 */
    val coveredMessageCount: Int = 0,
) {
    val hasSummary: Boolean
        get() = summary.isNotBlank() && coveredMessageCount > 0

    companion object {
        val EMPTY = ContextSummaryState()
    }
}
