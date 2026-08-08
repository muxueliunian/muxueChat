package com.muxuechat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.muxuechat.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAtMillis DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtMillis ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM message_rp_metadata WHERE conversationId = :conversationId")
    fun observeMessageRpMetadata(conversationId: String): Flow<List<MessageRpMetadataEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtMillis ASC")
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWritingStyleProfile(profile: WritingStyleProfileEntity)

    @Query("DELETE FROM writing_style_profiles WHERE id = :writingStyleId")
    suspend fun deleteWritingStyleProfile(writingStyleId: String)

    @Query("SELECT * FROM conversation_context WHERE conversationId = :conversationId LIMIT 1")
    fun observeConversationContext(conversationId: String): Flow<ConversationContextEntity?>

    @Query("SELECT * FROM conversation_context WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getConversationContext(conversationId: String): ConversationContextEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversationContext(context: ConversationContextEntity)

    @Query("DELETE FROM conversation_context WHERE conversationId = :conversationId")
    suspend fun deleteConversationContext(conversationId: String)

    @Query("SELECT * FROM role_cards WHERE id = :roleCardId LIMIT 1")
    suspend fun getRoleCard(roleCardId: String): RoleCardEntity?

    @Query("SELECT * FROM world_settings WHERE id = :worldSettingId LIMIT 1")
    suspend fun getWorldSetting(worldSettingId: String): WorldSettingEntity?

    @Query("SELECT * FROM conversation_rp_settings WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getConversationRpSettings(conversationId: String): ConversationRpSettingsEntity?

    @Query("SELECT * FROM rp_status_panels WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getRpStatusPanel(conversationId: String): RpStatusPanelEntity?

    @Query(
        """
        SELECT metadata.* FROM message_rp_metadata metadata
        INNER JOIN messages message ON message.id = metadata.messageId
        WHERE metadata.conversationId = :conversationId
            AND message.createdAtMillis < :createdAtMillis
        ORDER BY message.createdAtMillis DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestMessageRpMetadataBefore(
        conversationId: String,
        createdAtMillis: Long,
    ): MessageRpMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoleCard(roleCard: RoleCardEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorldSetting(worldSetting: WorldSettingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversationRpSettings(settings: ConversationRpSettingsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRpStatusPanel(panel: RpStatusPanelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessageRpMetadata(metadata: MessageRpMetadataEntity)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM role_cards WHERE id = :roleCardId")
    suspend fun deleteRoleCard(roleCardId: String)

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

    @Query("UPDATE messages SET status = :status, errorMessage = :errorMessage, updatedAtMillis = :updatedAtMillis WHERE status = :fromStatus")
    suspend fun updateAllMessagesWithStatus(
        fromStatus: MessageStatus,
        status: MessageStatus,
        errorMessage: String?,
        updatedAtMillis: Long,
    )
}
