package com.muxuechat.data.repository

import com.muxuechat.data.local.MessageEntity
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.MessageStatus
import com.muxuechat.domain.prompt.PromptImage

/** 只基于已经按 DAO 稳定顺序排列的历史计算轮次边界，便于 JVM 单测覆盖。 */
internal object RpHistoryMutation {
    /**
     * RP 辅助请求看到的「最近用户发言」。辅助请求不发图片：纯图片消息（D6）也算一次发言，
     * 图片换成一句占位，否则会跳过它、错拿更早的一条。
     */
    fun lastCompletedUserContentBefore(
        messages: List<MessageEntity>,
        targetMessageId: String,
        imageCountByMessageId: Map<String, Int> = emptyMap(),
    ): String {
        val targetIndex = messages.indexOfFirst { it.id == targetMessageId }
        if (targetIndex < 0) return ""
        return lastCompletedUserContent(messages.take(targetIndex), imageCountByMessageId)
    }

    fun lastCompletedUserContent(
        messages: List<MessageEntity>,
        imageCountByMessageId: Map<String, Int> = emptyMap(),
    ): String {
        val message = messages.lastOrNull {
            it.role == ChatRole.USER &&
                it.status == MessageStatus.COMPLETED &&
                (it.content.isNotBlank() || (imageCountByMessageId[it.id] ?: 0) > 0)
        } ?: return ""
        return PromptImage.appendNote(message.content, imageCountByMessageId[message.id] ?: 0)
    }
}
