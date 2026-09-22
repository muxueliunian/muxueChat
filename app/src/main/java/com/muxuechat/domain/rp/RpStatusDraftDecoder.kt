package com.muxuechat.domain.rp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 状态 JSON 对象 → [RpStatusPanelDraft] 的唯一解码入口。
 *
 * 正文状态块、元数据的 `state` 字段与兜底请求此前各自手写了一份几乎相同的解码，
 * 规则悄悄漂移过（例如缺 value 时回落到 min）。这里只做**无损解码**：
 * 不裁剪、不补默认值、不对照定义，那些全部交给 [RpStateMerger]。
 */
internal object RpStatusDraftDecoder {
    fun decode(root: JsonObject, rawJson: String): RpStatusPanelDraft = RpStatusPanelDraft(
        metrics = root["metrics"]
            ?.asJsonArrayOrNull()
            ?.mapNotNull { it.asJsonObjectOrNull()?.toMetricDraftOrNull() }
            .orEmpty(),
        relationship = root["relationship"].stringValue(),
        scene = root["scene"].stringValue(),
        rawJson = rawJson,
    )

    private fun JsonObject.toMetricDraftOrNull(): RpMetricDraft? {
        val name = this["name"].stringValue().trim().ifBlank { return null }
        return RpMetricDraft(
            name = name,
            value = this["value"].intOrNull(),
            min = this["min"].intOrNull(),
            max = this["max"].intOrNull(),
        )
    }

    private fun JsonElement?.stringValue(): String =
        (this as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** 模型常把数字写成字符串（`"7"`、`"7点"`），取第一段整数；取不到或溢出时为 null，而不是猜一个值。 */
    private fun JsonElement?.intOrNull(): Int? {
        val text = (this as? JsonPrimitive)?.contentOrNull ?: return null
        return INTEGER_REGEX.find(text)?.value?.toIntOrNull()
    }

    private fun JsonElement.asJsonArrayOrNull(): JsonArray? =
        runCatching { jsonArray }.getOrNull()

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private val INTEGER_REGEX = Regex("""-?\d+""")
}
