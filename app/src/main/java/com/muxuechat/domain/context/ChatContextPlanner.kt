package com.muxuechat.domain.context

import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.ConversationRpSettings
import com.muxuechat.domain.model.ConversationTokenUsage
import com.muxuechat.domain.model.ProtagonistProfile
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RpStatusPanel
import com.muxuechat.domain.model.SupportingCharacter
import com.muxuechat.domain.model.WorldSetting
import com.muxuechat.domain.model.WritingStyleProfile
import com.muxuechat.domain.prompt.PromptComposer
import com.muxuechat.domain.prompt.PromptContext
import com.muxuechat.domain.prompt.PromptMessage

/** 一个工具定义在上下文里占的位置。只保留估算需要的三段文本，不依赖任何网络 DTO。 */
data class ContextToolDefinition(
    val name: String,
    val description: String,
    val parametersJson: String,
)

/** 上下文占用的分项。UI 的「构成」区域直接用它，不再自己算。 */
enum class ContextSectionKind {
    /** 用户自己写的 system prompt。 */
    SYSTEM_PROMPT,

    /** RP 规则 + 角色卡 + 世界观 + 文风 + 主角档案 + 状态栏契约 + 本轮状态 + RP Marker。 */
    RP_SETTINGS,

    /** 更早剧情的摘要。 */
    SUMMARY,

    /** 逐字保留、原样发送的历史消息。 */
    VERBATIM_HISTORY,

    /** 工具可用性说明 + 工具 JSON schema。 */
    TOOL_DEFINITIONS,

    /** 本轮工具调用产生的 assistant(tool_calls) 与 tool 结果消息。 */
    TOOL_EXCHANGE,
}

data class ContextSectionUsage(
    val kind: ContextSectionKind,
    val tokens: Long,
    val messageCount: Int,
)

/**
 * 规划一次请求所需的全部输入。
 *
 * 它必须覆盖**真正会被发出去的每一段内容**——旧实现只把历史消息交给规划器，
 * system prompt、RP 段和工具 schema 完全不进账，于是「占用百分比」和真实请求毫无关系。
 */
data class ChatContextInput(
    /**
     * 请求协议。App 只有 DeepSeek 与 OpenAI 兼容两条，[ChatProviderId] 已经精确表达了这件事，
     * 不再单独定义一个同构的枚举。
     */
    val protocol: ChatProviderId,
    val settings: ChatSettings,
    val rpSettings: ConversationRpSettings?,
    val roleCard: RoleCard?,
    val worldSetting: WorldSetting?,
    val writingStyle: WritingStyleProfile?,
    val statusPanel: RpStatusPanel?,
    /** 会话绑定的主角档案，只在 RP 启用时非空。 */
    val protagonistProfile: ProtagonistProfile? = null,
    /** 会话勾选的配角，只在 RP 启用时非空；计入 RP 固定成本（§6.2）。 */
    val supportingCharacters: List<SupportingCharacter> = emptyList(),
    /** 完整的可用历史，未经裁剪。 */
    val history: List<PromptMessage>,
    val summaryState: ContextSummaryState = ContextSummaryState.EMPTY,
    val toolDefinitions: List<ContextToolDefinition> = emptyList(),
    /**
     * 本轮工具调用产生的临时消息，只用于**计入预算**。
     *
     * 它们带有 `tool_call_id`、`tool_calls` 等协议专属字段，塞不进 [PromptMessage]，
     * 因此由请求层原样追加在 [ContextWindowPlan.requestMessages] 之后。
     * 这里传入的是等价文本，保证工具轮的占用不会被漏算——旧实现在工具轮里完全不重算上下文。
     */
    val toolExchange: List<PromptMessage> = emptyList(),
    val spec: ModelContextSpec,
    val policy: ContextCompressionPolicy = ContextCompressionPolicy(),
    val lastApiUsage: ConversationTokenUsage? = null,
)

/**
 * 一次请求的最终规划结果。
 *
 * 这是**唯一真相来源**：网络层照着 [requestMessages] 发，UI 照着 [sections] 显示，
 * 压缩照着 [usagePercent] 判断。三者不允许各算各的。
 */
