package com.muxuechat.data.repository

import com.muxuechat.data.remote.SafeUserFacingError
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.ProviderAuthKind
import java.net.URI

/**
 * 一轮请求冻结时持有的凭据。
 *
 * 两种形态必须分开表达，**不允许用假 API Key 冒充 OAuth**：否则「是否已登录」的真相会分散到
 * Keystore 与 OAuth 仓库两处，假值还可能流进日志或错误提示。
 * 两个实现都是普通类并重写 [toString]，误打印时不会展开凭据。
 */
internal sealed interface ProviderCredential {
    class ApiKey(val value: String) : ProviderCredential {
        override fun toString(): String = "ProviderCredential.ApiKey"
    }

    class OAuthBearer(val accessToken: String, val sessionGeneration: Long? = null) : ProviderCredential {
        fun lease(): XaiAccessTokenLease = XaiAccessTokenLease(accessToken, sessionGeneration)
        override fun toString(): String = "ProviderCredential.OAuthBearer"
    }
}

/** 冻结请求时向 xAI 订阅登录索取一个当前有效的 access token（必要时先刷新）。 */
/** 仅在内存中存在的认证租约；刷新可换 token，但不能改变所属登录会话。 */
class XaiAccessTokenLease(val accessToken: String, val sessionGeneration: Long? = null) {
    override fun toString(): String = "XaiAccessTokenLease"
}

interface XaiAccessTokenSource {
    suspend fun getValidAccessToken(rejectedAccessToken: String? = null): String

    // 默认实现只用于无登录生命周期的替身；生产仓库严格要求非空会话世代。
    suspend fun acquireAccessToken(): XaiAccessTokenLease = XaiAccessTokenLease(getValidAccessToken())

    suspend fun refreshAccessToken(lease: XaiAccessTokenLease): XaiAccessTokenLease =
        XaiAccessTokenLease(getValidAccessToken(lease.accessToken), lease.sessionGeneration)

    suspend fun validateSession(lease: XaiAccessTokenLease) = Unit

    /** 校验与动作在同一认证临界区；动作不得再调用认证方法。 */
    suspend fun <T> withCurrentSession(lease: XaiAccessTokenLease, block: suspend () -> T): T {
        validateSession(lease)
        return block()
    }
}

/**
 * xAI 供应商的地址由 App 钉死、也不使用 API Key，唯一可能缺的就是模型名。
 *
 * 因此绝不能落到「请检查 Base URL、模型和 API Key」那句兜底上——
 * 那两项在 xAI 供应商的界面上根本不存在，用户会被指向找不到的输入框。
 */
internal class XaiProviderNotConfiguredException(providerName: String) :
    IllegalStateException("请先为「$providerName」选择模型：在该供应商设置里获取模型列表，或手动添加模型名。"),
    SafeUserFacingError

/**
 * 单轮请求的不可变路由快照。凭据只在内存中短暂保留，绝不进入日志、消息或持久化层。
 * 后台 RP 补写复用同一快照，避免用户切换设置后把同一轮请求拆到两个 Provider。
 */
// 普通类不会像 data class 一样在误调用 toString() 时展开凭据、Base URL 与模型名。
internal class ProviderRequestSnapshot(
    val provider: ChatProviderId,
    val settings: ChatSettings,
    val credential: ProviderCredential,
    val baseUrl: String,
    val model: String,
    /** 自定义供应商的 `reasoning_effort`；DeepSeek 走自己的 thinking 协议，这里恒为 null。 */
    val reasoningEffort: String? = null,
    /** 该供应商是否已由用户确认支持 `stream_options`；DeepSeek 不需要：其官方流式默认就在最后一个 chunk 返回 usage，恒为 false。 */
    val includeStreamUsage: Boolean = false,
) {
    /**
     * DeepSeek 协议只接受 API Key，因此只有 DeepSeek 链路可以调用本方法。
     *
     * 写成方法而不是属性是刻意的：它**会失败**，而属性读起来像个普通字段。
     * OAuth 凭据在这里直接报错，绝不把 bearer token 当作 API Key 发出去。
     * OpenAI 兼容链路一律走 [ChatTransportRouter]，由它按凭据类型分派传输实现。
     */
    fun requireApiKey(): String = when (credential) {
        is ProviderCredential.ApiKey -> credential.value
        is ProviderCredential.OAuthBearer ->
            error("DeepSeek 协议不接受 OAuth 凭据，这是一个路由错误")
    }
}

/**
 * 在请求开始前冻结路由与凭据。
 *
 * - API Key 型（DeepSeek 与普通自定义供应商）：设置与 Key 在 [SettingsRepository] 的同一临界区内读取，
 *   校验顺序与报错文案保持改动前原样。
 * - xAI OAuth 型：设置在临界区内冻结，随后在临界区**外**向 [XaiAccessTokenSource] 取 token。
 *   刷新是网络请求，不能压在设置锁里阻塞所有设置写入；期间用户登出会让取 token 直接失败，
 *   不会得到一个已失效的快照。
 */
