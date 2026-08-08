package com.muxuechat.data.remote

import kotlinx.serialization.json.Json

class DeepSeekStreamParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parseLine(line: String): DeepSeekStreamEvent? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return null

        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return null
        if (payload == "[DONE]") return DeepSeekStreamEvent.Done

        val chunk = json.decodeFromString<DeepSeekChatChunkDto>(payload)
        val choice = chunk.choices.firstOrNull()
        return DeepSeekStreamEvent.Delta(
            content = choice?.delta?.content,
            reasoningContent = choice?.delta?.reasoningContent,
            finishReason = choice?.finishReason,
            usage = chunk.usage,
            toolCalls = choice?.delta?.toolCalls,
        )
    }

    fun parseLines(text: String): List<DeepSeekStreamEvent> =
        text.lineSequence().mapNotNull(::parseLine).toList()
}
