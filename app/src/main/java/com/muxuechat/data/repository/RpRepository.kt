package com.muxuechat.data.repository

import com.muxuechat.domain.model.ConversationRpSettings
import com.muxuechat.domain.model.ProtagonistDraft
import com.muxuechat.domain.model.ProtagonistProfile
import com.muxuechat.domain.model.ProtagonistProfileDraft
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RoleCardDraft
import com.muxuechat.domain.model.RpMarkerMode
import com.muxuechat.domain.model.RpEntryOwner
import com.muxuechat.domain.model.RpMetricSnapshot
import com.muxuechat.domain.model.RpNarrativePerspective
import com.muxuechat.domain.model.RpPlotSuggestionPerspective
import com.muxuechat.domain.model.RpSettingKind
import com.muxuechat.domain.model.RpSettingRef
import com.muxuechat.domain.model.RpStatusPanel
import com.muxuechat.domain.model.SupportingCharacter
import com.muxuechat.domain.model.SupportingCharacterDraft
import com.muxuechat.domain.model.WorldSetting
import com.muxuechat.domain.model.WorldSettingDraft
import com.muxuechat.domain.model.WritingStyleProfile
import com.muxuechat.domain.model.WritingStyleProfileDraft
import com.muxuechat.domain.tool.SettingsUpdateTarget
import kotlinx.coroutines.flow.Flow

/** 一次草稿保存的结果，供 UI 提示与后续绑定。 */
data class SavedGeneratedSettings(
    val roleCard: RoleCard? = null,
    val worldSetting: WorldSetting? = null,
    val writingStyle: WritingStyleProfile? = null,
    /** 工具生成的主角：保存为独立档案并绑定到会话。 */
    val protagonistProfile: ProtagonistProfile? = null,
    val supportingCharacter: SupportingCharacter? = null,
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

    /** 档案 id → 绑定它的会话数（含暂停 RP 的会话）；没有会话绑定的档案不在表里。 */
    fun observeProtagonistProfileUsage(): Flow<Map<String, Int>>

    /**
     * 「另存为新档案，只给本会话用」（RP-3b）：用 [draft] 新建一份档案，并把本会话从
     * [sourceProfileId] 改绑过去。源档案与其它会话都不动。
     *
     * 这**不是换主角**：草稿沿用源档案的词条 id，主角数值按 entryId 保留，
     * 新增的条用初始值、删掉的条移除（与 [syncMetricDefinitionsFromProfiles] 同一规则）；角色组不动。
     * 本会话当前没绑 [sourceProfileId] 时抛异常，避免把过期的编辑界面落到别的档案上。
     */
    suspend fun forkProtagonistProfileForConversation(
        conversationId: String,
        sourceProfileId: String,
        draft: ProtagonistProfileDraft,
    ): ProtagonistProfile

    fun observeSupportingCharacters(): Flow<List<SupportingCharacter>>
    suspend fun saveSupportingCharacter(id: String?, draft: SupportingCharacterDraft): SupportingCharacter
    suspend fun deleteSupportingCharacter(characterId: String)
    fun observeConversationSupportingCharacters(conversationId: String): Flow<List<SupportingCharacter>>

    /** 配角 id → 勾选它的会话数；没有会话勾选的配角不在表里。 */
    fun observeSupportingCharacterUsage(): Flow<Map<String, Int>>

    /** 整体替换会话勾选的配角，顺序即列表顺序。配角不进状态面板，不涉及数值。 */
    suspend fun setConversationSupportingCharacters(conversationId: String, characterIds: List<String>)

    /**
     * 把会话的数值定义快照重新对齐到所绑档案的**最新**定义：按 entryId 保留现有数值，
     * 新增的条用初始值，删掉的条移除。**只影响这一个会话**。
     *
     * @return 定义确实有变化并已同步时为 true；本来就一致时为 false，不写库。
     */
    suspend fun syncMetricDefinitionsFromProfiles(
        conversationId: String,
        owners: Set<RpEntryOwner> = setOf(RpEntryOwner.ROLE_CARD, RpEntryOwner.PROTAGONIST),
    ): Boolean

    /** 修正当前数值；定义过期或输入越界时拒绝，不覆盖其它项，并维护最近有效回复的历史检查点。 */
    suspend fun updateMetricValue(
        conversationId: String,
        expectedDefinition: RpMetricSnapshot,
        value: Int,
    ): RpStatusPanel

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
        /** 绑定时追加到会话已勾选配角的末尾。 */
        supportingCharacter: SupportingCharacterDraft? = null,
        bindToConversationId: String?,
    ): SavedGeneratedSettings

    /**
     * 删除设定工具请求、并经用户确认的若干项（全部在一个事务里，任一失败整体回滚）。
     * 每类走各自的删除入口，绑定变化按第 4 节的转换规则处理。已不存在的项跳过。
     *
     * @return 实际删除的项数。
     */
    suspend fun deleteSettings(refs: List<RpSettingRef>): Int

    /**
     * 用户确认 AI 的修改请求（`update_rp_setting`）后覆盖原设定。只用与 [target] 类型对应的那份草稿。
     *
     * - 生成请求后这项设定又被改过（`updatedAtMillis` 变了）或已删除时抛异常，不覆盖别处的修改。
     * - 角色卡 / 主角的状态条定义变了时，**所有**绑定它的会话立即对齐到新定义（用户 2026-09-25 选定，
     *   是 R6「改档案不影响会话」的例外）：定义被改动的状态条回到新的初始值，并从历史快照里移除；
     *   新增的用初始值，删掉的移除，没动的保留原值。
     *
     * @return 状态条被重置的会话数。
     */
    suspend fun applySettingsUpdate(
        target: SettingsUpdateTarget,
        roleCard: RoleCardDraft?,
        worldSetting: WorldSettingDraft?,
        writingStyle: WritingStyleProfileDraft?,
        protagonistProfile: ProtagonistProfileDraft?,
        supportingCharacter: SupportingCharacterDraft?,
    ): Int

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
        narrativeMinChars: Int? = null,
        narrativePerspective: RpNarrativePerspective? = null,
        plotSuggestionPerspective: RpPlotSuggestionPerspective? = null,
        rpMarkerMode: RpMarkerMode? = null,
    )
}
