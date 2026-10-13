package com.muxuechat.data.remote

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 自定义供应商的 OpenAI 兼容 chat/completions 请求。
 *
 * 除正常聊天字段外，支持 OpenAI 标准的 function tools / tool_calls。它们不属于
 * DeepSeek 私有协议，因此可以安全地发送给声明兼容 OpenAI chat/completions 的上游。
 * 仍然不能透传 DeepSeek 的 thinking 字段或任何 OAuth 身份。
 */
@Serializable
data class OpenAiCompatibleChatRequest(
    val model: String,
    val messages: List<OpenAiCompatibleMessageDto>,
    /** 不提供默认值，确保即使为 false 也会实际序列化到请求体。 */
    val stream: Boolean,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    /**
     * OpenAI 系的思考等级，由用户在供应商设置里自行填写（low / medium / high 等）。
     * null 表示不发送——不支持思考的模型收到未知字段可能直接报 400。
     */
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    /** 仅正文聊天使用的标准 OpenAI function 工具定义。 */
    val tools: List<OpenAiCompatibleToolDto>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    /**
     * 仅在用户为该供应商显式开启「请求流式用量统计」时才非 null。
     * 默认省略，避免向只支持普通聊天的旧反代发送未知字段。
     */
    @SerialName("stream_options") val streamOptions: OpenAiCompatibleStreamOptionsDto? = null,
)

/**
 * 流式用量开关。
 *
 * 不少 OpenAI 兼容上游在流式下**只有**收到这个字段才会在末尾推一个带 usage 的 chunk。
 * 但它同时也是一个可选字段，只支持普通聊天的旧反代收到未知字段可能直接 400，
 * 因此默认不发，由用户在该供应商上显式开启。
 */
@Serializable
data class OpenAiCompatibleStreamOptionsDto(
    /**
     * 与 [OpenAiCompatibleToolDto.type] 同理：Json 配置是 `encodeDefaults = false`，
     * 只写默认值会被整个省略，请求体里就只剩一个空的 `"stream_options":{}`，
     * 上游收到后不会返回任何用量——这个字段必须强制序列化。
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("include_usage") val includeUsage: Boolean = true,
)

/**
 * OpenAI 兼容消息。assistant 的纯工具调用允许省略 content；tool 消息必须带
 * tool_call_id 与结果文本。
 */
@Serializable
data class OpenAiCompatibleMessageDto(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiCompatibleToolCallDto>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

/** 标准 OpenAI function tool 定义。 */
@Serializable
data class OpenAiCompatibleToolDto(
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "function",
    val function: OpenAiCompatibleFunctionDto,
)

@Serializable
data class OpenAiCompatibleFunctionDto(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/**
 * 标准 OpenAI tool call。流式分片会缺少 id、function.name 或 type；完整调用在
 * [OpenAiCompatibleToolCallAccumulator] 中拼接后才会回传给上游。
 */
@Serializable
data class OpenAiCompatibleToolCallDto(
    val id: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "function",
    val function: OpenAiCompatibleFunctionCallDto? = null,
    /** 只用于流式分片归并，回传 assistant 消息前必须清掉。 */
    val index: Int? = null,
)

@Serializable
data class OpenAiCompatibleFunctionCallDto(
    val name: String? = null,
    /** 模型返回的是 JSON 字符串；流式时可能被拆成多个片段。 */
    val arguments: String? = null,
)

@Serializable
data class OpenAiCompatibleChatChunkDto(
    val id: String? = null,
    val choices: List<OpenAiCompatibleChoiceDto> = emptyList(),
    val usage: OpenAiCompatibleUsageDto? = null,
)

@Serializable
data class OpenAiCompatibleChoiceDto(
    val index: Int? = null,
    val delta: OpenAiCompatibleDeltaDto? = null,
    val message: OpenAiCompatibleMessageResponseDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

/**
 * 思考内容的字段名在各家实现里不统一：DeepSeek/Qwen/GLM 等用 `reasoning_content`，
 * OpenRouter 用 `reasoning`。两个都解析，取先出现的非空值，避免思考过程被静默丢弃。
 */
@Serializable
data class OpenAiCompatibleDeltaDto(
    val content: String? = null,
    val refusal: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiCompatibleToolCallDto>? = null,
) {
    val anyReasoning: String?
        get() = reasoningContent?.takeIf { it.isNotEmpty() } ?: reasoning?.takeIf { it.isNotEmpty() }
}

@Serializable
data class OpenAiCompatibleMessageResponseDto(
    val role: String? = null,
    val content: String? = null,
    val refusal: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiCompatibleToolCallDto>? = null,
) {
    val anyReasoning: String?
        get() = reasoningContent?.takeIf { it.isNotEmpty() } ?: reasoning?.takeIf { it.isNotEmpty() }
}

/**
 * `GET {base}/models` 的响应。
 *
 * 各家实现返回的字段差异很大，这里只取 id：它是唯一能直接放进 chat 请求的值。
 */
@Serializable
data class OpenAiCompatibleModelsResponseDto(
    val data: List<OpenAiCompatibleModelDto> = emptyList(),
)

@Serializable
data class OpenAiCompatibleModelDto(
    val id: String = "",
)

@Serializable
data class OpenAiCompatibleUsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)
