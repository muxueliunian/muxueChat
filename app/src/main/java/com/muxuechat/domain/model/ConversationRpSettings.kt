package com.muxuechat.domain.model

data class ConversationRpSettings(
    val conversationId: String,
    val isRpEnabled: Boolean = false,
    val roleCardId: String? = null,
    val worldSettingId: String? = null,
    val writingStyleId: String? = null,
    val protagonistProfileId: String? = null,
    /**
     * 本会话有效的数值定义快照（R6 / R10），绑定角色卡或主角时冻结。
     *
     * 改档案不影响已绑定的会话，靠的就是这一列；它放在会话配置里而不是面板里，
     * 因为面板会被删除重建，快照跟着没了就失去了意义。
     */
    val metricSnapshot: List<RpMetricSnapshot> = emptyList(),
    /**
     * v9 的主角三字段，**正在停用**。
     *
     * 过渡期（RP-2 之前）prompt 与 RP 设置界面仍读写它们；RP-2 把 prompt 切到
     * [protagonistProfileId] 指向的档案后，它们只剩取证与修复材料的价值。
     * 保留它们不是为了回滚——数据库没有降级路径，旧 APK 装回去打不开新库（§8.6）。
     */
    val protagonistName: String = "",
    val protagonistDescription: String = "",
    val protagonistInitialState: String = "",
    val narrativeMinChars: Int = 0,
    val narrativePerspective: RpNarrativePerspective = RpNarrativePerspective.DEFAULT,
    val plotSuggestionPerspective: RpPlotSuggestionPerspective = RpPlotSuggestionPerspective.DEFAULT,
    val rpMarkerMode: RpMarkerMode = RpMarkerMode.DEFAULT,
    val updatedAtMillis: Long,
)
