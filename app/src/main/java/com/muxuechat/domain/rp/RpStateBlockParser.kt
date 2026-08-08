package com.muxuechat.domain.rp

import com.muxuechat.domain.model.RpMetric
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class RpStateExtraction(
    val cleanContent: String,
    val panel: RpStatusPanelDraft?,
)

data class RpStatusPanelDraft(
    val metrics: List<RpMetric>,
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

    private fun parsePanel(rawJson: String): RpStatusPanelDraft {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val metrics = root["metrics"]
            ?.asJsonArrayOrNull()
            ?.mapNotNull { it.asJsonObjectOrNull()?.toMetricOrNull() }
            .orEmpty()

        return RpStatusPanelDraft(
            metrics = metrics,
            relationship = root["relationship"].stringValue(),
            scene = root["scene"].stringValue(),
            rawJson = rawJson,
        )
    }

    private fun JsonObject.toMetricOrNull(): RpMetric? {
        val name = this["name"].stringValue().ifBlank { return null }
        val min = this["min"].intValue(default = 0)
        val max = this["max"].intValue(default = 100).let { if (it > min) it else min + 1 }
        val value = this["value"].intValue(default = min).coerceIn(min, max)
        return RpMetric(name = name, value = value, min = min, max = max)
    }

    private fun JsonElement?.stringValue(): String =
        (this as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonElement?.intValue(default: Int): Int {
        val text = (this as? JsonPrimitive)?.contentOrNull ?: return default
        return INTEGER_REGEX.find(text)?.value?.toIntOrNull() ?: default
    }

    private fun JsonElement.asJsonArrayOrNull(): JsonArray? =
        runCatching { jsonArray }.getOrNull()

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private companion object {
        val STATE_BLOCK_REGEX = Regex(
            pattern = """<muxue_state>(.*?)</muxue_state>""",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val INTEGER_REGEX = Regex("""-?\d+""")
    }
}
