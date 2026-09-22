package com.muxuechat.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.DeepSeekModels
import com.muxuechat.domain.model.ReasoningEffort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SettingsDataStoreDeepSeekModelTest {
    private lateinit var store: SettingsDataStore
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        dataStore = MemoryPreferencesDataStore()
        store = SettingsDataStore(dataStore)
    }

    @Test
    /** 旧名的改写只由 [DeepSeekModelIdMigration] 负责；读取本身不做任何隐式映射。 */
    fun legacyFlashAndPro_readAsTheSameIdsWithoutMigration() = runBlocking {
        dataStore.edit { prefs -> prefs[MODEL] = "deepseek-v4-flash" }
        assertEquals(DeepSeekModels.V4_FLASH, store.settings.first().model)

        store.updateSettings { it.copy(model = DeepSeekModels.V4_PRO) }
        assertEquals(DeepSeekModels.V4_PRO, store.settings.first().model)
    }

    @Test
    fun unknownFutureModelId_roundTripsWithoutSilentFallback() = runBlocking {
        store.updateSettings { it.copy(model = "deepseek-v4-future-pro") }
        assertEquals("deepseek-v4-future-pro", store.settings.first().model)

        dataStore.edit { prefs -> prefs[MODEL] = "  another-unknown-id  " }
        assertEquals("another-unknown-id", store.settings.first().model)

        store.updateSettings { current -> current.copy(model = current.model) }
        assertEquals("another-unknown-id", store.settings.first().model)
    }

    @Test
    fun missingModelKey_defaultsToFlash() = runBlocking {
        assertEquals(DeepSeekModels.DEFAULT_ID, store.settings.first().model)
        assertEquals(emptyList<String>(), store.settings.first().deepSeekCachedModelIds)
        assertEquals(0L, store.settings.first().deepSeekModelsUpdatedAtMillis)
    }

    @Test
    fun cachedModelsAndReasoningEffort_roundTrip() = runBlocking {
        store.updateSettings {
            ChatSettings(
                model = "kept-selection",
                deepSeekCachedModelIds = listOf("deepseek-v4-flash", "future-id"),
                deepSeekModelsUpdatedAtMillis = 1_700_000_000_000L,
                reasoningEffort = ReasoningEffort.LOW,
            )
        }

        val settings = store.settings.first()
        assertEquals("kept-selection", settings.model)
        assertEquals(listOf("deepseek-v4-flash", "future-id"), settings.deepSeekCachedModelIds)
        assertEquals(1_700_000_000_000L, settings.deepSeekModelsUpdatedAtMillis)
        assertEquals(ReasoningEffort.LOW, settings.reasoningEffort)
    }

    private companion object {
        val MODEL = stringPreferencesKey("model")
    }
}

internal class MemoryPreferencesDataStore : DataStore<Preferences> {
    private val mutex = Mutex()
    private val state = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = mutex.withLock {
        val updated = transform(state.value)
        state.value = updated
        updated
    }
}
