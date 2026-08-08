package com.muxuechat.data.repository

import com.muxuechat.domain.model.ConversationRpSettings
import com.muxuechat.domain.model.RoleCard
import com.muxuechat.domain.model.RoleCardDraft
import com.muxuechat.domain.model.RpMarkerMode
import com.muxuechat.domain.model.RpNarrativePerspective
import com.muxuechat.domain.model.RpPlotSuggestionPerspective
import com.muxuechat.domain.model.RpStatusPanel
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

    fun observeWritingStyleProfiles(): Flow<List<WritingStyleProfile>>
    suspend fun saveWritingStyleProfile(id: String?, draft: WritingStyleProfileDraft): WritingStyleProfile
    suspend fun deleteWritingStyleProfile(writingStyleId: String)

    /**
     * 一次性保存工具草稿里的全部设定，并可选绑定到会话。
     *
     * 三类设定走同一个事务：中途失败不能留下"角色卡存了但世界观没存"的半成品。
     */
    suspend fun saveGeneratedSettings(
        roleCard: RoleCardDraft?,
        worldSetting: WorldSettingDraft?,
        writingStyle: WritingStyleProfileDraft?,
        bindToConversationId: String?,
    ): SavedGeneratedSettings

    /**
     * [writingStyleId] 与 [roleCardId]、[worldSettingId] 一样是**必填**的：
     * null 表示解绑，不是「保持不变」。刻意不给默认值，
     * 逼每个调用点都想清楚要不要动这个绑定，避免默认值静默清空。
     */
    suspend fun updateConversationRpSettings(
        conversationId: String,
        isRpEnabled: Boolean,
        roleCardId: String?,
        worldSettingId: String?,
        writingStyleId: String?,
        protagonistName: String? = null,
        protagonistDescription: String? = null,
        protagonistInitialState: String? = null,
        narrativeMinChars: Int? = null,
        narrativePerspective: RpNarrativePerspective? = null,
        plotSuggestionPerspective: RpPlotSuggestionPerspective? = null,
        rpMarkerMode: RpMarkerMode? = null,
    )
}
