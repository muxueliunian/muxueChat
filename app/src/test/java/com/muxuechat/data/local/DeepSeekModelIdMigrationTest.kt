package com.muxuechat.data.local

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.DeepSeekModels
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekModelIdMigrationTest {

    @Test
    fun legacyModelIds_bothMigrateToFlash() = runBlocking {
        listOf(DeepSeekModels.V4_FLASH, DeepSeekModels.V4_FLASH_VISION_EXP).forEach { legacy ->
            val migrated = migrate { it[MODEL] = legacy }
            assertEquals(DeepSeekModels.FLASH, migrated[MODEL])
            assertEquals(true, migrated[MIGRATED])
        }
    }

    @Test
    fun proUnknownAndMissingModel_areUntouched() = runBlocking {
        assertEquals(DeepSeekModels.V4_PRO, migrate { it[MODEL] = DeepSeekModels.V4_PRO }[MODEL])
        assertEquals("deepseek-v4-flash-2", migrate { it[MODEL] = "deepseek-v4-flash-2" }[MODEL])
        assertNull(migrate { }[MODEL])
    }

    @Test
    fun rpAuxiliaryModel_migratesOnlyOnDeepSeek() = runBlocking {
        val onDeepSeek = migrate {
            it[CHAT_PROVIDER] = ChatProviderId.DEEPSEEK.storageValue
            it[RP_AUXILIARY_MODEL] = DeepSeekModels.V4_FLASH
        }
        assertEquals(DeepSeekModels.FLASH, onDeepSeek[RP_AUXILIARY_MODEL])

        // 旧版本没有 provider 键时就是 DeepSeek。
        val legacyDefault = migrate { it[RP_AUXILIARY_MODEL] = DeepSeekModels.V4_FLASH }
        assertEquals(DeepSeekModels.FLASH, legacyDefault[RP_AUXILIARY_MODEL])

        val onCustom = migrate {
            it[CHAT_PROVIDER] = ChatProviderId.CUSTOM_OPENAI.storageValue
            it[MODEL] = DeepSeekModels.V4_FLASH
            it[RP_AUXILIARY_MODEL] = DeepSeekModels.V4_FLASH
        }
        assertEquals(DeepSeekModels.V4_FLASH, onCustom[RP_AUXILIARY_MODEL])
        // DeepSeek 自己的选择与当前供应商无关，照常迁移。
        assertEquals(DeepSeekModels.FLASH, onCustom[MODEL])
    }

    @Test
    fun contextWindows_moveLegacyValueUnlessFlashAlreadyConfigured() {
        assertEquals(
            mapOf("deepseek-v4-pro" to 500_000L, DeepSeekModels.FLASH to 200_000L),
            DeepSeekModelIdMigration.migrateContextWindows(
                mapOf(DeepSeekModels.V4_FLASH to 200_000L, "deepseek-v4-pro" to 500_000L),
            ),
        )
        assertEquals(
            mapOf(DeepSeekModels.FLASH to 300_000L),
            DeepSeekModelIdMigration.migrateContextWindows(
                mapOf(DeepSeekModels.FLASH to 300_000L, DeepSeekModels.V4_FLASH_VISION_EXP to 100_000L),
            ),
        )
        assertNull(DeepSeekModelIdMigration.migrateContextWindows(mapOf("deepseek-v4-pro" to 1L)))
    }

    @Test
    fun contextWindows_areRewrittenInPreferences() = runBlocking {
        val migrated = migrate {
            it[CONTEXT_WINDOWS] = """{"deepseek-v4-flash":200000}"""
        }
        assertEquals("""{"deepseek-flash":200000}""", migrated[CONTEXT_WINDOWS])
    }

    @Test
    fun cachedModelsWithLegacyName_areClearedBackToBuiltinFallback() = runBlocking {
        val migrated = migrate {
            it[CACHED_MODELS] = """["deepseek-v4-flash","deepseek-v4-pro"]"""
            it[MODELS_UPDATED_AT] = 42L
        }
        assertEquals("[]", migrated[CACHED_MODELS])
        assertEquals(0L, migrated[MODELS_UPDATED_AT])

        val current = migrate {
            it[CACHED_MODELS] = """["deepseek-flash","deepseek-v4-pro"]"""
            it[MODELS_UPDATED_AT] = 42L
        }
        assertEquals("""["deepseek-flash","deepseek-v4-pro"]""", current[CACHED_MODELS])
        assertEquals(42L, current[MODELS_UPDATED_AT])
    }

    @Test
    fun corruptJson_isLeftForTheReaderToTolerate() = runBlocking {
        val migrated = migrate {
            it[CONTEXT_WINDOWS] = "{broken"
            it[CACHED_MODELS] = "[broken"
        }
        assertEquals("{broken", migrated[CONTEXT_WINDOWS])
        assertEquals("[broken", migrated[CACHED_MODELS])
        assertEquals(true, migrated[MIGRATED])
    }

    @Test
    fun migration_runsOnlyOnce() = runBlocking {
        val first = migrate { it[MODEL] = DeepSeekModels.V4_FLASH }
        assertFalse(DeepSeekModelIdMigration.shouldMigrate(first))

        // 迁移之后用户或真实刷新再写入旧名，不应被再次改写。
        val later = first.toMutablePreferences().apply {
            this[MODEL] = DeepSeekModels.V4_FLASH
            this[CACHED_MODELS] = """["deepseek-v4-flash"]"""
        }
        DeepSeekModelIdMigration.migrateInPlace(later)
        assertEquals(DeepSeekModels.V4_FLASH, later[MODEL])
        assertEquals("""["deepseek-v4-flash"]""", later[CACHED_MODELS])
    }

    @Test
    fun migratedPreferences_readBackThroughSettingsDataStore() = runBlocking {
        val dataStore = MemoryPreferencesDataStore()
        dataStore.edit {
            it[MODEL] = DeepSeekModels.V4_FLASH_VISION_EXP
            it[CONTEXT_WINDOWS] = """{"deepseek-v4-flash-vision-exp":128000}"""
        }
        dataStore.edit { DeepSeekModelIdMigration.migrateInPlace(it) }

        val settings = SettingsDataStore(dataStore).settings.first()
        assertEquals(DeepSeekModels.FLASH, settings.model)
        assertEquals(mapOf(DeepSeekModels.FLASH to 128_000L), settings.deepSeekModelContextWindows)
        assertTrue(settings.deepSeekCachedModelIds.isEmpty())
    }

    private suspend fun migrate(seed: (MutablePreferences) -> Unit): Preferences {
        val before = emptyPreferences().toMutablePreferences().apply(seed).toPreferences()
        assertTrue(DeepSeekModelIdMigration.shouldMigrate(before))
        return DeepSeekModelIdMigration.migrate(before)
    }

    private companion object {
        val MIGRATED = booleanPreferencesKey("deepseek_model_ids_migrated_v1")
        val MODEL = stringPreferencesKey("model")
        val RP_AUXILIARY_MODEL = stringPreferencesKey("rp_auxiliary_model")
        val CHAT_PROVIDER = stringPreferencesKey("chat_provider")
        val CONTEXT_WINDOWS = stringPreferencesKey("deepseek_model_context_windows")
        val CACHED_MODELS = stringPreferencesKey("deepseek_cached_models")
        val MODELS_UPDATED_AT = longPreferencesKey("deepseek_models_updated_at")
    }
}
