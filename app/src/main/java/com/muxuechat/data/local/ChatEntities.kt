package com.muxuechat.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.muxuechat.domain.model.ChatMessage
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.ConversationRpSettings
import com.muxuechat.domain.model.Conversation
import com.muxuechat.domain.model.MessageStatus
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RpMarkerMode
import com.muxuechat.domain.model.RpMetric
import com.muxuechat.domain.model.RpMetricDefinition
import com.muxuechat.domain.model.RpMessageMetadata
import com.muxuechat.domain.model.RpNarrativePerspective
import com.muxuechat.domain.model.RpPlotSuggestionPerspective
import com.muxuechat.domain.model.RpStatusPanel
import com.muxuechat.domain.model.WorldSetting
import com.muxuechat.domain.model.WritingStyleProfile
import com.muxuechat.domain.context.ContextSummaryState
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    fun toDomain() = Conversation(id, title, createdAtMillis, updatedAtMillis)
}

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: ChatRole,
    val content: String,
    val reasoningContent: String?,
    val status: MessageStatus,
    val errorMessage: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    fun toDomain(rpMetadata: RpMessageMetadata? = null) = ChatMessage(
        id = id,
        conversationId = conversationId,
        role = role,
        content = content,
        reasoningContent = reasoningContent,
        status = status,
        errorMessage = errorMessage,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        rpMetadata = rpMetadata,
    )
}

fun Conversation.toEntity() = ConversationEntity(id, title, createdAtMillis, updatedAtMillis)

fun ChatMessage.toEntity() = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    reasoningContent = reasoningContent,
    status = status,
    errorMessage = errorMessage,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

@Entity(tableName = "role_cards")
data class RoleCardEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val persona: String,
    val speakingStyle: String,
    val userIdentity: String,
    val openingMessage: String,
    val boundaries: String,
    val metricDefinitionsJson: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    fun toDomain() = RoleCard(
        id = id,
        name = name,
        description = description,
        persona = persona,
        speakingStyle = speakingStyle,
        userIdentity = userIdentity,
        openingMessage = openingMessage,
        boundaries = boundaries,
        metricDefinitions = runCatching {
            entityJson.decodeFromString<List<RpMetricDefinition>>(metricDefinitionsJson)
        }.getOrDefault(emptyList()),
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )
}

@Entity(tableName = "world_settings")
data class WorldSettingEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    fun toDomain() = WorldSetting(
        id = id,
        title = title,
        content = content,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )
}

@Entity(tableName = "writing_style_profiles")
data class WritingStyleProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val content: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    fun toDomain() = WritingStyleProfile(
        id = id,
        name = name,
        content = content,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )
}

/**
 * 会话的上下文压缩状态。
 *
 * 单独建表而不是加到 `conversations` 上：摘要正文可能上千字，
 * 而会话列表是常驻 Flow，没必要让抽屉每次刷新都把所有摘要读进内存。
 */
