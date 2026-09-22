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

    /** 完整的 `<muxue_state>{...}</muxue_state>` 示例。 */
    fun stateBlockExample(
        definitions: List<RpMetricDefinition>,
        panel: RpStatusPanel? = null,
        useCurrentValues: Boolean = panel != null,
    ): String {
        val metrics = metricsJson(definitions, panel, useCurrentValues)
        val relationship = panel?.relationship.orEmpty().ifBlank { "当前关系" }.escapeJson()
        val scene = panel?.scene.orEmpty().ifBlank { "当前场景" }.escapeJson()
        return """$TAG_OPEN{"metrics":[$metrics],"relationship":"$relationship","scene":"$scene"}$TAG_CLOSE"""
    }

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
