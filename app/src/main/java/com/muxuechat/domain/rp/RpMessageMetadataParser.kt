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

data class RpMessageMetadataDraft(
    val innerThought: String,
    val options: List<String>,
    val statusPanel: RpStatusPanelDraft?,
    val rawJson: String,
)

class RpMessageMetadataParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parse(content: String): RpMessageMetadataDraft? {
        val rawJson = extractRawJson(content) ?: return null
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
        val state = root["state"]?.asJsonObjectOrNull()
        return RpMessageMetadataDraft(
            innerThought = root["inner_thought"].stringValue()
                .ifBlank { root["innerThought"].stringValue() },
            options = root["options"]
                ?.asJsonArrayOrNull()
                ?.mapNotNull { option ->
                    option.stringValue().trim().takeIf { it.isNotBlank() }
                }
                ?.take(OPTION_COUNT)
                .orEmpty(),
            statusPanel = state?.toStatusPanelDraft(),
            rawJson = rawJson,
        )
    }

    private fun extractRawJson(content: String): String? {
        META_BLOCK_REGEX.find(content)?.let { return it.groupValues[1].trim() }

        val cleaned = content
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return cleaned.substringOrNull(start, end + 1)
    }

    private fun JsonObject.toStatusPanelDraft(): RpStatusPanelDraft {
        val metrics = this["metrics"]
            ?.asJsonArrayOrNull()
            ?.mapNotNull { it.asJsonObjectOrNull()?.toMetricOrNull() }
            .orEmpty()
        return RpStatusPanelDraft(
            metrics = metrics,
            relationship = this["relationship"].stringValue(),
            scene = this["scene"].stringValue(),
            rawJson = toString(),
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

    private fun String.substringOrNull(startIndex: Int, endIndex: Int): String? {
        if (startIndex < 0 || endIndex <= startIndex || endIndex > length) return null
        return substring(startIndex, endIndex)
    }

    private companion object {
        const val OPTION_COUNT = 3
        val META_BLOCK_REGEX = Regex(
            pattern = """<muxue_meta>(.*?)</muxue_meta>""",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val INTEGER_REGEX = Regex("""-?\d+""")
    }
}
