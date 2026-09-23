package com.muxuechat.data.repository

import androidx.room.withTransaction
import com.muxuechat.data.local.ChatDao
import com.muxuechat.data.local.ChatDatabase
import com.muxuechat.data.local.ConversationRpSettingsEntity
import com.muxuechat.data.local.ProtagonistProfileEntity
import com.muxuechat.data.local.RoleCardEntity
import com.muxuechat.data.local.SupportingCharacterEntity
import com.muxuechat.data.local.WorldSettingEntity
import com.muxuechat.data.local.WritingStyleProfileEntity
import com.muxuechat.data.local.toEntity
import com.muxuechat.domain.model.ConversationRpSettings
import com.muxuechat.domain.model.ProtagonistDraft
import com.muxuechat.domain.model.ProtagonistProfile
import com.muxuechat.domain.model.ProtagonistProfileDraft
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RoleCardDraft
import com.muxuechat.domain.model.RpEntry
import com.muxuechat.domain.model.RpEntryOwner
import com.muxuechat.domain.model.RpMarkerMode
import com.muxuechat.domain.model.RpMetricDefinition
import com.muxuechat.domain.model.RpMetricSnapshot
import com.muxuechat.domain.model.RpNarrativePerspective
import com.muxuechat.domain.model.RpPlotSuggestionPerspective
import com.muxuechat.domain.model.RpStatusPanel
import com.muxuechat.domain.model.SupportingCharacter
import com.muxuechat.domain.model.SupportingCharacterDraft
import com.muxuechat.domain.model.WorldSetting
import com.muxuechat.domain.model.WorldSettingDraft
import com.muxuechat.domain.model.WritingStyleProfile
import com.muxuechat.domain.model.WritingStyleProfileDraft
import com.muxuechat.domain.model.toMetricDefinition
import com.muxuechat.domain.rp.RpEffectiveDefinitions
import com.muxuechat.domain.rp.RpEntryCodec
import com.muxuechat.domain.rp.RpEntryRules
import com.muxuechat.domain.rp.RpStateBlockFormat
import com.muxuechat.domain.rp.RpStateMerger
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

    /**
     * 保存角色卡。**不再同步到已绑定的会话**（R6）：它们用的是绑定时的定义快照，
     * 要用新定义需在会话里显式调用 [syncMetricDefinitionsFromProfiles]。
     */
    override suspend fun saveRoleCard(id: String?, draft: RoleCardDraft): RoleCard {
        val name = draft.name.trim()
        require(name.isNotBlank()) { "角色名称不能为空" }
        val entries = normalizeEntries(draft.toEntries())

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
                // entriesJson 是唯一读源；metricDefinitionsJson 同步写入，
                // 作为 entriesJson 万一被写坏时的就地恢复材料（见 RoleCardEntity 注释）。
                metricDefinitionsJson = repositoryJson.encodeToString(
                    entries.filterIsInstance<RpEntry.Numeric>().map { it.toMetricDefinition() },
                ),
                entriesJson = RpEntryCodec.encodeEntries(entries),
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = now,
            )

            if (existing == null) {
                chatDao.insertRoleCard(entity)
            } else {
                check(chatDao.updateRoleCard(entity) == 1) { "角色卡已被删除，保存失败" }
            }
            entity.toDomain()
        }
    }

    /**
     * 删除角色卡等价于在所有绑定它的会话里解绑：走 [applyBindingTransition] 同一条规则，
     * 只清角色组数值、关系与场景，**不连带清主角组**（§4）。
     *
     * 会话还绑着主角时 RP 保持原开关（RP-2f 起主角单独也能驱动状态链路）；
     * 角色卡与主角都没了才关闭 RP。历史消息与其元数据行保留。
     */
    override suspend fun deleteRoleCard(roleCardId: String) {
        database.withTransaction {
            val now = clock()
            chatDao.getConversationRpSettingsByRoleCard(roleCardId).forEach { previous ->
                val next = previous.copy(
                    roleCardId = null,
                    isRpEnabled = previous.isRpEnabled && previous.protagonistProfileId != null,
                    metricSnapshotJson = RpEntryCodec.encodeSnapshot(
                        nextMetricSnapshot(previous, roleCardId = null, protagonistProfileId = previous.protagonistProfileId),
                    ),
                    updatedAtMillis = now,
                )
                chatDao.upsertConversationRpSettings(next)
                applyBindingTransition(previous.conversationId, previous, next, now)
                chatDao.touchConversation(previous.conversationId, now)
            }
            chatDao.deleteRoleCardRow(roleCardId)
        }
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

    // ---------- 主角档案 ----------

    override fun observeProtagonistProfiles(): Flow<List<ProtagonistProfile>> =
        chatDao.observeProtagonistProfiles().map { rows -> rows.map { it.toDomain() } }

    override suspend fun saveProtagonistProfile(
        id: String?,
        draft: ProtagonistProfileDraft,
    ): ProtagonistProfile {
        // 名字允许为空（用户 2026-09-23 选定）：空名表示「玩家本人」，prompt 不发名称行。
        // 但整份档案不能什么都没有，否则它进不了 prompt，只是一条看不见的空记录。
        val name = draft.name.trim().replace(NEWLINE_REGEX, " ")
        val description = draft.description.trim()
        val entries = normalizeEntries(draft.entries)
        require(name.isNotBlank() || description.isNotBlank() || entries.any { it.hasContent() }) {
            "主角档案不能为空：名称、简介和词条至少填一项"
        }

        return database.withTransaction {
            val now = clock()
            val existing = id?.let { chatDao.getProtagonistProfile(it) }
            require(id == null || existing != null) { "主角档案不存在，无法保存修改" }
            val entity = ProtagonistProfileEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name.take(MAX_NAME_LENGTH),
                description = description,
                entriesJson = RpEntryCodec.encodeEntries(entries),
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = now,
            )
            if (existing == null) {
                chatDao.insertProtagonistProfile(entity)
            } else {
                check(chatDao.updateProtagonistProfile(entity) == 1) { "主角档案已被删除，保存失败" }
            }
            entity.toDomain()
        }
    }

    override suspend fun deleteProtagonistProfile(profileId: String) {
        database.withTransaction {
            val now = clock()
            chatDao.getConversationRpSettingsByProtagonist(profileId).forEach { previous ->
                val next = previous.copy(
                    protagonistProfileId = null,
                    metricSnapshotJson = RpEntryCodec.encodeSnapshot(
                        nextMetricSnapshot(previous, roleCardId = previous.roleCardId, protagonistProfileId = null),
                    ),
                    updatedAtMillis = now,
                )
                chatDao.upsertConversationRpSettings(next)
                applyBindingTransition(previous.conversationId, previous, next, now)
                chatDao.touchConversation(previous.conversationId, now)
            }
            chatDao.deleteProtagonistProfileRow(profileId)
        }
    }

    override fun observeProtagonistProfileUsage(): Flow<Map<String, Int>> =
        chatDao.observeProtagonistProfileUsage().map { rows ->
            rows.associate { it.profileId to it.conversationCount }
        }

    // ---------- 配角 ----------

    override fun observeSupportingCharacters(): Flow<List<SupportingCharacter>> =
        chatDao.observeSupportingCharacters().map { rows -> rows.map { it.toDomain() } }

    override suspend fun saveSupportingCharacter(
        id: String?,
        draft: SupportingCharacterDraft,
    ): SupportingCharacter {
        val name = draft.name.trim().replace(NEWLINE_REGEX, " ")
        require(name.isNotBlank()) { "配角名字不能为空" }
        val entries = normalizeEntries(draft.entries).filterIsInstance<RpEntry.Text>()

        return database.withTransaction {
            val now = clock()
            val existing = id?.let { chatDao.getSupportingCharacter(it) }
            require(id == null || existing != null) { "配角不存在，无法保存修改" }
            val entity = SupportingCharacterEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name.take(MAX_NAME_LENGTH),
                description = draft.description.trim(),
                entriesJson = RpEntryCodec.encodeEntries(entries),
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = now,
            )
            if (existing == null) {
                chatDao.insertSupportingCharacter(entity)
            } else {
                check(chatDao.updateSupportingCharacter(entity) == 1) { "配角已被删除，保存失败" }
            }
            entity.toDomain()
        }
    }

    override suspend fun deleteSupportingCharacter(characterId: String) {
        chatDao.deleteSupportingCharacter(characterId)
    }

    override fun observeConversationSupportingCharacters(conversationId: String): Flow<List<SupportingCharacter>> =
        chatDao.observeConversationSupportingCharacters(conversationId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun setConversationSupportingCharacters(conversationId: String, characterIds: List<String>) {
        database.withTransaction {
            require(chatDao.getConversation(conversationId) != null) { "会话不存在" }
            // 选择界面打开期间配角可能被删掉：只保留仍存在的，不因一条失效 id 让整次保存失败。
            val valid = characterIds.filter { chatDao.getSupportingCharacter(it) != null }
            chatDao.replaceConversationSupportingCharacters(conversationId, valid)
            chatDao.touchConversation(conversationId, clock())
        }
    }

    // ---------- 文风 ----------

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

    // ---------- 设定生成工具 ----------

    override suspend fun saveGeneratedSettings(
        roleCard: RoleCardDraft?,
        worldSetting: WorldSettingDraft?,
        writingStyle: WritingStyleProfileDraft?,
        protagonist: ProtagonistDraft?,
        bindToConversationId: String?,
    ): SavedGeneratedSettings {
        require(roleCard != null || worldSetting != null || writingStyle != null || protagonist != null) {
            "没有可保存的内容"
        }
        // 工具协议要求主角落到一个会话：它是「为这个故事建的主角」。
        require(protagonist == null || bindToConversationId != null) {
            "主角设定必须保存到一个会话"
        }

        // 全部在同一个事务内：任何一步失败都整体回滚，不留半成品。
        return database.withTransaction {
            val savedRoleCard = roleCard?.let { saveRoleCard(id = null, draft = it) }
            val savedWorldSetting = worldSetting?.let { saveWorldSetting(id = null, draft = it) }
            val savedWritingStyle = writingStyle?.let { saveWritingStyleProfile(id = null, draft = it) }
            val savedProtagonist = protagonist?.let { saveProtagonistProfile(id = null, draft = it.toProfileDraft()) }

            var bound = false
            if (bindToConversationId != null && chatDao.getConversation(bindToConversationId) != null) {
                val previousEntity = chatDao.getConversationRpSettings(bindToConversationId)
                val previous = previousEntity?.toDomain()
                val now = clock()
                val roleCardId = savedRoleCard?.id ?: previous?.roleCardId
                val protagonistProfileId = savedProtagonist?.id ?: previous?.protagonistProfileId
                val nextEntity = ConversationRpSettingsEntity(
                    conversationId = bindToConversationId,
                    // 绑定不等于开启 RP：沿用当前开关，由用户自己决定何时进入 RP。
                    isRpEnabled = previous?.isRpEnabled ?: false,
                    roleCardId = roleCardId,
                    worldSettingId = savedWorldSetting?.id ?: previous?.worldSettingId,
                    writingStyleId = savedWritingStyle?.id ?: previous?.writingStyleId,
                    protagonistProfileId = protagonistProfileId,
                    metricSnapshotJson = RpEntryCodec.encodeSnapshot(
                        nextMetricSnapshot(previousEntity, roleCardId, protagonistProfileId),
                    ),
                    // v9 的主角三字段已停用，原样保留为取证材料，不再写入。
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
                applyBindingTransition(bindToConversationId, previousEntity, nextEntity, now)
                chatDao.touchConversation(bindToConversationId, now)
                bound = true
            }
            // 会话可能刚被删除：主角是为这个会话建的，这时必须让整笔保存失败，而不是静默留下孤儿档案。
            check(protagonist == null || bound) { "会话已不存在，主角设定未能保存" }

            SavedGeneratedSettings(
                roleCard = savedRoleCard,
                worldSetting = savedWorldSetting,
                writingStyle = savedWritingStyle,
                protagonistProfile = savedProtagonist,
                boundToConversation = bound,
            )
        }
    }

    // ---------- 会话 RP 设置 ----------

    override suspend fun updateConversationRpSettings(
        conversationId: String,
        isRpEnabled: Boolean,
        roleCardId: String?,
        worldSettingId: String?,
        writingStyleId: String?,
        protagonistProfileId: String?,
        narrativeMinChars: Int?,
        narrativePerspective: RpNarrativePerspective?,
        plotSuggestionPerspective: RpPlotSuggestionPerspective?,
        rpMarkerMode: RpMarkerMode?,
    ) = database.withTransaction {
        require(chatDao.getConversation(conversationId) != null) { "会话不存在" }
        val previousEntity = chatDao.getConversationRpSettings(conversationId)
        val previousSettings = previousEntity?.toDomain()
        val now = clock()
        val validRoleCardId = roleCardId?.takeIf { chatDao.getRoleCard(it) != null }
        val validWorldSettingId = worldSettingId?.takeIf { chatDao.getWorldSetting(it) != null }
        val validWritingStyleId = writingStyleId?.takeIf { chatDao.getWritingStyleProfile(it) != null }
        val validProtagonistProfileId = protagonistProfileId?.takeIf { chatDao.getProtagonistProfile(it) != null }
        val nextEntity = ConversationRpSettingsEntity(
            conversationId = conversationId,
            isRpEnabled = isRpEnabled,
            roleCardId = validRoleCardId,
            worldSettingId = validWorldSettingId,
            writingStyleId = validWritingStyleId,
            protagonistProfileId = validProtagonistProfileId,
            metricSnapshotJson = RpEntryCodec.encodeSnapshot(
                nextMetricSnapshot(previousEntity, validRoleCardId, validProtagonistProfileId),
            ),
            // v9 的主角三列已停用（RP-2 起主角只读档案），原样保留为取证材料。
            protagonistName = previousSettings?.protagonistName.orEmpty(),
            protagonistDescription = previousSettings?.protagonistDescription.orEmpty(),
            protagonistInitialState = previousSettings?.protagonistInitialState.orEmpty(),
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
        applyBindingTransition(conversationId, previousEntity, nextEntity, now)
        chatDao.touchConversation(conversationId, now)
    }

    override suspend fun syncMetricDefinitionsFromProfiles(conversationId: String): Boolean =
        database.withTransaction {
            val previous = chatDao.getConversationRpSettings(conversationId) ?: return@withTransaction false
            resyncMetricGroups(previous, setOf(RpEntryOwner.ROLE_CARD, RpEntryOwner.PROTAGONIST))
        }

    override suspend fun forkProtagonistProfileForConversation(
        conversationId: String,
        sourceProfileId: String,
        draft: ProtagonistProfileDraft,
    ): ProtagonistProfile = database.withTransaction {
        val previous = chatDao.getConversationRpSettings(conversationId)
        require(previous != null && previous.protagonistProfileId == sourceProfileId) {
            "本会话已不再使用这份主角档案，请重新打开编辑"
        }
        val forked = saveProtagonistProfile(id = null, draft = draft)
        val rebound = previous.copy(protagonistProfileId = forked.id, updatedAtMillis = clock())
        chatDao.upsertConversationRpSettings(rebound)
        // 快照此刻仍是源档案的，按 entryId 对齐到新档案。这里刻意不走 applyBindingTransition：
        // 那会把它当成换主角，把主角组数值重置为初始值（§4）。
        if (!resyncMetricGroups(rebound, setOf(RpEntryOwner.PROTAGONIST))) {
            chatDao.touchConversation(conversationId, clock())
        }
        forked
    }

    /**
     * 把会话快照里 [owners] 这几组重新对齐到所绑档案的**最新**定义：按 entryId 保值（R14），
     * 新条用初始值，删掉的条移除，面板与历史快照一起迁移。必须在事务内调用。
     *
     * @return 定义确实有变化并已写库时为 true；本来就一致时为 false，不写库。
     */
    private suspend fun resyncMetricGroups(
        previous: ConversationRpSettingsEntity,
        owners: Set<RpEntryOwner>,
    ): Boolean {
        val conversationId = previous.conversationId
        val oldSnapshot = previous.toDomain().metricSnapshot
        var snapshot = oldSnapshot
        if (RpEntryOwner.ROLE_CARD in owners) {
            previous.roleCardId?.let { chatDao.getRoleCard(it)?.toDomain() }?.let { card ->
                snapshot = RpEffectiveDefinitions.replaceGroup(
                    snapshot,
                    RpEntryOwner.ROLE_CARD,
                    RpEffectiveDefinitions.snapshotOf(RpEntryOwner.ROLE_CARD, card.entries),
                )
            }
        }
        if (RpEntryOwner.PROTAGONIST in owners) {
            previous.protagonistProfileId?.let { chatDao.getProtagonistProfile(it)?.toDomain() }?.let { profile ->
                snapshot = RpEffectiveDefinitions.replaceGroup(
                    snapshot,
                    RpEntryOwner.PROTAGONIST,
                    RpEffectiveDefinitions.snapshotOf(RpEntryOwner.PROTAGONIST, profile.entries),
                )
            }
        }
        if (snapshot == oldSnapshot) return false

        val now = clock()
        // entryId → 旧 wireName：档案里改过名的条按身份找回原值，不因改名归零（R14）。
        val previousNames = oldSnapshot.associate { it.entryId to it.wireName }
        val definitions = RpEffectiveDefinitions.of(snapshot)
        chatDao.upsertConversationRpSettings(
            previous.copy(
                metricSnapshotJson = RpEntryCodec.encodeSnapshot(snapshot),
                updatedAtMillis = now,
            ),
        )
        migrateStatusSnapshots(conversationId, definitions, previousNames, now)
        alignStatusPanel(conversationId, definitions, previousNames, now)
        chatDao.touchConversation(conversationId, now)
        return true
    }

    /**
     * 绑定变化后该有的定义快照：**变了的组重拍、没变的组原样保留**。
     * 换角色卡只重拍角色组，换主角只重拍主角组（§4）；没变的组一律不读实时档案（R6）。
     */
    private suspend fun nextMetricSnapshot(
        previous: ConversationRpSettingsEntity?,
        roleCardId: String?,
        protagonistProfileId: String?,
    ): List<RpMetricSnapshot> {
        var snapshot = previous?.toDomain()?.metricSnapshot.orEmpty()
        if (previous?.roleCardId != roleCardId) {
            val entries = roleCardId?.let { chatDao.getRoleCard(it)?.toDomain()?.entries }.orEmpty()
            snapshot = RpEffectiveDefinitions.replaceGroup(
                snapshot,
                RpEntryOwner.ROLE_CARD,
                RpEffectiveDefinitions.snapshotOf(RpEntryOwner.ROLE_CARD, entries),
            )
        }
        if (previous?.protagonistProfileId != protagonistProfileId) {
            val entries = protagonistProfileId?.let { chatDao.getProtagonistProfile(it)?.toDomain()?.entries }.orEmpty()
            snapshot = RpEffectiveDefinitions.replaceGroup(
                snapshot,
                RpEntryOwner.PROTAGONIST,
                RpEffectiveDefinitions.snapshotOf(RpEntryOwner.PROTAGONIST, entries),
            )
        }
        return snapshot
    }

    /**
     * 会话 RP 设置变化后，状态面板与历史状态快照的唯一转换规则（RP_ACTOR_PLAN 第 4 节）。
     * 手动设置、设定生成绑定、删除档案全部走这里。
     *
     * - 换 / 解绑角色卡：角色组从新卡初始值开始，**不按名字继承**——两张卡都有「好感度」
     *   不代表是同一个东西；关系与场景一并重置。主角组不动。
     * - 换 / 解绑主角：只重置主角组；角色组、关系与场景不动。
     * - 暂停 RP：面板与快照原样保留。暂停不等于重置。
     * - 其余设置与数值无关，不动面板也不动快照。
     *
     * 被重置的组同样从历史快照里移除，否则回滚会把旧档案的数值带回来。
     */
    private suspend fun applyBindingTransition(
        conversationId: String,
        previous: ConversationRpSettingsEntity?,
        next: ConversationRpSettingsEntity,
        now: Long,
    ) {
        val roleChanged = previous?.roleCardId != next.roleCardId
        val protagonistChanged = previous?.protagonistProfileId != next.protagonistProfileId
        val definitions = RpEffectiveDefinitions.of(next.toDomain().metricSnapshot)

        if (!roleChanged && !protagonistChanged) {
            // 首次开启 RP 时面板可能还不存在，按快照补齐；已对齐时不写库。
            // 没有定义时面板里是模型自报的状态条，对齐会把它们清空，因此跳过。
            if (next.isRpEnabled && definitions.isNotEmpty()) {
                alignStatusPanel(conversationId, definitions, previousNames = emptyMap(), now = now)
            }
            return
        }

        val ownerByWireName = previous?.toDomain()?.metricSnapshot.orEmpty()
            .associate { it.wireName to it.ownerType }
        val keep: (String) -> Boolean = { name ->
            when (ownerByWireName[name]) {
                RpEntryOwner.ROLE_CARD -> !roleChanged
                RpEntryOwner.PROTAGONIST -> !protagonistChanged
                // 快照里没登记的名字（v9 遗留、模型自报）按 R16 一律视为角色侧。
                null -> !roleChanged
            }
        }
        dropMetricsFromHistory(conversationId, keep, clearStoryState = roleChanged, now = now)
        rebuildStatusPanel(conversationId, keep, clearStoryState = roleChanged, definitions = definitions, now = now)
    }

    private suspend fun rebuildStatusPanel(
        conversationId: String,
        keep: (String) -> Boolean,
        clearStoryState: Boolean,
        definitions: List<RpMetricDefinition>,
        now: Long,
    ) {
        val existing = chatDao.getRpStatusPanel(conversationId)?.toDomain()
        val kept = existing?.metrics.orEmpty().filter { keep(it.name) }
        val metrics = if (definitions.isEmpty()) kept else RpStateMerger.alignToDefinitions(definitions, kept)
        val relationship = if (clearStoryState) "" else existing?.relationship.orEmpty()
        val scene = if (clearStoryState) "" else existing?.scene.orEmpty()

        if (metrics.isEmpty() && relationship.isBlank() && scene.isBlank()) {
            if (existing != null) chatDao.deleteRpStatusPanel(conversationId)
            return
        }
        if (existing != null && existing.metrics == metrics &&
            existing.relationship == relationship && existing.scene == scene
        ) {
            return
        }
        chatDao.upsertRpStatusPanel(
            RpStatusPanel(
                conversationId = conversationId,
                metrics = metrics,
                relationship = relationship,
                scene = scene,
                rawJson = "",
                updatedAtMillis = now,
            ).toEntity(),
        )
    }

    /** 被重置的组从历史快照里移除；换角色卡时关系、场景与原始留档一并清空。行本身保留。 */
    private suspend fun dropMetricsFromHistory(
        conversationId: String,
        keep: (String) -> Boolean,
        clearStoryState: Boolean,
        now: Long,
    ) {
        chatDao.getMessageRpMetadata(conversationId).forEach { row ->
            val metrics = row.toDomain().statusPanelSnapshot?.metrics.orEmpty()
            val kept = metrics.filter { keep(it.name) }
            val storyStateToClear = clearStoryState &&
                (row.relationship.isNotEmpty() || row.scene.isNotEmpty() || row.rawJson.isNotEmpty())
            if (kept == metrics && !storyStateToClear) return@forEach
            chatDao.upsertMessageRpMetadata(
                row.copy(
                    metricsJson = repositoryJson.encodeToString(kept),
                    relationship = if (clearStoryState) "" else row.relationship,
                    scene = if (clearStoryState) "" else row.scene,
                    rawJson = if (clearStoryState) "" else row.rawJson,
                    updatedAtMillis = now,
                ),
            )
        }
    }

    /**
     * 把当前面板对齐到给定定义：保留现有数值，新增的状态条用初始值，删掉的状态条移除。
     * 面板本来就对齐时不写库——写一次会刷新时间戳，让在途请求的提交守卫误以为面板被改过。
     */
    private suspend fun alignStatusPanel(
        conversationId: String,
        definitions: List<RpMetricDefinition>,
        previousNames: Map<String, String>,
        now: Long,
    ) {
        val existing = chatDao.getRpStatusPanel(conversationId)?.toDomain()
        if (existing == null && definitions.isEmpty()) return

        val metrics = RpStateMerger.alignToDefinitions(
            definitions = definitions,
            metrics = existing?.metrics.orEmpty(),
            previousNames = previousNames,
        )
        if (existing != null && existing.metrics == metrics) return

        chatDao.upsertRpStatusPanel(
            RpStatusPanel(
                conversationId = conversationId,
                metrics = metrics,
                relationship = existing?.relationship.orEmpty(),
                scene = existing?.scene.orEmpty(),
                rawJson = "",
                updatedAtMillis = now,
            ).toEntity(),
        )
    }

    /** 历史快照里的状态条按与当前面板相同的规则对齐；关系、场景、剧情建议与内心独白不动。 */
    private suspend fun migrateStatusSnapshots(
        conversationId: String,
        definitions: List<RpMetricDefinition>,
        previousNames: Map<String, String>,
        now: Long,
    ) {
        chatDao.getMessageRpMetadata(conversationId).forEach { row ->
            val metrics = row.toDomain().statusPanelSnapshot?.metrics.orEmpty()
            if (metrics.isEmpty()) return@forEach
            val aligned = RpStateMerger.alignToDefinitions(definitions, metrics, previousNames)
            if (aligned == metrics) return@forEach
            chatDao.upsertMessageRpMetadata(
                row.copy(
                    metricsJson = repositoryJson.encodeToString(aligned),
                    updatedAtMillis = now,
                ),
            )
        }
    }

    /**
     * 词条的统一归一化：trim、去换行、补 id、数值范围修正。
     *
     * 数值词条名要进状态命名空间，因此额外校验（§3.3）：不得含分隔符「::」，
     * 同一档案内不得重名——否则状态块只会匹配到第一项，第二项永远更新不了。
     * 这两条违反时**抛异常而不是静默修正**：改掉用户起的名字比报错更糟。
     */
    private fun normalizeEntries(entries: List<RpEntry>): List<RpEntry> {
        val numericNames = mutableSetOf<String>()
        var numericCount = 0
        return entries.mapNotNull { entry ->
            val name = RpEntryRules.normalizeName(entry.name)
            if (name.isBlank()) return@mapNotNull null
            val id = entry.id.ifBlank { UUID.randomUUID().toString() }
            when (entry) {
                is RpEntry.Text -> entry.copy(id = id, name = name, value = entry.value.trim())
                is RpEntry.Numeric -> {
                    require(!name.contains(RpStateBlockFormat.WIRE_NAME_SEPARATOR)) {
                        "状态条名称不能包含「${RpStateBlockFormat.WIRE_NAME_SEPARATOR}」：$name"
                    }
                    require(numericNames.add(name)) { "状态条名称重复：$name" }
                    if (++numericCount > MAX_METRIC_DEFINITIONS) return@mapNotNull null
                    val minValue = entry.minValue
                    val maxValue = if (entry.maxValue > minValue) entry.maxValue else minValue + 1
                    entry.copy(
                        id = id,
                        name = name,
                        minValue = minValue,
                        maxValue = maxValue,
                        initialValue = entry.initialValue.coerceIn(minValue, maxValue),
                        description = entry.description.trim(),
                        progressRule = entry.progressRule.trim(),
                    )
                }
            }
        }
    }

    private fun ProtagonistDraft.toProfileDraft(): ProtagonistProfileDraft = ProtagonistProfileDraft(
        name = name,
        description = description,
        entries = if (initialState.isBlank()) {
            emptyList()
        } else {
            listOf(RpEntry.Text(id = "", name = ProtagonistProfile.INITIAL_STATE_ENTRY_NAME, value = initialState))
        },
    )

    private fun RpEntry.hasContent(): Boolean = when (this) {
        is RpEntry.Text -> value.isNotBlank()
        is RpEntry.Numeric -> true
    }

    private fun Int?.orZero(): Int = this ?: 0

    private companion object {
        const val MAX_NAME_LENGTH = RpEntryRules.MAX_NAME_LENGTH
        const val MAX_NARRATIVE_MIN_CHARS = 5000
        const val MAX_METRIC_DEFINITIONS = RpEntryRules.MAX_NUMERIC_ENTRIES
        val NEWLINE_REGEX = Regex("[\\r\\n]+")
        val repositoryJson = Json { ignoreUnknownKeys = true }
    }
}
