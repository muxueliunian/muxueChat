package com.muxuechat.domain.model

import com.muxuechat.domain.context.ModelContextCatalog
import java.net.URI

/**
 * 供应商的认证方式。
 *
 * [XAI_OAUTH] 是 AGENT.md §2 批准的唯一 OAuth 例外：凭据来自 xAI 订阅登录，
 * 不使用、也不读取任何 API Key；服务地址由 App 钉死为 [CustomProvider.XAI_SUBSCRIPTION_BASE_URL]。
 *
 * [GOOGLE_OAUTH] 是 AGENT.md §2 批准的第二个 OAuth 例外：凭据来自 Google 登录（用户自己 GCP 项目里的
 * Android OAuth 客户端），App 不保存任何 token；服务地址由项目 ID 推导并钉死在 aiplatform.googleapis.com。
 */
enum class ProviderAuthKind(val storageValue: String) {
    API_KEY("api_key"),
    XAI_OAUTH("xai_oauth"),
    GOOGLE_OAUTH("google_oauth"),
    ;

    companion object {
        /** 旧 JSON 缺字段或出现未知值时，一律按 API Key 型处理，保持升级前的行为。 */
        fun fromStorage(value: String?): ProviderAuthKind =
            entries.firstOrNull { it.storageValue == value } ?: API_KEY
    }
}

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
    /** 是否在该供应商的请求体中发送全局 temperature；关闭时字段完全省略。 */
    val sendTemperature: Boolean = true,
    /** 是否在该供应商的请求体中发送全局 top_p；关闭时字段完全省略。 */
    val sendTopP: Boolean = true,
    /**
     * OpenAI 系的 `reasoning_effort` 取值，例如 low / medium / high。
     *
     * 空串表示**完全不发送**该字段：不支持思考的模型收到未知字段可能直接 400，
     * 因此默认不发，由用户按自己服务的实际支持情况填写。
     * 这里不做枚举校验——各家取值并不统一（还有 minimal、none 等），
     * 预设只是 UI 的快捷填充。
     */
    val reasoningEffort: String = "",
    /**
     * 该供应商下每个模型的上下文窗口（tokens）。
     *
     * 标准 `GET /models` 只保证返回 `id`，拿不到 context 长度，而 App 不解析任何厂商私有字段，
     * 所以这里只接受用户逐模型手填。未配置的模型会回退到
     * [ModelContextCatalog.FALLBACK_CONTEXT_TOKENS] 并在 UI 上警告。
     */
    val modelContextWindows: Map<String, Long> = emptyMap(),
    /**
     * 是否在流式请求中附带 `stream_options: {"include_usage": true}`。
     *
     * 默认关闭：这是一个可选字段，只支持普通聊天的旧反代收到未知字段可能直接 400。
     * 不开启时流式用量为尽力读取——上游若不主动返回 usage，界面会如实标注「本次上游未返回用量」。
     */
    val streamUsageEnabled: Boolean = false,
    /** 旧数据缺省为 [ProviderAuthKind.API_KEY]。 */
    val authKind: ProviderAuthKind = ProviderAuthKind.API_KEY,
    /** 输出上限字段，旧端点默认沿用 max_tokens。 */
    val outputTokenField: OpenAiOutputTokenField = OpenAiOutputTokenField.MAX_TOKENS,
    /** 可选的每会话标识扩展头；值由 App 填稳定会话 ID，不保存任何凭据。 */
    val conversationHeaderName: String = "",
    /** 仅 [ProviderAuthKind.GOOGLE_OAUTH] 使用：用户填写的 GCP 项目 ID，区域固定 global。 */
    val googleProjectId: String = "",
) {
    val usesXaiOAuth: Boolean
        get() = authKind == ProviderAuthKind.XAI_OAUTH

    val usesGoogleOAuth: Boolean
        get() = authKind == ProviderAuthKind.GOOGLE_OAUTH

    /** 走 OAuth 登录、没有 API Key 的供应商。 */
    val usesOAuth: Boolean
        get() = usesXaiOAuth || usesGoogleOAuth

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
        val normalizedProjectId = googleProjectId.trim()
        return copy(
            name = name.trim().take(MAX_NAME_LENGTH),
            // OAuth token 绝不能跟随用户填写的地址走，xAI 供应商的地址每次归一化都重新钉死。
            baseUrl = when {
                usesXaiOAuth -> XAI_SUBSCRIPTION_BASE_URL
                // Google 的地址只由项目 ID 推导；项目 ID 非法则归零，由 isConfigured 拦截。
                usesGoogleOAuth -> googleBaseUrl(normalizedProjectId)
                else -> normalizeBaseUrl(baseUrl)
            },
            googleProjectId = if (usesGoogleOAuth) normalizedProjectId else "",
            models = normalizedModels,
            // 选中的模型未必在列表里（用户手填、或服务端未列出），这是允许的。
            selectedModel = normalizedSelected.ifBlank { normalizedModels.firstOrNull().orEmpty() },
            reasoningEffort = reasoningEffort.trim().take(MAX_REASONING_EFFORT_LENGTH),
            // 刻意不按 models 裁剪：`/models` 刷新时上游临时不列出某个模型，
            // 不应把用户手填过的窗口悄悄删掉。只有设置页显式移除模型才删对应项。
            modelContextWindows = ChatSettings.normalizeContextWindows(modelContextWindows),
            conversationHeaderName = if (usesOAuth) "" else ConversationHeaderPolicy.normalize(conversationHeaderName),
        )
    }

    companion object {
        const val MAX_NAME_LENGTH = 40
        const val MAX_REASONING_EFFORT_LENGTH = 20

        /** xAI 订阅链路的固定地址（AGENT.md §2），不接受用户编辑，也不适用明文 HTTP 放行策略。 */
        const val XAI_SUBSCRIPTION_BASE_URL = "https://cli-chat-proxy.grok.com/v1"

        const val XAI_DEFAULT_NAME = "xAI 订阅"

        /** Google Vertex 的 OpenAI 兼容端点主机（AGENT.md §2），不接受用户编辑。 */
        const val GOOGLE_VERTEX_HOST = "aiplatform.googleapis.com"

        const val GOOGLE_DEFAULT_NAME = "Google Vertex"

        /** 新建 Google 供应商时预填的模型（实测可用）；`GET /models` 在 Vertex 上 404，只能手填。 */
        const val GOOGLE_DEFAULT_MODEL = "google/gemini-4-argon"

        /** GCP 项目 ID：6~30 位小写字母、数字、连字符，字母开头，不以连字符结尾。 */
        private val GOOGLE_PROJECT_ID_REGEX = Regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$")

        fun isValidGoogleProjectId(value: String): Boolean = GOOGLE_PROJECT_ID_REGEX.matches(value)

        /** 非法项目 ID 返回空串（调用方据 [isConfigured] 拦截），绝不拼出带用户任意字符的地址。 */
        fun googleBaseUrl(projectId: String): String =
            if (isValidGoogleProjectId(projectId)) {
                "https://$GOOGLE_VERTEX_HOST/v1/projects/$projectId/locations/global/endpoints/openapi"
            } else {
                ""
            }

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
