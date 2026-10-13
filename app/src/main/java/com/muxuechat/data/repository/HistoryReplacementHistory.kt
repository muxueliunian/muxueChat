package com.muxuechat.data.repository

import com.muxuechat.data.local.MessageEntity
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.MessageStatus
import com.muxuechat.domain.prompt.PromptMessage

/** 为历史替换请求构造尚未落库的目标分支，避免为改 prompt 而提前破坏正式历史。 */
internal object HistoryReplacementHistory {
    fun beforeAssistant(
        messages: List<MessageEntity>,
        assistantMessageId: String,
    ): List<PromptMessage> {
        val assistantIndex = messages.indexOfFirst { it.id == assistantMessageId }
        require(assistantIndex >= 0) { "回复不存在" }
        return messages.take(assistantIndex).toPromptMessages()
    }

    fun throughEditedUser(
        messages: List<MessageEntity>,
        userMessageId: String,
        editedContent: String,
    ): List<PromptMessage> {
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
        return editedMessages.toPromptMessages()
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

    private fun List<MessageEntity>.toPromptMessages(): List<PromptMessage> =
        asSequence()
            .filter { it.status == MessageStatus.COMPLETED }
            .filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
            .filter { it.content.isNotBlank() }
            .map { PromptMessage(role = it.role.name.lowercase(), content = it.content) }
            .toList()
}
