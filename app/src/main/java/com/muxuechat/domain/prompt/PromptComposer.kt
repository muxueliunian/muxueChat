package com.muxuechat.domain.prompt

import com.muxuechat.domain.rp.RpEffectiveDefinitions
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.DeepSeekModels
import com.muxuechat.domain.model.ProtagonistProfile
import com.muxuechat.domain.model.ConversationRpSettings
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RpEntryOwner
import com.muxuechat.domain.model.RpMarkerMode
import com.muxuechat.domain.model.RpMetricDefinition
import com.muxuechat.domain.model.RpMetricSnapshot
import com.muxuechat.domain.model.RpNarrativePerspective
import com.muxuechat.domain.model.RpPlotSuggestionPerspective
import com.muxuechat.domain.model.RpStatusPanel
import com.muxuechat.domain.model.SupportingCharacter
import com.muxuechat.domain.model.ThinkingMode
import com.muxuechat.domain.model.WorldSetting
import com.muxuechat.domain.model.WritingStyleProfile
import com.muxuechat.domain.rp.RpStateBlockFormat

data class PromptMessage(
    val role: String,
    val content: String,
)

data class PromptContext(
    val settings: ChatSettings,
    val rpSettings: ConversationRpSettings?,
    val roleCard: RoleCard?,
    val worldSetting: WorldSetting?,
    val writingStyle: WritingStyleProfile? = null,
    val statusPanel: RpStatusPanel?,
    val history: List<PromptMessage>,
    /**
     * 会话绑定的主角档案（RP-2）。只在 RP 启用时由请求层填入。
     * 姓名、简介、文本词条实时跟随档案；数值定义走 [rpSettings] 的快照，不从这里读。
     */
    val protagonistProfile: ProtagonistProfile? = null,
    /**
     * 会话勾选的配角，按勾选顺序（RP-2e）。只在 RP 启用时由请求层填入。
     * 配角只进正文 prompt：不进状态块，不进元数据补写。
     */
    val supportingCharacters: List<SupportingCharacter> = emptyList(),
    /** 本次请求是否挂载了设定生成工具。挂载时才注入工具使用规则。 */
    val settingsToolsAvailable: Boolean = false,
)

/**
 * 一次组合的结果：最终消息列表 + 各段落原文。
 *
 * 段落原文是给上下文规划器做 token 分项统计用的。之前 UI 只能统计历史字符数，
 * system prompt、RP 规则、角色卡、世界观、文风、状态栏契约这些**最长的部分**完全不进账，
 * 于是「占用 12%」这种数字与真实请求毫无关系。
 *
 * 这里刻意返回原文而不是 token 数：分词口径属于 domain/context 的职责，
 * PromptComposer 只负责「发什么」，不负责「多大」。
 */
data class ComposedPrompt(
    val messages: List<PromptMessage>,
    /** 用户自己写的 system prompt，原样直送的那一段。 */
    val userSystemPrompt: String,
    /** 工具可用性说明（不含工具 JSON schema，schema 由请求层持有）。 */
    val toolInstructions: String,
    /** RP 规则 + 主角档案 + 角色卡 + 配角 + 世界观 + 文风 + 状态栏契约。 */
    val rpInstructions: String,
    /** 附加到最后一条 user 消息末尾的本轮易变状态。 */
    val volatileState: String,
    /** 附加到第一条 user 消息的 DeepSeek RP Marker；不适用时为空串。 */
    val deepSeekRpMarker: String,
)

data class RpMetadataPromptContext(
    val rpSettings: ConversationRpSettings?,
    val roleCard: RoleCard,
    val protagonistProfile: ProtagonistProfile? = null,
    val definitions: List<RpMetricDefinition>,
    val currentPanel: RpStatusPanel?,
    val lastUser: String,
    val assistantContent: String,
)

class PromptComposer {
    fun compose(context: PromptContext): List<PromptMessage> = buildList {
        val systemPrompt = buildSystemPrompt(context)
        if (systemPrompt.isNotBlank()) {
            add(PromptMessage(role = "system", content = systemPrompt))
        }
        addAll(context.history)
    }

