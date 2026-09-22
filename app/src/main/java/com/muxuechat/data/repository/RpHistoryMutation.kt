package com.muxuechat.data.repository

import com.muxuechat.data.local.MessageEntity
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.MessageStatus

/** 只基于已经按 DAO 稳定顺序排列的历史计算轮次边界，便于 JVM 单测覆盖。 */
internal object RpHistoryMutation {
    fun lastCompletedUserContentBefore(
        messages: List<MessageEntity>,
        targetMessageId: String,
    ): String {
        val targetIndex = messages.indexOfFirst { it.id == targetMessageId }
        if (targetIndex < 0) return ""
        return messages
            .take(targetIndex)
            .lastOrNull {
                it.role == ChatRole.USER &&
                    it.status == MessageStatus.COMPLETED &&
                    it.content.isNotBlank()
            }
            ?.content
            .orEmpty()
    }
}
