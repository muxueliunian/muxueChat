package com.muxuechat.domain.rp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

data class RpStateExtraction(
    val cleanContent: String,
    val panel: RpStatusPanelDraft?,
)

/** 模型输出的一整块状态，尚未对照定义。必须经 [RpStateMerger] 合并后才能落库。 */
data class RpStatusPanelDraft(
    val metrics: List<RpMetricDraft>,
    val relationship: String,
    val scene: String,
    val rawJson: String,
)

class RpStateBlockParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun extract(content: String): RpStateExtraction {
        val match = STATE_BLOCK_REGEX.find(content)
        val cleanContent = content.replace(STATE_BLOCK_REGEX, "").trim()
        if (match == null) {
            return RpStateExtraction(cleanContent = content, panel = null)
        }

        val rawJson = match.groupValues[1].trim()
        val panel = runCatching { parsePanel(rawJson) }.getOrNull()
        return RpStateExtraction(cleanContent = cleanContent, panel = panel)
    }

    private fun parsePanel(rawJson: String): RpStatusPanelDraft =
        RpStatusDraftDecoder.decode(json.parseToJsonElement(rawJson).jsonObject, rawJson)

    private companion object {
        val STATE_BLOCK_REGEX = Regex(
            pattern = """<muxue_state>(.*?)</muxue_state>""",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    }
}