    /**
     * 唯一的组合实现，同时产出最终消息与各段落原文。
     *
     * [protocol] 决定是否注入 DeepSeek 专属的 RP Marker——协议边界只有两条，
     * 不按供应商名称分叉。
     */
    fun composeStructured(context: PromptContext, protocol: ChatProviderId): ComposedPrompt {
        val parts = buildSystemPromptParts(context)
        val base = buildList {
            if (parts.joined.isNotBlank()) {
                add(PromptMessage(role = "system", content = parts.joined))
            }
            addAll(context.history)
        }

        val marker = when (protocol) {
            ChatProviderId.DEEPSEEK -> resolveDeepSeekRpMarker(context.settings, context.rpSettings)
            ChatProviderId.CUSTOM_OPENAI -> ""
        }
        val withMarker = if (marker.isBlank()) base else appendToFirstUser(base, marker)

        val volatileState = resolveVolatileState(context)
        val messages = if (volatileState.isBlank()) {
            withMarker
        } else {
            appendToLastUser(withMarker, volatileState)
        }

        return ComposedPrompt(
            messages = messages,
            userSystemPrompt = parts.userSystemPrompt,
            toolInstructions = parts.toolInstructions,
            rpInstructions = parts.rpInstructions,
            volatileState = volatileState,
            deepSeekRpMarker = marker,
        )
    }

    fun composeForDeepSeekRequest(context: PromptContext): List<PromptMessage> =
        composeStructured(context, ChatProviderId.DEEPSEEK).messages

    /**
     * 为自定义 OpenAI 兼容供应商的普通聊天请求组合消息。
     *
     * 上游服务可能把请求再转发给任意模型，但 App 的协议边界仍是标准聊天接口；
     * 因此不发送 DeepSeek 专属的 RP Marker。标准 OpenAI tools 则由调用方在已确认
     * 端点支持时挂载，并保留相应的使用说明。RP 的稳定系统提示词与本轮易变状态仍需
     * 保留，避免同一会话切换 Provider 后丢失正常对话/RP 语义。
     */
    fun composeForOpenAiCompatibleRequest(context: PromptContext): List<PromptMessage> =
        composeStructured(context, ChatProviderId.CUSTOM_OPENAI).messages

    /**
     * 把每轮都会变的「当前状态」附加到最后一条 user 消息末尾。
     *
     * 之前这段内容直接写在 system prompt 中部，导致 system prompt 每轮都不同，
     * 服务端的前缀缓存从很靠前的位置就失效——而这个 system prompt 恰恰是最长的部分
     * （RP 规则 + 角色卡七段 + 世界观 + 状态条定义 + 输出契约）。
     *
     * 挪到最后一条 user 消息之后，前面所有内容逐轮保持一致，缓存才有机会命中。
     * 选择附加到 user 消息而不是追加一条末尾 system 消息，是因为前者在
     * OpenAI 兼容实现里行为最稳定，语义上也仍然是"本轮的即时状态"。
     */
    internal fun appendVolatileStateIfNeeded(
        messages: List<PromptMessage>,
        context: PromptContext,
    ): List<PromptMessage> {
        val volatileState = resolveVolatileState(context)
        if (volatileState.isBlank()) return messages
        return appendToLastUser(messages, volatileState)
    }

    /** RP 关闭或没有状态面板时返回空串。规划器据此把这段计入 token 估算。 */
    internal fun resolveVolatileState(context: PromptContext): String {
        if (context.rpSettings?.isRpEnabled != true) return ""
        return buildVolatileStateSection(context)
    }

    private fun appendToLastUser(messages: List<PromptMessage>, section: String): List<PromptMessage> {
        val lastUserIndex = messages.indexOfLast { it.role.equals("user", ignoreCase = true) }
        if (lastUserIndex < 0) return messages

        return messages.mapIndexed { index, message ->
            if (index != lastUserIndex) {
                message
            } else {
                message.copy(content = message.content.trimEnd() + "\n\n" + section)
            }
        }
    }

    private fun appendToFirstUser(messages: List<PromptMessage>, section: String): List<PromptMessage> {
        val firstUserIndex = messages.indexOfFirst { it.role.equals("user", ignoreCase = true) }
        if (firstUserIndex < 0) return messages

        return messages.mapIndexed { index, message ->
            if (index != firstUserIndex) {
                message
            } else {
                val separator = if (message.content.endsWith("\n")) "\n" else "\n\n"
                message.copy(content = message.content + separator + section)
            }
        }
    }

