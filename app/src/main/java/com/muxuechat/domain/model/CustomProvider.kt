package com.muxuechat.domain.model

import java.net.URI

/**
 * 用户自定义的 OpenAI 兼容供应商。
 *
 * 这里只描述"往哪发、发什么模型"，**不包含 API Key**：
 * Key 由 [com.muxuechat.data.local.EncryptedProviderKeyStore] 按 [id] 单独加密保存。
 * 因此本对象可以安全地序列化进 DataStore。
 */
data class CustomProvider(
    val id: String,
    /** 用户自定义显示名，例如 "CLIProxyAPI"、"OpenRouter"。 */
    val name: String,
    /** OpenAI 兼容端点根地址，不含 /chat/completions。 */
    val baseUrl: String,
    /** 可用模型缓存：来自 GET /models 拉取，或用户手填。 */
    val models: List<String> = emptyList(),
    val selectedModel: String = "",
    val modelsUpdatedAtMillis: Long = 0L,
    /**
     * 此端点是否支持标准 OpenAI `tools` / `tool_calls`。
     *
     * 默认关闭，避免升级后向只支持普通聊天的旧反代发送未知字段而导致 400。用户可在
     * 对应供应商设置中按服务实际能力显式开启；这不是 Gemini 等厂商的特判。
     */
    val toolCallingEnabled: Boolean = false,
    /**
     * OpenAI 系的 `reasoning_effort` 取值，例如 low / medium / high。
     *
     * 空串表示**完全不发送**该字段：不支持思考的模型收到未知字段可能直接 400，
     * 因此默认不发，由用户按自己服务的实际支持情况填写。
     * 这里不做枚举校验——各家取值并不统一（还有 minimal、none 等），
     * 预设只是 UI 的快捷填充。
     */
    val reasoningEffort: String = "",
) {
    /** 名称、地址、模型三者齐备才可发起请求。 */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && selectedModel.isNotBlank()

    val displayName: String
        get() = name.ifBlank { baseUrl.hostOrRaw() }

    /**
     * 收敛用户输入：地址非法一律归零，由调用方据 [isConfigured] 拦截，
     * 而不是把坏地址一路带到 OkHttp 再抛异常。
     */
    fun normalized(): CustomProvider {
        val normalizedModels = models.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val normalizedSelected = selectedModel.trim()
        return copy(
            name = name.trim().take(MAX_NAME_LENGTH),
            baseUrl = normalizeBaseUrl(baseUrl),
            models = normalizedModels,
            // 选中的模型未必在列表里（用户手填、或服务端未列出），这是允许的。
            selectedModel = normalizedSelected.ifBlank { normalizedModels.firstOrNull().orEmpty() },
            reasoningEffort = reasoningEffort.trim().take(MAX_REASONING_EFFORT_LENGTH),
        )
    }

    companion object {
        const val MAX_NAME_LENGTH = 40
        const val MAX_REASONING_EFFORT_LENGTH = 20

        /** UI 快捷填充用，不是取值白名单。 */
        val REASONING_EFFORT_PRESETS = listOf("low", "medium", "high")

        /**
         * 接受 http 与 https。
         *
         * **安全权衡**：经用户确认，为了接入没有证书的局域网自建服务，
         * 这里允许明文 http，且不限制地址范围。代价是——若填的是公网 http 地址，
         * API Key 与聊天正文都会以明文经过链路，可被同网段或中间设备读取。
         * 因此 http 只应用于自己可控的局域网服务。
         *
         * 仍然不允许 user-info / query / fragment，避免误填的认证信息落入 DataStore 或日志。
         * 非法值返回空串。
         */
        fun normalizeBaseUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            if (trimmed.isBlank()) return ""

            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return ""
            val hasValidPort = uri.port == -1 || uri.port in 1..65_535
            val hasSupportedScheme = uri.scheme.equals("https", ignoreCase = true) ||
                uri.scheme.equals("http", ignoreCase = true)
            val isValid = hasSupportedScheme &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                hasValidPort

            return trimmed.takeIf { isValid }.orEmpty()
        }

        /** 明文 http 端点。UI 据此给出提示，不阻断使用。 */
        fun isCleartext(baseUrl: String): Boolean =
            baseUrl.trim().startsWith("http://", ignoreCase = true)

        private fun String.hostOrRaw(): String =
            runCatching { URI(this).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: this
    }
}
