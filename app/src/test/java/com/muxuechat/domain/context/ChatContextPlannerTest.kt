package com.muxuechat.domain.context

import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.ConversationRpSettings
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.SupportingCharacter
import com.muxuechat.domain.prompt.PromptMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContextPlannerTest {
    private val planner = ChatContextPlanner()

    private fun user(content: String) = PromptMessage(role = "user", content = content)
    private fun assistant(content: String) = PromptMessage(role = "assistant", content = content)

    /** 交替的 user/assistant 历史，每条 [charsEach] 个中文字符（≈ 每字符 1 token）。 */
    private fun history(turns: Int, charsEach: Int): List<PromptMessage> =
        (0 until turns).flatMap { index ->
            listOf(
                user("问$index".padEnd(charsEach, '问')),
                assistant("答$index".padEnd(charsEach, '答')),
            )
        }

    private fun spec(
        window: Long = ModelContextCatalog.DEEPSEEK_V4_CONTEXT_TOKENS,
        maxOutput: Long = 4_096L,
        costCap: Long? = null,
        source: ContextCapacitySource = ContextCapacitySource.BUILT_IN,
    ) = ModelContextSpec(
        providerKey = ChatProviderId.DEEPSEEK.storageValue,
        providerLabel = "DeepSeek",
        modelId = "deepseek-v4-flash",
        contextWindowTokens = window,
        source = source,
        maxOutputTokens = maxOutput,
        costCapTokens = costCap,
    )

    private fun input(
        history: List<PromptMessage>,
        settings: ChatSettings = ChatSettings(),
        rpSettings: ConversationRpSettings? = null,
        roleCard: RoleCard? = null,
        summaryState: ContextSummaryState = ContextSummaryState.EMPTY,
        toolDefinitions: List<ContextToolDefinition> = emptyList(),
        toolExchange: List<PromptMessage> = emptyList(),
        spec: ModelContextSpec = spec(),
        policy: ContextCompressionPolicy = ContextCompressionPolicy(),
    ) = ChatContextInput(
        protocol = ChatProviderId.DEEPSEEK,
        settings = settings,
        rpSettings = rpSettings,
        roleCard = roleCard,
        worldSetting = null,
        writingStyle = null,
        statusPanel = null,
        history = history,
        summaryState = summaryState,
        toolDefinitions = toolDefinitions,
        toolExchange = toolExchange,
        spec = spec,
        policy = policy,
    )

    private fun tool(name: String, schemaChars: Int = 400) = ContextToolDefinition(
        name = name,
        description = "创建一份设定草稿",
        parametersJson = "x".repeat(schemaChars),
    )

    // ---------- 预算口径 ----------

    /**
     * 旧实现把 40,000 字符当成硬上限。DeepSeek 的真实窗口是它的几十倍，
     * 6 万字符的历史必须一条不裁地全部带上。
     */
    @Test
    fun legacyCharacterBudget_isNoLongerALimit() {
        val messages = history(turns = 30, charsEach = 1_000)

        val plan = planner.plan(input(messages))

        assertEquals(0, plan.hardTrimmedMessageCount)
        assertEquals(messages.size, plan.messagesOf(ContextSectionKind.VERBATIM_HISTORY))
        assertTrue(plan.estimatedInputTokens > 40_000L)
        assertFalse(plan.hasUnrecoverableLoss)
    }

    @Test
    fun inputBudget_comesFromSpec() {
        val plan = planner.plan(input(history(turns = 2, charsEach = 10)))

        assertEquals(945_904L, plan.inputBudgetTokens)
        assertNull(plan.overflowError)
    }

    // ---------- 分项覆盖 ----------

    /** system prompt 以前完全不进账；现在必须出现在分项里并抬高总量。 */
    @Test
    fun systemPrompt_isCounted() {
        val messages = history(turns = 1, charsEach = 10)
        val bare = planner.plan(input(messages))
        val withPrompt = planner.plan(
            input(messages, settings = ChatSettings(systemPrompt = "系".repeat(500))),
        )

        assertEquals(0L, bare.tokensOf(ContextSectionKind.SYSTEM_PROMPT))
        assertTrue(withPrompt.tokensOf(ContextSectionKind.SYSTEM_PROMPT) > 400L)
        assertTrue(withPrompt.estimatedInputTokens > bare.estimatedInputTokens)
    }

    /** RP 规则 + 角色卡是 RP 模式下最长的一段，旧实现同样完全没算。 */
    @Test
    fun rpSettingsAndRoleCard_areCounted() {
        val messages = history(turns = 1, charsEach = 10)
        val rp = ConversationRpSettings(
            conversationId = "c",
            isRpEnabled = true,
            roleCardId = "r",
            updatedAtMillis = 0L,
        )
        val roleCard = RoleCard(
            id = "r",
            name = "角色",
            description = "描".repeat(300),
            persona = "设".repeat(300),
            speakingStyle = "",
            userIdentity = "",
                        boundaries = "",
            createdAtMillis = 0L,
            updatedAtMillis = 0L,
        )

        val plan = planner.plan(input(messages, rpSettings = rp, roleCard = roleCard))

        assertTrue(plan.tokensOf(ContextSectionKind.RP_SETTINGS) > 600L)
    }

    /** 配角常驻 prompt（§6.2）：它们计入 RP 固定成本，否则一个超长配角能让估算与实际请求脱节。 */
    @Test
    fun supportingCharacters_areCountedAsRpSettings() {
        val messages = history(turns = 1, charsEach = 10)
        val rp = ConversationRpSettings(conversationId = "c", isRpEnabled = true, updatedAtMillis = 0L)
        val base = input(messages, rpSettings = rp)
        val withSupporting = base.copy(
            supportingCharacters = listOf(
                SupportingCharacter(
                    id = "s",
                    name = "老周",
                    description = "配".repeat(500),
                    createdAtMillis = 0L,
                    updatedAtMillis = 0L,
                ),
            ),
        )

        val delta = planner.plan(withSupporting).tokensOf(ContextSectionKind.RP_SETTINGS) -
            planner.plan(base).tokensOf(ContextSectionKind.RP_SETTINGS)

        assertTrue(delta > 400L)
    }

    /** 工具 schema 也是发出去的字节。 */
    @Test
    fun toolDefinitions_areCounted() {
        val messages = history(turns = 1, charsEach = 10)
        val bare = planner.plan(input(messages))
        val withTools = planner.plan(input(messages, toolDefinitions = listOf(tool("a"), tool("b"))))

        assertEquals(0L, bare.tokensOf(ContextSectionKind.TOOL_DEFINITIONS))
        assertEquals(2, withTools.messagesOf(ContextSectionKind.TOOL_DEFINITIONS))
        assertTrue(withTools.tokensOf(ContextSectionKind.TOOL_DEFINITIONS) > 200L)
        assertTrue(withTools.estimatedInputTokens > bare.estimatedInputTokens)
    }

    /** 摘要本身也占预算，否则摘要越长能保留的原文越少却无人察觉。 */
    @Test
    fun summary_isCounted() {
        val messages = history(turns = 6, charsEach = 100)
        val plan = planner.plan(
            input(
                messages,
                summaryState = ContextSummaryState(summary = "摘".repeat(800), coveredMessageCount = 4),
            ),
        )

        assertTrue(plan.tokensOf(ContextSectionKind.SUMMARY) > 800L)
        assertEquals(4, plan.summarizedMessageCount)
        // 被摘要覆盖的部分不再逐字发送。
        assertEquals(messages.size - 4, plan.messagesOf(ContextSectionKind.VERBATIM_HISTORY))
    }

    /** 工具轮的临时消息不会进 requestMessages，但必须计入预算。 */
    @Test
    fun toolExchange_isCountedButNotInRequestMessages() {
        val messages = history(turns = 2, charsEach = 50)
        val exchange = listOf(
            assistant("调用工具 create_role_card"),
            PromptMessage(role = "tool", content = "已创建角色卡草稿".repeat(50)),
        )

        val first = planner.plan(input(messages))
        val second = planner.plan(input(messages, toolExchange = exchange))

        assertEquals(first.requestMessages, second.requestMessages)
        assertEquals(2, second.messagesOf(ContextSectionKind.TOOL_EXCHANGE))
        assertTrue(second.estimatedInputTokens > first.estimatedInputTokens)
    }

    // ---------- 请求消息一致性 ----------

    /** 摘要必须作为独立 system 消息排在逐字历史之前。 */
    @Test
    fun requestMessages_putSummaryBeforeVerbatimHistory() {
        val messages = history(turns = 6, charsEach = 20)
        val plan = planner.plan(
            input(messages, summaryState = ContextSummaryState(summary = "前情", coveredMessageCount = 4)),
        )

        val first = plan.requestMessages.first()
        assertEquals("system", first.role)
        assertTrue(first.content.contains("更早剧情摘要"))
        assertEquals(messages.size - 4, plan.requestMessages.size - 1)
    }

    /** 分项之和与总计只允许因为各自向下取整而相差几个 token。 */
    @Test
    fun sections_roughlySumToEstimatedTotal() {
        val plan = planner.plan(
            input(
                history(turns = 5, charsEach = 200),
                settings = ChatSettings(systemPrompt = "系统提示".repeat(50)),
                toolDefinitions = listOf(tool("a")),
            ),
        )

        val sum = plan.sections.sumOf { it.tokens }
        assertTrue(
            "sum=$sum estimated=${plan.estimatedInputTokens}",
            kotlin.math.abs(plan.estimatedInputTokens - sum) <= plan.sections.size + 5,
        )
    }

    // ---------- 硬裁剪 ----------

    /** 保留窗口必须以 user 开头，否则模型会看到一条没有对应输入的回复。 */
    @Test
    fun hardTrim_keepsWindowStartingWithUser() {
        val messages = history(turns = 40, charsEach = 400)

        val plan = planner.plan(input(messages, spec = spec(window = 20_000L)))

        assertTrue(plan.hardTrimmedMessageCount > 0)
        assertEquals("user", plan.requestMessages.first { it.role != "system" }.role)
        assertTrue(plan.hasUnrecoverableLoss)
    }

    @Test
    fun hardTrim_respectsBudget() {
        val messages = history(turns = 40, charsEach = 400)
        val modelSpec = spec(window = 20_000L)

        val plan = planner.plan(input(messages, spec = modelSpec))

        assertTrue(
            "estimated=${plan.estimatedInputTokens} budget=${plan.inputBudgetTokens}",
            plan.estimatedInputTokens <= plan.inputBudgetTokens,
        )
    }

    /** 预算极小时也要留下最近一个完整用户回合，否则这一轮等于什么都没问。 */
    @Test
    fun hardTrim_keepsLastUserTurnEvenWhenBudgetIsTiny() {
        val messages = history(turns = 20, charsEach = 300)

        val plan = planner.plan(input(messages, spec = spec(window = 2_000L)))

        val nonSystem = plan.requestMessages.filter { it.role != "system" }
        assertTrue(nonSystem.isNotEmpty())
        assertEquals("user", nonSystem.first().role)
    }

    /** 不可裁剪的内容本身超限时必须本地失败，而不是换一个服务端 400。 */
    @Test
    fun overflow_isReportedWhenIncompressibleContentExceedsBudget() {
        val messages = listOf(user("巨".repeat(50_000)))

        val plan = planner.plan(input(messages, spec = spec(window = 8_000L)))

        assertNotNull(plan.overflowError)
        assertTrue(plan.overflowError!!.contains("超出当前可用输入预算"))
    }

    /** 回退窗口下的报错要顺带告诉用户「去填写上下文长度」。 */
    @Test
    fun overflowMessage_mentionsFallbackConfiguration() {
        val messages = listOf(user("巨".repeat(50_000)))

        val plan = planner.plan(
            input(messages, spec = spec(window = 8_000L, source = ContextCapacitySource.FALLBACK)),
        )

        assertTrue(plan.overflowError!!.contains("上下文长度"))
    }

    /** 成本上限生效时也要在报错里提一句，否则用户找不到是哪个开关卡住的。 */
    @Test
    fun overflowMessage_mentionsCostCap() {
        val messages = listOf(user("巨".repeat(50_000)))

        val plan = planner.plan(input(messages, spec = spec(costCap = 4_000L)))

        assertTrue(plan.overflowError!!.contains("成本上限"))
    }

    // ---------- 自动压缩 ----------

    /** 触发线是「输入预算的 75%」，不再是一个写死的字符数。 */
    @Test
    fun autoCompact_triggersAtSeventyFivePercentOfInputBudget() {
        val modelSpec = spec(window = 20_000L)
        val budget = modelSpec.inputBudgetTokens
        val perMessage = (budget * 8 / 10 / 12).toInt()

        val plan = planner.plan(input(history(turns = 6, charsEach = perMessage), spec = modelSpec))

        assertTrue(
            "estimated=${plan.estimatedInputTokens} trigger=${plan.policy.autoTriggerTokens(budget)}",
            planner.shouldAutoCompact(plan),
        )
    }

    @Test
    fun autoCompact_doesNotTriggerWellBelowThreshold() {
        val plan = planner.plan(input(history(turns = 6, charsEach = 100)))

        assertFalse(planner.shouldAutoCompact(plan))
    }

    @Test
    fun autoCompact_neverTriggersWhenModeIsNotAuto() {
        val modelSpec = spec(window = 20_000L)
        val perMessage = (modelSpec.inputBudgetTokens * 8 / 10 / 12).toInt()

        listOf(ContextCompressionMode.MANUAL, ContextCompressionMode.OFF).forEach { mode ->
            val plan = planner.plan(
                input(
                    history(turns = 6, charsEach = perMessage),
                    spec = modelSpec,
                    policy = ContextCompressionPolicy(mode = mode),
                ),
            )
            assertFalse(mode.name, planner.shouldAutoCompact(plan))
        }
    }

    /** 关闭压缩时忽略已有摘要，但硬裁剪仍然生效。 */
    @Test
    fun compressionOff_ignoresExistingSummary() {
        val messages = history(turns = 6, charsEach = 50)

        val plan = planner.plan(
            input(
                messages,
                summaryState = ContextSummaryState(summary = "前情", coveredMessageCount = 4),
                policy = ContextCompressionPolicy(mode = ContextCompressionMode.OFF),
            ),
        )

        assertEquals(0, plan.summarizedMessageCount)
        assertEquals(0L, plan.tokensOf(ContextSectionKind.SUMMARY))
        assertEquals(messages.size, plan.messagesOf(ContextSectionKind.VERBATIM_HISTORY))
    }

    // ---------- 压缩规划 ----------

    @Test
    fun planCompaction_returnsNullForShortHistory() {
        assertNull(planner.planCompaction(input(history(turns = 2, charsEach = 50))))
    }

    @Test
    fun planCompaction_returnsNullWhenDisabled() {
        val modelSpec = spec(window = 20_000L)
        val perMessage = (modelSpec.inputBudgetTokens * 8 / 10 / 20).toInt()

        assertNull(
            planner.planCompaction(
                input(
                    history(turns = 10, charsEach = perMessage),
                    spec = modelSpec,
                    policy = ContextCompressionPolicy(mode = ContextCompressionMode.OFF),
                ),
            ),
        )
    }

    /** 压缩后重新规划，占用必须真的降到目标线以下。 */
    @Test
    fun planCompaction_bringsUsageDownToTarget() {
        val modelSpec = spec(window = 30_000L)
        val perMessage = (modelSpec.inputBudgetTokens * 8 / 10 / 20).toInt()
        val messages = history(turns = 10, charsEach = perMessage)
        val policy = ContextCompressionPolicy()

        val compaction = planner.planCompaction(input(messages, spec = modelSpec, policy = policy))
        assertNotNull(compaction)

        // 摘要通常远短于原文；这里用一个保守的短摘要模拟压缩结果。
        val afterCompaction = planner.plan(
            input(
                messages,
                spec = modelSpec,
                policy = policy,
                summaryState = ContextSummaryState(
                    summary = "摘".repeat(300),
                    coveredMessageCount = compaction!!.newCoveredMessageCount,
                ),
            ),
        )

        assertTrue(
            "estimated=${afterCompaction.estimatedInputTokens} " +
                "target=${policy.targetTokens(modelSpec.inputBudgetTokens)}",
            afterCompaction.estimatedInputTokens < policy.autoTriggerTokens(modelSpec.inputBudgetTokens),
        )
    }

    /** 被摘要的前缀必须以完整回合结尾，剩下的窗口仍以 user 开头。 */
    @Test
    fun planCompaction_leavesWindowStartingWithUser() {
        val modelSpec = spec(window = 30_000L)
        val perMessage = (modelSpec.inputBudgetTokens * 8 / 10 / 20).toInt()
        val messages = history(turns = 10, charsEach = perMessage)

        val compaction = planner.planCompaction(input(messages, spec = modelSpec))!!

        assertEquals("user", messages[compaction.newCoveredMessageCount].role)
    }

    @Test
    fun planCompaction_carriesPreviousSummaryForward() {
        val modelSpec = spec(window = 30_000L)
        val perMessage = (modelSpec.inputBudgetTokens * 8 / 10 / 20).toInt()
        val messages = history(turns = 10, charsEach = perMessage)

        val compaction = planner.planCompaction(
            input(
                messages,
                spec = modelSpec,
                summaryState = ContextSummaryState(summary = "旧摘要", coveredMessageCount = 2),
            ),
        )!!

        assertEquals("旧摘要", compaction.previousSummary)
        assertTrue(compaction.newCoveredMessageCount > 2)
    }

    /**
     * 摘要请求自己也要发这些消息。宁可这一轮少压几条，也不能为了压缩去发一个必然超限的请求。
     */
    @Test
    fun planCompaction_neverExceedsTheSummaryRequestBudget() {
        val modelSpec = spec(window = 12_000L)
        val messages = history(turns = 20, charsEach = 1_500)

        val compaction = planner.planCompaction(input(messages, spec = modelSpec))
        // 这个用例一定有东西可压；允许 null 会让下面的断言形同虚设。
        assertNotNull(compaction)

        val summaryInputBudget = modelSpec.effectiveContextTokens -
            ChatContextPlanner.SUMMARY_OUTPUT_RESERVE_TOKENS
        val raw = compaction!!.messagesToSummarize.sumOf { HeuristicTokenEstimator.estimateMessage(it) } +
            ChatContextPlanner.SUMMARY_PROMPT_OVERHEAD_TOKENS
        assertTrue(
            "raw=$raw budget=$summaryInputBudget",
            HeuristicTokenEstimator.withSafetyMargin(raw) <= summaryInputBudget,
        )
    }

    /**
     * 发送前的历史以本轮 user 结尾，条数通常是奇数，此时 maxCut 恰好落在 assistant 上。
     * 旧实现向后对齐越过 maxCut 后直接返回 null，最需要压缩的时候反而什么都不做。
     */
    @Test
    fun planCompaction_oddHistoryBacksOffToPreviousUserBoundaryInsteadOfGivingUp() {
        // 成本上限把预算压得很小：只留最少的逐字消息也到不了目标线，切点会一路推到 maxCut。
        val modelSpec = spec(costCap = 5_000L)
        val messages = history(turns = 10, charsEach = 1_000) + user("新问题".padEnd(1_000, '问'))
        assertEquals(21, messages.size)

        val compaction = planner.planCompaction(input(messages, spec = modelSpec))

        assertNotNull(compaction)
        val covered = compaction!!.newCoveredMessageCount
        val maxCut = messages.size - ChatContextPlanner.MIN_MESSAGES_TO_KEEP_VERBATIM
        assertEquals(maxCut - 1, covered)
        assertEquals("user", messages[covered].role)
        assertTrue(messages.size - covered >= ChatContextPlanner.MIN_MESSAGES_TO_KEEP_VERBATIM)
    }

    // ---------- 摘要作废 ----------

    /** 编辑历史后摘要被清空，UI 要能解释「占用为什么突然变大」。 */
    @Test
    fun summaryInvalidated_isFlaggedWhenCoverageNoLongerFits() {
        val messages = history(turns = 2, charsEach = 20)

        val plan = planner.plan(
            input(messages, summaryState = ContextSummaryState(summary = "前情", coveredMessageCount = 99)),
        )

        assertTrue(plan.summaryInvalidated)
        assertEquals(0, plan.summarizedMessageCount)
    }
}