    private fun buildVolatileStateSection(context: PromptContext): String {
        val panel = context.statusPanel ?: return ""
        return buildString {
            appendLine("### 当前状态（本轮即时数据，非玩家输入）")
            buildCurrentMetricLines(context).forEach { line -> appendLine(line) }
            // 历史面板里可能存着旧示例的占位文字，不能再喂回给模型强化它。
            val relationship = RpStateBlockFormat.meaningfulText(panel.relationship)
            val scene = RpStateBlockFormat.meaningfulText(panel.scene)
            if (relationship.isNotBlank()) {
                appendLine("- 当前关系: $relationship")
            }
            if (scene.isNotBlank()) {
                appendLine("- 当前场景: $scene")
            }
        }.trim()
    }

    fun composeRpMetadata(context: RpMetadataPromptContext): List<PromptMessage> =
        listOf(
            PromptMessage(
                role = "system",
                content = buildRpMetadataSystemPrompt(
                    rpSettings = context.rpSettings,
                    roleCard = context.roleCard,
                ),
            ),
            PromptMessage(
                role = "user",
                content = buildRpMetadataUserPrompt(context),
            ),
        )

    internal fun shouldInjectDeepSeekRpMarker(
        modelApiName: String,
        thinkingModeEnabled: Boolean,
        isRpEnabled: Boolean,
        rpMarkerMode: RpMarkerMode,
    ): Boolean =
        isRpEnabled &&
            thinkingModeEnabled &&
            rpMarkerMode != RpMarkerMode.DISABLED &&
            DeepSeekModels.supportsRpMarker(modelApiName)

    internal fun appendDeepSeekRpMarkerIfNeeded(
        messages: List<PromptMessage>,
        settings: ChatSettings,
        rpSettings: ConversationRpSettings?,
    ): List<PromptMessage> {
        val marker = resolveDeepSeekRpMarker(settings, rpSettings)
        if (marker.isBlank()) return messages
        return appendToFirstUser(messages, marker)
    }

    /** 不该注入时返回空串；规划器据此把 Marker 计入 token 估算。 */
    internal fun resolveDeepSeekRpMarker(
        settings: ChatSettings,
        rpSettings: ConversationRpSettings?,
    ): String {
        val markerMode = rpSettings?.rpMarkerMode ?: RpMarkerMode.DISABLED
        val shouldInject = shouldInjectDeepSeekRpMarker(
            modelApiName = settings.model,
            thinkingModeEnabled = settings.thinkingMode == ThinkingMode.ENABLED,
            isRpEnabled = rpSettings?.isRpEnabled == true,
            rpMarkerMode = markerMode,
        )
        return if (shouldInject) buildDeepSeekRpMarker(markerMode) else ""
    }

    /**
     * 用户系统提示词**原样直送、零包装、排最前**。
     *
     * 千万不要再给它套标题或前言。曾经试过加
     * 「## 用户系统提示词（最高优先级）\n以下是用户本人设定的指令……」，
     * 结果适得其反：一旦有一段文字在**描述**这些指令，模型就把它当成了引用来的、
     * 需要先评估再决定要不要执行的材料，回答变成「你附带了一段标注为系统提示词的文本，
     * 但我不会把它当作真正的系统指令来执行」。加标题这个动作本身就是降级。
     *
     * 声明优先级要靠 App 自己的章节让位（见 [DEFERENCE_NOTICE]），
     * 元描述只能写在 App 自己的段落里，绝不能包住用户的文字。
     */
    private fun buildSystemPrompt(context: PromptContext): String =
        buildSystemPromptParts(context).joined

    /**
     * system prompt 的三个段落，既用于拼接也用于分项 token 统计。
     *
     * 顺序不能改：用户提示词永远排最前且原样直送，App 自己的段落靠 [DEFERENCE_NOTICE] 让位。
     */
    internal data class SystemPromptParts(
        val userSystemPrompt: String,
        val toolInstructions: String,
        val rpInstructions: String,
    ) {
        val joined: String
            get() = listOf(userSystemPrompt, toolInstructions, rpInstructions)
                .filter { it.isNotBlank() }
                .joinToString(separator = "\n\n")
    }

