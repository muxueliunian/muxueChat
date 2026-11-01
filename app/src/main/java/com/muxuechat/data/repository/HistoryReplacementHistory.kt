package com.muxuechat.data.repository

import com.muxuechat.data.local.MessageAttachmentEntity
import com.muxuechat.data.local.MessageEntity
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.MessageStatus
import com.muxuechat.domain.prompt.PromptImage
import com.muxuechat.domain.prompt.PromptMessage

/** 为历史替换请求构造尚未落库的目标分支，避免为改 prompt 而提前破坏正式历史。 */
internal object HistoryReplacementHistory {
    /** 映射结果：进入 prompt 的历史，以及因文件丢失而换成占位的图片张数。 */
    data class MappedHistory(
        val messages: List<PromptMessage>,
        val missingImageCount: Int = 0,
    )

    /**
     * **唯一**的「库里消息 → prompt 历史」映射。发送、重新生成、编辑与上下文面板四处共用，
     * 否则 UI 显示的占用与实际请求会对不上，重新生成还会静默丢图（MULTIMODAL_PLAN 3.3）。
     *
     * 只包含已完成的用户/助手消息；「有文字或带图片」都算（D6 允许纯图片消息）。
     * 文件已丢失的图片在这里换成占位并计数，不留到请求层再悄悄替换。
     */
    fun toPromptHistory(
        messages: List<MessageEntity>,
        attachmentsByMessageId: Map<String, List<MessageAttachmentEntity>> = emptyMap(),
        isAvailable: (MessageAttachmentEntity) -> Boolean = { true },
        excludeMessageId: String? = null,
    ): MappedHistory {
        var missing = 0
        val history = messages.asSequence()
            .filter { it.id != excludeMessageId }
            .filter { it.status == MessageStatus.COMPLETED }
            .filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
            .mapNotNull { message ->
                // 图片只挂在用户消息上；即使库里出现了别的情况，也绝不把图片发给 assistant 角色（会被拒）。
                val attachments = if (message.role == ChatRole.USER) {
                    attachmentsByMessageId[message.id].orEmpty().sortedBy { it.orderIndex }
                } else {
                    emptyList()
                }
                if (message.content.isBlank() && attachments.isEmpty()) return@mapNotNull null
                val (available, lost) = attachments.partition(isAvailable)
                missing += lost.size
                PromptMessage(
                    role = message.role.name.lowercase(),
                    content = if (lost.isEmpty()) message.content else missingNote(message.content, lost.size),
                    images = available.map { PromptImage(it.id, it.mimeType, it.byteSize) },
                )
            }
            .toList()
        return MappedHistory(history, missing)
    }

    fun beforeAssistant(
        messages: List<MessageEntity>,
        assistantMessageId: String,
        attachmentsByMessageId: Map<String, List<MessageAttachmentEntity>> = emptyMap(),
        isAvailable: (MessageAttachmentEntity) -> Boolean = { true },
    ): MappedHistory {
        val assistantIndex = messages.indexOfFirst { it.id == assistantMessageId }
        require(assistantIndex >= 0) { "回复不存在" }
        return toPromptHistory(messages.take(assistantIndex), attachmentsByMessageId, isAvailable)
    }

    /**
     * [attachmentsByMessageId] 里被编辑消息的那一项应已替换为编辑后的图片集合；
     * 这里只负责截取与替换文字。
     */
    fun throughEditedUser(
        messages: List<MessageEntity>,
        userMessageId: String,
        editedContent: String,
        attachmentsByMessageId: Map<String, List<MessageAttachmentEntity>> = emptyMap(),
        isAvailable: (MessageAttachmentEntity) -> Boolean = { true },
    ): MappedHistory {
        val userIndex = messages.indexOfFirst { it.id == userMessageId }
        require(userIndex >= 0) { "消息不存在" }
        val editedMessages = messages.take(userIndex + 1).map { message ->
            if (message.id == userMessageId) {
                message.copy(
                    content = editedContent,
                    status = MessageStatus.COMPLETED,
                    errorMessage = null,
                )
            } else {
                message
            }
        }
        return toPromptHistory(editedMessages, attachmentsByMessageId, isAvailable)
    }

    /**
     * 替换进行期间界面应看到的消息：旧分支仍在库里（失败/取消时要原样保留），
     * 但不能和正在流式输出的临时回复同时出现；编辑过的玩家消息提前显示新内容。
     */
    fun visibleDuringReplacement(
        messages: List<MessageEntity>,
        obsoleteMessageIds: Collection<String>,
        editedUserMessage: MessageEntity?,
    ): List<MessageEntity> {
        val hidden = obsoleteMessageIds.toSet()
        return messages
            .filterNot { it.id in hidden }
            .map { message ->
                if (editedUserMessage != null && message.id == editedUserMessage.id) editedUserMessage else message
            }
    }

    private fun missingNote(content: String, count: Int): String {
        val note = if (count == 1) "[图片文件已丢失]" else "[$count 张图片文件已丢失]"
        return if (content.isBlank()) note else content.trimEnd() + "\n" + note
    }
}
