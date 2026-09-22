package com.muxuechat.presentation.common

import com.muxuechat.domain.context.ModelContextCatalog
import com.muxuechat.domain.model.DeepSeekModelDirectory
import com.muxuechat.domain.model.DeepSeekModelDirectorySource
import com.muxuechat.domain.model.DeepSeekModels

/**
 * 模型行上的一条说明。
 *
 * 用 [tone] 而不是直接给颜色：调用方据此选颜色 **并且** 选前缀文案，
 * 保证「不把颜色当唯一反馈方式」这条要求在每个使用点都成立。
 */
data class ModelNote(
    val text: String,
    val tone: ModelNoteTone,
)

enum class ModelNoteTone {
    /** 中性事实，例如「未配置窗口，按回退值计算」。 */
    NEUTRAL,

    /** 需要用户注意的限制或偏差，例如「仅支持文本输入」。 */
    CAUTION,
}

/**
 * 模型标注文案的唯一出处。
 *
 * 规则（对应 AGENT.md 的模型元数据边界）：
 * - 只认完整模型 ID，**绝不**按名字里是否含 vision / v4 / 32k 猜测能力；
 * - 未知模型只说「App 不知道它的窗口」，不说它支不支持视觉或 RP；
 * - 多模态模型必须明确说明当前 App 只能发文本；
 * - 已迁移走的旧 ID 必须说明已被取代。
 */
object ModelNotes {
    /** 图片输入尚未实现，这条文案不允许在别处另写一份。 */
    const val TEXT_ONLY_INPUT = "当前 App 仅支持文本输入，尚未支持图片。"

    const val LEGACY_FLASH_ID = "旧名，已由 ${DeepSeekModels.FLASH} 取代。"

    const val UNKNOWN_WINDOW_PREFIX = "App 不知道此模型的上下文窗口"

    /** DeepSeek 模型行的标注。 */
    fun deepSeekNotes(
        modelId: String,
        directory: DeepSeekModelDirectory,
        configuredWindowTokens: Long?,
    ): List<ModelNote> {
        val notes = deepSeekCapabilityNotes(modelId).toMutableList()
        if (directory.selectedMissingFromCatalog && modelId == directory.selectedModelId) {
            notes += ModelNote(
                "服务器目录未返回该模型，仍保留当前选择",
                ModelNoteTone.CAUTION,
            )
        }
        notes += windowNote(
            configuredWindowTokens = configuredWindowTokens,
            builtInWindowTokens = DeepSeekModels.knownContextWindowTokens(modelId),
        )
        return notes
    }

    /** 只取决于模型 ID 本身的说明（旧名、仅文本输入），设置页与模型面板共用。 */
    fun deepSeekCapabilityNotes(modelId: String): List<ModelNote> =
        when {
            // 视觉实验旧名仍可能出现在服务器目录里；即使它已被取代，也必须写明 App 只能发文本，
            // 否则用户看到「vision」字样会以为能发图。
            modelId.trim() == DeepSeekModels.V4_FLASH_VISION_EXP -> listOf(
                ModelNote(LEGACY_FLASH_ID, ModelNoteTone.CAUTION),
                ModelNote(TEXT_ONLY_INPUT, ModelNoteTone.CAUTION),
            )
            DeepSeekModels.isLegacyFlashId(modelId) -> listOf(ModelNote(LEGACY_FLASH_ID, ModelNoteTone.CAUTION))
            modelId.trim() == DeepSeekModels.FLASH -> listOf(ModelNote(TEXT_ONLY_INPUT, ModelNoteTone.CAUTION))
            else -> emptyList()
        }

    /**
     * 自定义供应商模型行的标注。
     *
     * 上游是谁与 App 无关，因此这里只有「有没有手填窗口」这一件事可说。
     */
    fun customProviderNotes(configuredWindowTokens: Long?): List<ModelNote> =
        listOf(windowNote(configuredWindowTokens = configuredWindowTokens, builtInWindowTokens = null))

    private fun windowNote(
        configuredWindowTokens: Long?,
        builtInWindowTokens: Long?,
    ): ModelNote = when {
        configuredWindowTokens != null -> ModelNote(
            "上下文窗口 ${formatCompactTokens(configuredWindowTokens)}（你手动配置）",
            ModelNoteTone.NEUTRAL,
        )
        builtInWindowTokens != null -> ModelNote(
            "上下文窗口 ${formatCompactTokens(builtInWindowTokens)}（App 内置已知值）",
            ModelNoteTone.NEUTRAL,
        )
        else -> ModelNote(
            "$UNKNOWN_WINDOW_PREFIX，暂按 " +
                "${formatCompactTokens(ModelContextCatalog.FALLBACK_CONTEXT_TOKENS)} 回退值计算",
            ModelNoteTone.CAUTION,
        )
    }
}

/** 目录来源说明：内置兜底与服务器缓存必须是两句不同的话。 */
fun describeDirectorySource(directory: DeepSeekModelDirectory): String =
    when (directory.source) {
        DeepSeekModelDirectorySource.BUILTIN_FALLBACK ->
            "来源：App 内置兜底列表（尚未成功从服务器刷新）"
        DeepSeekModelDirectorySource.SERVER_CACHE ->
            "来源：服务器目录缓存 · ${describeLastRefreshed(directory.lastRefreshedAtMillis)}"
    }

/** 最近一次成功刷新的时间。0 表示从未成功过，这不是「刚刚刷新过」。 */
fun describeLastRefreshed(timestampMillis: Long): String =
    if (timestampMillis <= 0L) {
        "从未成功刷新"
    } else {
        "上次成功刷新 ${formatConversationTime(timestampMillis)}"
    }

/**
 * 刷新入口的状态文案。
 *
 * 刷新在 ViewModel 层是全局串行的：任意供应商正在刷新时，其他入口点了也会被静默忽略。
 * 因此这里必须让每个入口都能说清楚「现在为什么点不动」，而不是只把自己那一个置灰。
 */
fun describeRefreshAction(
    isRefreshingThis: Boolean,
    isRefreshingOther: Boolean,
): String = when {
    isRefreshingThis -> "正在刷新…"
    isRefreshingOther -> "其他列表刷新中"
    else -> "刷新列表"
}