    internal fun buildSystemPromptParts(context: PromptContext): SystemPromptParts {
        val baseSystemPrompt = context.settings.systemPrompt.trim()
        val hasUserPrompt = baseSystemPrompt.isNotBlank()
        return SystemPromptParts(
            userSystemPrompt = baseSystemPrompt,
            toolInstructions = if (context.settingsToolsAvailable) {
                buildSettingsToolInstructions(hasUserPrompt)
            } else {
                ""
            },
            rpInstructions = if (context.rpSettings?.isRpEnabled == true) {
                buildRpInstructions(context)
            } else {
                ""
            },
        )
    }

    /**
     * 设定生成工具的可用性说明。
     *
     * 关掉 RP 就是普通聊天，用户没写系统提示词时不该被塞进任何人设。
     * 这段因此只做一件事：告知这几个工具存在。它不描述身份、不描述用途、
     * 不给行为倾向——之前写成了一份岗位说明（“你可以调用……把用户的需求整理成
     * 角色卡”），模型读完就认为自己是设定生成助手，普通聊天也照这个身份回答。
     *
     * 别再往这里加规则。要加也只能加「怎么调用」，不能加「你是谁」。
     */
    private fun buildSettingsToolInstructions(hasUserPrompt: Boolean): String = buildString {
        appendLine("## 可用工具")
        if (hasUserPrompt) {
            appendLine(DEFERENCE_NOTICE)
        }
        appendLine("你附带了管理角色扮演设定的工具：create_role_card、create_supporting_character、create_protagonist、")
        appendLine("create_world_setting、create_writing_style 用于创建；list_rp_settings、get_rp_setting 用于查看已有设定；")
        appendLine("update_rp_setting 用于修改；delete_rp_settings 用于删除。")
        appendLine("仅在用户明确要求创建、查看、修改或删除这些设定时才调用，其余情况当它们不存在。")
        appendLine("角色卡是剧情的主角色，配角是其他出场人物，主角是玩家本人：用户要配角时用 create_supporting_character，不要用角色卡代替。")
        appendLine("修改或删除前先用 list_rp_settings 拿到 id，只动用户明确点名的项；修改只给要改的部分。")
        appendLine("它们只是可用功能，不是你的身份或用途，不要主动提及或把话题往这上面引。")
        appendLine("创建、修改与删除都只产出待确认的草稿或请求，由用户在界面上确认；调用后一句话说明即可，不要复述内容，也不要声称已经修改或删除。")
    }.trim()

