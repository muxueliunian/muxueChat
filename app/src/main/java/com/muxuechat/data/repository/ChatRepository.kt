package com.muxuechat.data.repository

import com.muxuechat.domain.context.ContextWindowPlan
import com.muxuechat.domain.model.ChatMessage
import com.muxuechat.domain.model.Conversation
import com.muxuechat.domain.tool.SettingsToolDraft
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    /**
     * 上下文占用的响应式视图。
     *
     * 消息、摘要、RP 配置、全局设置或上次 API 用量任一变化都会重新发射，
     * 因此顶栏与底部面板消费的是同一份数据，不会再出现「顶栏还是旧值」。
     */
    fun observeContextWindow(conversationId: String): Flow<ContextWindowPlan>

    /**
     * 主动压缩：把最老的一段历史换成摘要。
     * 返回 true 表示压缩成功，false 表示摘要请求失败（状态未改变）。
     */
    suspend fun compactContext(conversationId: String): Boolean

    /** 正在后台补写剧情建议/状态的消息 id。UI 用它显示局部 loading。 */
    fun observeEnrichingMessageIds(): Flow<Set<String>>

    /** 待用户确认的设定草稿。工具调用只产出草稿，不写数据库。 */
    fun observeSettingsToolDrafts(): Flow<List<SettingsToolDraft>>

    /** 丢弃草稿。数据库不会留下任何痕迹。 */
    fun discardSettingsToolDraft(draftId: String)

    fun observeConversations(): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>
    suspend fun createConversation(title: String? = null): Conversation
    fun sendMessage(conversationId: String?, content: String): Flow<ChatMessage>
    fun regenerateLastResponse(conversationId: String): Flow<ChatMessage>
    fun retryLatestRpOptions(conversationId: String): Flow<ChatMessage>
    fun editUserMessageAndRegenerate(messageId: String, content: String): Flow<ChatMessage>

    /**
     * 就地改写一条模型回复，不重新生成。
     *
     * 与 [editUserMessageAndRegenerate] 相反：那个会删掉后续消息并重跑，
     * 这个只改这一条。改完的正文直接成为后续请求的历史，
     * 用来在 RP 里手工修掉不满意的段落而不打断已有剧情。
     */
    suspend fun editAssistantMessage(messageId: String, content: String)
    /** 只停止指定会话拥有的正文 session；null 表示停止当前唯一 session。 */
    suspend fun stopGeneration(conversationId: String? = null)

    /** ViewModel 销毁时取消正文与 Repository 自有的后台补写 scope。 */
    fun cancelAllWork()
    suspend fun deleteConversation(conversationId: String)
    suspend fun renameConversation(conversationId: String, title: String)
    suspend fun clearConversation(conversationId: String)
}
