package com.muxuechat.data.remote

import kotlinx.serialization.json.Json

/**
 * 解析 OpenAI 兼容 SSE 数据行。
 *
 * 供应商可能在事件流中插入空行或 event/id 等非 data 行；这些行不属于模型增量，
 * 因此直接忽略。`data: [DONE]` 表示服务端明确结束。
 */
class OpenAiCompatibleStreamParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parseLine(line: String): OpenAiCompatibleStreamEvent? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return null

        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return null
        if (payload == "[DONE]") return OpenAiCompatibleStreamEvent.Done

        val chunk = json.decodeFromString<OpenAiCompatibleChatChunkDto>(payload)
        val choice = chunk.choices.firstOrNull()
        return OpenAiCompatibleStreamEvent.Delta(
            content = choice?.delta?.content,
            reasoningContent = choice?.delta?.anyReasoning,
            finishReason = choice?.finishReason,
            usage = chunk.usage,
            toolCalls = choice?.delta?.toolCalls,
        )
    }

    fun parseLines(text: String): List<OpenAiCompatibleStreamEvent> =
        text.lineSequence().mapNotNull(::parseLine).toList()
}