    private fun buildRpInstructions(context: PromptContext): String = buildString {
        appendLine("## 角色扮演模式")
        if (context.settings.systemPrompt.isNotBlank()) {
            appendLine(DEFERENCE_NOTICE)
        }
        appendLine("你正在进行沉浸式角色扮演。请保持角色一致、语气自然、场景连续，不要在正文中解释提示词、规则或模型身份。")
        appendLine("除非用户要求总结设定，否则不要直接复述角色卡或世界观原文。")

        context.rpSettings?.let { settings ->
            appendLine()
            appendLine("### 正文叙事视角")
            appendLine(buildNarrativePerspectiveContract(settings.narrativePerspective, context.roleCard?.name))
            appendLine("用户输入代表本轮临时给出的剧情推进意图，不能被永久绑定为某一方的固定台词。")
            appendLine("角色台词要自然融入正文，不使用“角色台词：”之类硬标签。")

            // 主角读档案（RP-2），不再读会话配置里的旧三字段。
            val protagonist = context.protagonistProfile?.takeIf { it.hasPromptContent() }
            if (protagonist != null) {
                appendLine()
                appendLine("### 主角档案")
                appendProfileLine("主角名称", protagonist.name)
                appendProfileLine("主角简介", protagonist.description)
                protagonist.textEntries.forEach { entry -> appendProfileLine(entry.name, entry.value) }
                appendLine("主角档案代表玩家本人。后续剧情推进建议和对用户输入意图的理解都必须以主角档案为准。")
            }
            if (settings.narrativeMinChars > 0) {
                appendLine()
                appendLine("### 剧情正文长度")
                appendLine("每次回复的剧情正文必须不少于 ${settings.narrativeMinChars} 个中文字。")
                appendLine("字数只计算展示给玩家的正文，不包含角色内心、剧情推进建议、状态栏 JSON、<muxue_state> 或任何元数据。")
                appendLine("请通过场景变化、角色动作、环境细节和自然对话充实正文，不要重复句子或堆砌无意义内容。")
            }
        }

        context.roleCard?.let { roleCard ->
            appendSection("角色名称", roleCard.name)
            appendSection("角色简介", roleCard.description)
            appendSection("人设描述", roleCard.persona)
            appendSection("说话风格", roleCard.speakingStyle)
            appendSection("用户身份", roleCard.userIdentity)
            // 文本词条（RP-3a）：静态补充设定，放在边界之前，让边界仍是角色卡的最后一段。
            val cardEntries = roleCard.textEntries.filter { it.name.isNotBlank() && it.value.isNotBlank() }
            if (cardEntries.isNotEmpty()) {
                appendLine()
                appendLine("### 角色补充设定")
                cardEntries.forEach { entry -> appendProfileLine(entry.name, entry.value) }
            }
            appendSection("禁止事项与边界", roleCard.boundaries)
        }

        if (context.protagonistProfile?.hasPromptContent() == true && context.roleCard?.userIdentity.isNullOrBlank().not()) {
            appendLine()
            appendLine("### 视角规则")
            appendLine("角色卡中的“用户身份”只作为该角色如何称呼或理解玩家的参考；主角档案优先决定玩家本人是谁。")
        }

        appendSupportingCharacters(context.supportingCharacters)

        context.worldSetting?.let { world ->
            appendSection("世界观：${world.title}", world.content)
        }

        context.writingStyle?.let { style ->
            appendSection("文风设定：${style.name}", style.content)
            appendLine("文风只约束正文如何书写，不改变剧情走向、角色设定或状态栏契约。")
            appendLine("文风要求与上述 RP 基础规则冲突时，以 RP 基础规则为准。")
        }

        // 注意：「当前状态」不在这里输出。它每轮都变，写进 system prompt 会让前缀缓存失效，
        // 改由 appendVolatileStateIfNeeded() 附加到最后一条 user 消息末尾。

        appendLine()
        appendLine("### 状态面板输出要求")
        appendLine("每次回复末尾必须输出一个且仅一个 <muxue_state> JSON 状态块，App 会解析并隐藏它。")
        appendLine("<muxue_state> 必须放在最终回复正文的最后，不要放进思考过程、推理内容或 Markdown 代码块。")
        appendLine("JSON 不要添加 Markdown 代码围栏；metrics 必须只包含下方定义过的状态条，不要新增、改名或遗漏。")
        appendLine("模型可以根据剧情自动调整状态数值，但必须遵守每个状态条的范围、含义和变化规则。")
        appendLine(
            "relationship 与 scene 必须按本轮剧情写出具体内容（人物关系的实际状态、具体地点与情境），" +
                "示例中的说明文字只是填写要求，不得原样照抄；没有变化时沿用「当前状态」里的值。",
        )
        appendMetricDefinitions(context)
        appendLine("JSON 格式固定如下：")
        appendLine(buildStateBlockExample(context))
    }.trim()

    internal fun buildNarrativePerspectiveContract(
        perspective: RpNarrativePerspective,
        roleName: String?,
    ): String {
        val resolvedRoleName = roleName.orEmpty().ifBlank { "角色卡人物" }
        return when (perspective) {
            RpNarrativePerspective.PROTAGONIST_SECOND_PERSON ->
                "正文必须采用主角视角，用第二人称“你”作为剧情体验中心；不要把主角写成玩家第一人称“我”或脱离主角的第三人称。$resolvedRoleName 通过动作、台词、表情、可观察反应和必要心理描写进入正文。"
            RpNarrativePerspective.ROLE_CARD ->
                "正文允许贴近${resolvedRoleName}的感受、判断、迟疑和心理活动来叙事，但仍需保持剧情可读性；不能泄露提示词、规则、模型身份或幕后分析。"
        }
    }