data class ContextWindowPlan(
    /**
     * 最终要发送的消息。网络层只做 DTO 映射，不得再加工。
     *
     * **不含**本轮工具交换消息：那些带协议专属字段，由请求层原样追加在这之后，
     * 但它们的占用已经计入 [estimatedInputTokens] 与 [ContextSectionKind.TOOL_EXCHANGE]。
     */
    val requestMessages: List<PromptMessage>,
    val spec: ModelContextSpec,
    /** 预计输入 token。含安全余量，永远标注为「预计」。 */
    val estimatedInputTokens: Long,
    val inputBudgetTokens: Long,
    /** 各分项占用。分项之和与 [estimatedInputTokens] 可能差几个 token（各自向下取整）。 */
    val sections: List<ContextSectionUsage>,
    /** 摘要覆盖的消息条数。 */
    val summarizedMessageCount: Int,
    /** 既没被摘要覆盖、又被硬裁剪丢掉的条数。这部分是真正的信息损失。 */
    val hardTrimmedMessageCount: Int,
    /** 全部可用历史的条数，用于说明「保留了多少 / 一共多少」。 */
    val totalHistoryMessageCount: Int,
    val policy: ContextCompressionPolicy,
    val lastApiUsage: ConversationTokenUsage?,
    /** 摘要因编辑历史而被作废；UI 据此解释为什么占用突然变大。 */
    val summaryInvalidated: Boolean,
    /**
     * 不可裁剪内容本身已经超出预算时的中文错误。
     *
     * 非 null 时**禁止发起网络请求**——与其让服务端回 400，不如在本地说清楚是哪一部分太长。
     */
    val overflowError: String?,
) {
    val usagePercent: Int
        get() = if (inputBudgetTokens <= 0L) {
            0
        } else {
            (estimatedInputTokens * 100 / inputBudgetTokens).coerceIn(0L, MAX_USAGE_PERCENT).toInt()
        }

    fun tokensOf(kind: ContextSectionKind): Long =
        sections.firstOrNull { it.kind == kind }?.tokens ?: 0L

    fun messagesOf(kind: ContextSectionKind): Int =
        sections.firstOrNull { it.kind == kind }?.messageCount ?: 0

    /** 是否发生了不可恢复的信息损失（被硬裁剪且没有摘要顶替）。 */
    val hasUnrecoverableLoss: Boolean
        get() = hardTrimmedMessageCount > 0

    private companion object {
        /** 超预算时不让进度条数字失控，999% 和 12000% 对用户是一样的信息。 */
        const val MAX_USAGE_PERCENT = 999L
    }
}

/**
 * 一次压缩操作的输入。由 [ChatContextPlanner.planCompaction] 产出，
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
 * 决定一次请求带上多少内容、什么时候压缩、以及最终发出去的消息长什么样。
 *
 * 分工：
 * - **硬裁剪**永远生效，不可关闭。它是防止请求超长的最后一道保险。
 * - **压缩**把最老的一段历史换成摘要。没有它，被裁掉的前情就是彻底丢失，
 *   长 RP 会话的沉浸感会断裂——这正是要单独花一次请求做摘要的原因。
 *
 * 与旧的 `ContextWindowPlanner` 的根本区别：预算是模型真实 token 窗口推导出来的，
 * 且 system prompt / RP 段 / 工具 schema / 工具轮消息全部计入。
 */
