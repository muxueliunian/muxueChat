package com.muxuechat.data.repository

import androidx.room.withTransaction
import com.muxuechat.data.local.ChatDao
import com.muxuechat.data.local.ChatDatabase
import com.muxuechat.data.local.ConversationRpSettingsEntity
import com.muxuechat.data.local.RoleCardEntity
import com.muxuechat.data.local.WorldSettingEntity
import com.muxuechat.data.local.WritingStyleProfileEntity
import com.muxuechat.data.local.toEntity
import com.muxuechat.domain.model.WritingStyleProfile
import com.muxuechat.domain.model.WritingStyleProfileDraft
import com.muxuechat.domain.model.ConversationRpSettings
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RoleCardDraft
import com.muxuechat.domain.model.RpMarkerMode
import com.muxuechat.domain.model.RpMetric
import com.muxuechat.domain.model.RpMetricDefinition
import com.muxuechat.domain.model.RpStatusPanel
import com.muxuechat.domain.model.RpNarrativePerspective
import com.muxuechat.domain.model.RpPlotSuggestionPerspective
import com.muxuechat.domain.model.WorldSetting
import com.muxuechat.domain.model.WorldSettingDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class RpRepositoryImpl(
    private val database: ChatDatabase,
    private val chatDao: ChatDao = database.chatDao(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : RpRepository {
    override fun observeRoleCards(): Flow<List<RoleCard>> =
        chatDao.observeRoleCards().map { rows -> rows.map { it.toDomain() } }

    override fun observeWorldSettings(): Flow<List<WorldSetting>> =
        chatDao.observeWorldSettings().map { rows -> rows.map { it.toDomain() } }

    override fun observeConversationRpSettings(conversationId: String): Flow<ConversationRpSettings?> =
        chatDao.observeConversationRpSettings(conversationId).map { it?.toDomain() }

    override fun observeRpStatusPanel(conversationId: String): Flow<RpStatusPanel?> =
        chatDao.observeRpStatusPanel(conversationId).map { it?.toDomain() }

    override suspend fun saveRoleCard(id: String?, draft: RoleCardDraft): RoleCard {
        val name = draft.name.trim()
        require(name.isNotBlank()) { "角色名称不能为空" }

        return database.withTransaction {
            val now = clock()
            val existing = id?.let { chatDao.getRoleCard(it) }
            require(id == null || existing != null) { "角色卡不存在，无法保存修改" }
            val entity = RoleCardEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name.take(MAX_NAME_LENGTH),
                description = draft.description.trim(),
                persona = draft.persona.trim(),
                speakingStyle = draft.speakingStyle.trim(),
                userIdentity = draft.userIdentity.trim(),
                openingMessage = draft.openingMessage.trim(),
                boundaries = draft.boundaries.trim(),
                metricDefinitionsJson = repositoryJson.encodeToString(
                    normalizeMetricDefinitions(draft.metricDefinitions),
                ),
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = now,
            )

            if (existing == null) {
                chatDao.insertRoleCard(entity)
            } else {
                check(chatDao.updateRoleCard(entity) == 1) { "角色卡已被删除，保存失败" }
                val roleSemanticsChanged = existing.copy(updatedAtMillis = entity.updatedAtMillis) != entity
                if (roleSemanticsChanged) {
                    refreshBindingsAfterRoleCardEdit(
                        roleCard = entity.toDomain(),
                        normalizeCurrentPanel = existing.metricDefinitionsJson != entity.metricDefinitionsJson,
                        now = now,
                    )
                }
            }
            entity.toDomain()
        }
    }

    override suspend fun deleteRoleCard(roleCardId: String) {
        chatDao.deleteRoleCardAndResetBindings(roleCardId, clock())
    }

    override suspend fun saveWorldSetting(id: String?, draft: WorldSettingDraft): WorldSetting {
        val title = draft.title.trim()
        require(title.isNotBlank()) { "世界观标题不能为空" }

        return database.withTransaction {
            val now = clock()
            val existing = id?.let { chatDao.getWorldSetting(it) }
            require(id == null || existing != null) { "世界观不存在，无法保存修改" }
            val entity = WorldSettingEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                title = title.take(MAX_NAME_LENGTH),
                content = draft.content.trim(),
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = now,
            )
            if (existing == null) {
                chatDao.insertWorldSetting(entity)
            } else {
                check(chatDao.updateWorldSetting(entity) == 1) { "世界观已被删除，保存失败" }
            }
            entity.toDomain()
        }
    }

    override suspend fun deleteWorldSetting(worldSettingId: String) {
        chatDao.deleteWorldSetting(worldSettingId)
    }

    override fun observeWritingStyleProfiles(): Flow<List<WritingStyleProfile>> =
        chatDao.observeWritingStyleProfiles().map { rows -> rows.map { it.toDomain() } }

    override suspend fun saveWritingStyleProfile(
        id: String?,
        draft: WritingStyleProfileDraft,
    ): WritingStyleProfile {
        val name = draft.name.trim()
        require(name.isNotBlank()) { "文风名称不能为空" }

        return database.withTransaction {
            val now = clock()
            val existing = id?.let { chatDao.getWritingStyleProfile(it) }
            require(id == null || existing != null) { "文风不存在，无法保存修改" }
            val entity = WritingStyleProfileEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name.take(MAX_NAME_LENGTH),
                content = draft.content.trim(),
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = now,
            )
            if (existing == null) {
                chatDao.insertWritingStyleProfile(entity)
            } else {
                check(chatDao.updateWritingStyleProfile(entity) == 1) { "文风已被删除，保存失败" }
            }
            entity.toDomain()
        }
    }

    override suspend fun deleteWritingStyleProfile(writingStyleId: String) {
        chatDao.deleteWritingStyleProfile(writingStyleId)
    }

    override suspend fun saveGeneratedSettings(
        roleCard: RoleCardDraft?,
        worldSetting: WorldSettingDraft?,
        writingStyle: WritingStyleProfileDraft?,
        bindToConversationId: String?,
    ): SavedGeneratedSettings {
        require(roleCard != null || worldSetting != null || writingStyle != null) {
            "没有可保存的内容"
        }

        // 全部在同一个事务内：任何一步失败都整体回滚，不留半成品。
        return database.withTransaction {
            val savedRoleCard = roleCard?.let { saveRoleCard(id = null, draft = it) }
            val savedWorldSetting = worldSetting?.let { saveWorldSetting(id = null, draft = it) }
            val savedWritingStyle = writingStyle?.let { saveWritingStyleProfile(id = null, draft = it) }

            var bound = false
            if (bindToConversationId != null && chatDao.getConversation(bindToConversationId) != null) {
                val previousEntity = chatDao.getConversationRpSettings(bindToConversationId)
                val previous = previousEntity?.toDomain()
                val now = clock()
                val nextEntity = ConversationRpSettingsEntity(
                    conversationId = bindToConversationId,
                    // 绑定不等于开启 RP：沿用当前开关，由用户自己决定何时进入 RP。
                    isRpEnabled = previous?.isRpEnabled ?: false,
                    roleCardId = savedRoleCard?.id ?: previous?.roleCardId,
                    worldSettingId = savedWorldSetting?.id ?: previous?.worldSettingId,
                    writingStyleId = savedWritingStyle?.id ?: previous?.writingStyleId,
                    protagonistName = previous?.protagonistName.orEmpty(),
                    protagonistDescription = previous?.protagonistDescription.orEmpty(),
                    protagonistInitialState = previous?.protagonistInitialState.orEmpty(),
                    narrativeMinChars = previous?.narrativeMinChars.orZero(),
                    narrativePerspective = (previous?.narrativePerspective
                        ?: RpNarrativePerspective.DEFAULT).storageValue,
                    plotSuggestionPerspective = (previous?.plotSuggestionPerspective
                        ?: RpPlotSuggestionPerspective.DEFAULT).storageValue,
                    rpMarkerMode = (previous?.rpMarkerMode ?: RpMarkerMode.DEFAULT).storageValue,
                    updatedAtMillis = now,
                )
                chatDao.upsertConversationRpSettings(nextEntity)
                if (previousEntity.hasDifferentRpSemanticsFrom(nextEntity)) {
                    chatDao.clearMessageRpStatusSnapshots(bindToConversationId, now)
                }
                if (
                    previousEntity?.isRpEnabled != nextEntity.isRpEnabled ||
                    previousEntity?.roleCardId != nextEntity.roleCardId
                ) {
                    if (nextEntity.isRpEnabled && nextEntity.roleCardId != null) {
                        chatDao.getRoleCard(nextEntity.roleCardId)?.toDomain()?.let { activeRoleCard ->
                            ensureInitialStatusPanel(
                                conversationId = bindToConversationId,
                                roleCard = activeRoleCard,
                                resetNarrative = previousEntity?.roleCardId != nextEntity.roleCardId,
                                now = now,
                            )
                        } ?: chatDao.deleteRpStatusPanel(bindToConversationId)
                    } else {
                        chatDao.deleteRpStatusPanel(bindToConversationId)
                    }
                }
                chatDao.touchConversation(bindToConversationId, now)
                bound = true
            }

            SavedGeneratedSettings(
                roleCard = savedRoleCard,
                worldSetting = savedWorldSetting,
                writingStyle = savedWritingStyle,
                boundToConversation = bound,
            )
        }
    }

    override suspend fun updateConversationRpSettings(
        conversationId: String,
        isRpEnabled: Boolean,
        roleCardId: String?,
        worldSettingId: String?,
        writingStyleId: String?,
        protagonistName: String?,
        protagonistDescription: String?,
        protagonistInitialState: String?,
        narrativeMinChars: Int?,
        narrativePerspective: RpNarrativePerspective?,
        plotSuggestionPerspective: RpPlotSuggestionPerspective?,
        rpMarkerMode: RpMarkerMode?,
    ) = database.withTransaction {
        require(chatDao.getConversation(conversationId) != null) { "会话不存在" }
        val previousEntity = chatDao.getConversationRpSettings(conversationId)
        val previousSettings = previousEntity?.toDomain()
        val now = clock()
        val roleCard = roleCardId?.let { chatDao.getRoleCard(it)?.toDomain() }
        val validRoleCardId = roleCard?.id
        val validWorldSettingId = worldSettingId?.takeIf { chatDao.getWorldSetting(it) != null }
        val validWritingStyleId = writingStyleId?.takeIf { chatDao.getWritingStyleProfile(it) != null }
        val nextEntity = ConversationRpSettingsEntity(
            conversationId = conversationId,
            isRpEnabled = isRpEnabled,
            roleCardId = validRoleCardId,
            worldSettingId = validWorldSettingId,
            writingStyleId = validWritingStyleId,
            protagonistName = protagonistName?.trim()?.take(MAX_PROFILE_LENGTH)
                ?: previousSettings?.protagonistName.orEmpty(),
            protagonistDescription = protagonistDescription?.trim()?.take(MAX_PROFILE_LENGTH)
                ?: previousSettings?.protagonistDescription.orEmpty(),
            protagonistInitialState = protagonistInitialState?.trim()?.take(MAX_PROFILE_LENGTH)
                ?: previousSettings?.protagonistInitialState.orEmpty(),
            narrativeMinChars = narrativeMinChars?.coerceIn(0, MAX_NARRATIVE_MIN_CHARS)
                ?: previousSettings?.narrativeMinChars.orZero(),
            narrativePerspective = (
                narrativePerspective
                    ?: previousSettings?.narrativePerspective
                    ?: RpNarrativePerspective.DEFAULT
                ).storageValue,
            plotSuggestionPerspective = (
                plotSuggestionPerspective
                    ?: previousSettings?.plotSuggestionPerspective
                    ?: RpPlotSuggestionPerspective.DEFAULT
                ).storageValue,
            rpMarkerMode = (
                rpMarkerMode
                    ?: previousSettings?.rpMarkerMode
                    ?: RpMarkerMode.DEFAULT
                ).storageValue,
            updatedAtMillis = now,
        )
        chatDao.upsertConversationRpSettings(nextEntity)
        if (previousEntity.hasDifferentRpSemanticsFrom(nextEntity)) {
            chatDao.clearMessageRpStatusSnapshots(conversationId, now)
        }
        if (isRpEnabled && roleCard != null) {
            ensureInitialStatusPanel(
                conversationId = conversationId,
                roleCard = roleCard,
                resetNarrative = previousSettings?.roleCardId != validRoleCardId,
                now = now,
            )
        } else {
            chatDao.deleteRpStatusPanel(conversationId)
        }
        chatDao.touchConversation(conversationId, now)
    }

    private suspend fun ensureInitialStatusPanel(
        conversationId: String,
        roleCard: RoleCard,
        resetNarrative: Boolean,
        now: Long,
    ) {
        if (roleCard.metricDefinitions.isEmpty()) {
            chatDao.deleteRpStatusPanel(conversationId)
            return
        }

        val existing = chatDao.getRpStatusPanel(conversationId)?.toDomain()
        val metrics = roleCard.metricDefinitions.map { definition ->
            val current = existing?.metrics?.firstOrNull { it.name == definition.name }
            val value = current?.value?.coerceIn(definition.minValue, definition.maxValue)
                ?: definition.initialValue
            RpMetric(
                name = definition.name,
                value = value,
                min = definition.minValue,
                max = definition.maxValue,
            )
        }
        chatDao.upsertRpStatusPanel(
            RpStatusPanel(
                conversationId = conversationId,
                metrics = metrics,
                relationship = if (resetNarrative) "" else existing?.relationship.orEmpty(),
                scene = if (resetNarrative) "" else existing?.scene.orEmpty(),
                rawJson = "",
                updatedAtMillis = now,
            ).toEntity(),
        )
    }

    /** 清除绑定会话的旧历史状态；指标定义变化时同时按新定义收敛当前面板。 */
    private suspend fun refreshBindingsAfterRoleCardEdit(
        roleCard: RoleCard,
        normalizeCurrentPanel: Boolean,
        now: Long,
    ) {
        chatDao.getConversationRpSettingsByRoleCard(roleCard.id).forEach { settings ->
            chatDao.clearMessageRpStatusSnapshots(settings.conversationId, now)
            if (normalizeCurrentPanel && settings.isRpEnabled) {
                ensureInitialStatusPanel(
                    conversationId = settings.conversationId,
                    roleCard = roleCard,
                    resetNarrative = false,
                    now = now,
                )
            } else if (normalizeCurrentPanel) {
                chatDao.deleteRpStatusPanel(settings.conversationId)
            }
        }
    }

    private companion object {
        const val MAX_NAME_LENGTH = 80
        const val MAX_PROFILE_LENGTH = 2000
        const val MAX_NARRATIVE_MIN_CHARS = 5000
        const val MAX_METRIC_DEFINITIONS = 12
        val repositoryJson = Json { ignoreUnknownKeys = true }
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun ConversationRpSettingsEntity?.hasDifferentRpSemanticsFrom(
        other: ConversationRpSettingsEntity,
    ): Boolean = this?.copy(updatedAtMillis = other.updatedAtMillis) != other

    private fun normalizeMetricDefinitions(definitions: List<RpMetricDefinition>): List<RpMetricDefinition> =
        definitions
            .mapNotNull { definition ->
                val name = definition.name.trim().take(MAX_NAME_LENGTH)
                if (name.isBlank()) return@mapNotNull null

                val minValue = definition.minValue
                val maxValue = if (definition.maxValue > minValue) {
                    definition.maxValue
                } else {
                    minValue + 1
                }
                RpMetricDefinition(
                    id = definition.id.ifBlank { UUID.randomUUID().toString() },
                    name = name,
                    minValue = minValue,
                    maxValue = maxValue,
                    initialValue = definition.initialValue.coerceIn(minValue, maxValue),
                    description = definition.description.trim(),
                    progressRule = definition.progressRule.trim(),
                )
            }
            .take(MAX_METRIC_DEFINITIONS)
}
