package com.muxuechat.data.repository

import com.muxuechat.data.local.ChatDao
import com.muxuechat.domain.model.RpSettingKind
import com.muxuechat.domain.model.RpSettingRef
import com.muxuechat.domain.tool.RpSettingCurrent
import com.muxuechat.domain.tool.RpSettingsInventory
import com.muxuechat.domain.tool.SettingsDeletionTarget
import kotlinx.coroutines.flow.first

/**
 * 设定工具的两个只读查询：`list_rp_settings` 的完整清单，与 `delete_rp_settings` 的目标解析。
 * 都不写库；删除本身在用户确认后由 [RpRepository.deleteSettings] 执行。
 */
class RpSettingsToolQueries(private val chatDao: ChatDao) {

    suspend fun inventory(): RpSettingsInventory {
        val bindings = chatDao.getAllConversationRpSettings()
        val usage = buildMap {
            fun count(kind: RpSettingKind, ids: List<String?>) {
                ids.filterNotNull().groupingBy { it }.eachCount().forEach { (id, n) -> put(RpSettingRef(kind, id), n) }
            }
            count(RpSettingKind.ROLE_CARD, bindings.map { it.roleCardId })
            count(RpSettingKind.PROTAGONIST, bindings.map { it.protagonistProfileId })
            count(RpSettingKind.WORLD_SETTING, bindings.map { it.worldSettingId })
            count(RpSettingKind.WRITING_STYLE, bindings.map { it.writingStyleId })
            chatDao.getSupportingCharacterUsageRows().forEach { row ->
                put(RpSettingRef(RpSettingKind.SUPPORTING_CHARACTER, row.characterId), row.conversationCount)
            }
        }
        return RpSettingsInventory(
            roleCards = chatDao.observeRoleCards().first().map { it.toDomain() },
            supportingCharacters = chatDao.observeSupportingCharacters().first().map { it.toDomain() },
            protagonists = chatDao.observeProtagonistProfiles().first().map { it.toDomain() },
            worldSettings = chatDao.observeWorldSettings().first().map { it.toDomain() },
            writingStyles = chatDao.observeWritingStyleProfiles().first().map { it.toDomain() },
            usage = usage,
        )
    }

    /** `update_rp_setting` 的目标此刻在库里的样子；不存在时返回 null。 */
    suspend fun current(ref: RpSettingRef): RpSettingCurrent? = when (ref.kind) {
        RpSettingKind.ROLE_CARD -> chatDao.getRoleCard(ref.id)?.toDomain()?.let(RpSettingCurrent::OfRoleCard)
        RpSettingKind.SUPPORTING_CHARACTER ->
            chatDao.getSupportingCharacter(ref.id)?.toDomain()?.let(RpSettingCurrent::OfSupportingCharacter)
        RpSettingKind.PROTAGONIST ->
            chatDao.getProtagonistProfile(ref.id)?.toDomain()?.let(RpSettingCurrent::OfProtagonist)
        RpSettingKind.WORLD_SETTING -> chatDao.getWorldSetting(ref.id)?.toDomain()?.let(RpSettingCurrent::OfWorldSetting)
        RpSettingKind.WRITING_STYLE ->
            chatDao.getWritingStyleProfile(ref.id)?.toDomain()?.let(RpSettingCurrent::OfWritingStyle)
    }

    /** 把删除目标解析成可展示的项；库里查不到的 id 单独返回，由调用方告知模型。 */
    suspend fun resolve(refs: List<RpSettingRef>): Pair<List<SettingsDeletionTarget>, List<RpSettingRef>> {
        val inventory = inventory()
        val found = mutableListOf<SettingsDeletionTarget>()
        val missing = mutableListOf<RpSettingRef>()
        refs.forEach { ref ->
            val name = when (ref.kind) {
                RpSettingKind.ROLE_CARD -> inventory.roleCards.firstOrNull { it.id == ref.id }?.name
                RpSettingKind.SUPPORTING_CHARACTER ->
                    inventory.supportingCharacters.firstOrNull { it.id == ref.id }?.name
                RpSettingKind.PROTAGONIST -> inventory.protagonists.firstOrNull { it.id == ref.id }
                    ?.name?.ifBlank { "未命名主角" }
                RpSettingKind.WORLD_SETTING -> inventory.worldSettings.firstOrNull { it.id == ref.id }?.title
                RpSettingKind.WRITING_STYLE -> inventory.writingStyles.firstOrNull { it.id == ref.id }?.name
            }
            if (name == null) {
                missing += ref
            } else {
                found += SettingsDeletionTarget(ref = ref, name = name, usageCount = inventory.usage[ref] ?: 0)
            }
        }
        return found to missing
    }
}