internal class ProviderRequestFreezer(
    private val settingsRepository: SettingsRepository,
    private val xaiAccessTokens: XaiAccessTokenSource,
) {
    suspend fun freeze(onProviderSelected: (ChatProviderId) -> Unit = {}): ProviderRequestSnapshot {
        val frozen = settingsRepository.withCurrentProviderCredentials { settings, apiKey ->
            val normalized = settings.normalized()
            onProviderSelected(normalized.provider)
            when (normalized.provider) {
                ChatProviderId.DEEPSEEK -> Frozen.Complete(
                    ProviderRequestSnapshot(
                        provider = ChatProviderId.DEEPSEEK,
                        settings = normalized,
                        credential = ProviderCredential.ApiKey(
                            apiKey?.takeIf { it.isNotBlank() }
                                ?: error("请先在设置中保存 DeepSeek API Key"),
                        ),
                        baseUrl = normalized.activeBaseUrl,
                        model = normalized.activeModel,
                    ),
                )

                ChatProviderId.CUSTOM_OPENAI -> {
                    val activeProvider = normalized.activeCustomProvider
                        ?: error("请先在设置中添加一个供应商")
                    // 配置校验必须按认证类型分开：两类供应商缺的东西和能改的东西都不一样。
                    when (activeProvider.authKind) {
                        ProviderAuthKind.API_KEY -> {
                            if (!normalized.isCustomProviderConfigured) {
                                error("请先在设置中配置「${activeProvider.displayName}」的地址和模型")
                            }
                            Frozen.Complete(
                                customProviderSnapshot(
                                    settings = normalized,
                                    activeProvider = activeProvider,
                                    baseUrl = normalized.activeBaseUrl,
                                    credential = ProviderCredential.ApiKey(
                                        apiKey?.takeIf { it.isNotBlank() }
                                            ?: error("请先在设置中保存「${activeProvider.displayName}」的 API Key"),
                                    ),
                                ),
                            )
                        }

                        // 刻意忽略 apiKey：OAuth 供应商与 Keystore 里的任何 Key 都无关。
                        // 地址是钉死常量，必然非空，所以唯一可能缺的就是模型名。
                        ProviderAuthKind.XAI_OAUTH -> {
                            if (normalized.activeModel.isBlank()) {
                                throw XaiProviderNotConfiguredException(activeProvider.displayName)
                            }
                            Frozen.NeedsXaiToken(normalized, activeProvider)
                        }
                    }
                }
            }
        }

        return when (frozen) {
            is Frozen.Complete -> frozen.snapshot.also { validateProviderRequest(it.baseUrl, it.model) }
            is Frozen.NeedsXaiToken -> {
                // 地址不取自用户数据，直接用钉死常量，再校验一次作为纵深防御。
                val baseUrl = CustomProvider.XAI_SUBSCRIPTION_BASE_URL
                validateProviderRequest(baseUrl, frozen.settings.activeModel)
                val lease = xaiAccessTokens.acquireAccessToken()
                customProviderSnapshot(
                    settings = frozen.settings,
                    activeProvider = frozen.activeProvider,
                    baseUrl = baseUrl,
                    credential = ProviderCredential.OAuthBearer(lease.accessToken, lease.sessionGeneration),
                )
            }
        }
    }

    private fun customProviderSnapshot(
        settings: ChatSettings,
        activeProvider: CustomProvider,
        baseUrl: String,
        credential: ProviderCredential,
    ) = ProviderRequestSnapshot(
        provider = ChatProviderId.CUSTOM_OPENAI,
        settings = settings,
        credential = credential,
        baseUrl = baseUrl,
        model = settings.activeModel,
        reasoningEffort = activeProvider.reasoningEffort.takeIf { it.isNotBlank() },
        includeStreamUsage = activeProvider.streamUsageEnabled,
    )

    /** 只做本地结构校验；http/https 均保留，不能借预检改变用户确认过的明文 HTTP 策略。 */
    private fun validateProviderRequest(baseUrl: String, model: String) {
        require(model.isNotBlank()) { "请先配置要使用的模型" }
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
            ?: error("Provider Base URL 格式无效")
        val supportedScheme = uri.scheme.equals("https", ignoreCase = true) ||
            uri.scheme.equals("http", ignoreCase = true)
        val validPort = uri.port == -1 || uri.port in 1..65_535
        require(
            supportedScheme &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                validPort,
        ) { "Provider Base URL 必须是有效的 http(s) 地址，且不能包含账号信息、查询参数或片段" }
    }

    /** 临界区内的冻结结果；不持有 token，因此可以安全地离开设置锁。 */
    private sealed interface Frozen {
        class Complete(val snapshot: ProviderRequestSnapshot) : Frozen

        class NeedsXaiToken(val settings: ChatSettings, val activeProvider: CustomProvider) : Frozen
    }
}
