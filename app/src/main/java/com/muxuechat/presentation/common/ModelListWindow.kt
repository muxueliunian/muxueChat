package com.muxuechat.presentation.common

/**
 * 一次实际渲染出来的模型子集。
 *
 * @param pinned 永远显示的模型：当前选中项、以及用户已经配置过的项。
 *   它们数量很少，但必须最先出现，否则用户在两千个模型里找不到自己的东西。
 * @param others 其余按需展示的模型，已经过搜索过滤并截断。
 * @param hiddenCount 因为截断而没有渲染的数量。>0 时 UI 必须说明还有多少没显示。
 * @param isFiltered 当前是否处于搜索状态，用于区分「没有模型」和「没搜到」。
 */
data class ModelListWindow(
    val pinned: List<String>,
    val others: List<String>,
    val hiddenCount: Int,
    val isFiltered: Boolean,
) {
    val isEmpty: Boolean get() = pinned.isEmpty() && others.isEmpty()

    companion object {
        /**
         * 单次最多渲染多少个非置顶模型。
         *
         * 目录上限是 2000（[com.muxuechat.domain.model.DeepSeekModels.MAX_CACHED_COUNT]）。
         * 在普通 verticalScroll 里急切组合两千个输入框会直接把设置页拖垮，
         * 所以未搜索时只渲染前若干个，其余交给搜索框。
         */
        const val MAX_RENDERED_OTHERS = 30

        /**
         * 计算本次要渲染的模型。
         *
         * @param all 完整目录，可能有两千项。
         * @param pinnedIds 必须常驻的模型 ID，按调用方给的顺序保留。
         * @param query 用户输入的搜索词；空串表示未搜索。
         */
        fun of(
            all: List<String>,
            pinnedIds: List<String>,
            query: String,
            maxRenderedOthers: Int = MAX_RENDERED_OTHERS,
        ): ModelListWindow {
            val normalizedQuery = query.trim()
            val pinnedSet = pinnedIds.toSet()
            // 置顶项也参与搜索：搜索时用户要的是「符合条件的全部」，
            // 而不是被强行插在结果前面的当前模型。
            val pinned = pinnedIds.filter { it.matches(normalizedQuery) }
            val candidates = all.asSequence()
                .filter { it !in pinnedSet }
                .filter { it.matches(normalizedQuery) }
                .toList()
            val others = candidates.take(maxRenderedOthers.coerceAtLeast(0))
            return ModelListWindow(
                pinned = pinned,
                others = others,
                hiddenCount = candidates.size - others.size,
                isFiltered = normalizedQuery.isNotEmpty(),
            )
        }

        private fun String.matches(query: String): Boolean =
            query.isEmpty() || contains(query, ignoreCase = true)
    }
}