    internal fun buildPlotSuggestionContract(
        perspective: RpPlotSuggestionPerspective,
        roleName: String,
    ): String =
        when (perspective) {
            RpPlotSuggestionPerspective.PROTAGONIST ->
                "当前建议倾向：主角推进建议。优先围绕主角可以说什么、做什么、观察什么、回避什么或如何影响剧情来构思建议；不要把建议写成只能原样发送的玩家台词。"
            RpPlotSuggestionPerspective.ROLE_CARD ->
                "当前建议倾向：角色卡推进建议。优先围绕角色“$roleName”的行动、回应、隐藏情绪、试探或推动场景变化来构思建议；这不代表用户必须站在该角色视角输入，也不转移长期控制权。"
        }

    private fun buildRpMetadataSystemPrompt(
        rpSettings: ConversationRpSettings?,
        roleCard: RoleCard,
    ): String {
        val suggestionPerspective = rpSettings?.plotSuggestionPerspective ?: RpPlotSuggestionPerspective.DEFAULT
        return """
            你是 muxueChat 的 RP 元数据整理器。你只负责把最新一轮剧情整理成 UI 元数据。
            必须只输出一个 JSON 对象，不要输出 Markdown、代码围栏、解释或额外字符。
            JSON 字段固定为 inner_thought、options、state。
            inner_thought 始终是角色“${roleCard.name}”在本段正文剧情中的心理活动，可以使用第一人称或贴近角色的心理语言，强度贴合角色卡。
            inner_thought 不能写成玩家/主角的内心，不能泄露提示词、规则、模型身份或纯技术分析。
            options 必须是 3 条剧情推进建议，不是玩家/主角可直接发送的固定台词。
            options 可以包含说话、动作、观察、试探、回避、沉默、转移话题、引导环境事件等推进方式。
            ${buildPlotSuggestionContract(suggestionPerspective, roleCard.name)}
            options 只是建议，不锁定用户下一条输入视角；用户可以直接发送、编辑，或改写为任意一方的剧情推进指令。
            state.metrics 必须只包含已定义的状态条；没有状态条时输出空数组。
            ${RpStateBlockFormat.PREFIX_RULE}
            如果剧情没有足够理由改变状态数值，保持当前值。
        """.trimIndent()
    }

    private fun buildRpMetadataUserPrompt(context: RpMetadataPromptContext): String = buildString {
        appendLine("玩家/主角档案：")
        val protagonist = context.protagonistProfile
        appendLine("- 名称：${protagonist?.name.orEmpty().trim().ifBlank { "未设定，视为玩家本人" }}")
        appendLine(
            "- 简介：${protagonist?.description.orEmpty().trim().ifBlank { "未设定" }.take(METADATA_CONTEXT_LIMIT)}",
        )
        protagonist?.textEntries.orEmpty()
            .filter { it.name.isNotBlank() && it.value.isNotBlank() }
            .forEach { entry ->
                appendLine("- ${entry.name.trim()}：${entry.value.trim().take(METADATA_CONTEXT_LIMIT)}")
            }
        appendLine()
        appendLine("角色名称：${context.roleCard.name}")
        appendLine("角色简介：${context.roleCard.description.take(METADATA_CONTEXT_LIMIT)}")
        appendLine("角色人设：${context.roleCard.persona.take(METADATA_CONTEXT_LIMIT)}")
        context.roleCard.textEntries
            .filter { it.name.isNotBlank() && it.value.isNotBlank() }
            .forEach { entry ->
                appendLine("角色${entry.name.trim()}：${entry.value.trim().take(METADATA_CONTEXT_LIMIT)}")
            }
        if (context.roleCard.userIdentity.isNotBlank()) {
            appendLine("角色眼中的玩家身份：${context.roleCard.userIdentity.take(METADATA_CONTEXT_LIMIT)}")
            appendLine("注意：这只是角色对玩家的称呼/关系参考，不要把剧情推进建议误写成固定玩家台词。")
        }
        appendLine()
        appendLine("状态条定义与当前值：")
        if (context.definitions.isEmpty()) {
            appendLine("- 无状态条，state.metrics 必须是 []。")
        } else {
            context.definitions.forEach { definition ->
                val current = context.currentPanel?.metrics
                    ?.firstOrNull { it.name == definition.name }
                    ?.value
                    ?.coerceIn(definition.minValue, definition.maxValue)
                    ?: definition.initialValue
                appendLine(
                    "- ${definition.name}: 当前 $current，范围 ${definition.minValue}-${definition.maxValue}，初始 ${definition.initialValue}",
                )
                if (definition.description.isNotBlank()) {
                    appendLine("  含义：${definition.description}")
                }
                if (definition.progressRule.isNotBlank()) {
                    appendLine("  变化规则：${definition.progressRule}")
                }
            }
        }
        appendLine()
        appendLine("当前关系：${context.currentPanel?.relationship.orEmpty().ifBlank { "未记录" }}")
        appendLine("当前场景：${context.currentPanel?.scene.orEmpty().ifBlank { "未记录" }}")
        appendLine()
        appendLine("最近用户剧情推进意图：")
        appendLine(context.lastUser.take(METADATA_CONTEXT_LIMIT))
        appendLine()
        appendLine("最新正文回复：")
        appendLine(context.assistantContent.takeTailForContext())
        appendLine()
        appendLine("输出示例：")
        appendLine(buildMetadataExample(context.definitions, context.currentPanel))
    }

