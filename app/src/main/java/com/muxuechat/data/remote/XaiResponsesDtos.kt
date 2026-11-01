package com.muxuechat.data.remote

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * xAI 订阅链路的 Responses 协议 DTO。
 *
 * **全部字段形状来自 `docs/XAI_WIRE_FORMAT.md` 的实测记录**，未实测的字段一律不构造，
 * 也不按 OpenAI Responses 的公开文档补全（AGENT.md §3.3）。
 *
 * 这些类型只在 [XaiResponsesTransport] 内部使用，绝不外泄到上层：
 * 上层只认 OpenAI 兼容的请求 DTO 与流式事件。
 */
@Serializable
internal data class XaiResponsesRequest(
    val model: String,
    val input: List<XaiInputItem>,
    /**
     * 恒为 true。非流式响应未采集，因此 [XaiResponsesTransport.completeChat] 也走流式再聚合，
     * 而不是猜一个没见过的响应体形状。
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val stream: Boolean = true,
    /** 实测恒为 false：App 不在上游留存会话。 */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val store: Boolean = false,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    val reasoning: XaiReasoningRequestDto? = null,
    /** 为空时整个字段省略：不带工具的请求与 XAI-2 的请求体逐字节相同。 */
    val tools: List<XaiToolDto>? = null,
)

/**
 * 实测形状（线上格式 §2.2）：扁平的 `{type,name,description,parameters}`，
 * 不是 chat/completions 那样把后三者包在 `function` 里。
 */
@Serializable
internal data class XaiToolDto(
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "function",
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/** 实测形状：`{ "effort": "medium", "summary": "auto" }`。 */
@Serializable
internal data class XaiReasoningRequestDto(
    val effort: String,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val summary: String = "auto",
)

/**
 * `input[]` 的五种实测形状（见线上格式 §2.1）：
 *
 * - 系统提示：`{"role":"developer","content":"纯字符串"}`——注意角色是 `developer` 而非 `system`
 * - 用户：`{"role":"user","content":[{"type":"input_text","text":…}]}`
 * - 助手：`{"type":"message","role":"assistant","content":[{"type":"output_text","text":…,"annotations":[]}]}`
 * - 工具调用：`{"type":"function_call","call_id":…,"name":…,"arguments":"JSON 字符串"}`
 * - 工具结果：`{"type":"function_call_output","call_id":…,"output":"纯字符串"}`
 *
 * 用一个可空字段的类覆盖五者，序列化时 `explicitNulls = false` 会省掉所有 null。
 * pi 回放 function_call 时还带 `id`（`fc_…`），上层没有保留它，因此不发（线上格式 §5.5）。
 */
@Serializable
internal data class XaiInputItem(
    val type: String? = null,
    val role: String? = null,
    val content: JsonElement? = null,
    @SerialName("call_id") val callId: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val output: String? = null,
)

@Serializable
internal data class XaiInputTextPart(
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "input_text",
    val text: String,
)

/**
 * 用户图片块（xAI Image Understanding 文档，MULTIMODAL_PLAN 1.2）。订阅路径未抓包验证，需真机验收。
 * `image_url` 是字符串（data URL），不是 OpenAI 的 `{"url": …}` 对象；不发 `detail`。
 */
@Serializable
internal data class XaiInputImagePart(
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "input_image",
    @SerialName("image_url") val imageUrl: String,
)

@Serializable
internal data class XaiOutputTextPart(
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "output_text",
    val text: String,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val annotations: List<JsonElement> = emptyList(),
)

/**
 * 一个 SSE 事件的 data 负载。
 *
 * 事件名同时出现在 `event:` 行与 data 的 `type` 里，取值相同；这里只认 data 中的 `type`，
 * 少解析一处就少一处不一致。未知 `type` 一律忽略（线上格式 §3.4：未采集事件不得臆测）。
 */
@Serializable
internal data class XaiResponseEventDto(
    val type: String = "",
    @SerialName("output_index") val outputIndex: Int? = null,
    val delta: String? = null,
    val text: String? = null,
    /** `function_call_arguments.done` 的完整参数，比累积的 delta 权威（线上格式 §3.3）。 */
    val arguments: String? = null,
    val item: XaiOutputItemDto? = null,
    val response: XaiResponseBodyDto? = null,
)

@Serializable
internal data class XaiOutputItemDto(
    val id: String? = null,
    val type: String? = null,
    val name: String? = null,
    @SerialName("call_id") val callId: String? = null,
    /** 只在 function_call 项上出现：added 时为空串，done 时是完整参数。 */
    val arguments: String? = null,
)

@Serializable
internal data class XaiResponseBodyDto(
    val status: String? = null,
    val usage: XaiUsageDto? = null,
)

/**
 * `response.completed` 里的用量。字段名与 OpenAI 兼容协议不同，必须归一化后才能交给上层
 * （线上格式 §4）。`output_tokens_details.reasoning_tokens` 没有对应字段，丢弃。
 */
@Serializable
internal data class XaiUsageDto(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
) {
    fun toOpenAiCompatible() = OpenAiCompatibleUsageDto(
        promptTokens = inputTokens,
        completionTokens = outputTokens,
        totalTokens = totalTokens,
    )
}

internal object XaiResponsesEventNames {
    const val OUTPUT_ITEM_ADDED = "response.output_item.added"
    const val OUTPUT_ITEM_DONE = "response.output_item.done"
    const val OUTPUT_TEXT_DELTA = "response.output_text.delta"
    const val FUNCTION_CALL_ARGUMENTS_DELTA = "response.function_call_arguments.delta"
    const val FUNCTION_CALL_ARGUMENTS_DONE = "response.function_call_arguments.done"
    const val REASONING_SUMMARY_DELTA = "response.reasoning_summary_text.delta"
    const val COMPLETED = "response.completed"
}
