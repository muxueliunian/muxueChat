package com.muxuechat.data.remote

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class DeepSeekChatRequest(
    val model: String,
    val messages: List<DeepSeekMessageDto>,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val thinking: DeepSeekThinkingDto? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    // presence_penalty / frequency_penalty 已被 DeepSeek 废弃且永不生效，不再发送。
    val tools: List<DeepSeekToolDto>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
)

/**
 * [images] 只出现在 user 消息上（官方：system/assistant 带图返回 400）。非空时 content 序列化为
 * content part 数组，否则仍是字符串，请求字节与引入图片前一致（见 [MessageContentJson]）。
 */
@Serializable(with = DeepSeekMessageDtoSerializer::class)
data class DeepSeekMessageDto(
    val role: String,
    val content: String? = null,
    val toolCalls: List<DeepSeekToolCallDto>? = null,
    val toolCallId: String? = null,
    val images: List<ImagePayload> = emptyList(),
)

/** 线上形状。字段顺序与引入图片前的 DTO 相同，纯文本请求的序列化结果因此逐字节不变。 */
@Serializable
private data class DeepSeekMessageWire(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: List<DeepSeekToolCallDto>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

internal object DeepSeekMessageDtoSerializer : KSerializer<DeepSeekMessageDto> {
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        SerialDescriptor("DeepSeekMessageDto", DeepSeekMessageWire.serializer().descriptor)

    override fun serialize(encoder: Encoder, value: DeepSeekMessageDto) {
        encoder.encodeSerializableValue(
            DeepSeekMessageWire.serializer(),
            DeepSeekMessageWire(
                role = value.role,
                content = MessageContentJson.encode(value.content, value.images),
                toolCalls = value.toolCalls,
                toolCallId = value.toolCallId,
            ),
        )
    }

    override fun deserialize(decoder: Decoder): DeepSeekMessageDto {
        val wire = decoder.decodeSerializableValue(DeepSeekMessageWire.serializer())
        return DeepSeekMessageDto(
            role = wire.role,
            content = MessageContentJson.decodeText(wire.content),
            toolCalls = wire.toolCalls,
            toolCallId = wire.toolCallId,
        )
    }
}

/** 本次请求随消息发出的全部图片，按消息顺序。 */
internal fun DeepSeekChatRequest.imagePayloads(): List<ImagePayload> = messages.flatMap { it.images }

/**
 * `type` 是 DeepSeek 的必填字段。这里的 Json 配置是 `encodeDefaults = false`，
 * 如果只写默认值会被整个省略，服务端会报 `tools[0]: missing field type`。
 * 因此必须用 [EncodeDefault] 强制序列化。
 */
@Serializable
data class DeepSeekToolDto(
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "function",
    val function: DeepSeekFunctionDto,
)

@Serializable
data class DeepSeekFunctionDto(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class DeepSeekToolCallDto(
    /**
     * 流式续传分片里**没有** `id`：只有第一个分片带 id 和 function.name，
     * 后续分片只有 `index` 和一小段 `arguments`。声明成非空会让反序列化直接抛
     * 「Field 'id' is required ... at path: $.choices[0].delta.tool_calls[0]」。
     * 回传请求时 id 一定非空——[DeepSeekToolCallAccumulator.build] 会丢掉缺 id 的调用。
     */
    val id: String? = null,
    /** 与 [DeepSeekToolDto.type] 同理：回传 assistant 消息时必须带上，不能被默认值省略。 */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "function",
    /** 同样可能整段缺席：某些分片只推进 arguments，不重复 function 对象。 */
    val function: DeepSeekFunctionCallDto? = null,
    /** 流式增量拼接时用于识别是第几个 tool call。为 null 时不会出现在请求体里。 */
    val index: Int? = null,
)

@Serializable
data class DeepSeekFunctionCallDto(
    val name: String? = null,
    /** 模型返回的是 JSON 字符串，不是对象；流式下会分片到达。 */
    val arguments: String? = null,
)

/**
 * `reasoning_effort` 是 `thinking` 的**子字段**，不是顶层字段。
 * 之前当顶层发送，服务端静默忽略——推理强度设置从来没有真正生效过。
 */
@Serializable
data class DeepSeekThinkingDto(
    val type: String,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

@Serializable
data class DeepSeekChatChunkDto(
    val id: String? = null,
    val choices: List<DeepSeekChoiceDto> = emptyList(),
    val usage: DeepSeekUsageDto? = null,
)

@Serializable
data class DeepSeekChoiceDto(
    val index: Int? = null,
    val delta: DeepSeekDeltaDto? = null,
    val message: DeepSeekMessageResponseDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class DeepSeekDeltaDto(
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<DeepSeekToolCallDto>? = null,
)

@Serializable
data class DeepSeekUsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

@Serializable
data class DeepSeekMessageResponseDto(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<DeepSeekToolCallDto>? = null,
)
