package com.muxuechat.data.remote

/**
 * 把 OpenAI 兼容 SSE 中的 tool_call 分片拼回完整调用。
 *
 * 标准流式格式首片通常带 id 和 function.name，后续片只追加 arguments；用 index
 * 区分同一轮里的多个调用。id 或 name 直到结束仍缺失的调用无法安全回传，会被丢弃。
 */
class OpenAiCompatibleToolCallAccumulator {
    private val byIndex = linkedMapOf<Int, Builder>()

    fun accept(fragments: List<OpenAiCompatibleToolCallDto>?) {
        fragments.orEmpty().forEachIndexed { fallbackIndex, fragment ->
            val key = fragment.index ?: fallbackIndex
            val builder = byIndex.getOrPut(key) { Builder() }
            fragment.id?.takeIf { it.isNotBlank() }?.let { builder.id = it }
            fragment.function?.name?.takeIf { it.isNotBlank() }?.let { builder.name = it }
            fragment.function?.arguments?.let { builder.arguments.append(it) }
        }
    }

    fun hasCalls(): Boolean = byIndex.isNotEmpty()

    fun build(): List<OpenAiCompatibleToolCallDto> = byIndex.entries
        .sortedBy { it.key }
        .mapNotNull { (index, builder) ->
            val id = builder.id
            val name = builder.name
            if (id.isNullOrBlank() || name.isNullOrBlank()) return@mapNotNull null
            OpenAiCompatibleToolCallDto(
                id = id,
                function = OpenAiCompatibleFunctionCallDto(
                    name = name,
                    arguments = builder.arguments.toString(),
                ),
                index = index,
            )
        }

    fun reset() = byIndex.clear()

    private class Builder {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }
}