class ChatContextPlanner(
    private val promptComposer: PromptComposer = PromptComposer(),
) {

    fun plan(input: ChatContextInput): ContextWindowPlan {
        val policy = input.policy.normalized()
        val budget = input.spec.inputBudgetTokens

        val useSummary = policy.mode != ContextCompressionMode.OFF &&
            input.summaryState.hasSummary &&
            input.summaryState.coveredMessageCount <= input.history.size

        val coveredCount = if (useSummary) input.summaryState.coveredMessageCount else 0
        val summaryText = if (useSummary) input.summaryState.summary else ""
        val summaryMessage = summaryText.takeIf { it.isNotBlank() }?.let(::buildSummaryMessage)
        val uncovered = input.history.drop(coveredCount)

        val fixed = estimateFixedCost(input, summaryMessage)
        val keepFromIndex = findKeepFromIndex(uncovered, fixed.rawTokens, budget)
        val kept = uncovered.drop(keepFromIndex)

        val composed = promptComposer.composeStructured(
            context = buildPromptContext(input, listOfNotNull(summaryMessage) + kept),
            protocol = input.protocol,
        )

        val historyRaw = kept.sumOf { HeuristicTokenEstimator.estimateMessage(it) }
        val totalRaw = fixed.rawTokens + historyRaw
        val estimated = HeuristicTokenEstimator.withSafetyMargin(totalRaw)

        return ContextWindowPlan(
            requestMessages = composed.messages,
            spec = input.spec,
            estimatedInputTokens = estimated,
            inputBudgetTokens = budget,
            sections = buildSections(fixed, historyRaw, kept.size),
            summarizedMessageCount = coveredCount,
            hardTrimmedMessageCount = keepFromIndex,
            totalHistoryMessageCount = input.history.size,
            policy = policy,
            lastApiUsage = input.lastApiUsage,
            summaryInvalidated = input.summaryState.hasSummary && !useSummary &&
                policy.mode != ContextCompressionMode.OFF,
            overflowError = overflowErrorOrNull(estimated, budget, input),
        )
    }

    /** 自动模式下，预计占用是否已经达到触发线。 */
    fun shouldAutoCompact(plan: ContextWindowPlan): Boolean =
        plan.policy.mode == ContextCompressionMode.AUTO &&
            plan.estimatedInputTokens >= plan.policy.autoTriggerTokens(plan.inputBudgetTokens) &&
            plan.messagesOf(ContextSectionKind.VERBATIM_HISTORY) > MIN_MESSAGES_TO_KEEP_VERBATIM

    /**
     * 规划一次压缩：从最老的未覆盖消息开始吃，直到预计总输入降到目标线以下。
     *
     * 返回 null 表示没有可压缩的内容（历史太短，或压完也省不出空间）。
     */
    fun planCompaction(input: ChatContextInput): ContextCompactionPlan? {
        val policy = input.policy.normalized()
        if (policy.mode == ContextCompressionMode.OFF) return null

        val coveredCount = input.summaryState.coveredMessageCount.coerceIn(0, input.history.size)
        val uncovered = input.history.drop(coveredCount)
        if (uncovered.size <= MIN_MESSAGES_TO_KEEP_VERBATIM) return null

        val budget = input.spec.inputBudgetTokens
        val targetTokens = policy.targetTokens(budget)
        // 摘要本身也要占位置，否则「压到 45%」压完还是超。这里用当前摘要长度做保守占位。
        val fixed = estimateFixedCost(
            input = input,
            summaryMessage = input.summaryState.summary
                .takeIf { it.isNotBlank() }
                ?.let(::buildSummaryMessage),
        )

        var remainingRaw = uncovered.sumOf { HeuristicTokenEstimator.estimateMessage(it) }
        var cutIndex = 0
        val maxCut = uncovered.size - MIN_MESSAGES_TO_KEEP_VERBATIM
        while (
            cutIndex < maxCut &&
            HeuristicTokenEstimator.withSafetyMargin(fixed.rawTokens + remainingRaw) > targetTokens
        ) {
            remainingRaw -= HeuristicTokenEstimator.estimateMessage(uncovered[cutIndex])
            cutIndex++
        }

        // 保留窗口必须以 user 消息开头，否则模型会看到一条没有对应输入的回复。
        // 这一步要在收缩摘要请求之前做：先对齐再收缩，收缩时才能整回合地退，
        // 否则「先收缩、后对齐」会把 cutIndex 再往前推一条，重新撑破摘要请求的预算。
        while (cutIndex < uncovered.size && !uncovered[cutIndex].isUser) {
            cutIndex++
        }
        if (cutIndex > maxCut) {
            // 向后对齐吃掉了必须逐字保留的最近消息：发送前历史以本轮 user 结尾、条数为奇数时，
            // maxCut 恰好落在 assistant 上。此时向前退到上一个 user 边界——少压一点也比完全不压好，
            // 否则最需要压缩的时候反而静默跳过，只剩硬裁剪丢前情。
            cutIndex = maxCut
            while (cutIndex > 0 && !uncovered[cutIndex].isUser) {
                cutIndex--
            }
        }

        // 摘要请求本身也有输入预算。宁可这一轮少压一点，也不能为了压缩去发一个超限的请求。
        cutIndex = capBySummaryRequestBudget(uncovered, input, cutIndex)
        if (cutIndex <= 0) return null

        return ContextCompactionPlan(
            messagesToSummarize = uncovered.take(cutIndex),
            previousSummary = input.summaryState.summary,
            newCoveredMessageCount = coveredCount + cutIndex,
        )
    }

    // ---------- 固定开销 ----------

    /**
     * 与「保留多少历史」无关的那部分开销。
     *
     * system prompt、RP 段、本轮状态、RP Marker、工具定义、工具轮消息和摘要都在这里——
     * 它们每一段都随请求发出去，却是旧实现里完全没被统计的部分。
     */
    private fun estimateFixedCost(input: ChatContextInput, summaryMessage: PromptMessage?): FixedCost {
        val composed = promptComposer.composeStructured(
            context = buildPromptContext(input, emptyList()),
            protocol = input.protocol,
        )

        val systemPromptRaw = HeuristicTokenEstimator.estimateText(composed.userSystemPrompt)
        val toolInstructionsRaw = HeuristicTokenEstimator.estimateText(composed.toolInstructions)
        val toolSchemaRaw = input.toolDefinitions.sumOf { tool ->
            HeuristicTokenEstimator.estimateToolDefinition(
                name = tool.name,
                description = tool.description,
                parametersJson = tool.parametersJson,
            )
        }
        val rpRaw = HeuristicTokenEstimator.estimateText(composed.rpInstructions) +
            HeuristicTokenEstimator.estimateText(composed.volatileState) +
            HeuristicTokenEstimator.estimateText(composed.deepSeekRpMarker)

        // system 段被拼成一条消息，协议开销只算一次，挂在第一个非空段落上，
        // 避免出现「用户没写 system prompt，SYSTEM_PROMPT 却有 5 token」这种看不懂的分项。
        val systemMessageOverhead = if (composed.userSystemPrompt.isNotBlank() ||
            composed.toolInstructions.isNotBlank() ||
            composed.rpInstructions.isNotBlank()
        ) {
            HeuristicTokenEstimator.estimateText("system") + HeuristicTokenEstimator.PER_MESSAGE_OVERHEAD_TOKENS
        } else {
            0L
        }

        val summaryRaw = summaryMessage?.let { HeuristicTokenEstimator.estimateMessage(it) } ?: 0L
        val toolExchangeRaw = input.toolExchange.sumOf { HeuristicTokenEstimator.estimateMessage(it) }

        val overheadOwner = when {
            composed.userSystemPrompt.isNotBlank() -> ContextSectionKind.SYSTEM_PROMPT
            composed.toolInstructions.isNotBlank() -> ContextSectionKind.TOOL_DEFINITIONS
            else -> ContextSectionKind.RP_SETTINGS
        }

        return FixedCost(
            systemPromptRaw = systemPromptRaw +
                systemMessageOverhead.onlyIf(overheadOwner == ContextSectionKind.SYSTEM_PROMPT),
            toolDefinitionsRaw = toolInstructionsRaw + toolSchemaRaw +
                systemMessageOverhead.onlyIf(overheadOwner == ContextSectionKind.TOOL_DEFINITIONS),
            rpSettingsRaw = rpRaw +
                systemMessageOverhead.onlyIf(overheadOwner == ContextSectionKind.RP_SETTINGS),
            summaryRaw = summaryRaw,
            summaryMessageCount = if (summaryMessage != null) 1 else 0,
            toolExchangeRaw = toolExchangeRaw,
            toolExchangeMessageCount = input.toolExchange.size,
            toolDefinitionCount = input.toolDefinitions.size,
        )
    }

    private data class FixedCost(
        val systemPromptRaw: Long,
        val toolDefinitionsRaw: Long,
        val rpSettingsRaw: Long,
        val summaryRaw: Long,
        val summaryMessageCount: Int,
        val toolExchangeRaw: Long,
        val toolExchangeMessageCount: Int,
        val toolDefinitionCount: Int,
    ) {
        val rawTokens: Long
            get() = systemPromptRaw + toolDefinitionsRaw + rpSettingsRaw + summaryRaw + toolExchangeRaw +
                HeuristicTokenEstimator.REQUEST_OVERHEAD_TOKENS
    }

    private fun buildSections(fixed: FixedCost, historyRaw: Long, historyCount: Int): List<ContextSectionUsage> =
        listOf(
            ContextSectionUsage(
                kind = ContextSectionKind.SYSTEM_PROMPT,
                tokens = HeuristicTokenEstimator.withSafetyMargin(fixed.systemPromptRaw),
                messageCount = 0,
            ),
            ContextSectionUsage(
                kind = ContextSectionKind.RP_SETTINGS,
                tokens = HeuristicTokenEstimator.withSafetyMargin(fixed.rpSettingsRaw),
                messageCount = 0,
            ),
            ContextSectionUsage(
                kind = ContextSectionKind.SUMMARY,
                tokens = HeuristicTokenEstimator.withSafetyMargin(fixed.summaryRaw),
                messageCount = fixed.summaryMessageCount,
            ),
            ContextSectionUsage(
                kind = ContextSectionKind.VERBATIM_HISTORY,
                tokens = HeuristicTokenEstimator.withSafetyMargin(historyRaw),
                messageCount = historyCount,
            ),
            ContextSectionUsage(
                kind = ContextSectionKind.TOOL_DEFINITIONS,
                tokens = HeuristicTokenEstimator.withSafetyMargin(fixed.toolDefinitionsRaw),
                messageCount = fixed.toolDefinitionCount,
            ),
            ContextSectionUsage(
                kind = ContextSectionKind.TOOL_EXCHANGE,
                tokens = HeuristicTokenEstimator.withSafetyMargin(fixed.toolExchangeRaw),
                messageCount = fixed.toolExchangeMessageCount,
            ),
        )

    // ---------- 硬裁剪 ----------

    /**
     * 从最新往前累计，找到第一个仍在预算内的保留起点。返回的是「要丢弃的条数」。
     *
     * 两条不可让步的约束：
     * 1. 保留窗口必须以 user 消息开头，否则模型会看到一条没有对应输入的 assistant 回复。
     * 2. 最少也要留下最近一个完整用户回合，否则这一轮等于什么都没问。
     */
    private fun findKeepFromIndex(history: List<PromptMessage>, fixedRaw: Long, budget: Long): Int {
        if (history.isEmpty()) return 0

        var usedRaw = 0L
        var index = history.size
        while (index > 0) {
            val candidateRaw = HeuristicTokenEstimator.estimateMessage(history[index - 1])
            if (HeuristicTokenEstimator.withSafetyMargin(fixedRaw + usedRaw + candidateRaw) > budget) break
            usedRaw += candidateRaw
            index--
        }

        val lastUserIndex = history.indexOfLast { it.isUser }
        // 全是 assistant 的极端情况：退回保留最后一条，至少不会发出空历史。
        if (lastUserIndex < 0) return (history.size - 1).coerceAtLeast(0)
        if (index >= lastUserIndex) return lastUserIndex

        var start = index
        while (start < history.size && !history[start].isUser) {
            start++
        }
        return if (start > lastUserIndex) lastUserIndex else start
    }

    /**
     * 连最近一个用户回合加上固定开销都装不下时，本地就要失败。
     *
     * 让请求发出去只会换来一个 400，用户看到的是「服务器错误」；在这里失败才能说清楚
     * 是 system prompt / 角色卡 / 工具定义太长，还是这一条消息本身太长。
     */
    private fun overflowErrorOrNull(estimated: Long, budget: Long, input: ChatContextInput): String? {
        if (estimated <= budget) return null
        val overBy = estimated - budget
        return buildString {
            append("本轮请求预计需要约 ")
            append(estimated)
            append(" tokens，超出当前可用输入预算 ")
            append(budget)
            append(" tokens 约 ")
            append(overBy)
            append(" tokens。")
            append("即使裁掉全部可裁历史也放不下，请缩短系统提示词、角色卡、世界观或本条消息")
            if (input.spec.isFallback) {
                append("，或在设置中为模型「")
                append(input.spec.modelId)
                append("」填写真实的上下文长度")
            }
            if (input.spec.isCostCapped) {
                append("，或调高/关闭成本上限")
            }
            append("。")
        }
    }

    // ---------- 压缩 ----------

    /**
     * 摘要请求自己也要发 `messagesToSummarize` + 旧摘要，同样受模型窗口约束。
     *
     * 因此这里从**最老一端**收缩，保证摘要请求本身不超限；宁可这一轮少压几条，
     * 也不能为了压缩而发一个必然失败的请求。
     */
    private fun capBySummaryRequestBudget(
        uncovered: List<PromptMessage>,
        input: ChatContextInput,
        alignedCutIndex: Int,
    ): Int {
        if (alignedCutIndex <= 0) return alignedCutIndex

        val summaryBudget = (input.spec.effectiveContextTokens - SUMMARY_OUTPUT_RESERVE_TOKENS.toLong())
            .coerceAtLeast(ModelContextSpec.MIN_INPUT_BUDGET_TOKENS)
        val previousSummaryRaw = HeuristicTokenEstimator.estimateText(input.summaryState.summary)

        var cut = alignedCutIndex
        var raw = uncovered.take(cut).sumOf { HeuristicTokenEstimator.estimateMessage(it) }
        while (cut > 0 && !fitsSummaryRequest(previousSummaryRaw, raw, summaryBudget)) {
            // 整回合地退：退到上一个 user 边界，保证剩余窗口仍以 user 开头。
            do {
                cut--
                raw -= HeuristicTokenEstimator.estimateMessage(uncovered[cut])
            } while (cut > 0 && !uncovered[cut].isUser)
        }
        return cut
    }

    private fun fitsSummaryRequest(previousSummaryRaw: Long, raw: Long, summaryBudget: Long): Boolean =
        HeuristicTokenEstimator.withSafetyMargin(
            previousSummaryRaw + raw + SUMMARY_PROMPT_OVERHEAD_TOKENS,
        ) <= summaryBudget

    // ---------- 组装 ----------

    private fun buildPromptContext(input: ChatContextInput, history: List<PromptMessage>): PromptContext =
        PromptContext(
            settings = input.settings,
            rpSettings = input.rpSettings,
            roleCard = input.roleCard,
            worldSetting = input.worldSetting,
            writingStyle = input.writingStyle,
            statusPanel = input.statusPanel,
            history = history,
            protagonistProfile = input.protagonistProfile,
            supportingCharacters = input.supportingCharacters,
            settingsToolsAvailable = input.toolDefinitions.isNotEmpty(),
        )

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

    private val PromptMessage.isUser: Boolean
        get() = role.equals("user", ignoreCase = true)

    private fun Long.onlyIf(condition: Boolean): Long = if (condition) this else 0L

    companion object {
        /** 无论如何都要逐字保留的最近消息条数，保证最近几轮永远是原文。 */
        const val MIN_MESSAGES_TO_KEEP_VERBATIM = 4

        /**
         * 摘要请求为输出预留的 token。
         *
         * 必须与 `DeepSeekRequestFactory.CONTEXT_SUMMARY_MAX_TOKENS` 及其 OpenAI 兼容对应项一致，
         * 两处请求工厂直接引用本常量，避免各写一份后悄悄漂移。
         */
        const val SUMMARY_OUTPUT_RESERVE_TOKENS = 2_000

        /** 摘要提示词本身（指令段、分隔符、输出要求）的固定开销。 */
        const val SUMMARY_PROMPT_OVERHEAD_TOKENS = 400L
    }
}
