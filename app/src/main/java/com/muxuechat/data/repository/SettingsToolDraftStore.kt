package com.muxuechat.data.repository

import com.muxuechat.domain.tool.SettingsToolDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 待确认草稿的内存暂存区。
 *
 * 刻意不落库：草稿是"模型建议"，不是用户数据。进程被杀就丢失，这是可接受的——
 * 反过来如果存进 Room，取消操作就得再写一套清理逻辑，还容易留下孤儿记录。
 */
class SettingsToolDraftStore {
    private val state = MutableStateFlow<List<SettingsToolDraft>>(emptyList())

    val drafts: StateFlow<List<SettingsToolDraft>> = state.asStateFlow()

    fun add(draft: SettingsToolDraft) {
        state.update { current -> (current + draft).takeLast(MAX_PENDING_DRAFTS) }
    }

    fun get(draftId: String): SettingsToolDraft? = state.value.firstOrNull { it.id == draftId }

    fun remove(draftId: String) {
        state.update { current -> current.filterNot { it.id == draftId } }
    }

    fun clear() {
        state.value = emptyList()
    }

    private companion object {
        /** 防止模型反复调用把内存堆满。 */
        const val MAX_PENDING_DRAFTS = 20
    }
}
