package com.muxuechat.data.repository

import com.muxuechat.domain.model.ConversationRpSettings
import com.muxuechat.domain.model.ProtagonistDraft
import com.muxuechat.domain.model.ProtagonistProfile
import com.muxuechat.domain.model.ProtagonistProfileDraft
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RoleCardDraft
import com.muxuechat.domain.model.RpMarkerMode
import com.muxuechat.domain.model.RpNarrativePerspective
import com.muxuechat.domain.model.RpPlotSuggestionPerspective
import com.muxuechat.domain.model.RpStatusPanel
import com.muxuechat.domain.model.SupportingCharacter
import com.muxuechat.domain.model.SupportingCharacterDraft
import com.muxuechat.domain.model.WorldSetting
import com.muxuechat.domain.model.WorldSettingDraft
import com.muxuechat.domain.model.WritingStyleProfile
import com.muxuechat.domain.model.WritingStyleProfileDraft
import kotlinx.coroutines.flow.Flow

/** 一次草稿保存的结果，供 UI 提示与后续绑定。 */
data class SavedGeneratedSettings(
    val roleCard: RoleCard? = null,
    val worldSetting: WorldSetting? = null,
    val writingStyle: WritingStyleProfile? = null,
    /** 工具生成的主角：保存为独立档案并绑定到会话。 */
    val protagonistProfile: ProtagonistProfile? = null,
    val boundToConversation: Boolean = false,
)

interface RpRepository {
    fun observeRoleCards(): Flow<List<RoleCard>>
    fun observeWorldSettings(): Flow<List<WorldSetting>>
    fun observeConversationRpSettings(conversationId: String): Flow<ConversationRpSettings?>
    fun observeRpStatusPanel(conversationId: String): Flow<RpStatusPanel?>

    suspend fun saveRoleCard(id: String?, draft: RoleCardDraft): RoleCard
    suspend fun deleteRoleCard(roleCardId: String)
    suspend fun saveWorldSetting(id: String?, draft: WorldSettingDraft): WorldSetting
    suspend fun deleteWorldSetting(worldSettingId: String)

    fun observeProtagonistProfiles(): Flow<List<ProtagonistProfile>>

    /**
     * 保存主角档案。数值词条名不得含「::」、同一档案内不得重名，违反时抛出带原因的异常。
     *
     * 修改档案**不影响**已绑定它的会话（R6）：它们用的是绑定时的定义快照，
     * 要用新定义需在会话里显式调用 [syncMetricDefinitionsFromProfiles]。
     */
    suspend fun saveProtagonistProfile(id: String?, draft: ProtagonistProfileDraft): ProtagonistProfile

    /** 删除档案等价于在所有绑定它的会话里解绑主角：只清主角组数值，角色组不动（§4）。 */
    suspend fun deleteProtagonistProfile(profileId: String)

    fun observeSupportingCharacters(): Flow<List<SupportingCharacter>>
    suspend fun saveSupportingCharacter(id: String?, draft: SupportingCharacterDraft): SupportingCharacter
    suspend fun deleteSupportingCharacter(characterId: String)
    fun observeConversationSupportingCharacters(conversationId: String): Flow<List<SupportingCharacter>>

    /** 整体替换会话勾选的配角，顺序即列表顺序。配角不进状态面板，不涉及数值。 */
    suspend fun setConversationSupportingCharacters(conversationId: String, characterIds: List<String>)

    /**
     * 把会话的数值定义快照重新对齐到所绑档案的**最新**定义：按 entryId 保留现有数值，
     * 新增的条用初始值，删掉的条移除。**只影响这一个会话**。
     *
     * @return 定义确实有变化并已同步时为 true；本来就一致时为 false，不写库。
     */
    suspend fun syncMetricDefinitionsFromProfiles(conversationId: String): Boolean

    fun observeWritingStyleProfiles(): Flow<List<WritingStyleProfile>>
    suspend fun saveWritingStyleProfile(id: String?, draft: WritingStyleProfileDraft): WritingStyleProfile
    suspend fun deleteWritingStyleProfile(writingStyleId: String)

    /**
     * 一次性保存工具草稿里的全部设定，并可选绑定到会话。
     *
     * 各类设定走同一个事务：中途失败不能留下"角色卡存了但世界观没存"的半成品。
     *
     * [protagonist] 会保存为独立的主角档案并绑定到 [bindToConversationId]；
     * 工具协议要求它必须落到一个会话，因此它不为空时 [bindToConversationId] 必填。
     */
    suspend fun saveGeneratedSettings(
        roleCard: RoleCardDraft?,
        worldSetting: WorldSettingDraft?,
        writingStyle: WritingStyleProfileDraft?,
        protagonist: ProtagonistDraft? = null,
        bindToConversationId: String?,
    ): SavedGeneratedSettings

    /**
     * [writingStyleId]、[protagonistProfileId] 与 [roleCardId]、[worldSettingId] 一样是**必填**的：
     * null 表示解绑，不是「保持不变」。刻意不给默认值，
     * 逼每个调用点都想清楚要不要动这个绑定，避免默认值静默清空。
     */
    suspend fun updateConversationRpSettings(
        conversationId: String,
        isRpEnabled: Boolean,
        roleCardId: String?,
        worldSettingId: String?,
        writingStyleId: String?,
        protagonistProfileId: String?,
        protagonistName: String? = null,
        protagonistDescription: String? = null,
        protagonistInitialState: String? = null,
        narrativeMinChars: Int? = null,
        narrativePerspective: RpNarrativePerspective? = null,
        plotSuggestionPerspective: RpPlotSuggestionPerspective? = null,
        rpMarkerMode: RpMarkerMode? = null,
    )
}
