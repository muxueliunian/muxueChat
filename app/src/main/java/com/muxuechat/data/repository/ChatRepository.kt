package com.muxuechat.data.repository

import com.muxuechat.domain.context.ContextWindowPlan
import com.muxuechat.domain.model.ChatMessage
import com.muxuechat.domain.model.Conversation
import com.muxuechat.domain.model.MessageImageEdit
import com.muxuechat.domain.model.PendingImage
import com.muxuechat.domain.tool.SettingsToolDraft
import kotlinx.coroutines.flow.Flow

/**
 * RP 设置 Sheet 里尚未保存的绑定选择（RP-3c），用于预估「按这个选择发送时」的上下文占用。
 * 字段语义与 `updateConversationRpSettings` 一致：null 表示不绑定。
 */
data class RpBindingDraft(
    val isRpEnabled: Boolean,
    val roleCardId: String?,
    val worldSettingId: String?,
    val writingStyleId: String?,
    val protagonistProfileId: String?,
    /** 勾选的配角，顺序即 prompt 里的顺序。 */
    val supportingCharacterIds: List<String>,
)

interface ChatRepository {
    /**
     * 上下文占用的响应式视图。
     *
     * 消息、摘要、RP 配置、全局设置或上次 API 用量任一变化都会重新发射，
     * 因此顶栏与底部面板消费的是同一份数据，不会再出现「顶栏还是旧值」。
     */
    fun observeContextWindow(conversationId: String): Flow<ContextWindowPlan>

    /**
     * 按 RP 设置草稿规划一次上下文（§6.2：配角预算的主警告必须依据草稿的实际规划结果）。
     *
     * 历史、摘要、状态面板与全局设置取库里的现值，只有绑定换成 [draft]；
     * 换了角色卡或主角的组按新档案的最新定义估算状态栏。[conversationId] 为 null 表示还没有会话（空历史）。
     * 结果只用于展示「预计」，不影响任何请求。
     */
    suspend fun planContextWindowForRpDraft(conversationId: String?, draft: RpBindingDraft): ContextWindowPlan

    /**
     * 主动压缩：把最老的一段历史换成摘要。
     * 返回 true 表示压缩成功，false 表示摘要请求失败（状态未改变）。
     */
    suspend fun compactContext(conversationId: String): Boolean

    /**
     * 「AI 重命名」：用当前选中的供应商与模型，根据会话内容生成标题并直接保存。
     * 会话没有内容、请求失败或模型没给出可用标题时抛出带中文原因的异常，标题不变。
     *
     * @return 保存后的标题。
     */
    suspend fun renameConversationWithAi(conversationId: String): String

    /** 正在后台补写剧情建议/状态的消息 id。UI 用它显示局部 loading。 */
    fun observeEnrichingMessageIds(): Flow<Set<String>>

    /** 待用户确认的设定草稿。工具调用只产出草稿，不写数据库。 */
    fun observeSettingsToolDrafts(): Flow<List<SettingsToolDraft>>

    /** 丢弃草稿。数据库不会留下任何痕迹。 */
    fun discardSettingsToolDraft(draftId: String)

    fun observeConversations(): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>
    suspend fun createConversation(title: String? = null): Conversation
    /**
     * 发送一条用户消息。[images] 是已导入的待发送图片（最多 4 张）；允许只发图片不写文字（D6）。
     * 不判断当前模型是否支持图片（D8）：上游拒绝时按普通请求失败处理，用户消息与图片保留。
     */
    fun sendMessage(conversationId: String?, content: String, images: List<PendingImage> = emptyList()): Flow<ChatMessage>
    fun regenerateLastResponse(conversationId: String): Flow<ChatMessage>
    fun retryLatestRpOptions(conversationId: String): Flow<ChatMessage>
    /**
     * 编辑历史用户消息并重新生成。[imageEdit] 为 null 表示图片不变；否则按它保留原图、追加新图（D4）。
     * 失败、取消时原消息与原图片完全不变。
     */
    fun editUserMessageAndRegenerate(
        messageId: String,
        content: String,
        imageEdit: MessageImageEdit? = null,
    ): Flow<ChatMessage>

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

    /**
     * 启动孤儿图片扫描（MULTIMODAL_PLAN 3.1）：删除库里不再引用的图片文件与过期的待发送图片。
     * 每个进程只真正执行一次（重复调用直接返回）；在数据库打开之后、持有附件锁时执行，
     * 失败只记日志，不影响聊天。
     */
    suspend fun removeOrphanImagesOncePerProcess()
}
