package com.muxuechat.domain.model

/**
 * RP 辅助请求（剧情推进建议、元数据补写、状态兜底）发往哪家供应商。
 *
 * 这三类请求只要稳定的结构化 JSON，不需要正文那档推理能力；正文用 Vertex 这类按量计费的
 * 大模型时，让它们继续跟随正文会把每一轮的成本翻倍。所以允许单独指定走 DeepSeek。
 *
 * 只有这两个取值：它们仍然落在 DeepSeek 与 OpenAI 兼容这两条既有协议里，没有新增传输实现。
 */
enum class RpAuxiliaryProvider(val storageValue: String) {
    /** 跟随当前正文所用的供应商（旧行为）。 */
    FOLLOW_ACTIVE("follow_active"),

    /** 固定走 DeepSeek（使用已保存的 DeepSeek API Key），与正文用哪家无关。 */
    DEEPSEEK("deepseek"),
    ;

    companion object {
        /** 未知或旧版本没有写入的值一律回到旧行为。 */
        fun fromStorage(value: String?): RpAuxiliaryProvider =
            entries.firstOrNull { it.storageValue == value } ?: FOLLOW_ACTIVE
    }
}
