package com.muxuechat.domain.rp

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

    private fun JsonObject.toStatusPanelDraft(): RpStatusPanelDraft =
        RpStatusDraftDecoder.decode(this, rawJson = toString())

    private fun JsonElement?.stringValue(): String =
        (this as? JsonPrimitive)?.contentOrNull.orEmpty()

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
    }
}
