package com.muxuechat.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muxuechat.data.local.EncryptedApiKeyStore
import com.muxuechat.data.local.EncryptedCliProxyApiKeyStore
import com.muxuechat.data.local.EncryptedProviderKeyStore
import com.muxuechat.data.local.SettingsDataStore
import com.muxuechat.data.remote.DeepSeekApiClient
import com.muxuechat.data.remote.OpenAiCompatibleClient
import com.muxuechat.domain.model.CustomProvider
import java.io.File
import java.security.KeyStore
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FIX-11 / TEST-05 的设备级回归。
 *
 * 每个用例使用独立的 DataStore、SharedPreferences 名称和 Android Keystore alias，
 * 不会读取或清理开发者安装包中真实保存的凭据。测试值也仅为固定的合成占位串。
 */
@RunWith(AndroidJUnit4::class)
class SettingsMigrationAndCredentialConcurrencyTest {
    private lateinit var context: Context
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var preferencesDataStore: DataStore<Preferences>
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var providerKeyStore: EncryptedProviderKeyStore
    private lateinit var legacyKeyStore: EncryptedCliProxyApiKeyStore
    private lateinit var deepSeekKeyStore: EncryptedApiKeyStore
    private lateinit var repository: SettingsRepositoryImpl

    private lateinit var providerPreferencesName: String
    private lateinit var legacyPreferencesName: String
    private lateinit var deepSeekPreferencesName: String
    private lateinit var providerAliasPrefix: String
    private lateinit var legacyAlias: String
    private lateinit var deepSeekAlias: String
    private val providerIdsToClean = linkedSetOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val suffix = UUID.randomUUID().toString()
        dataStoreFile = File(context.cacheDir, "settings-migration-$suffix.preferences_pb")
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferencesDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { dataStoreFile },
        )
        settingsDataStore = SettingsDataStore(preferencesDataStore)

        providerPreferencesName = "test-provider-credentials-$suffix"
        legacyPreferencesName = "test-legacy-credentials-$suffix"
        deepSeekPreferencesName = "test-deepseek-credentials-$suffix"
        providerAliasPrefix = "muxuechat_test_provider_${suffix}_"
        legacyAlias = "muxuechat_test_legacy_$suffix"
        deepSeekAlias = "muxuechat_test_deepseek_$suffix"
        providerKeyStore = EncryptedProviderKeyStore(
            context = context,
            preferencesName = providerPreferencesName,
            keyAliasPrefix = providerAliasPrefix,
        )
        legacyKeyStore = EncryptedCliProxyApiKeyStore(
            context = context,
            preferencesName = legacyPreferencesName,
            keyAlias = legacyAlias,
        )
        deepSeekKeyStore = EncryptedApiKeyStore(
            context = context,
            preferencesName = deepSeekPreferencesName,
            keyAlias = deepSeekAlias,
        )
        repository = SettingsRepositoryImpl(
            settingsDataStore = settingsDataStore,
            apiKeyStore = deepSeekKeyStore,
            providerKeyStore = providerKeyStore,
            openAiCompatibleClient = OpenAiCompatibleClient(),
            deepSeekApiClient = DeepSeekApiClient(),
            legacyCliProxyApiKeyStore = legacyKeyStore,
        )
    }

    @After
    fun tearDown() = runBlocking {
        providerIdsToClean.forEach { providerId ->
            runCatching { providerKeyStore.clearApiKey(providerId) }
        }
        runCatching { legacyKeyStore.clearApiKey() }
        runCatching { deepSeekKeyStore.clearApiKey() }
        context.getSharedPreferences(providerPreferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(legacyPreferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(deepSeekPreferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
        dataStoreFile.delete()
        Unit
    }

    @Test
    fun pendingAfterProviderPreparation_reusesStableIdAndCompletesWithoutDuplicate() = runBlocking {
        seedLegacySettingsAndCredential()

        val firstPlan = requireNotNull(settingsDataStore.prepareLegacyCliProxyProviderMigration())
        val firstId = requireNotNull(firstPlan.targetProviderId)
        providerIdsToClean += firstId
        val retriedPlan = settingsDataStore.prepareLegacyCliProxyProviderMigration()

        assertEquals(firstPlan, retriedPlan)
        assertEquals(listOf(firstId), settingsDataStore.settings.first().customProviders.map { it.id })

        repository.migrateLegacyProviderIfNeeded()
        repository.migrateLegacyProviderIfNeeded()

        val migrated = settingsDataStore.settings.first().customProviders
        assertEquals(listOf(firstId), migrated.map { it.id })
        assertEquals(SYNTHETIC_LEGACY_CREDENTIAL, providerKeyStore.getApiKey(firstId))
        assertEquals(
            setOf("$providerAliasPrefix$firstId"),
            providerAliasesInAndroidKeyStore(),
        )
        assertLegacyCredentialRemoved()
        assertMigrationCompleted()
    }

    @Test
    fun pendingAfterTargetCredentialCopy_resumesAtCleanupAndDoesNotCreateAnotherProvider() = runBlocking {
        seedLegacySettingsAndCredential()
        val providerId = prepareTargetProviderId()
        providerIdsToClean += providerId
        providerKeyStore.saveApiKey(providerId, SYNTHETIC_LEGACY_CREDENTIAL)

        repository.migrateLegacyProviderIfNeeded()

        assertEquals(SYNTHETIC_LEGACY_CREDENTIAL, providerKeyStore.getApiKey(providerId))
        assertEquals(listOf(providerId), settingsDataStore.settings.first().customProviders.map { it.id })
        assertLegacyCredentialRemoved()
        assertMigrationCompleted()
    }

    @Test
    fun pendingAfterLegacyCredentialCleanup_resumesAtCompletion() = runBlocking {
        seedLegacySettingsAndCredential()
        val providerId = prepareTargetProviderId()
        providerIdsToClean += providerId
        providerKeyStore.saveApiKey(providerId, SYNTHETIC_LEGACY_CREDENTIAL)
        legacyKeyStore.clearApiKey()

        assertEquals(
            providerId,
            settingsDataStore.prepareLegacyCliProxyProviderMigration()?.targetProviderId,
        )
        repository.migrateLegacyProviderIfNeeded()

        assertEquals(SYNTHETIC_LEGACY_CREDENTIAL, providerKeyStore.getApiKey(providerId))
        assertLegacyCredentialRemoved()
        assertMigrationCompleted()
    }

    @Test
    fun cleanupOnlyWithoutLegacyUrl_clearsOrphanCredentialWithoutCreatingProvider() = runBlocking {
        seedLegacySettingsAndCredential(baseUrl = "")
        val cleanupPlan = requireNotNull(settingsDataStore.prepareLegacyCliProxyProviderMigration())

        assertNull(cleanupPlan.targetProviderId)

        repository.migrateLegacyProviderIfNeeded()

        assertTrue(settingsDataStore.settings.first().customProviders.isEmpty())
        assertLegacyCredentialRemoved()
        assertMigrationCompleted()
    }

    @Test
    fun cleanupOnlyWithCorruptedLegacyCiphertext_stillClearsAndCompletes() = runBlocking {
        seedLegacySettingsWithCorruptedCredential(baseUrl = "")
        assertTrue(runCatching { legacyKeyStore.getApiKey() }.isFailure)

        repository.migrateLegacyProviderIfNeeded()

        assertTrue(settingsDataStore.settings.first().customProviders.isEmpty())
        assertLegacyCredentialRemoved()
        assertMigrationCompleted()
    }

    @Test
    fun cleanupOnlyWithUnrelatedProvider_preservesItsCredentialAndSettings() = runBlocking {
        val providerId = "unrelated-${UUID.randomUUID()}"
        providerIdsToClean += providerId
        repository.upsertCustomProvider(testProvider(providerId, 0))
        repository.saveProviderKey(providerId, SYNTHETIC_NEWER_CREDENTIAL)
        seedLegacySettingsAndCredential()

        repository.migrateLegacyProviderIfNeeded()

        val providers = settingsDataStore.settings.first().customProviders
        assertEquals(listOf(providerId), providers.map { it.id })
        assertEquals(SYNTHETIC_NEWER_CREDENTIAL, providerKeyStore.getApiKey(providerId))
        assertLegacyCredentialRemoved()
        assertMigrationCompleted()
    }

    @Test
    fun oldCompletedFlagWithProviderAlreadyDeleted_doesNotResurrectProvider() = runBlocking {
        seedLegacySettingsAndCredential(oldCompletedFlag = true)

        repository.migrateLegacyProviderIfNeeded()

        assertTrue(settingsDataStore.settings.first().customProviders.isEmpty())
        assertLegacyCredentialRemoved()
        assertMigrationCompleted()
    }

    @Test
    fun oldCompletedFlagWithExistingLegacyProvider_resumesCopyWithoutChangingSelection() = runBlocking {
        seedLegacySettingsAndCredential(oldCompletedFlag = true)
        val providerId = "old-one-phase-${UUID.randomUUID()}"
        val selectedProviderId = "already-selected-${UUID.randomUUID()}"
        providerIdsToClean += setOf(providerId, selectedProviderId)
        settingsDataStore.updateSettings { current ->
            current.copy(
                customProviders = listOf(
                    CustomProvider(
                        id = providerId,
                        name = "CLIProxyAPI",
                        baseUrl = LEGACY_BASE_URL,
                        models = listOf(LEGACY_MODEL),
                        selectedModel = LEGACY_MODEL,
                    ),
                    testProvider(selectedProviderId, 0),
                ),
                activeCustomProviderId = selectedProviderId,
            )
        }
        providerKeyStore.saveApiKey(selectedProviderId, SYNTHETIC_NEWER_CREDENTIAL)

        repository.migrateLegacyProviderIfNeeded()

        val migrated = settingsDataStore.settings.first()
        assertEquals(listOf(providerId, selectedProviderId), migrated.customProviders.map { it.id })
        assertEquals(selectedProviderId, migrated.activeCustomProviderId)
        assertEquals(SYNTHETIC_LEGACY_CREDENTIAL, providerKeyStore.getApiKey(providerId))
        assertEquals(SYNTHETIC_NEWER_CREDENTIAL, providerKeyStore.getApiKey(selectedProviderId))
        assertLegacyCredentialRemoved()
        assertMigrationCompleted()
    }

    @Test
    fun pendingTargetDeletedBeforeRetry_cleansLegacyCredentialWithoutResurrection() = runBlocking {
        seedLegacySettingsAndCredential()
        val deletedProviderId = prepareTargetProviderId()
        providerIdsToClean += deletedProviderId
        // 模拟目标 Key 已复制，但 Provider 记录随后在另一次进程生命周期中被删除。
        providerKeyStore.saveApiKey(deletedProviderId, SYNTHETIC_LEGACY_CREDENTIAL)
        settingsDataStore.updateSettings { current ->
            current.copy(
                customProviders = current.customProviders.filterNot { it.id == deletedProviderId },
                activeCustomProviderId = null,
            )
        }

        repository.migrateLegacyProviderIfNeeded()

        assertTrue(settingsDataStore.settings.first().customProviders.isEmpty())
        assertNull(providerKeyStore.getApiKey(deletedProviderId))
        assertFalse(androidKeyStore().containsAlias("$providerAliasPrefix$deletedProviderId"))
        assertLegacyCredentialRemoved()
        assertMigrationCompleted()
    }

    @Test
    fun targetWithNewerCredential_isNeverOverwrittenByLegacyCredential() = runBlocking {
        seedLegacySettingsAndCredential()
        val providerId = prepareTargetProviderId()
        providerIdsToClean += providerId
        providerKeyStore.saveApiKey(providerId, SYNTHETIC_NEWER_CREDENTIAL)

        repository.migrateLegacyProviderIfNeeded()

        assertEquals(SYNTHETIC_NEWER_CREDENTIAL, providerKeyStore.getApiKey(providerId))
        assertLegacyCredentialRemoved()
        assertMigrationCompleted()
    }

    @Test
    fun targetWithNewerCredential_andCorruptedLegacyCiphertext_keepsNewValueAndCompletes() = runBlocking {
        seedLegacySettingsWithCorruptedCredential()
        val providerId = prepareTargetProviderId()
        providerIdsToClean += providerId
        providerKeyStore.saveApiKey(providerId, SYNTHETIC_NEWER_CREDENTIAL)
        assertTrue(runCatching { legacyKeyStore.getApiKey() }.isFailure)

        repository.migrateLegacyProviderIfNeeded()

        assertEquals(SYNTHETIC_NEWER_CREDENTIAL, providerKeyStore.getApiKey(providerId))
        assertLegacyCredentialRemoved()
        assertMigrationCompleted()
    }

    @Test
    fun concurrentProviderSaveAndDelete_neverLeavesOrphanCredential() = runBlocking {
        repeat(CONCURRENT_PROVIDER_ROUNDS) { round ->
            val providerId = "concurrent-${UUID.randomUUID()}"
            providerIdsToClean += providerId
            repository.upsertCustomProvider(testProvider(providerId, round))
            val start = CompletableDeferred<Unit>()

            val results = listOf(
                async(Dispatchers.Default) {
                    start.await()
                    runCatching {
                        repository.saveProviderKey(providerId, "$SYNTHETIC_CONCURRENT_CREDENTIAL-$round")
                    }
                },
                async(Dispatchers.Default) {
                    start.await()
                    runCatching { repository.deleteCustomProvider(providerId) }
                },
            )
            start.complete(Unit)
            results.awaitAll()

            assertFalse(settingsDataStore.settings.first().customProviders.any { it.id == providerId })
            assertNull(providerKeyStore.getApiKey(providerId))
            assertFalse(providerKeyStore.hasApiKey(providerId))
            assertFalse(androidKeyStore().containsAlias("$providerAliasPrefix$providerId"))
        }
    }

    @Test
    fun concurrentProviderSaveAndClear_keepsCredentialStorageWhole() = runBlocking {
        val providerId = "save-clear-${UUID.randomUUID()}"
        providerIdsToClean += providerId
        repository.upsertCustomProvider(testProvider(providerId, 0))
        val candidates = (0 until CONCURRENT_KEY_MUTATIONS).map { index ->
            "$SYNTHETIC_CONCURRENT_CREDENTIAL-$index"
        }
        val start = CompletableDeferred<Unit>()
        val jobs = candidates.flatMap { credential ->
            listOf(
                async(Dispatchers.Default) {
                    start.await()
                    repository.saveProviderKey(providerId, credential)
                },
                async(Dispatchers.Default) {
                    start.await()
                    repository.clearProviderKey(providerId)
                },
            )
        }

        start.complete(Unit)
        jobs.awaitAll()

        val stored = repository.getProviderKey(providerId)
        assertTrue(stored == null || stored in candidates)
    }

    @Test
    fun concurrentDeepSeekSaveAndClear_keepsCredentialStorageWhole() = runBlocking {
        val candidates = (0 until CONCURRENT_KEY_MUTATIONS).map { index ->
            "$SYNTHETIC_DEEPSEEK_CREDENTIAL-$index"
        }
        val start = CompletableDeferred<Unit>()
        val jobs = candidates.flatMap { credential ->
            listOf(
                async(Dispatchers.Default) {
                    start.await()
                    repository.saveApiKey(credential)
                },
                async(Dispatchers.Default) {
                    start.await()
                    repository.clearApiKey()
                },
            )
        }

        start.complete(Unit)
        jobs.awaitAll()

        val stored = repository.getApiKey()
        assertTrue(stored == null || stored in candidates)
        if (stored == null) {
            assertFalse(androidKeyStore().containsAlias(deepSeekAlias))
        } else {
            assertTrue(androidKeyStore().containsAlias(deepSeekAlias))
        }
    }

    @Test
    fun providerRequestSnapshot_blocksRouteAndCredentialMutationUntilFrozen() = runBlocking {
        val providerId = "snapshot-${UUID.randomUUID()}"
        providerIdsToClean += providerId
        repository.upsertCustomProvider(testProvider(providerId, 0))
        repository.saveProviderKey(providerId, SYNTHETIC_NEWER_CREDENTIAL)

        val snapshotEntered = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)
        val frozen = async(Dispatchers.IO) {
            repository.withCurrentProviderCredentials { settings, apiKey ->
                val provider = requireNotNull(settings.activeCustomProvider)
                snapshotEntered.countDown()
                check(releaseSnapshot.await(5, TimeUnit.SECONDS)) { "测试未释放凭据快照" }
                Triple(provider.baseUrl, provider.selectedModel, apiKey)
            }
        }
        assertTrue(snapshotEntered.await(5, TimeUnit.SECONDS))

        val routeMutation = async(Dispatchers.Default) {
            repository.updateCustomProvider(providerId) { provider ->
                provider.copy(baseUrl = UPDATED_BASE_URL)
            }
        }
        val credentialMutation = async(Dispatchers.Default) {
            repository.saveProviderKey(providerId, UPDATED_SYNTHETIC_CREDENTIAL)
        }
        delay(100)
        assertFalse(routeMutation.isCompleted)
        assertFalse(credentialMutation.isCompleted)

        releaseSnapshot.countDown()
        val original = frozen.await()
        listOf(routeMutation, credentialMutation).awaitAll()

        assertEquals("https://example.test/v1", original.first)
        assertEquals("test-model", original.second)
        assertEquals(SYNTHETIC_NEWER_CREDENTIAL, original.third)
        val latest = repository.withCurrentProviderCredentials { settings, apiKey ->
            settings.activeCustomProvider?.baseUrl to apiKey
        }
        assertEquals(UPDATED_BASE_URL, latest.first)
        assertEquals(UPDATED_SYNTHETIC_CREDENTIAL, latest.second)
    }

    private suspend fun seedLegacySettingsAndCredential(
        baseUrl: String = LEGACY_BASE_URL,
        oldCompletedFlag: Boolean = false,
    ) {
        preferencesDataStore.edit { preferences ->
            preferences[stringPreferencesKey("cli_proxy_api_base_url")] = baseUrl
            preferences[stringPreferencesKey("cli_proxy_api_model")] = LEGACY_MODEL
            if (oldCompletedFlag) {
                preferences[booleanPreferencesKey("custom_providers_migrated")] = true
            }
        }
        legacyKeyStore.saveApiKey(SYNTHETIC_LEGACY_CREDENTIAL)
        assertTrue(androidKeyStore().containsAlias(legacyAlias))
    }

    private suspend fun seedLegacySettingsWithCorruptedCredential(
        baseUrl: String = LEGACY_BASE_URL,
    ) {
        preferencesDataStore.edit { preferences ->
            preferences[stringPreferencesKey("cli_proxy_api_base_url")] = baseUrl
            preferences[stringPreferencesKey("cli_proxy_api_model")] = LEGACY_MODEL
        }
        context.getSharedPreferences(legacyPreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString("cli_proxy_ciphertext", "%%%not-base64%%%")
            .putString("cli_proxy_iv", "%%%not-base64%%%")
            .commit()
    }

    private fun testProvider(providerId: String, round: Int) = CustomProvider(
        id = providerId,
        name = "并发测试供应商 $round",
        baseUrl = "https://example.test/v1",
        models = listOf("test-model"),
        selectedModel = "test-model",
    )

    private suspend fun assertLegacyCredentialRemoved() {
        assertNull(legacyKeyStore.getApiKey())
        assertFalse(androidKeyStore().containsAlias(legacyAlias))
    }

    private suspend fun assertMigrationCompleted() {
        assertNull(settingsDataStore.prepareLegacyCliProxyProviderMigration())
    }

    private suspend fun prepareTargetProviderId(): String = requireNotNull(
        requireNotNull(settingsDataStore.prepareLegacyCliProxyProviderMigration()).targetProviderId,
    )

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun providerAliasesInAndroidKeyStore(): Set<String> =
        Collections.list(androidKeyStore().aliases())
            .filterTo(linkedSetOf()) { alias -> alias.startsWith(providerAliasPrefix) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val LEGACY_BASE_URL = "https://legacy.example.test/v1"
        const val LEGACY_MODEL = "legacy-test-model"
        const val SYNTHETIC_LEGACY_CREDENTIAL = "synthetic-legacy-credential"
        const val SYNTHETIC_NEWER_CREDENTIAL = "synthetic-user-saved-credential"
        const val SYNTHETIC_CONCURRENT_CREDENTIAL = "synthetic-concurrent-credential"
        const val SYNTHETIC_DEEPSEEK_CREDENTIAL = "synthetic-deepseek-credential"
        const val UPDATED_SYNTHETIC_CREDENTIAL = "synthetic-updated-credential"
        const val UPDATED_BASE_URL = "https://updated.example.test/v1"
        const val CONCURRENT_PROVIDER_ROUNDS = 4
        const val CONCURRENT_KEY_MUTATIONS = 4
    }
}