    private fun buildMetadataExample(
        definitions: List<RpMetricDefinition>,
        currentPanel: RpStatusPanel?,
    ): String {
        val metrics = RpStateBlockFormat.metricsJson(definitions, currentPanel, useCurrentValues = true)
        val relationship = with(RpStateBlockFormat) { relationshipExample(currentPanel).escapeJson() }
        val scene = with(RpStateBlockFormat) { sceneExample(currentPanel).escapeJson() }
        return """{"inner_thought":"角色此刻的真实想法","options":["靠近她，低声回应刚才的问题。","先观察周围，再追问她的目的。","短暂停顿，把话题转向窗外的雨声。"],"state":{"metrics":[$metrics],"relationship":"$relationship","scene":"$scene"}}"""
    }

    /**
     * 配角段（RP-2e，§6.1）：名称 + 简介 + 文本词条，按勾选顺序全部常驻。
     *
     * 说明里明确不为配角新增状态条：状态块只认定义过的前缀名，
     * 模型若给配角编状态条，会被当成未知键丢弃并计数，白白浪费输出。
     */
    private fun StringBuilder.appendSupportingCharacters(characters: List<SupportingCharacter>) {
        val blocks = characters.mapNotNull(SupportingCharacterPrompt::block)
        if (blocks.isEmpty()) return
        appendLine()
        appendLine("### 配角")
        appendLine("以下配角会在剧情中出现，由你按设定描写他们的言行。他们不是玩家，也不是角色卡的主角色；")
        appendLine("状态栏只记录下方定义过的状态条，不要为配角新增状态条。")
        blocks.forEach { block ->
            appendLine()
            append(block)
        }
    }

    /**
     * 状态条定义按归属分组（角色组在前、主角组在后，与快照顺序一致），
     * 每组标明前缀；名字本身就是带前缀的 wireName，照抄即可对上解析器。
     */
    private fun StringBuilder.appendMetricDefinitions(context: PromptContext) {
        val snapshot = context.metricSnapshot()
        appendLine()
        appendLine("### 状态栏定义")
        if (snapshot.isEmpty()) {
            appendLine("当前没有定义状态条。metrics 必须输出空数组。")
            return
        }

        appendLine(RpStateBlockFormat.PREFIX_RULE)
        snapshot.groupBy { it.ownerType }.forEach { (owner, items) ->
            val prefix = "${owner.wirePrefix}${RpStateBlockFormat.WIRE_NAME_SEPARATOR}"
            val roleName = context.roleCard?.name.orEmpty().trim().ifBlank { "角色卡人物" }
            appendLine()
            appendLine(
                when (owner) {
                    RpEntryOwner.ROLE_CARD -> "#### ${roleName}的状态条（前缀「$prefix」）"
                    RpEntryOwner.PROTAGONIST -> "#### 主角（玩家）的状态条（前缀「$prefix」）"
                },
            )
            RpEffectiveDefinitions.of(items).forEachIndexed { index, definition ->
                appendLine(
                    "${index + 1}. ${definition.name}: " +
                        "范围 ${definition.minValue}-${definition.maxValue}，初始值 ${definition.initialValue}。",
                )
                if (definition.description.isNotBlank()) {
                    appendLine("   含义：${definition.description}")
                }
                if (definition.progressRule.isNotBlank()) {
                    appendLine("   变化规则：${definition.progressRule}")
                }
            }
        }
    }

