package com.muxuechat.data.remote

/**
 * 把流式返回的 tool_call 分片拼回完整的调用。
 *
 * DeepSeek 在流式下会把一次工具调用拆成多个 chunk：第一个 chunk 通常带 id 和
 * function.name，后续 chunk 只带 arguments 的一小段字符串，靠 `index` 表明这是第几个调用。
 * 直接用最后一个 chunk 会得到残缺的 JSON，所以必须按 index 累积。
 */
class DeepSeekToolCallAccumulator {
    private val byIndex = linkedMapOf<Int, Builder>()

    fun accept(fragments: List<DeepSeekToolCallDto>?) {
        fragments.orEmpty().forEachIndexed { fallbackIndex, fragment ->
            // index 缺失时退化为分片自身的位置，至少保证多个调用不会互相覆盖。
            val key = fragment.index ?: fallbackIndex
            val builder = byIndex.getOrPut(key) { Builder() }
            fragment.id?.takeIf { it.isNotBlank() }?.let { builder.id = it }
            fragment.function?.name?.takeIf { it.isNotBlank() }?.let { builder.name = it }
            fragment.function?.arguments?.let { builder.arguments.append(it) }
        }
    }

    fun hasCalls(): Boolean = byIndex.isNotEmpty()

    /** 产出完整调用。id 或 name 缺失的分片会被丢弃——没有它们无法回传结果。 */
    fun build(): List<DeepSeekToolCallDto> = byIndex.entries
        .sortedBy { it.key }
        .mapNotNull { (index, builder) ->
            val id = builder.id
            val name = builder.name
            if (id.isNullOrBlank() || name.isNullOrBlank()) return@mapNotNull null
            DeepSeekToolCallDto(
                id = id,
                function = DeepSeekFunctionCallDto(
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
