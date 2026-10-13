package com.muxuechat.domain.rp

import com.muxuechat.domain.model.RpEntryOwner
import com.muxuechat.domain.model.RpMetricDefinition
import com.muxuechat.domain.model.RpStatusPanel

/**
 * 状态块 JSON 的唯一格式化入口。
 *
 * 在此之前 PromptComposer 和 ChatRepositoryImpl 里各自手写了三份几乎相同的示例构造代码，
 * 任何一处改格式都会和解析器悄悄漂移。这里集中一份，供所有 prompt 复用。
 */
object RpStateBlockFormat {
    const val TAG_OPEN = "<muxue_state>"
    const val TAG_CLOSE = "</muxue_state>"

    /**
     * 数值名的归属前缀分隔符（R11）。
     *
     * 用 `::` 而不是 `.`：`.` 在数值名里很可能自然出现（`Lv.`、`H.P.`、小数），
     * 用它会让校验频繁误伤。**不宣称 `::` 的模型复现率更高**——没有实测支持，
     * 稳定性靠 entryId 身份与失败保值兜底，不靠分隔符。
     */
    const val WIRE_NAME_SEPARATOR = "::"

    /**
     * 三处 prompt（正文、元数据补写、状态兜底）共用的前缀规则，放在状态条定义前。
     * 只说「原样照抄」，不解释前缀的实现含义——多写只会让模型自作主张地改名。
     */
    const val PREFIX_RULE =
        "状态条的 name 带有归属前缀（「角色::」「主角::」），输出时必须原样照抄完整的 name，" +
            "包括前缀；不要去掉前缀，也不要给名字另加前缀。"

    /** 把显示名加上归属前缀，得到发给模型、也写进 metricsJson 的名字。 */
    fun wireNameOf(owner: RpEntryOwner, displayName: String): String =
        "${owner.wirePrefix}$WIRE_NAME_SEPARATOR$displayName"

    private const val BACKSPACE = '\u0008'
    private const val FORM_FEED = '\u000C'

    /** 取某个状态条在当前面板中的值，缺失或越界时回落到定义的初始值/范围。 */
    fun currentValueOf(definition: RpMetricDefinition, panel: RpStatusPanel?): Int =
        panel?.metrics
            ?.firstOrNull { it.name == definition.name }
            ?.value
            ?.coerceIn(definition.minValue, definition.maxValue)
            ?: definition.initialValue

    /** 构造 metrics 数组的 JSON 片段。[useCurrentValues] 为 false 时一律用初始值。 */
    fun metricsJson(
        definitions: List<RpMetricDefinition>,
        panel: RpStatusPanel? = null,
        useCurrentValues: Boolean = panel != null,
    ): String = definitions.joinToString(separator = ",") { definition ->
        val value = if (useCurrentValues) currentValueOf(definition, panel) else definition.initialValue
        """{"name":"${definition.name.escapeJson()}","value":$value,""" +
            """"min":${definition.minValue},"max":${definition.maxValue}}"""
    }

    /**
     * 示例里 relationship / scene 的说明文字。
     *
     * 旧示例直接写「当前关系」「当前场景」，模型会把它当成合法取值原样抄回来，
     * 面板于是显示「关系：当前关系」；这个值再作为「当前状态」喂回下一轮，形成自我强化。
     * 现在示例写成明显的填写说明，并由 [meaningfulText] 把抄回来的说明文字当作「没给」。
     */
    const val RELATIONSHIP_HINT = "用一句话写本轮结束时双方的具体关系"
    const val SCENE_HINT = "用一句话写本轮结束时所在的具体地点与情境"

    /** 旧版示例的占位值，历史面板里可能已经存着它们。 */
    private val PLACEHOLDER_TEXTS = setOf("当前关系", "当前场景", RELATIONSHIP_HINT, SCENE_HINT)

