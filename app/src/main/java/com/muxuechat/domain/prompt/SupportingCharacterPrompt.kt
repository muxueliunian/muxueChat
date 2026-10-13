package com.muxuechat.domain.prompt

import com.muxuechat.domain.context.HeuristicTokenEstimator
import com.muxuechat.domain.model.SupportingCharacter

/**
 * 单个配角在正文 prompt 里的文本块（RP-2e / RP-3c）。
 *
 * [PromptComposer] 拼配角段与 RP 设置里的「预计占用」共用这一个渲染，
 * 否则估算与实际发出去的文本对不上（§6.2：必须估算实际拼装出来的文本，含标题与词条）。
 */
object SupportingCharacterPrompt {
    /** 名称、简介与有内容的文本词条都为空时返回 null：这样的配角不进 prompt。 */
    fun block(character: SupportingCharacter): String? {
        val name = character.name.trim()
        val description = character.description.trim()
        val entryLines = character.entries
            .filter { it.value.isNotBlank() }
            .map { "- ${it.name.trim()}: ${it.value.trim()}" }
        if (name.isBlank() && description.isBlank() && entryLines.isEmpty()) return null
        return buildString {
            appendLine("#### $name")
            if (description.isNotBlank()) appendLine(description)
            entryLines.forEach(::appendLine)
        }
    }

    /** 预计占用的 token 数，不进 prompt 的配角为 0。 */
    fun estimateTokens(character: SupportingCharacter): Long =
        block(character)?.let(HeuristicTokenEstimator::estimateText) ?: 0L
}
