package com.muxuechat.domain.prompt

/**
 * 把附加的资料拼进用户消息。
 *
 * 分隔符纯粹用于分段：让模型能看清哪里是用户自己写的话、哪里是附件正文，
 * 长文档尤其需要一个明确的结束标记。这里不做任何注入防护——
 * 这是个人项目，文档都是用户自己选的，没有不可信来源。
 */
object AttachmentFormat {
    const val BEGIN = "<<<ATTACHED_DOCUMENT_BEGIN>>>"
    const val END = "<<<ATTACHED_DOCUMENT_END>>>"

    fun composeUserMessage(
        userText: String,
        attachmentName: String?,
        attachmentText: String?,
    ): String {
        val instruction = userText.trim()
        if (attachmentName.isNullOrBlank() || attachmentText.isNullOrBlank()) {
            return instruction
        }

        return buildString {
            if (instruction.isNotBlank()) {
                appendLine(instruction)
                appendLine()
            }
            appendLine("以下是我附加的资料「$attachmentName」，共 ${attachmentText.length} 字。")
            appendLine(BEGIN)
            appendLine(attachmentText.trim())
            append(END)
        }
    }
}