    /** 关系/场景文本去掉首尾空白；抄回来的示例占位文字视为空，让合并逻辑保留上一轮的值。 */
    fun meaningfulText(value: String?): String {
        val trimmed = value.orEmpty().trim()
        return if (trimmed in PLACEHOLDER_TEXTS) "" else trimmed
    }

    /** 完整的 `<muxue_state>{...}</muxue_state>` 示例。 */
    fun stateBlockExample(
        definitions: List<RpMetricDefinition>,
        panel: RpStatusPanel? = null,
        useCurrentValues: Boolean = panel != null,
    ): String {
        val metrics = metricsJson(definitions, panel, useCurrentValues)
        val relationship = relationshipExample(panel).escapeJson()
        val scene = sceneExample(panel).escapeJson()
        return """$TAG_OPEN{"metrics":[$metrics],"relationship":"$relationship","scene":"$scene"}$TAG_CLOSE"""
    }

    fun relationshipExample(panel: RpStatusPanel?): String =
        meaningfulText(panel?.relationship).ifBlank { RELATIONSHIP_HINT }

    fun sceneExample(panel: RpStatusPanel?): String =
        meaningfulText(panel?.scene).ifBlank { SCENE_HINT }

    /**
     * 供界面显示的正文：去掉状态块。
     *
     * 状态块只在回复完成时才从正文里剥离，而模型总是把它写在最后，于是流式输出的末尾
     * 它会原样显示好几秒。这里在显示层提前隐藏：完整的块、尚未闭合的块，
     * 以及（仅流式中）末尾正在输出的半个起始标签。库里的正文不受影响。
     */
    fun stripForDisplay(content: String, isStreaming: Boolean): String {
        if (content.isEmpty()) return content
        var text = COMPLETE_BLOCK_REGEX.replace(content, "")
        val openIndex = text.indexOf(TAG_OPEN_PREFIX, ignoreCase = true)
        if (openIndex >= 0) {
            text = text.substring(0, openIndex)
        } else if (isStreaming) {
            // 末尾可能是刚输出一半的起始标签（如「<muxue_st」）；至少两个字符才判定，单个「<」不动。
            val partialLength = (minOf(TAG_OPEN_PREFIX.length - 1, text.length) downTo 2).firstOrNull { length ->
                TAG_OPEN_PREFIX.startsWith(text.takeLast(length), ignoreCase = true)
            }
            if (partialLength != null) text = text.dropLast(partialLength)
        }
        text = EMPTY_CODE_FENCE_REGEX.replace(text, "")
        // 围栏里的状态块被截掉后，只剩一个没有闭合的围栏起始行。围栏数为偶数时末尾的 ``` 是
        // 正常代码块的结束符，不能动。
        if (FENCE_REGEX.findAll(text).count() % 2 == 1) {
            text = DANGLING_FENCE_REGEX.replace(text, "")
        }
        return text.trimEnd()
    }

    private const val TAG_OPEN_PREFIX = "<muxue_state"
    private val COMPLETE_BLOCK_REGEX = Regex(
        pattern = """<muxue_state>.*?</muxue_state>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** 模型偶尔把状态块包进代码围栏；块被剥离后剩下的空围栏也一并去掉。 */
    val EMPTY_CODE_FENCE_REGEX = Regex("""```[A-Za-z]*\s*```""")
    private val FENCE_REGEX = Regex("```")
    private val DANGLING_FENCE_REGEX = Regex("""```[A-Za-z]*\s*$""")

    /**
     * JSON 字符串转义。
     *
     * 旧实现只处理了 `\` 和 `"`，状态条名称里一旦出现换行或制表符就会生成非法 JSON 示例，
     * 反过来诱导模型也输出非法 JSON。这里按 JSON 规范处理全部必须转义的控制字符。
     */
    fun String.escapeJson(): String = buildString(length) {
        this@escapeJson.forEach { char ->
            when {
                char == '\\' -> append("\\\\")
                char == '"' -> append("\\\"")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char == BACKSPACE -> append("\\b")
                char == FORM_FEED -> append("\\f")
                char < ' ' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
    }
}
