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
    val statusPanel: RpStatusPanelEntity?,
    val statusBeforeMetadata: MessageRpMetadataEntity?,
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
    ): RpTurnConfigurationSnapshot {
        val settings = getConversationRpSettings(conversationId)
        val roleCard = settings
            ?.takeIf { it.isRpEnabled }
            ?.roleCardId
            ?.let { getRoleCard(it) }
        return RpTurnConfigurationSnapshot(
            settings = settings,
            roleCard = roleCard,
            statusPanel = roleCard?.let { getRpStatusPanel(conversationId) },
            statusBeforeMetadata = statusBeforeMessageId?.let {
                getLatestMessageRpMetadataBeforeMessage(conversationId, it)
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

    @Query("SELECT conversationId FROM conversation_rp_settings WHERE roleCardId = :roleCardId")
    suspend fun getConversationIdsByRoleCard(roleCardId: String): List<String>

    @Query(
        """
        DELETE FROM rp_status_panels
        WHERE conversationId IN (
            SELECT conversationId FROM conversation_rp_settings WHERE roleCardId = :roleCardId
        )
        """,
    )
    suspend fun deleteRpStatusPanelsBoundToRoleCard(roleCardId: String)

    @Query(
        """
        UPDATE conversation_rp_settings
        SET roleCardId = NULL, isRpEnabled = 0, updatedAtMillis = :updatedAtMillis
        WHERE roleCardId = :roleCardId
        """,
    )
    suspend fun disableRpForRoleCard(roleCardId: String, updatedAtMillis: Long)

    @Query("UPDATE conversations SET updatedAtMillis = :updatedAtMillis WHERE id IN (:conversationIds)")
    suspend fun touchConversations(conversationIds: List<String>, updatedAtMillis: Long)

    /** 仅供 [deleteRoleCardAndResetBindings] 在同一事务内执行最终父记录删除。 */
    @Query("DELETE FROM role_cards WHERE id = :roleCardId")
    suspend fun deleteRoleCardRow(roleCardId: String)

    /**
     * 删除角色卡前关闭所有仍绑定它的 RP 会话，并清除当前状态栏。
     *
     * 历史消息及 message_rp_metadata 刻意保留，只用于历史展示；由于 RP 已关闭且当前状态栏已删除，
     * 它们不会继续进入后续请求的当前状态。
     */
    @Transaction
    suspend fun deleteRoleCardAndResetBindings(roleCardId: String, updatedAtMillis: Long) {
        val affectedConversationIds = getConversationIdsByRoleCard(roleCardId)
        deleteRpStatusPanelsBoundToRoleCard(roleCardId)
        disableRpForRoleCard(roleCardId, updatedAtMillis)
        if (affectedConversationIds.isNotEmpty()) {
            touchConversations(affectedConversationIds, updatedAtMillis)
        }
        deleteRoleCardRow(roleCardId)
    }

    @Query("DELETE FROM world_settings WHERE id = :worldSettingId")
    suspend fun deleteWorldSetting(worldSettingId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clearMessages(conversationId: String)

    @Query("DELETE FROM rp_status_panels WHERE conversationId = :conversationId")
    suspend fun deleteRpStatusPanel(conversationId: String)

    @Query("UPDATE conversations SET title = :title, updatedAtMillis = :updatedAtMillis WHERE id = :conversationId")
    suspend fun updateConversationTitle(conversationId: String, title: String, updatedAtMillis: Long)

    @Query("UPDATE conversations SET updatedAtMillis = :updatedAtMillis WHERE id = :conversationId")
    suspend fun touchConversation(conversationId: String, updatedAtMillis: Long)

    @Query("DELETE FROM messages WHERE id IN (:messageIds)")
    suspend fun deleteMessagesByIds(messageIds: List<String>)

    @Query("DELETE FROM message_rp_metadata WHERE messageId IN (:messageIds)")
    suspend fun deleteMessageRpMetadataByMessageIds(messageIds: List<String>)

    /**
     * RP 配置或角色卡语义改变后，旧状态快照不再能用于当前角色的历史回滚。
     * 保留内心与剧情选项，仅清空会话状态字段，避免丢失仍可安全展示的历史内容。
     */
    @Query(
        """
        UPDATE message_rp_metadata
        SET metricsJson = '[]', relationship = '', scene = '', rawJson = '', updatedAtMillis = :updatedAtMillis
        WHERE conversationId = :conversationId
            AND (
                metricsJson != '[]'
                OR relationship != ''
                OR scene != ''
                OR rawJson != ''
            )
        """,
    )
    suspend fun clearMessageRpStatusSnapshots(conversationId: String, updatedAtMillis: Long): Int

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
        rpMetadataSnapshot: MessageRpMetadataEntity? = null,
        tokenUsage: ConversationTokenUsageEntity? = null,
    ): Boolean {
        val currentOfficialMessages = getMessages(conversationId)
            .filterNot { it.id == completedAssistant.id }
        check(currentOfficialMessages == expectedMessages) { "生成期间历史已发生变化，请重试" }
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
        upsertMessage(completedAssistant)
        // 编辑历史会作废摘要，但**不**作废用量：那是一次真实发生过的请求，
        // 用户仍然应该看得到上次实际花了多少，界面会标注它属于哪个模型和时间。
        tokenUsage?.let { upsertConversationTokenUsage(it) }

        val rpSnapshotCurrent = matchesRpConfiguration(
            conversationId,
            expectedRpSettings,
            expectedRoleCard,
        )
        if (rpSnapshotCurrent) {
            if (rpStatusPanel != null) {
                upsertRpStatusPanel(rpStatusPanel)
            } else {
                deleteRpStatusPanel(conversationId)
            }
            rpMetadataSnapshot?.let { upsertMessageRpMetadata(it) }
        }
        deleteConversationContext(conversationId)
        touchConversation(conversationId, updatedAtMillis)
        return rpSnapshotCurrent
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
        rpStatusPanel: RpStatusPanelEntity?,
        rpMetadataSnapshot: MessageRpMetadataEntity?,
        tokenUsage: ConversationTokenUsageEntity?,
        updatedAtMillis: Long,
    ): Boolean {
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
        val rpSnapshotCurrent = matchesRpConfiguration(
            conversationId,
            expectedRpSettings,
            expectedRoleCard,
        )
        if (rpSnapshotCurrent) {
            rpStatusPanel?.let { upsertRpStatusPanel(it) }
            rpMetadataSnapshot?.let { upsertMessageRpMetadata(it) }
        }
        touchConversation(conversationId, updatedAtMillis)
        return rpSnapshotCurrent
    }

    /**
     * 保存单条回复的 RP 补写结果。消息正文/版本和 RP 配置任一发生变化时整次丢弃；
     * metadata 仍可落到自己的历史消息，但只有当前最新完成回复可以改写会话级状态栏。
     */
    @Transaction
    suspend fun commitRpEnrichment(
        expectedMessage: MessageEntity,
        expectedRpSettings: ConversationRpSettingsEntity?,
        expectedRoleCard: RoleCardEntity?,
        metadata: MessageRpMetadataEntity?,
        statusPanel: RpStatusPanelEntity?,
        missingStateMessage: String?,
        updatedAtMillis: Long,
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

        metadata?.let { upsertMessageRpMetadata(it) }
        val isLatestAssistant = getLatestCompletedAssistant(
            conversationId = expectedMessage.conversationId,
            assistantRole = ChatRole.ASSISTANT,
            completedStatus = MessageStatus.COMPLETED,
        )?.id == expectedMessage.id
        if (statusPanel != null && isLatestAssistant) {
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
        if (metadata != null || (statusPanel != null && isLatestAssistant) || missingStateMessage != null) {
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
        if (rollbackStatusPanel != null) {
            upsertRpStatusPanel(rollbackStatusPanel)
        } else {
            deleteRpStatusPanel(conversationId)
        }
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

private suspend fun ChatDao.matchesRpConfiguration(
    conversationId: String,
    expectedRpSettings: ConversationRpSettingsEntity?,
    expectedRoleCard: RoleCardEntity?,
): Boolean {
    val currentRpSettings = getConversationRpSettings(conversationId)
    if (currentRpSettings != expectedRpSettings) return false
    val activeRoleCardId = currentRpSettings
        ?.takeIf { it.isRpEnabled }
        ?.roleCardId
    return activeRoleCardId?.let { getRoleCard(it) } == expectedRoleCard
}
