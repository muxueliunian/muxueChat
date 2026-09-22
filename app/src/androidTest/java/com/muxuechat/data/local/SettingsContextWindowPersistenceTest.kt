package com.muxuechat.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muxuechat.domain.context.ModelContextCatalog
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.DeepSeekModels
import com.muxuechat.domain.model.ReasoningEffort
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 上下文相关设置的持久化与**旧数据兼容**。
 *
 * 重点是升级路径：旧版本写下的供应商 JSON 里没有 `modelContextWindows` 与
 * `streamUsageEnabled`，读到它们时必须安静地取默认值，而不是把整份供应商列表丢掉。
 */
@RunWith(AndroidJUnit4::class)
class SettingsContextWindowPersistenceTest {
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: SettingsDataStore
    private lateinit var scope: CoroutineScope
    private lateinit var file: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "settings-context-${UUID.randomUUID()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        store = SettingsDataStore(dataStore)
    }

    @After
    fun tearDown() = runBlocking {
        scope.coroutineContext[Job]?.cancelAndJoin()
        file.delete()
        Unit
    }

    /** 旧 JSON 缺少新字段时必须正常解码，且新字段取安全默认值。 */
    @Test
    fun legacyProviderJson_withoutNewFields_decodesWithSafeDefaults() = runBlocking {
        dataStore.edit { prefs ->
            prefs[CUSTOM_PROVIDERS] = """
                [{"id":"p1","name":"旧供应商","baseUrl":"https://example.com",
                  "models":["model-a"],"selectedModel":"model-a"}]
            """.trimIndent()
        }

        val provider = store.settings.first().customProviders.single()

        assertEquals("p1", provider.id)
        assertEquals(listOf("model-a"), provider.models)
        assertEquals(emptyMap<String, Long>(), provider.modelContextWindows)
        // 旧反代收到未知字段可能直接 400，升级不能替用户打开这个开关。
        assertFalse(provider.streamUsageEnabled)
    }

    /** 坏 JSON 只应退化成空列表，不能让整个设置流崩掉导致 App 打不开。 */
    @Test
    fun corruptedProviderJson_degradesToEmptyList() = runBlocking {
        dataStore.edit { prefs -> prefs[CUSTOM_PROVIDERS] = "{不是合法 JSON" }

        assertTrue(store.settings.first().customProviders.isEmpty())
    }

    @Test
    fun providerContextWindows_roundTrip() = runBlocking {
        store.updateSettings {
            ChatSettings(
                provider = ChatProviderId.CUSTOM_OPENAI,
                customProviders = listOf(
                    CustomProvider(
                        id = "p1",
                        name = "自建",
                        baseUrl = "https://example.com",
                        models = listOf("model-a", "model-b"),
                        selectedModel = "model-a",
                        modelContextWindows = mapOf("model-a" to 128_000L),
                        streamUsageEnabled = true,
                    ),
                ),
                activeCustomProviderId = "p1",
            )
        }

        val provider = store.settings.first().customProviders.single()
        assertEquals(mapOf("model-a" to 128_000L), provider.modelContextWindows)
        assertTrue(provider.streamUsageEnabled)
    }

    /** `/models` 刷新会整体替换模型列表，但不能顺手把用户填过的窗口清掉。 */
    @Test
    fun refreshingModels_keepsStoredContextWindows() = runBlocking {
        store.updateSettings {
            ChatSettings(
                provider = ChatProviderId.CUSTOM_OPENAI,
                customProviders = listOf(
                    CustomProvider(
                        id = "p1",
                        name = "自建",
                        baseUrl = "https://example.com",
                        models = listOf("model-a", "model-b"),
                        selectedModel = "model-a",
                        modelContextWindows = mapOf("model-a" to 128_000L, "model-b" to 64_000L),
                    ),
                ),
                activeCustomProviderId = "p1",
            )
        }

        store.updateSettings { current ->
            current.copy(
                customProviders = current.customProviders.map { provider ->
                    provider.copy(models = listOf("model-a"), selectedModel = "model-a")
                },
            )
        }

        val provider = store.settings.first().customProviders.single()
        assertEquals(128_000L, provider.modelContextWindows["model-a"])
        assertEquals(64_000L, provider.modelContextWindows["model-b"])
    }

    @Test
    fun deepSeekWindowsCostCapAndTargetPercent_roundTrip() = runBlocking {
        store.updateSettings {
            ChatSettings(
                deepSeekModelContextWindows = mapOf("deepseek-v4-pro" to 500_000L),
                contextCostCapEnabled = true,
                contextCostCapTokens = 80_000L,
                contextAutoTriggerPercent = 80,
                contextTargetPercent = 30,
            )
        }

        val settings = store.settings.first()
        assertEquals(mapOf("deepseek-v4-pro" to 500_000L), settings.deepSeekModelContextWindows)
        assertTrue(settings.contextCostCapEnabled)
        assertEquals(80_000L, settings.contextCostCapTokens)
        // targetPercent 以前根本没有持久化 key，每次读回来都被重置成默认值。
        assertEquals(30, settings.contextTargetPercent)
        assertEquals(30, settings.contextCompressionPolicy.targetPercent)
    }

    @Test
    fun unknownDeepSeekModelAndLowEffort_roundTrip() = runBlocking {
        store.updateSettings {
            ChatSettings(
                model = "deepseek-v4-future",
                deepSeekCachedModelIds = listOf("deepseek-v4-flash", "future-id"),
                deepSeekModelsUpdatedAtMillis = 99L,
                reasoningEffort = ReasoningEffort.LOW,
            )
        }

        val settings = store.settings.first()
        assertEquals("deepseek-v4-future", settings.model)
        assertEquals(listOf("deepseek-v4-flash", "future-id"), settings.deepSeekCachedModelIds)
        assertEquals(99L, settings.deepSeekModelsUpdatedAtMillis)
        assertEquals(ReasoningEffort.LOW, settings.reasoningEffort)
    }

    /** 生产环境通过 `produceMigrations` 注册；这里验证真实文件 DataStore 在首次读取前完成迁移。 */
    @Test
    fun legacyDeepSeekModelIds_areMigratedBeforeFirstRead() = runBlocking {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("model")] = DeepSeekModels.V4_FLASH
            prefs[stringPreferencesKey("deepseek_model_context_windows")] = """{"deepseek-v4-flash":200000}"""
        }
        scope.coroutineContext[Job]?.cancelAndJoin()

        // 同一文件同时只能有一个活跃 DataStore，所以换一个新 scope 重新打开。
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val migratedStore = SettingsDataStore(
            PreferenceDataStoreFactory.create(
                migrations = listOf(DeepSeekModelIdMigration),
                scope = scope,
                produceFile = { file },
            ),
        )

        val settings = migratedStore.settings.first()
        assertEquals(DeepSeekModels.FLASH, settings.model)
        assertEquals(mapOf(DeepSeekModels.FLASH to 200_000L), settings.deepSeekModelContextWindows)

        migratedStore.updateSettings { it.copy(model = DeepSeekModels.V4_FLASH) }
        assertEquals(DeepSeekModels.V4_FLASH, migratedStore.settings.first().model)
    }

    /** 全新安装的默认值：成本上限关闭，DeepSeek 用内置窗口。 */
    @Test
    fun freshInstall_hasCostCapDisabledAndNoOverrides() = runBlocking {
        val settings = store.settings.first()

        assertFalse(settings.contextCostCapEnabled)
        assertEquals(emptyMap<String, Long>(), settings.deepSeekModelContextWindows)
        assertEquals(
            ModelContextCatalog.DEEPSEEK_V4_CONTEXT_TOKENS,
            ModelContextCatalog.resolve(settings).contextWindowTokens,
        )
    }

    private companion object {
        val CUSTOM_PROVIDERS = stringPreferencesKey("custom_providers")
    }
}
