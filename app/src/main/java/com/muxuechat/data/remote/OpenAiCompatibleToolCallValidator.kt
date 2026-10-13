package com.muxuechat.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 普通响应与流式归并共用；协议损坏不能被当成空工具列表或部分成功。 */
internal object OpenAiCompatibleToolCallValidator {
    private val json = Json

    fun validate(calls: List<OpenAiCompatibleToolCallDto>): List<OpenAiCompatibleToolCallDto> {
        val ids = mutableSetOf<String>()
        calls.forEach { call ->
            val id = call.id
            if (id.isNullOrBlank() || call.function?.name.isNullOrBlank()) {
                throw OpenAiCompatibleProtocolException("供应商返回的工具调用不完整，缺少调用 ID 或函数名称")
            }
            if (call.type != "function") {
                throw OpenAiCompatibleProtocolException("供应商返回了不支持的工具调用类型")
            }
            if (!ids.add(id)) {
                throw OpenAiCompatibleProtocolException("供应商返回了重复的工具调用 ID")
            }
            val arguments = call.function?.arguments
            if (arguments.isNullOrBlank() || runCatching { json.parseToJsonElement(arguments) }.getOrNull() !is JsonObject) {
                throw OpenAiCompatibleProtocolException("供应商返回的工具参数不是完整 JSON 对象，请重试")
            }
        }
        return calls
    }
}
