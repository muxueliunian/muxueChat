package com.muxuechat.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.muxuechat.domain.model.ChatMessage
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.ConversationRpSettings
import com.muxuechat.domain.model.ConversationTokenUsage
import com.muxuechat.domain.model.Conversation
import com.muxuechat.domain.model.MessageStatus
import com.muxuechat.domain.model.ProtagonistProfile
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RpEntry
import com.muxuechat.domain.model.RpMarkerMode
import com.muxuechat.domain.model.RpMetric
import com.muxuechat.domain.model.RpMetricDefinition
import com.muxuechat.domain.model.RpMessageMetadata
import com.muxuechat.domain.model.RpNarrativePerspective
import com.muxuechat.domain.model.RpPlotSuggestionPerspective
import com.muxuechat.domain.model.RpStatusPanel
import com.muxuechat.domain.model.SupportingCharacter
import com.muxuechat.domain.model.WorldSetting
import com.muxuechat.domain.model.WritingStyleProfile
import com.muxuechat.domain.context.ContextSummaryState
import com.muxuechat.domain.rp.RpEntryCodec
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
    /**
     * v9 的数值定义。**已停用为取证材料**：唯一读源是 [entriesJson]。
     *
     * 保存时仍同步写入数值词条的旧形状——entriesJson 万一被写坏，这里是就地恢复的材料；
     * 迁移时解不出来的坏 JSON 也原样留在这一列，不会被覆盖成空串（§8.5）。
     * 与主角三列一起等 RP-3 收尾后删除。
     */
    val metricDefinitionsJson: String,
    @ColumnInfo(defaultValue = "[]") val entriesJson: String = "[]",
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
        entries = RpEntryCodec.decodeEntries(entriesJson).entries,
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

/**
 * 会话最近一次正文请求的真实 token 用量。
 *
 * 同样单独建表：它每轮都会被覆盖，而会话列表是常驻 Flow，没必要为此让抽屉重新发射。
 * 三个 token 列可空——上游不返回 usage 是常态，用 0 冒充会让界面撒谎。
 */
@Entity(
    tableName = "conversation_token_usage",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ConversationTokenUsageEntity(
    @PrimaryKey val conversationId: String,
    /** DeepSeek 固定为 "DEEPSEEK"，自定义供应商为其 id。切换供应商后据此标注「旧模型数据」。 */
    val providerKey: String,
    val model: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    /** 采集当时该模型的 95% 安全窗口，用于解释这份用量占了多少。 */
    val effectiveContextTokens: Long,
    val capturedAtMillis: Long,
) {
    fun toDomain() = ConversationTokenUsage(
        conversationId = conversationId,
        providerKey = providerKey,
        model = model,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        effectiveContextTokens = effectiveContextTokens,
        capturedAtMillis = capturedAtMillis,
    )
}

fun ConversationTokenUsage.toEntity() = ConversationTokenUsageEntity(
    conversationId = conversationId,
    providerKey = providerKey,
    model = model,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    totalTokens = totalTokens,
    effectiveContextTokens = effectiveContextTokens,
    capturedAtMillis = capturedAtMillis,
)

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
        // 删除主角档案后会话自动解绑，与角色卡一致。数值的清理由 applyBindingTransition 负责。
        ForeignKey(
            entity = ProtagonistProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["protagonistProfileId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("roleCardId"),
        Index("worldSettingId"),
        Index("writingStyleId"),
        Index("protagonistProfileId"),
    ],
)
data class ConversationRpSettingsEntity(
    @PrimaryKey val conversationId: String,
    val isRpEnabled: Boolean,
    val roleCardId: String?,
    val worldSettingId: String?,
    val writingStyleId: String? = null,
    val protagonistProfileId: String? = null,
    /** 本会话有效的数值定义快照（R6 / R10），见 [RpMetricSnapshot]。 */
    @ColumnInfo(defaultValue = "[]") val metricSnapshotJson: String = "[]",
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
        protagonistProfileId = protagonistProfileId,
        metricSnapshot = RpEntryCodec.decodeSnapshot(metricSnapshotJson).entries,
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
    entriesJson = RpEntryCodec.encodeEntries(entries),
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
    writingStyleId = writingStyleId,
    protagonistProfileId = protagonistProfileId,
    metricSnapshotJson = RpEntryCodec.encodeSnapshot(metricSnapshot),
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

@Entity(tableName = "protagonist_profiles")
data class ProtagonistProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val entriesJson: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    fun toDomain() = ProtagonistProfile(
        id = id,
        name = name,
        description = description,
        entries = RpEntryCodec.decodeEntries(entriesJson).entries,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )
}

@Entity(tableName = "supporting_characters")
data class SupportingCharacterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val entriesJson: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    /** 数值词条对配角没有意义，解码后直接丢弃——脏数据不该变成状态条。 */
    fun toDomain() = SupportingCharacter(
        id = id,
        name = name,
        description = description,
        entries = RpEntryCodec.decodeEntries(entriesJson).entries.filterIsInstance<RpEntry.Text>(),
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )
}

/**
 * 会话与配角的多对多关联。
 *
 * 复合主键让「同一个配角重复加进同一个会话」在库层面就不可能发生，
 * 不必在 Repository 里再写一遍去重。
 */
@Entity(
    tableName = "conversation_supporting_characters",
    primaryKeys = ["conversationId", "supportingCharacterId"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SupportingCharacterEntity::class,
            parentColumns = ["id"],
            childColumns = ["supportingCharacterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("supportingCharacterId")],
)
data class ConversationSupportingCharacterEntity(
    val conversationId: String,
    val supportingCharacterId: String,
    val orderIndex: Int,
)

fun ProtagonistProfile.toEntity() = ProtagonistProfileEntity(
    id = id,
    name = name,
    description = description,
    entriesJson = RpEntryCodec.encodeEntries(entries),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun SupportingCharacter.toEntity() = SupportingCharacterEntity(
    id = id,
    name = name,
    description = description,
    entriesJson = RpEntryCodec.encodeEntries(entries),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

private val entityJson = Json { ignoreUnknownKeys = true }
