package com.muxuechat.domain.model

import com.muxuechat.domain.context.ModelContextCatalog

/**
 * DeepSeek 当前选中模型是开放的模型 ID，而不是封闭枚举。
 *
 * 本对象只保存默认值、无缓存时的内置兜底，以及**已知**模型的上下文等元数据。
 * 不要再把它做成新的封闭 enum：服务端新增 ID 必须能在不发版的情况下被选中和请求。
 */
object DeepSeekModels {
    /** DeepSeek-V4.1-Flash（2026-09-10 发布），原生多模态。 */
    const val FLASH = "deepseek-flash"
    const val V4_PRO = "deepseek-v4-pro"
    const val DEFAULT_ID = FLASH

    /** 旧名：官方暂时兼容，请求已由 V4.1-Flash 处理。只用于迁移与识别，不再作为默认或兜底。 */
    const val V4_FLASH = "deepseek-v4-flash"

    /** 旧名：视觉实验模型已退役，请求同样由 V4.1-Flash 处理。 */
    const val V4_FLASH_VISION_EXP = "deepseek-v4-flash-vision-exp"

    /** 需要自动迁移到 [FLASH] 的旧 ID。 */
    val LEGACY_FLASH_IDS: Set<String> = setOf(V4_FLASH, V4_FLASH_VISION_EXP)

    /**
     * 首次安装或从未成功刷新时的内置兜底。
     *
     * 这不是服务器实时返回值，UI 必须和缓存列表区分开。
     */
    val BUILTIN_FALLBACK_IDS: List<String> = listOf(
        FLASH,
        V4_PRO,
    )

    /** 与 `GET /models` 解析上限对齐，避免把异常大的缓存写进 DataStore。 */
    const val MAX_CACHED_COUNT = 2_000
    const val MAX_MODEL_ID_LENGTH = 256

    fun normalizeSelectedId(value: String?): String =
        value?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_ID

    fun normalizeCachedIds(ids: List<String>): List<String> =
        ids.map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_MODEL_ID_LENGTH }
            .distinct()
            .take(MAX_CACHED_COUNT)

    fun directory(
        selectedModelId: String,
        cachedModelIds: List<String>,
        lastRefreshedAtMillis: Long = 0L,
    ): DeepSeekModelDirectory {
        val selected = normalizeSelectedId(selectedModelId)
        val cached = normalizeCachedIds(cachedModelIds)
        val source = if (cached.isEmpty()) {
            DeepSeekModelDirectorySource.BUILTIN_FALLBACK
        } else {
            DeepSeekModelDirectorySource.SERVER_CACHE
        }
        val catalogIds = when (source) {
            DeepSeekModelDirectorySource.BUILTIN_FALLBACK -> BUILTIN_FALLBACK_IDS
            DeepSeekModelDirectorySource.SERVER_CACHE -> cached
        }
        val selectedMissingFromCatalog = selected !in catalogIds
        val visibleModelIds = if (selectedMissingFromCatalog) {
            catalogIds + selected
        } else {
            catalogIds
        }
        return DeepSeekModelDirectory(
            selectedModelId = selected,
            cachedModelIds = cached,
            lastRefreshedAtMillis = lastRefreshedAtMillis,
            source = source,
            catalogIds = catalogIds,
            visibleModelIds = visibleModelIds,
            selectedMissingFromCatalog = selectedMissingFromCatalog,
        )
    }

    /**
     * 设置页上下文窗口编辑用的模型 ID：有效目录 + 当前选择 + 仍留有手填窗口的旧 ID。
     *
     * 不按缓存裁剪手填窗口：上游临时不列出某个模型时，不应让配置入口消失。
     */
    fun contextWindowEditorIds(settings: ChatSettings): List<String> {
        val directory = settings.deepSeekModelDirectory
        val extraConfigured = settings.deepSeekModelContextWindows.keys
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in directory.visibleModelIds }
        return directory.visibleModelIds + extraConfigured
    }

    /**
     * 已知官方模型的声明上下文窗口。未知 ID 返回 null，由 [ModelContextCatalog] 走 32K 回退。
     *
     * 只认完整 ID，不根据名字是否含 v4、vision 等字样猜测。
     */
    fun knownContextWindowTokens(modelId: String): Long? =
        when (modelId.trim()) {
            FLASH, V4_PRO, V4_FLASH, V4_FLASH_VISION_EXP -> ModelContextCatalog.DEEPSEEK_V4_CONTEXT_TOKENS
            else -> null
        }

    /** 是否为已迁移走的旧 Flash 名。 */
    fun isLegacyFlashId(modelId: String): Boolean = modelId.trim() in LEGACY_FLASH_IDS

    /**
     * 现有 RP Marker 只保证已知模型不回归。
     *
     * [FLASH] 纳入名单：2026-09-10 起旧名 [V4_FLASH] 的请求已由 V4.1-Flash 处理，
     * 现有 Marker 流量实际就运行在该模型上（仍待真机验收）。
     * 视觉实验旧名和新出现的未知 ID 都不自动开启 Marker，避免按名字扩张 RP。
     */
    fun supportsRpMarker(modelId: String): Boolean =
        when (modelId.trim()) {
            FLASH, V4_FLASH, V4_PRO -> true
            else -> false
        }
}

/** 有效目录来自哪里。内置兜底与服务器缓存必须分开，不能混成同一种状态。 */
enum class DeepSeekModelDirectorySource {
    SERVER_CACHE,
    BUILTIN_FALLBACK,
}

data class DeepSeekModelDirectory(
    val selectedModelId: String,
    val cachedModelIds: List<String>,
    val lastRefreshedAtMillis: Long,
    val source: DeepSeekModelDirectorySource,
    val catalogIds: List<String>,
    val visibleModelIds: List<String>,
    val selectedMissingFromCatalog: Boolean,
) {
    val isFromServerCache: Boolean
        get() = source == DeepSeekModelDirectorySource.SERVER_CACHE
}
