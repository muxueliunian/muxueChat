package com.muxuechat.data.remote

/**
 * 把 OpenAI 兼容 SSE 中的 tool_call 分片拼回完整调用。
 *
 * 标准流式格式首片通常带 id 和 function.name，后续片只追加 arguments；用 index
 * 区分同一轮里的多个调用。缺少必要字段或参数不完整时明确失败，不能静默丢弃。
 */
class OpenAiCompatibleToolCallAccumulator {
    private val byIndex = linkedMapOf<Int, Builder>()

    fun accept(fragments: List<OpenAiCompatibleToolCallDto>?) {
        fragments.orEmpty().forEachIndexed { fallbackIndex, fragment ->
            val key = fragment.index ?: fallbackIndex
            if (key < 0 || fragment.type != "function") {
                throw OpenAiCompatibleProtocolException("供应商返回的工具调用分片格式无效")
            }
            val builder = byIndex.getOrPut(key) { Builder() }
            fragment.id?.takeIf { it.isNotBlank() }?.let {
                if (builder.id != null && builder.id != it) {
                    throw OpenAiCompatibleProtocolException("供应商返回的工具调用 ID 分片不一致")
                }
                builder.id = it
            }
            fragment.function?.name?.takeIf { it.isNotBlank() }?.let {
                if (builder.name != null && builder.name != it) {
                    throw OpenAiCompatibleProtocolException("供应商返回的工具函数名称分片不一致")
                }
                builder.name = it
            }
            fragment.function?.arguments?.let { builder.arguments.append(it) }
        }
    }

    fun hasCalls(): Boolean = byIndex.isNotEmpty()

    fun build(): List<OpenAiCompatibleToolCallDto> = OpenAiCompatibleToolCallValidator.validate(
        byIndex.entries.sortedBy { it.key }.map { (index, builder) ->
            OpenAiCompatibleToolCallDto(
                id = builder.id,
                function = OpenAiCompatibleFunctionCallDto(
                    name = builder.name,
                    arguments = builder.arguments.toString(),
                ),
                index = index,
            )
        },
    )

    fun reset() = byIndex.clear()

    private class Builder {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }
}