    private fun buildCurrentMetricLines(context: PromptContext): List<String> {
        val panel = context.statusPanel ?: return emptyList()
        val definitions = context.metricDefinitions()
        if (definitions.isEmpty()) {
            return emptyList()
        }

        return definitions.map { definition ->
            val current = panel.metrics.firstOrNull { it.name == definition.name }
            val value = current?.value?.coerceIn(definition.minValue, definition.maxValue) ?: definition.initialValue
            "- ${definition.name}: $value/${definition.maxValue}"
        }
    }

    private fun buildStateBlockExample(context: PromptContext): String =
        RpStateBlockFormat.stateBlockExample(
            definitions = context.metricDefinitions(),
            panel = null,
            useCurrentValues = false,
        )

    /**
     * 本会话有效的数值定义快照，名字是带归属前缀的 wireName。
     * 只从会话快照取，不读实时角色卡：改档案不影响已绑定的会话（R6）。
     * 角色卡与主角都没绑时不发状态条；只绑了主角时照常发主角组（RP-2f）。
     * 判据与请求层 `captureRpTurnSnapshot` 的 stateActive 一致。
     */
    private fun PromptContext.metricSnapshot(): List<RpMetricSnapshot> =
        if (roleCard == null && protagonistProfile == null) emptyList() else rpSettings?.metricSnapshot.orEmpty()

    private fun PromptContext.metricDefinitions(): List<RpMetricDefinition> =
        RpEffectiveDefinitions.of(metricSnapshot())

    private fun StringBuilder.appendSection(title: String, content: String) {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return
        appendLine()
        appendLine("### $title")
        appendLine(trimmed)
    }

    private fun StringBuilder.appendProfileLine(label: String, content: String) {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return
        appendLine("- $label: $trimmed")
    }

    private fun ProtagonistProfile.hasPromptContent(): Boolean =
        name.isNotBlank() ||
            description.isNotBlank() ||
            textEntries.any { it.value.isNotBlank() }

    private fun buildDeepSeekRpMarker(mode: RpMarkerMode): String =
        when (mode) {
            RpMarkerMode.DISABLED -> ""
            RpMarkerMode.ROLE_IMMERSION -> """
                <muxue_rp_marker>
                本段是请求态 RP Marker，只作为 DeepSeek thinking mode 的角色沉浸辅助，不是剧情内容。
                思考时优先保持角色卡人物的情绪、欲望、顾虑和即时决策连续；最终回复仍必须遵守正文视角、剧情推进建议和状态栏契约。
                不要在最终回复中提及、解释或复述本 Marker。
                </muxue_rp_marker>
            """.trimIndent()
            RpMarkerMode.ANALYTICAL -> """
                <muxue_rp_marker>
                本段是请求态 RP Marker，只作为 DeepSeek thinking mode 的结构分析辅助，不是剧情内容。
                思考时先稳定场景、角色动机、因果推进和输出结构；最终回复仍必须遵守正文视角、剧情推进建议和状态栏契约。
                不要在最终回复中提及、解释或复述本 Marker。
                </muxue_rp_marker>
            """.trimIndent()
        }

    /**
     * 截取尾部而非开头。情绪转折和状态变化通常发生在一段剧情的后半段，
     * 从开头截断会把元数据生成最需要的信息丢掉。
     */
    private fun String.takeTailForContext(limit: Int = METADATA_CONTEXT_LIMIT): String {
        if (length <= limit) return this
        return "（前文已省略）\n" + takeLast(limit)
    }

    private companion object {
        /**
         * App 自己的章节用来声明让位的那一句。
         *
         * 必须写在 App 章节的开头，而不是包在用户提示词外面——
         * 让位声明由让位的一方说出，用户那段就能保持原样直送。
         */
        const val DEFERENCE_NOTICE = "本节是 App 附加的补充规则，优先级低于本文开头的指令；两者冲突时以开头为准。"

        /**
         * 旧值是 1200。当 narrativeMinChars 设到 800~1000 中文字时，1200 会把最新正文
         * 砍掉后半段，元数据模型据此生成内心独白和状态数值——质量下降且完全静默。
         */
        const val METADATA_CONTEXT_LIMIT = 4000
    }
}
