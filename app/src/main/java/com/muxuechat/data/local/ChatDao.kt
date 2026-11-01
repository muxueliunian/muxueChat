package com.muxuechat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow

data class RpTurnConfigurationSnapshot(
    val settings: ConversationRpSettingsEntity?,
    val roleCard: RoleCardEntity?,
    /** 只在 RP 开启且绑了角色卡时非空：它是本轮 prompt 用的当前状态。 */
    val statusPanel: RpStatusPanelEntity?,
    /**
     * 库里的面板原样，不论 RP 是否开启。它是提交时的**期望值**：
     * 请求期间面板被别处改过，旧请求的派生结果就不得再覆盖它（RP_ACTOR_PLAN 第 5 节）。
     */
    val persistedStatusPanel: RpStatusPanelEntity?,
    val statusBeforeMetadata: MessageRpMetadataEntity?,
    /** 只在 RP 开启且绑了主角档案时非空；与配置同一事务读出，避免混入换绑中途的状态。 */
    val protagonistProfile: ProtagonistProfileEntity? = null,
    /** 只在 RP 开启时读取，按勾选顺序；同样与配置同一事务读出。 */
    val supportingCharacters: List<SupportingCharacterEntity> = emptyList(),
)

/** 带 RP 派生数据的提交结果。三种情况下正文本身都已提交。 */
enum class RpStateCommitResult {
    /** 配置与面板都没变，派生数据已按请求写入。 */
    APPLIED,

    /** 配置没变，但面板在请求期间被改过：保留库里的面板，不用旧请求的结果覆盖。 */
    STATUS_PANEL_CHANGED,

    /** RP 配置或角色卡已变化，旧请求的派生数据整体丢弃。 */
    CONFIGURATION_CHANGED,
}

/** 一份主角档案被多少个会话绑定（RP-3b 档案列表的「N 个会话在用」）。 */
data class ProtagonistProfileUsageRow(
    val profileId: String,
    val conversationCount: Int,
)

