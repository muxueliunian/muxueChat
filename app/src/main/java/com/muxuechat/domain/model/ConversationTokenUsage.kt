package com.muxuechat.domain.model

/**
 * 某个会话最近一次**正文**请求由服务端返回的真实用量。
 *
 * 这是估算之外唯一可信的数字，因此要和它是哪个供应商、哪个模型、什么时候产生的一起保存：
 * 用户切换模型后这份数据仍然有参考价值，但绝不能拿去当当前模型的占用百分比。
 *
 * 三个 token 字段都可空——上游不返回 usage 是常态，不能用 0 冒充「用了 0 个 token」。
 */
data class ConversationTokenUsage(
    val conversationId: String,
    /** 产生这份用量的供应商标识（DeepSeek 或自定义供应商 id）。 */
    val providerKey: String,
    val model: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    /** 采集时该模型的 95% 安全窗口，用于解释这份用量占了多少。 */
    val effectiveContextTokens: Long,
    val capturedAtMillis: Long,
) {
    val hasAnyValue: Boolean
        get() = promptTokens != null || completionTokens != null || totalTokens != null

    /** 这份用量是否来自当前正在使用的供应商与模型。不是的话 UI 要标注「旧模型数据」。 */
    fun matches(providerKey: String, model: String): Boolean =
        this.providerKey == providerKey && this.model == model
}

/**
 * 一次网络响应里的原始用量字段，尚未绑定会话。
 *
 * 归一化规则集中在 [TokenUsageNormalizer]，避免每个调用点各写一套「反推 prompt」的算法。
 */
data class TokenUsageSnapshot(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
)

object TokenUsageNormalizer {
    /**
     * 把上游返回的三个可空字段收敛成一份可用快照。
     *
     * - 三个都缺 → null，调用方必须保留上一份有效快照，而不是写入一行空数据。
     * - 缺 `prompt_tokens` 时，**只有** `total_tokens` 与 `completion_tokens` 同时存在才反推。
     *   只有 `total_tokens` 时绝不拿它冒充输入 token——那会让上下文占用凭空多出输出部分。
     * - 负数一律当作缺失：个别代理会回 -1 表示「不支持」。
     */
    fun normalize(promptTokens: Int?, completionTokens: Int?, totalTokens: Int?): TokenUsageSnapshot? {
        val prompt = promptTokens.nonNegativeOrNull()
        val completion = completionTokens.nonNegativeOrNull()
        val total = totalTokens.nonNegativeOrNull()

        val resolvedPrompt = prompt ?: derivePrompt(total, completion)
        val resolvedTotal = total ?: sumOrNull(resolvedPrompt, completion)

        if (resolvedPrompt == null && completion == null && resolvedTotal == null) return null

        return TokenUsageSnapshot(
            promptTokens = resolvedPrompt,
            completionTokens = completion,
            totalTokens = resolvedTotal,
        )
    }

    private fun derivePrompt(total: Int?, completion: Int?): Int? {
        if (total == null || completion == null) return null
        return (total - completion).takeIf { it >= 0 }
    }

    private fun sumOrNull(prompt: Int?, completion: Int?): Int? {
        if (prompt == null || completion == null) return null
        return prompt + completion
    }

    private fun Int?.nonNegativeOrNull(): Int? = this?.takeIf { it >= 0 }
}
