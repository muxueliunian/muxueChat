package com.muxuechat.data.local

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDataStoreConcurrencyTest {
    private lateinit var store: SettingsDataStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dataStoreFile = File(
            context.cacheDir,
            "settings-concurrency-${UUID.randomUUID()}.preferences_pb",
        )
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = SettingsDataStore(
            PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { dataStoreFile },
            ),
        )
        store.updateSettings { ChatSettings() }
    }

    @After
    fun tearDown() = runBlocking {
        dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
        dataStoreFile.delete()
        Unit
    }

    @Test
    fun concurrentFieldMutations_keepBothLatestValues() = runBlocking {
        runConcurrently(
            { store.updateSettings { it.copy(temperature = 0.25) } },
            { store.updateSettings { it.copy(topP = 0.45) } },
        )

        val saved = store.settings.first()
        assertEquals(0.25, saved.temperature, 0.0)
        assertEquals(0.45, saved.topP, 0.0)
    }

    @Test
    fun providerEditAndModelRefresh_doNotOverwriteEachOther() = runBlocking {
        val provider = CustomProvider(
            id = "provider-test",
            name = "初始名称",
            baseUrl = "https://example.test/v1",
            models = listOf("old-model"),
            selectedModel = "old-model",
        )
        store.updateSettings {
            it.copy(
                provider = ChatProviderId.CUSTOM_OPENAI,
                customProviders = listOf(provider),
                activeCustomProviderId = provider.id,
            )
        }

        runConcurrently(
            {
                store.updateSettings { current ->
                    current.copy(
                        customProviders = current.customProviders.map {
                            if (it.id == provider.id) it.copy(name = "最新名称") else it
                        },
                    )
                }
            },
            {
                store.updateSettings { current ->
                    current.copy(
                        customProviders = current.customProviders.map {
                            if (it.id == provider.id) {
                                it.copy(
                                    models = listOf("new-model"),
                                    selectedModel = "new-model",
                                    modelsUpdatedAtMillis = 123L,
                                )
                            } else {
                                it
                            }
                        },
                    )
                }
            },
        )

        val saved = store.settings.first().customProviders.single()
        assertEquals("最新名称", saved.name)
        assertEquals(listOf("new-model"), saved.models)
        assertEquals("new-model", saved.selectedModel)
        assertEquals(123L, saved.modelsUpdatedAtMillis)
    }

    @Test
    fun systemPromptAndProviderSelection_doNotOverwriteEachOther() = runBlocking {
        val first = CustomProvider(
            id = "provider-first",
            name = "第一个",
            baseUrl = "https://first.example.test/v1",
            models = listOf("first-model"),
            selectedModel = "first-model",
        )
        val second = CustomProvider(
            id = "provider-second",
            name = "第二个",
            baseUrl = "https://second.example.test/v1",
            models = listOf("second-model"),
            selectedModel = "second-model",
        )
        store.updateSettings {
            it.copy(
                customProviders = listOf(first, second),
                activeCustomProviderId = first.id,
            )
        }

        runConcurrently(
            { store.updateSettings { it.copy(systemPrompt = "最新系统提示词") } },
            {
                store.updateSettings {
                    it.copy(
                        provider = ChatProviderId.CUSTOM_OPENAI,
                        activeCustomProviderId = second.id,
                    )
                }
            },
        )

        val saved = store.settings.first()
        assertEquals("最新系统提示词", saved.systemPrompt)
        assertEquals(ChatProviderId.CUSTOM_OPENAI, saved.provider)
        assertEquals(second.id, saved.activeCustomProviderId)
        assertEquals(listOf(first.id, second.id), saved.customProviders.map { it.id })
    }

    private suspend fun runConcurrently(vararg updates: suspend () -> Unit) = coroutineScope {
        val start = CompletableDeferred<Unit>()
        val jobs = updates.map { update ->
            async(Dispatchers.Default) {
                start.await()
                update()
            }
        }
        start.complete(Unit)
        jobs.awaitAll()
    }
}