/** 一个配角被多少个会话勾选（RP-3c）。 */
data class SupportingCharacterUsageRow(
    val characterId: String,
    val conversationCount: Int,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAtMillis DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtMillis ASC, rowid ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM message_rp_metadata WHERE conversationId = :conversationId")
    fun observeMessageRpMetadata(conversationId: String): Flow<List<MessageRpMetadataEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtMillis ASC, rowid ASC")
    suspend fun getMessages(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getMessage(messageId: String): MessageEntity?

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    suspend fun getConversation(conversationId: String): ConversationEntity?

    @Query("SELECT * FROM role_cards ORDER BY updatedAtMillis DESC")
    fun observeRoleCards(): Flow<List<RoleCardEntity>>

    @Query("SELECT * FROM world_settings ORDER BY updatedAtMillis DESC")
    fun observeWorldSettings(): Flow<List<WorldSettingEntity>>

    @Query("SELECT * FROM conversation_rp_settings WHERE conversationId = :conversationId LIMIT 1")
    fun observeConversationRpSettings(conversationId: String): Flow<ConversationRpSettingsEntity?>

    @Query("SELECT * FROM rp_status_panels WHERE conversationId = :conversationId LIMIT 1")
    fun observeRpStatusPanel(conversationId: String): Flow<RpStatusPanelEntity?>

    @Query("SELECT * FROM writing_style_profiles ORDER BY updatedAtMillis DESC")
    fun observeWritingStyleProfiles(): Flow<List<WritingStyleProfileEntity>>

    @Query("SELECT * FROM writing_style_profiles WHERE id = :writingStyleId LIMIT 1")
    suspend fun getWritingStyleProfile(writingStyleId: String): WritingStyleProfileEntity?

    @Insert
    suspend fun insertWritingStyleProfile(profile: WritingStyleProfileEntity)

    @Update
    suspend fun updateWritingStyleProfile(profile: WritingStyleProfileEntity): Int

    @Upsert
    suspend fun upsertWritingStyleProfile(profile: WritingStyleProfileEntity)

    @Query("DELETE FROM writing_style_profiles WHERE id = :writingStyleId")
    suspend fun deleteWritingStyleProfile(writingStyleId: String)

    @Query("SELECT * FROM conversation_context WHERE conversationId = :conversationId LIMIT 1")
    fun observeConversationContext(conversationId: String): Flow<ConversationContextEntity?>

    @Query("SELECT * FROM conversation_context WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getConversationContext(conversationId: String): ConversationContextEntity?

    @Upsert
    suspend fun upsertConversationContext(context: ConversationContextEntity)

    @Query("DELETE FROM conversation_context WHERE conversationId = :conversationId")
    suspend fun deleteConversationContext(conversationId: String)

    @Query("SELECT * FROM conversation_token_usage WHERE conversationId = :conversationId LIMIT 1")
    fun observeConversationTokenUsage(conversationId: String): Flow<ConversationTokenUsageEntity?>

    @Query("SELECT * FROM conversation_token_usage WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getConversationTokenUsage(conversationId: String): ConversationTokenUsageEntity?

    @Upsert
    suspend fun upsertConversationTokenUsage(usage: ConversationTokenUsageEntity)

    @Query("DELETE FROM conversation_token_usage WHERE conversationId = :conversationId")
    suspend fun deleteConversationTokenUsage(conversationId: String)

    @Query("SELECT * FROM role_cards WHERE id = :roleCardId LIMIT 1")
    suspend fun getRoleCard(roleCardId: String): RoleCardEntity?

    @Query("SELECT * FROM world_settings WHERE id = :worldSettingId LIMIT 1")
    suspend fun getWorldSetting(worldSettingId: String): WorldSettingEntity?

    @Query("SELECT * FROM conversation_rp_settings WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getConversationRpSettings(conversationId: String): ConversationRpSettingsEntity?

    @Query("SELECT * FROM conversation_rp_settings WHERE roleCardId = :roleCardId")
    suspend fun getConversationRpSettingsByRoleCard(roleCardId: String): List<ConversationRpSettingsEntity>

    @Query("SELECT * FROM rp_status_panels WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getRpStatusPanel(conversationId: String): RpStatusPanelEntity?

    /** 供一次正文请求冻结自洽的 RP 配置；不得在三次独立查询之间混入配置切换事务。 */
    @Transaction
    suspend fun getRpTurnConfigurationSnapshot(
        conversationId: String,
        statusBeforeMessageId: String? = null,
        includePausedBindings: Boolean = false,
    ): RpTurnConfigurationSnapshot {
        val settings = getConversationRpSettings(conversationId)
        val roleCard = settings
            ?.takeIf { it.isRpEnabled || includePausedBindings }
            ?.roleCardId
            ?.let { getRoleCard(it) }
        val persistedStatusPanel = getRpStatusPanel(conversationId)
        val protagonistProfile = settings
            ?.takeIf { it.isRpEnabled || includePausedBindings }
            ?.protagonistProfileId
            ?.let { getProtagonistProfile(it) }
        return RpTurnConfigurationSnapshot(
            settings = settings,
            roleCard = roleCard,
            // 状态链路在「绑了角色卡或主角」时生效（RP-2f）：只有主角有数值的会话同样要有面板。
            statusPanel = persistedStatusPanel?.takeIf { roleCard != null || protagonistProfile != null },
            persistedStatusPanel = persistedStatusPanel,
            statusBeforeMetadata = statusBeforeMessageId?.let {
                getLatestMessageRpMetadataBeforeMessage(conversationId, it)
            },
            protagonistProfile = protagonistProfile,
            supportingCharacters = if (settings?.isRpEnabled == true) {
                getConversationSupportingCharacters(conversationId)
            } else {
                emptyList()
            },
        )
    }

    @Query(
        """
        SELECT metadata.* FROM message_rp_metadata metadata
        INNER JOIN messages message ON message.id = metadata.messageId
        INNER JOIN messages target ON target.id = :messageId
        WHERE metadata.conversationId = :conversationId
            AND target.conversationId = :conversationId
            AND (
                message.createdAtMillis < target.createdAtMillis
                OR (
                    message.createdAtMillis = target.createdAtMillis
                    AND message.rowid < target.rowid
                )
            )
            AND (
                metadata.metricsJson != '[]'
                OR metadata.relationship != ''
                OR metadata.scene != ''
                OR metadata.rawJson != ''
            )
        ORDER BY message.createdAtMillis DESC, message.rowid DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestMessageRpMetadataBeforeMessage(
        conversationId: String,
        messageId: String,
    ): MessageRpMetadataEntity?

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
            AND role = :assistantRole
            AND status = :completedStatus
        ORDER BY createdAtMillis DESC, rowid DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestCompletedAssistant(
        conversationId: String,
        assistantRole: ChatRole,
        completedStatus: MessageStatus,
    ): MessageEntity?

    @Query("SELECT * FROM message_rp_metadata WHERE conversationId = :conversationId")
    suspend fun getMessageRpMetadata(conversationId: String): List<MessageRpMetadataEntity>

    @Query("SELECT * FROM message_rp_metadata WHERE messageId = :messageId")
    suspend fun getMessageRpMetadataForMessage(messageId: String): MessageRpMetadataEntity?

    @Upsert
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Upsert
    suspend fun upsertMessage(message: MessageEntity)

    @Insert
    suspend fun insertRoleCard(roleCard: RoleCardEntity)

    @Update
    suspend fun updateRoleCard(roleCard: RoleCardEntity): Int

    @Upsert
    suspend fun upsertRoleCard(roleCard: RoleCardEntity)

    @Insert
    suspend fun insertWorldSetting(worldSetting: WorldSettingEntity)

    @Update
    suspend fun updateWorldSetting(worldSetting: WorldSettingEntity): Int

    @Upsert
    suspend fun upsertWorldSetting(worldSetting: WorldSettingEntity)

    @Upsert
    suspend fun upsertConversationRpSettings(settings: ConversationRpSettingsEntity)

    @Upsert
    suspend fun upsertRpStatusPanel(panel: RpStatusPanelEntity)

    @Upsert
    suspend fun upsertMessageRpMetadata(metadata: MessageRpMetadataEntity)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    /** 只删角色卡行；绑定它的会话由 Repository 先按统一的转换规则解绑（清角色组数值）。 */
    @Query("DELETE FROM role_cards WHERE id = :roleCardId")
    suspend fun deleteRoleCardRow(roleCardId: String)

    // ---------- 主角档案 ----------

    @Query("SELECT * FROM protagonist_profiles ORDER BY updatedAtMillis DESC")
    fun observeProtagonistProfiles(): Flow<List<ProtagonistProfileEntity>>

    @Query("SELECT * FROM protagonist_profiles WHERE id = :profileId")
    suspend fun getProtagonistProfile(profileId: String): ProtagonistProfileEntity?

    @Insert
    suspend fun insertProtagonistProfile(profile: ProtagonistProfileEntity)

    @Update
    suspend fun updateProtagonistProfile(profile: ProtagonistProfileEntity): Int

    /** 只删档案行；绑定它的会话由 Repository 先按统一的转换规则解绑（清主角组数值）。 */
    @Query("DELETE FROM protagonist_profiles WHERE id = :profileId")
    suspend fun deleteProtagonistProfileRow(profileId: String)

    @Query("SELECT * FROM conversation_rp_settings WHERE protagonistProfileId = :profileId")
    suspend fun getConversationRpSettingsByProtagonist(profileId: String): List<ConversationRpSettingsEntity>

    /** 暂停 RP 的会话也算：绑定还在，恢复后照样用这份档案。 */
    @Query(
        """
        SELECT protagonistProfileId AS profileId, COUNT(*) AS conversationCount
        FROM conversation_rp_settings
        WHERE protagonistProfileId IS NOT NULL
        GROUP BY protagonistProfileId
        """,
    )
    fun observeProtagonistProfileUsage(): Flow<List<ProtagonistProfileUsageRow>>

    // ---------- 配角 ----------

    @Query("SELECT * FROM supporting_characters ORDER BY updatedAtMillis DESC")
    fun observeSupportingCharacters(): Flow<List<SupportingCharacterEntity>>

    @Query("SELECT * FROM supporting_characters WHERE id = :characterId")
    suspend fun getSupportingCharacter(characterId: String): SupportingCharacterEntity?

    @Insert
    suspend fun insertSupportingCharacter(character: SupportingCharacterEntity)

    @Update
    suspend fun updateSupportingCharacter(character: SupportingCharacterEntity): Int

    /** 关联行由外键 CASCADE 一并删除；配角没有数值，不涉及面板。 */
    @Query("DELETE FROM supporting_characters WHERE id = :characterId")
    suspend fun deleteSupportingCharacter(characterId: String)

    @Query(
        """
        SELECT character.* FROM supporting_characters AS character
        INNER JOIN conversation_supporting_characters AS link
            ON link.supportingCharacterId = character.id
        WHERE link.conversationId = :conversationId
        ORDER BY link.orderIndex ASC
        """,
    )
    fun observeConversationSupportingCharacters(conversationId: String): Flow<List<SupportingCharacterEntity>>

    @Query(
        """
        SELECT character.* FROM supporting_characters AS character
        INNER JOIN conversation_supporting_characters AS link
            ON link.supportingCharacterId = character.id
        WHERE link.conversationId = :conversationId
        ORDER BY link.orderIndex ASC
        """,
    )
    suspend fun getConversationSupportingCharacters(conversationId: String): List<SupportingCharacterEntity>

    @Query("DELETE FROM conversation_supporting_characters WHERE conversationId = :conversationId")
    suspend fun clearConversationSupportingCharacters(conversationId: String)

    @Query(
        """
        SELECT supportingCharacterId AS characterId, COUNT(*) AS conversationCount
        FROM conversation_supporting_characters
        GROUP BY supportingCharacterId
        """,
    )
    fun observeSupportingCharacterUsage(): Flow<List<SupportingCharacterUsageRow>>

    @Query(
        """
        SELECT supportingCharacterId AS characterId, COUNT(*) AS conversationCount
        FROM conversation_supporting_characters
        GROUP BY supportingCharacterId
        """,
    )
    suspend fun getSupportingCharacterUsageRows(): List<SupportingCharacterUsageRow>

    /** 设定工具统计每项被几个会话使用（`list_rp_settings`）。 */
    @Query("SELECT * FROM conversation_rp_settings")
    suspend fun getAllConversationRpSettings(): List<ConversationRpSettingsEntity>

    @Insert
    suspend fun insertConversationSupportingCharacters(links: List<ConversationSupportingCharacterEntity>)

    /**
     * 整体替换一个会话勾选的配角，顺序即 [characterIds] 的顺序。
     * 重复 id 只保留第一次出现的位置（复合主键本来也不允许重复）。
     */
    @Transaction
    suspend fun replaceConversationSupportingCharacters(conversationId: String, characterIds: List<String>) {
        clearConversationSupportingCharacters(conversationId)
        val links = characterIds.distinct().mapIndexed { index, characterId ->
            ConversationSupportingCharacterEntity(
                conversationId = conversationId,
                supportingCharacterId = characterId,
                orderIndex = index,
            )
        }
        if (links.isNotEmpty()) insertConversationSupportingCharacters(links)
    }

    // ---------- 图片附件（v11） ----------

    @Query("SELECT * FROM message_attachments WHERE conversationId = :conversationId ORDER BY orderIndex ASC")
    fun observeAttachments(conversationId: String): Flow<List<MessageAttachmentEntity>>

    @Query("SELECT * FROM message_attachments WHERE conversationId = :conversationId ORDER BY orderIndex ASC")
    suspend fun getAttachments(conversationId: String): List<MessageAttachmentEntity>

    @Query("SELECT * FROM message_attachments WHERE messageId = :messageId ORDER BY orderIndex ASC")
    suspend fun getAttachmentsForMessage(messageId: String): List<MessageAttachmentEntity>

    @Query("SELECT * FROM message_attachments WHERE id = :attachmentId LIMIT 1")
    suspend fun getAttachment(attachmentId: String): MessageAttachmentEntity?

    @Insert
    suspend fun insertAttachments(attachments: List<MessageAttachmentEntity>)

    /** 文件去留只按库里的事实决定（MULTIMODAL_PLAN 3.1）：返回仍然存在的那部分 id。 */
    @Query("SELECT id FROM message_attachments WHERE id IN (:attachmentIds)")
    suspend fun getExistingAttachmentIds(attachmentIds: List<String>): List<String>

    /** 启动孤儿扫描用：库里仍被引用的全部文件。 */
    @Query("SELECT relativePath FROM message_attachments")
    suspend fun getAllAttachmentPaths(): List<String>

    @Query("DELETE FROM message_attachments WHERE id IN (:attachmentIds)")
    suspend fun deleteAttachmentsByIds(attachmentIds: List<String>)

    /**
     * 用户消息与它的图片在同一个事务里落库：不会出现「消息在、图片行还没插」的中间态，
     * 也不会出现图片行指向不存在的消息。
     */
    @Transaction
    suspend fun insertUserMessageWithAttachments(
        message: MessageEntity,
        attachments: List<MessageAttachmentEntity>,
        updatedAtMillis: Long,
    ) {
        require(attachments.all { it.messageId == message.id && it.conversationId == message.conversationId }) {
            "图片不属于这条消息"
        }
        upsertMessage(message)
        if (attachments.isNotEmpty()) insertAttachments(attachments)
        touchConversation(message.conversationId, updatedAtMillis)
    }

    @Query("DELETE FROM world_settings WHERE id = :worldSettingId")
    suspend fun deleteWorldSetting(worldSettingId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clearMessages(conversationId: String)

    @Query("DELETE FROM rp_status_panels WHERE conversationId = :conversationId")
    suspend fun deleteRpStatusPanel(conversationId: String)

    @Query("UPDATE conversations SET title = :title, updatedAtMillis = :updatedAtMillis WHERE id = :conversationId")
    suspend fun updateConversationTitle(conversationId: String, title: String, updatedAtMillis: Long)

    /** 迟到的 AI 标题不得覆盖请求期间的会话修改；检查与写入必须在同一条 SQL 内完成。 */
    @Query(
        """
        UPDATE conversations SET title = :title, updatedAtMillis = :updatedAtMillis
        WHERE id = :conversationId AND title = :expectedTitle AND updatedAtMillis = :expectedUpdatedAtMillis
        """,
    )
    suspend fun updateConversationTitleIfUnchanged(
        conversationId: String,
        expectedTitle: String,
        expectedUpdatedAtMillis: Long,
        title: String,
        updatedAtMillis: Long,
    ): Int

    @Query("UPDATE conversations SET updatedAtMillis = :updatedAtMillis WHERE id = :conversationId")
    suspend fun touchConversation(conversationId: String, updatedAtMillis: Long)

    @Query("DELETE FROM messages WHERE id IN (:messageIds)")
    suspend fun deleteMessagesByIds(messageIds: List<String>)

    @Query("DELETE FROM message_rp_metadata WHERE messageId IN (:messageIds)")
    suspend fun deleteMessageRpMetadataByMessageIds(messageIds: List<String>)


    @Query(
        """
        DELETE FROM messages
        WHERE id LIKE :idPattern
            AND status != :completedStatus
            AND updatedAtMillis <= :beforeMillis
        """,
    )
    suspend fun deleteStaleHistoryReplacementMessages(
        idPattern: String,
        completedStatus: MessageStatus,
        beforeMillis: Long,
    )

    @Query(
        """
        DELETE FROM messages
        WHERE id = :messageId
            AND conversationId = :conversationId
            AND id LIKE :idPattern
            AND status != :completedStatus
        """,
    )
    suspend fun deleteIncompleteHistoryReplacementMessage(
        messageId: String,
        conversationId: String,
        idPattern: String,
        completedStatus: MessageStatus,
    ): Int

    /** 只回收一个 GenerationSession 拥有的普通消息，绝不扫到其他会话。 */
    @Query(
        """
        UPDATE messages
        SET status = :status, errorMessage = :errorMessage, updatedAtMillis = :updatedAtMillis
        WHERE id = :messageId
            AND conversationId = :conversationId
            AND status IN (:pendingStatus, :streamingStatus)
        """,
    )
    suspend fun updateGenerationMessageStatus(
        messageId: String,
        conversationId: String,
        pendingStatus: MessageStatus,
        streamingStatus: MessageStatus,
        status: MessageStatus,
        errorMessage: String?,
        updatedAtMillis: Long,
    ): Int

    /**
     * 普通发送本地预检失败时，仅当原用户消息仍存在且未被清空/删除时插入失败回复。
     * 与 clear/delete 串行化在 SQLite 事务边界，避免留下没有用户消息的孤立 assistant。
     */
    @Transaction
    suspend fun insertFailedAssistantIfUserPresent(
        expectedUserMessage: MessageEntity,
        failedAssistant: MessageEntity,
        updatedAtMillis: Long,
    ): Boolean {
        if (getConversation(expectedUserMessage.conversationId) == null) return false
        if (getMessage(expectedUserMessage.id) != expectedUserMessage) return false
        upsertMessage(failedAssistant)
        touchConversation(expectedUserMessage.conversationId, updatedAtMillis)
        return true
    }

    /**
     * 把已成功生成的临时回复原子切换为正式历史。
     *
     * 网络请求绝不能进入本事务；调用方必须先在事务外完成生成和本地解析。
     * [expectedMessages] 是开始生成时的正式历史快照，用来阻止生成期间的并发编辑被静默覆盖。
     */
    @Transaction
    suspend fun commitHistoryReplacement(
        conversationId: String,
        expectedMessages: List<MessageEntity>,
        obsoleteMessageIds: List<String>,
        editedUserMessage: MessageEntity?,
        completedAssistant: MessageEntity,
        rpStatusPanel: RpStatusPanelEntity?,
        updatedAtMillis: Long,
        expectedRpSettings: ConversationRpSettingsEntity? = null,
        expectedRoleCard: RoleCardEntity? = null,
        expectedStatusPanel: RpStatusPanelEntity? = null,
        rpMetadataSnapshot: MessageRpMetadataEntity? = null,
        tokenUsage: ConversationTokenUsageEntity? = null,
        /**
         * 开始生成时会话的全部附件；非 null 时与库里比对，生成期间附件变了（例如另一处编辑）就拒绝提交。
         * 消息快照只覆盖 messages 表，附件必须单独比对。
         */
        expectedAttachments: List<MessageAttachmentEntity>? = null,
        /** 编辑时移除的原附件；行删除后文件由调用方查库确认再删。 */
        removedAttachmentIds: List<String> = emptyList(),
        /** 编辑时新增的附件；文件已在提交前移入正式目录，提交后才被引用。 */
        addedAttachments: List<MessageAttachmentEntity> = emptyList(),
    ): RpStateCommitResult {
        val currentOfficialMessages = getMessages(conversationId)
            .filterNot { it.id == completedAssistant.id }
        check(currentOfficialMessages == expectedMessages) { "生成期间历史已发生变化，请重试" }
        if (expectedAttachments != null) {
            check(getAttachments(conversationId).sortedBy { it.id } == expectedAttachments.sortedBy { it.id }) {
                "生成期间图片已发生变化，请重试"
            }
        }
        require(addedAttachments.all { it.conversationId == conversationId && it.messageId == editedUserMessage?.id }) {
            "新增图片不属于被编辑的消息"
        }
        require(completedAssistant.conversationId == conversationId) { "替换回复不属于目标会话" }
        require(completedAssistant.status == MessageStatus.COMPLETED) { "替换回复尚未完成" }
        require(
            rpStatusPanel == null || rpStatusPanel.conversationId == conversationId,
        ) { "RP 状态栏不属于目标会话" }
        require(
            rpMetadataSnapshot == null || (
                rpMetadataSnapshot.messageId == completedAssistant.id &&
                    rpMetadataSnapshot.conversationId == conversationId
                ),
        ) { "RP 状态快照不属于替换回复" }
        require(
            tokenUsage == null || tokenUsage.conversationId == conversationId,
        ) { "token 用量不属于目标会话" }

        if (obsoleteMessageIds.isNotEmpty()) {
            deleteMessagesByIds(obsoleteMessageIds)
        }
        if (editedUserMessage != null) {
            updateMessage(editedUserMessage)
        }
        if (removedAttachmentIds.isNotEmpty()) {
            deleteAttachmentsByIds(removedAttachmentIds)
        }
        if (addedAttachments.isNotEmpty()) {
            insertAttachments(addedAttachments)
        }
        upsertMessage(completedAssistant)
        // 编辑历史会作废摘要，但**不**作废用量：那是一次真实发生过的请求，
        // 用户仍然应该看得到上次实际花了多少，界面会标注它属于哪个模型和时间。
        tokenUsage?.let { upsertConversationTokenUsage(it) }

        val result = applyRpStateOfLatestReply(
            conversationId = conversationId,
            expectedRpSettings = expectedRpSettings,
            expectedRoleCard = expectedRoleCard,
            expectedStatusPanel = expectedStatusPanel,
            rpStatusPanel = rpStatusPanel,
            rpMetadataSnapshot = rpMetadataSnapshot,
        )
        deleteConversationContext(conversationId)
        touchConversation(conversationId, updatedAtMillis)
        return result
    }

    /**
     * 普通回复完整生成后的唯一提交点。正文完成不依赖 RP 配置仍保持不变；配置快照失效时
     * 只跳过旧 panel/metadata，避免覆盖用户刚切换的新角色状态。
     */
    @Transaction
    suspend fun completeAssistantAndApplyRpState(
        completedAssistant: MessageEntity,
        expectedRpSettings: ConversationRpSettingsEntity?,
        expectedRoleCard: RoleCardEntity?,
        expectedStatusPanel: RpStatusPanelEntity?,
        rpStatusPanel: RpStatusPanelEntity?,
        rpMetadataSnapshot: MessageRpMetadataEntity?,
        tokenUsage: ConversationTokenUsageEntity?,
        updatedAtMillis: Long,
    ): RpStateCommitResult {
        val conversationId = completedAssistant.conversationId
        check(getConversation(conversationId) != null) { "会话已被删除" }
        val persisted = getMessage(completedAssistant.id) ?: error("生成消息已被删除")
        check(persisted.conversationId == conversationId) { "生成消息不属于目标会话" }
        check(
            persisted.status == MessageStatus.PENDING || persisted.status == MessageStatus.STREAMING,
        ) { "生成消息状态已发生变化" }
        require(completedAssistant.status == MessageStatus.COMPLETED) { "回复尚未完成" }
        require(
            rpStatusPanel == null || rpStatusPanel.conversationId == conversationId,
        ) { "RP 状态栏不属于目标会话" }
        require(
            rpMetadataSnapshot == null || (
                rpMetadataSnapshot.messageId == completedAssistant.id &&
                    rpMetadataSnapshot.conversationId == conversationId
                ),
        ) { "RP 状态快照不属于完成回复" }
        require(
            tokenUsage == null || tokenUsage.conversationId == conversationId,
        ) { "token 用量不属于目标会话" }

        updateMessage(completedAssistant)
        // 用量必须和「回复已完成」一起提交：分两步写会出现回复成功但用量丢失，
        // 或用量已更新而回复其实被回滚。传 null 表示上游没给用量，此时保留上一份有效快照。
        tokenUsage?.let { upsertConversationTokenUsage(it) }
        val result = applyRpStateOfLatestReply(
            conversationId = conversationId,
            expectedRpSettings = expectedRpSettings,
            expectedRoleCard = expectedRoleCard,
            expectedStatusPanel = expectedStatusPanel,
            rpStatusPanel = rpStatusPanel,
            rpMetadataSnapshot = rpMetadataSnapshot,
        )
        touchConversation(conversationId, updatedAtMillis)
        return result
    }

    /**
     * 保存单条回复的 RP 补写结果。消息正文/版本和 RP 配置任一发生变化时整次丢弃；
     * metadata 仍可落到自己的历史消息；会话级状态栏只有在面板仍是 [expectedStatusPanel]、
     * 且本条之后既没有完成的回复、也没有在途的下一轮时才会改写。
     *
     * 为什么要让路给在途的下一轮：用户在补写期间就发了下一句时，下一轮已经按补写前的面板
     * 冻结了输入。此时旧补写再改面板，下一轮提交会因面板变了而丢掉自己的状态更新——
     * 宁可让旧补写只落到它自己的消息快照上。下一轮已失败或已取消时不再让路，
     * 否则之后直接发新消息会悄悄丢掉这次补写的状态。
     */
    @Transaction
    suspend fun commitRpEnrichment(
        expectedMessage: MessageEntity,
        expectedRpSettings: ConversationRpSettingsEntity?,
        expectedRoleCard: RoleCardEntity?,
        expectedStatusPanel: RpStatusPanelEntity?,
        metadata: MessageRpMetadataEntity?,
        statusPanel: RpStatusPanelEntity?,
        missingStateMessage: String?,
        updatedAtMillis: Long,
        expectedMetadata: MessageRpMetadataEntity? = null,
    ): Boolean {
        if (getConversation(expectedMessage.conversationId) == null) return false
        if (getMessage(expectedMessage.id) != expectedMessage) return false
        if (!matchesRpConfiguration(
                expectedMessage.conversationId,
                expectedRpSettings,
                expectedRoleCard,
            )
        ) {
            return false
        }
        if (metadata != null && (
                metadata.messageId != expectedMessage.id ||
                    metadata.conversationId != expectedMessage.conversationId
                )
        ) {
            return false
        }
        if (statusPanel != null && statusPanel.conversationId != expectedMessage.conversationId) return false

        val laterDialogue = getMessages(expectedMessage.conversationId)
            .dropWhile { it.id != expectedMessage.id }
            .drop(1)
            .filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
        // 之后已有回复完成，面板的变化（至少部分）来自那条回复，不能记进本条的快照，
        // 否则从本条回滚会回到后一轮之后的状态。
        // 依赖的前提：没有删除单条消息的功能，已完成的回复也不会回退成其它状态。
        val laterReplyCommitted = laterDialogue.any {
            it.role == ChatRole.ASSISTANT && it.status == MessageStatus.COMPLETED
        }
        // 下一轮已冻结了补写前的面板：还没回复的用户消息（本轮快照在建回复行之前就已捕获），
        // 或仍在生成的回复。已失败 / 已取消的回复不算——它们不会再提交，不必为它们让路。
        val laterTurnInFlight = laterDialogue.lastOrNull()?.role == ChatRole.USER ||
            laterDialogue.any {
                it.role == ChatRole.ASSISTANT &&
                    (it.status == MessageStatus.PENDING || it.status == MessageStatus.STREAMING)
            }
        val currentPanel = getRpStatusPanel(expectedMessage.conversationId)
        val panelUnchanged = currentPanel == expectedStatusPanel
        val ownMetadata = getMessageRpMetadataForMessage(expectedMessage.id)
        val ownSnapshotChanged = ownMetadata != expectedMetadata
        // 调度补写前的读库也可能与手动修正交错：期望 metadata 已读到修正，冻结面板却仍是旧的。
        // 这种情况下自身检查点是更晚的事实；即使随后一轮已完成，也不得重新用旧请求计算的状态替换。
        val ownSnapshotAlreadyCorrected = expectedMetadata != null && !expectedMetadata.hasStatusOf(expectedStatusPanel)
        if (metadata != null) {
            // 手动修正更新的是这条回复自己的检查点；即使后一轮已经完成，也必须保留它。
            // 不能从当前面板反推这条历史回复的状态，否则会把未来一轮写进过去。
            val snapshot = when {
                ownSnapshotChanged || ownSnapshotAlreadyCorrected -> metadata.copy(
                    metricsJson = ownMetadata?.metricsJson ?: EMPTY_METRICS_JSON,
                    relationship = ownMetadata?.relationship.orEmpty(),
                    scene = ownMetadata?.scene.orEmpty(),
                    rawJson = ownMetadata?.rawJson.orEmpty(),
                )
                !panelUnchanged && !laterReplyCommitted -> metadata.withStatusOf(currentPanel)
                else -> metadata
            }
            upsertMessageRpMetadata(
                snapshot,
            )
        }
        val panelWritten = statusPanel != null && panelUnchanged && !ownSnapshotChanged && !ownSnapshotAlreadyCorrected &&
            !laterReplyCommitted && !laterTurnInFlight
        if (statusPanel != null && panelWritten) {
            upsertRpStatusPanel(statusPanel)
        }
        if (missingStateMessage != null) {
            updateMessage(
                expectedMessage.copy(
                    errorMessage = missingStateMessage,
                    updatedAtMillis = updatedAtMillis,
                ),
            )
        }
        if (metadata != null || panelWritten || missingStateMessage != null) {
            touchConversation(expectedMessage.conversationId, updatedAtMillis)
        }
        return true
    }

    /** 编辑 assistant 后保留正文历史，但原子清除编辑点及其后的派生状态并回滚当前面板。 */
    @Transaction
    suspend fun editAssistantAndInvalidateDerivedData(
        expectedMessages: List<MessageEntity>,
        editedMessage: MessageEntity,
        expectedRpSettings: ConversationRpSettingsEntity?,
        expectedRoleCard: RoleCardEntity?,
        affectedAssistantMessageIds: List<String>,
        downstreamCompletedAssistantIds: List<String>,
        rollbackStatusPanel: RpStatusPanelEntity?,
        downstreamNotice: String,
        updatedAtMillis: Long,
    ): Boolean {
        val conversationId = editedMessage.conversationId
        if (getMessages(conversationId) != expectedMessages) return false
        val original = expectedMessages.firstOrNull { it.id == editedMessage.id } ?: return false
        if (original.role != ChatRole.ASSISTANT) return false
        if (!matchesRpConfiguration(conversationId, expectedRpSettings, expectedRoleCard)) return false

        if (affectedAssistantMessageIds.isNotEmpty()) {
            deleteMessageRpMetadataByMessageIds(affectedAssistantMessageIds)
        }
        updateMessage(editedMessage)
        downstreamCompletedAssistantIds.forEach { messageId ->
            getMessage(messageId)?.let { message ->
                updateMessage(
                    message.copy(
                        errorMessage = downstreamNotice,
                        updatedAtMillis = updatedAtMillis,
                    ),
                )
            }
        }
        // 暂停仅改变生成方式；人工改写历史仍传入切点状态，不能保留已失效的未来状态。
        // 没有任何绑定档案时才可能没有切点面板。
        rollbackStatusPanel?.let { upsertRpStatusPanel(it) }
        deleteConversationContext(conversationId)
        touchConversation(conversationId, updatedAtMillis)
        return true
    }

    /** 清空正式历史时，所有由该历史派生的表必须在同一个事务里失效。 */
    @Transaction
    suspend fun clearConversationAndDerivedData(conversationId: String, updatedAtMillis: Long) {
        clearMessages(conversationId)
        deleteRpStatusPanel(conversationId)
        deleteConversationContext(conversationId)
        // 历史被清空后，「上次实际用量」已经没有任何可解释的对象，留着只会误导。
        deleteConversationTokenUsage(conversationId)
        touchConversation(conversationId, updatedAtMillis)
    }

}

/**
 * 最新一条回复的 RP 派生数据的唯一写入规则，普通完成与历史替换共用。
 *
 * [rpStatusPanel] 为 null 表示本轮不改面板：普通完成时是正文没带状态（等补写），
 * 历史替换时是没有可用绑定——暂停 RP 本身不重置，但暂停期间历史变更仍传入切点状态，
 * 角色卡没有状态条也一样（关系与场景要回滚）。
 */
private suspend fun ChatDao.applyRpStateOfLatestReply(
    conversationId: String,
    expectedRpSettings: ConversationRpSettingsEntity?,
    expectedRoleCard: RoleCardEntity?,
    expectedStatusPanel: RpStatusPanelEntity?,
    rpStatusPanel: RpStatusPanelEntity?,
    rpMetadataSnapshot: MessageRpMetadataEntity?,
): RpStateCommitResult {
    if (!matchesRpConfiguration(conversationId, expectedRpSettings, expectedRoleCard)) {
        return RpStateCommitResult.CONFIGURATION_CHANGED
    }
    val currentPanel = getRpStatusPanel(conversationId)
    if (currentPanel != expectedStatusPanel) {
        // 保留库里的面板；这条回复的历史快照记录的也应该是它，理由同 commitRpEnrichment。
        rpMetadataSnapshot?.let { upsertMessageRpMetadata(it.withStatusOf(currentPanel)) }
        return RpStateCommitResult.STATUS_PANEL_CHANGED
    }
    rpStatusPanel?.let { upsertRpStatusPanel(it) }
    rpMetadataSnapshot?.let { upsertMessageRpMetadata(it) }
    return RpStateCommitResult.APPLIED
}

/** 用面板的状态字段替换快照里的状态；面板不存在时快照不再携带状态。 */
private fun MessageRpMetadataEntity.withStatusOf(panel: RpStatusPanelEntity?): MessageRpMetadataEntity = copy(
    metricsJson = panel?.metricsJson ?: EMPTY_METRICS_JSON,
    relationship = panel?.relationship.orEmpty(),
    scene = panel?.scene.orEmpty(),
    rawJson = panel?.rawJson.orEmpty(),
)

private fun MessageRpMetadataEntity.hasStatusOf(panel: RpStatusPanelEntity?): Boolean =
    metricsJson == (panel?.metricsJson ?: EMPTY_METRICS_JSON) &&
        relationship == panel?.relationship.orEmpty() && scene == panel?.scene.orEmpty() &&
        rawJson == panel?.rawJson.orEmpty()

private const val EMPTY_METRICS_JSON = "[]"

private suspend fun ChatDao.matchesRpConfiguration(
    conversationId: String,
    expectedRpSettings: ConversationRpSettingsEntity?,
    expectedRoleCard: RoleCardEntity?,
): Boolean {
    val currentRpSettings = getConversationRpSettings(conversationId)
    if (currentRpSettings != expectedRpSettings) return false
    val activeRoleCardId = currentRpSettings
        ?.takeIf { it.isRpEnabled || expectedRoleCard != null }
        ?.roleCardId
    return activeRoleCardId?.let { getRoleCard(it) } == expectedRoleCard
}
