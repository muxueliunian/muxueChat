package com.muxuechat.presentation.chat

import com.muxuechat.domain.model.ChatMessage
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.Conversation
import com.muxuechat.domain.model.ConversationRpSettings
import com.muxuechat.domain.model.DisplaySettings
import com.muxuechat.domain.model.ImageInputSpec
import com.muxuechat.domain.model.MessageAttachment
import com.muxuechat.domain.model.PendingImage
import com.muxuechat.domain.model.ProtagonistProfile
import com.muxuechat.domain.context.ContextWindowPlan
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RpStatusPanel
import com.muxuechat.domain.model.SupportingCharacter
import com.muxuechat.domain.model.WorldSetting
import com.muxuechat.domain.model.WritingStyleProfile
import com.muxuechat.domain.tool.SettingsToolDraft

/** 已附加、等待随下一条消息发送的资料。正文只在内存中，不落库。 */
data class PendingAttachment(
    val fileName: String,
    val text: String,
) {
    val charCount: Int get() = text.length
}

/**
 * 编辑历史用户消息时的图片草稿（D4、MM-2）：保留哪些原图、追加哪些新选的待发送图片。
 * 新图在选中时就已导入、压缩进待发送区，由 ViewModel 持有；提交或放弃编辑时交给 Repository 或丢弃。
 */
data class EditImageDraft(
    val messageId: String,
    val original: List<MessageAttachment>,
    val kept: List<MessageAttachment>,
    val added: List<PendingImage> = emptyList(),
    val isImporting: Boolean = false,
) {
    val totalCount: Int get() = kept.size + added.size
    val remainingSlots: Int get() = ImageInputSpec.MAX_IMAGES_PER_MESSAGE - totalCount
    val isChanged: Boolean get() = kept.map { it.id } != original.map { it.id } || added.isNotEmpty()
}

data class ChatUiState(
    val conversations: List<Conversation> = emptyList(),
    val activeConversationId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val settings: ChatSettings = ChatSettings(),
    val displaySettings: DisplaySettings = DisplaySettings(),
    val isGenerating: Boolean = false,
    val aiRenamingConversationIds: Set<String> = emptySet(),
    val retryingRpOptionsMessageId: String? = null,
    val errorMessage: String? = null,
    val roleCards: List<RoleCard> = emptyList(),
    val worldSettings: List<WorldSetting> = emptyList(),
    val activeRpSettings: ConversationRpSettings? = null,
    val activeRoleCard: RoleCard? = null,
    /** 当前会话绑定的主角档案。 */
    val activeProtagonistProfile: ProtagonistProfile? = null,
    val protagonistProfiles: List<ProtagonistProfile> = emptyList(),
    /** 档案 id → 绑定它的会话数；没有会话绑定的档案不在表里。 */
    val protagonistProfileUsage: Map<String, Int> = emptyMap(),
    val supportingCharacters: List<SupportingCharacter> = emptyList(),
    /** 配角 id → 勾选它的会话数。 */
    val supportingCharacterUsage: Map<String, Int> = emptyMap(),
    /** 当前会话勾选的配角 id，顺序即 prompt 里的顺序。 */
    val activeSupportingCharacterIds: List<String> = emptyList(),
    /** 按 RP 设置草稿规划的上下文（只在 Sheet 打开期间有值），配角预算提示用。 */
    val rpDraftContextPlan: ContextWindowPlan? = null,
    val activeWorldSetting: WorldSetting? = null,
    val activeRpStatusPanel: RpStatusPanel? = null,
    val writingStyleProfiles: List<WritingStyleProfile> = emptyList(),
    val activeWritingStyle: WritingStyleProfile? = null,
    /** 正在后台补写剧情建议的消息 id，UI 用它显示局部 loading。 */
    val enrichingMessageIds: Set<String> = emptySet(),
    val contextWindowPlan: ContextWindowPlan? = null,
    val isCompactingContext: Boolean = false,
    val settingsToolDrafts: List<SettingsToolDraft> = emptyList(),
    val isSavingSettingsDraft: Boolean = false,
    val pendingAttachment: PendingAttachment? = null,
    /** 已导入、等待随下一条消息发送的图片（最多 4 张）。 */
    val pendingImages: List<PendingImage> = emptyList(),
    val isImportingImages: Boolean = false,
    /** 正在编辑的用户消息的图片草稿；没有打开编辑弹窗时为 null。 */
    val editImageDraft: EditImageDraft? = null,
    /** 已保存 API Key 的自定义供应商 id；只用于显示状态，不含明文。 */
    val providersWithSavedKey: Set<String> = emptySet(),
    /** 正在拉取模型列表的供应商 id。 */
    val isImportingAttachment: Boolean = false,
    val modelRefreshErrors: Map<String, String> = emptyMap(),
    val refreshingModelsProviderId: String? = null,
)