@Entity(
    tableName = "conversation_context",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ConversationContextEntity(
    @PrimaryKey val conversationId: String,
    val summary: String,
    /** 摘要已覆盖到该会话历史的前多少条消息。 */
    val coveredMessageCount: Int,
    val updatedAtMillis: Long,
) {
    fun toDomain() = ContextSummaryState(
        summary = summary,
        coveredMessageCount = coveredMessageCount,
    )
}

@Entity(
    tableName = "conversation_rp_settings",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RoleCardEntity::class,
            parentColumns = ["id"],
            childColumns = ["roleCardId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = WorldSettingEntity::class,
            parentColumns = ["id"],
            childColumns = ["worldSettingId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        // 与角色卡/世界观保持一致：删除文风后，会话上的绑定自动变为 null。
        ForeignKey(
            entity = WritingStyleProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["writingStyleId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("roleCardId"), Index("worldSettingId"), Index("writingStyleId")],
)
data class ConversationRpSettingsEntity(
    @PrimaryKey val conversationId: String,
    val isRpEnabled: Boolean,
    val roleCardId: String?,
    val worldSettingId: String?,
    val writingStyleId: String? = null,
    val protagonistName: String,
    val protagonistDescription: String,
    val protagonistInitialState: String,
    val narrativeMinChars: Int,
    val narrativePerspective: String,
    val plotSuggestionPerspective: String,
    val rpMarkerMode: String,
    val updatedAtMillis: Long,
) {
    fun toDomain() = ConversationRpSettings(
        conversationId = conversationId,
        isRpEnabled = isRpEnabled,
        roleCardId = roleCardId,
        worldSettingId = worldSettingId,
        writingStyleId = writingStyleId,
        protagonistName = protagonistName,
        protagonistDescription = protagonistDescription,
        protagonistInitialState = protagonistInitialState,
        narrativeMinChars = narrativeMinChars,
        narrativePerspective = RpNarrativePerspective.fromStorageValue(narrativePerspective),
        plotSuggestionPerspective = RpPlotSuggestionPerspective.fromStorageValue(plotSuggestionPerspective),
        rpMarkerMode = RpMarkerMode.fromStorageValue(rpMarkerMode),
        updatedAtMillis = updatedAtMillis,
    )
}

@Entity(
    tableName = "rp_status_panels",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RpStatusPanelEntity(
    @PrimaryKey val conversationId: String,
    val metricsJson: String,
    val relationship: String,
    val scene: String,
    val rawJson: String,
    val updatedAtMillis: Long,
) {
    fun toDomain() = RpStatusPanel(
        conversationId = conversationId,
        metrics = runCatching { entityJson.decodeFromString<List<RpMetric>>(metricsJson) }.getOrDefault(emptyList()),
        relationship = relationship,
        scene = scene,
        rawJson = rawJson,
        updatedAtMillis = updatedAtMillis,
    )
}

@Entity(
    tableName = "message_rp_metadata",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageRpMetadataEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val optionsJson: String,
    val innerThought: String,
    val metricsJson: String,
    val relationship: String,
    val scene: String,
    val rawJson: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    fun toDomain(): RpMessageMetadata {
        val metrics = runCatching { entityJson.decodeFromString<List<RpMetric>>(metricsJson) }.getOrDefault(emptyList())
        val hasStatusSnapshot = metrics.isNotEmpty() ||
            relationship.isNotBlank() ||
            scene.isNotBlank() ||
            rawJson.isNotBlank()
        return RpMessageMetadata(
            messageId = messageId,
            conversationId = conversationId,
            innerThought = innerThought,
            options = runCatching { entityJson.decodeFromString<List<String>>(optionsJson) }.getOrDefault(emptyList()),
            statusPanelSnapshot = if (hasStatusSnapshot) {
                RpStatusPanel(
                    conversationId = conversationId,
                    metrics = metrics,
                    relationship = relationship,
                    scene = scene,
                    rawJson = rawJson,
                    updatedAtMillis = updatedAtMillis,
                )
            } else {
                null
            },
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
        )
    }
}

fun RoleCard.toEntity() = RoleCardEntity(
    id = id,
    name = name,
    description = description,
    persona = persona,
    speakingStyle = speakingStyle,
    userIdentity = userIdentity,
    openingMessage = openingMessage,
    boundaries = boundaries,
    metricDefinitionsJson = entityJson.encodeToString(metricDefinitions),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun WorldSetting.toEntity() = WorldSettingEntity(
    id = id,
    title = title,
    content = content,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun ConversationRpSettings.toEntity() = ConversationRpSettingsEntity(
    conversationId = conversationId,
    isRpEnabled = isRpEnabled,
    roleCardId = roleCardId,
    worldSettingId = worldSettingId,
    protagonistName = protagonistName,
    protagonistDescription = protagonistDescription,
    protagonistInitialState = protagonistInitialState,
    narrativeMinChars = narrativeMinChars,
    narrativePerspective = narrativePerspective.storageValue,
    plotSuggestionPerspective = plotSuggestionPerspective.storageValue,
    rpMarkerMode = rpMarkerMode.storageValue,
    updatedAtMillis = updatedAtMillis,
)

fun RpStatusPanel.toEntity() = RpStatusPanelEntity(
    conversationId = conversationId,
    metricsJson = entityJson.encodeToString(metrics),
    relationship = relationship,
    scene = scene,
    rawJson = rawJson,
    updatedAtMillis = updatedAtMillis,
)

fun RpMessageMetadata.toEntity() = MessageRpMetadataEntity(
    messageId = messageId,
    conversationId = conversationId,
    optionsJson = entityJson.encodeToString(options),
    innerThought = innerThought,
    metricsJson = entityJson.encodeToString(statusPanelSnapshot?.metrics.orEmpty()),
    relationship = statusPanelSnapshot?.relationship.orEmpty(),
    scene = statusPanelSnapshot?.scene.orEmpty(),
    rawJson = statusPanelSnapshot?.rawJson.orEmpty(),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

private val entityJson = Json { ignoreUnknownKeys = true }
