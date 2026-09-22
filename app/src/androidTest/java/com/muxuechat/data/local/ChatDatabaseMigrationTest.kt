package com.muxuechat.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Modifier
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room v1-v7 没有导出 schema，仓库和工作区也没有历史 APK/数据库备份。
 *
 * 因此这里明确采用“重建库”测试：v1 只包含从 v8 反推、且从未被任何 migration 修改的
 * conversations/messages 两张业务表；v2 及之后则由生产代码里的真实 Migration 逐段构造。
 * 这能验证迁移 SQL 的连续性、数据保留和最终 Room schema，但不能替代来自已发布版本的
 * 原始 schema/identity hash。证据与边界见 docs/ROOM_MIGRATION_TEST_NOTES.md。
 *
 * 例外是 8→9：8.json 与 9.json 都是 Room 真实导出的 schema，因此另有一个
 * [MigrationTestHelper] 用例按导出 schema 建库并做真实升级校验。
 */
@RunWith(AndroidJUnit4::class)
class ChatDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseNames = mutableSetOf<String>()
    private val migrations: List<Migration> by lazy {
        (1..8).map { startVersion -> findProductionMigration(startVersion, startVersion + 1) }
    }

    /** 只用于 8→9：从测试 APK assets 中读取 Room 导出的 8.json / 9.json。 */
    @get:Rule
    val exportedSchemaHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ChatDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun tearDown() {
        databaseNames.forEach { context.deleteDatabase(it) }
        databaseNames.clear()
    }

    @Test
    fun migrate1To2_addsRpParentsBindingsAndCascadeRules() = withReconstructedDatabase(1) { db ->
        insertCoreConversationAndMessage(db)

        migrate(db, 1, 2)

        assertEquals(1, rowCount(db, "conversations"))
        assertEquals(1, rowCount(db, "messages"))
        assertColumns(
            db,
            "role_cards",
            column("id", "TEXT", primaryKey = true),
            column("name", "TEXT"),
            column("description", "TEXT"),
            column("persona", "TEXT"),
            column("speakingStyle", "TEXT"),
            column("userIdentity", "TEXT"),
            column("openingMessage", "TEXT"),
            column("boundaries", "TEXT"),
            column("createdAtMillis", "INTEGER"),
            column("updatedAtMillis", "INTEGER"),
        )
        assertColumns(
            db,
            "world_settings",
            column("id", "TEXT", primaryKey = true),
            column("title", "TEXT"),
            column("content", "TEXT"),
            column("createdAtMillis", "INTEGER"),
            column("updatedAtMillis", "INTEGER"),
        )
        assertColumns(
            db,
            "conversation_rp_settings",
            column("conversationId", "TEXT", primaryKey = true),
            column("isRpEnabled", "INTEGER"),
            column("roleCardId", "TEXT", notNull = false),
            column("worldSettingId", "TEXT", notNull = false),
            column("updatedAtMillis", "INTEGER"),
        )
        assertColumns(
            db,
            "rp_status_panels",
            column("conversationId", "TEXT", primaryKey = true),
            column("metricsJson", "TEXT"),
            column("relationship", "TEXT"),
            column("scene", "TEXT"),
            column("rawJson", "TEXT"),
            column("updatedAtMillis", "INTEGER"),
        )
        assertIndex(db, "conversation_rp_settings", "index_conversation_rp_settings_roleCardId", "roleCardId")
        assertIndex(db, "conversation_rp_settings", "index_conversation_rp_settings_worldSettingId", "worldSettingId")
        assertForeignKey(db, "conversation_rp_settings", "conversationId", "conversations", "id", "CASCADE")
        assertForeignKey(db, "conversation_rp_settings", "roleCardId", "role_cards", "id", "SET NULL")
        assertForeignKey(db, "conversation_rp_settings", "worldSettingId", "world_settings", "id", "SET NULL")
        assertForeignKey(db, "rp_status_panels", "conversationId", "conversations", "id", "CASCADE")

        insertRoleCardV2(db)
        insertWorldSetting(db)
        db.execSQL(
            "INSERT INTO conversation_rp_settings " +
                "(conversationId, isRpEnabled, roleCardId, worldSettingId, updatedAtMillis) " +
                "VALUES ('conversation', 1, 'role', 'world', 10)",
        )
        insertStatusPanel(db)

        db.execSQL("DELETE FROM role_cards WHERE id = 'role'")
        assertNull(queryNullableString(db, "SELECT roleCardId FROM conversation_rp_settings"))
        db.execSQL("DELETE FROM world_settings WHERE id = 'world'")
        assertNull(queryNullableString(db, "SELECT worldSettingId FROM conversation_rp_settings"))

        db.execSQL("DELETE FROM conversations WHERE id = 'conversation'")
        assertEquals(0, rowCount(db, "messages"))
        assertEquals(0, rowCount(db, "conversation_rp_settings"))
        assertEquals(0, rowCount(db, "rp_status_panels"))
    }

    @Test
    fun migrate2To3_addsMetricDefinitionsWithHistoricalDefault() = withReconstructedDatabase(2) { db ->
        insertRoleCardV2(db)

        migrate(db, 2, 3)

        val metricColumn = columns(db, "role_cards").single { it.name == "metricDefinitionsJson" }
        assertEquals(Column("metricDefinitionsJson", "TEXT", notNull = true, defaultValue = "'[]'", primaryKey = false), metricColumn)
        assertEquals("[]", queryString(db, "SELECT metricDefinitionsJson FROM role_cards WHERE id = 'role'"))
    }

    @Test
    fun migrate3To4_addsMessageMetadataIndexesAndCascadeRules() = withReconstructedDatabase(3) { db ->
        insertCoreConversationAndMessage(db)

        migrate(db, 3, 4)

        assertColumns(
            db,
            "message_rp_metadata",
            column("messageId", "TEXT", primaryKey = true),
            column("conversationId", "TEXT"),
            column("optionsJson", "TEXT"),
            column("innerThought", "TEXT"),
            column("metricsJson", "TEXT"),
            column("relationship", "TEXT"),
            column("scene", "TEXT"),
            column("rawJson", "TEXT"),
            column("createdAtMillis", "INTEGER"),
            column("updatedAtMillis", "INTEGER"),
        )
        assertIndex(db, "message_rp_metadata", "index_message_rp_metadata_conversationId", "conversationId")
        assertForeignKey(db, "message_rp_metadata", "messageId", "messages", "id", "CASCADE")
        assertForeignKey(db, "message_rp_metadata", "conversationId", "conversations", "id", "CASCADE")

        insertMessageMetadata(db, "message")
        db.execSQL("DELETE FROM messages WHERE id = 'message'")
        assertEquals(0, rowCount(db, "message_rp_metadata"))

        insertMessage(db, "message-2")
        insertMessageMetadata(db, "message-2")
        db.execSQL("DELETE FROM conversations WHERE id = 'conversation'")
        assertEquals(0, rowCount(db, "messages"))
        assertEquals(0, rowCount(db, "message_rp_metadata"))
    }

    @Test
    fun migrate4To5_addsProtagonistFieldsAndPreservesBindings() = withReconstructedDatabase(4) { db ->
        insertCoreConversationAndMessage(db)
        insertRoleCardV3(db)
        insertWorldSetting(db)
        insertRpSettingsV4(db)

        migrate(db, 4, 5)

        assertColumn(db, "conversation_rp_settings", "protagonistName", "TEXT", true, "''")
        assertColumn(db, "conversation_rp_settings", "protagonistDescription", "TEXT", true, "''")
        assertColumn(db, "conversation_rp_settings", "protagonistInitialState", "TEXT", true, "''")
        assertEquals("", queryString(db, "SELECT protagonistName FROM conversation_rp_settings"))
        assertEquals("role", queryString(db, "SELECT roleCardId FROM conversation_rp_settings"))
        assertEquals("world", queryString(db, "SELECT worldSettingId FROM conversation_rp_settings"))
    }

    @Test
    fun migrate5To6_addsNarrativeMinimumWithHistoricalDefault() = withReconstructedDatabase(5) { db ->
        insertCoreConversationAndMessage(db)
        insertRpSettingsV5(db)

        migrate(db, 5, 6)

        assertColumn(db, "conversation_rp_settings", "narrativeMinChars", "INTEGER", true, "0")
        assertEquals(0L, queryLong(db, "SELECT narrativeMinChars FROM conversation_rp_settings"))
        assertEquals("主角", queryString(db, "SELECT protagonistName FROM conversation_rp_settings"))
    }

    @Test
    fun migrate6To7_addsPerspectiveAndMarkerDefaults() = withReconstructedDatabase(6) { db ->
        insertCoreConversationAndMessage(db)
        insertRpSettingsV6(db)

        migrate(db, 6, 7)

        assertColumn(
            db,
            "conversation_rp_settings",
            "narrativePerspective",
            "TEXT",
            true,
            "'PROTAGONIST_SECOND_PERSON'",
        )
        assertColumn(
            db,
            "conversation_rp_settings",
            "plotSuggestionPerspective",
            "TEXT",
            true,
            "'PROTAGONIST'",
        )
        assertColumn(db, "conversation_rp_settings", "rpMarkerMode", "TEXT", true, "'DISABLED'")
        assertEquals(
            "PROTAGONIST_SECOND_PERSON",
            queryString(db, "SELECT narrativePerspective FROM conversation_rp_settings"),
        )
        assertEquals("PROTAGONIST", queryString(db, "SELECT plotSuggestionPerspective FROM conversation_rp_settings"))
        assertEquals("DISABLED", queryString(db, "SELECT rpMarkerMode FROM conversation_rp_settings"))
        assertEquals(1200L, queryLong(db, "SELECT narrativeMinChars FROM conversation_rp_settings"))
    }

    @Test
    fun migrate7To8_rebuildsBindingsPreservesDataAndAddsContextAndWritingStyle() =
        withReconstructedDatabase(7) { db ->
            insertCoreConversationAndMessage(db)
            insertRoleCardV3(db)
            insertWorldSetting(db)
            insertRpSettingsV7(db)
            insertStatusPanel(db)

            migrate(db, 7, 8)

            assertColumns(
                db,
                "writing_style_profiles",
                column("id", "TEXT", primaryKey = true),
                column("name", "TEXT"),
                column("content", "TEXT"),
                column("createdAtMillis", "INTEGER"),
                column("updatedAtMillis", "INTEGER"),
            )
            assertColumns(
                db,
                "conversation_context",
                column("conversationId", "TEXT", primaryKey = true),
                column("summary", "TEXT"),
                column("coveredMessageCount", "INTEGER"),
                column("updatedAtMillis", "INTEGER"),
            )
            assertColumns(
                db,
                "conversation_rp_settings",
                column("conversationId", "TEXT", primaryKey = true),
                column("isRpEnabled", "INTEGER"),
                column("roleCardId", "TEXT", notNull = false),
                column("worldSettingId", "TEXT", notNull = false),
                column("writingStyleId", "TEXT", notNull = false),
                column("protagonistName", "TEXT"),
                column("protagonistDescription", "TEXT"),
                column("protagonistInitialState", "TEXT"),
                column("narrativeMinChars", "INTEGER"),
                column("narrativePerspective", "TEXT"),
                column("plotSuggestionPerspective", "TEXT"),
                column("rpMarkerMode", "TEXT"),
                column("updatedAtMillis", "INTEGER"),
            )
            assertIndex(db, "conversation_rp_settings", "index_conversation_rp_settings_roleCardId", "roleCardId")
            assertIndex(db, "conversation_rp_settings", "index_conversation_rp_settings_worldSettingId", "worldSettingId")
            assertIndex(db, "conversation_rp_settings", "index_conversation_rp_settings_writingStyleId", "writingStyleId")
            assertForeignKey(db, "conversation_rp_settings", "conversationId", "conversations", "id", "CASCADE")
            assertForeignKey(db, "conversation_rp_settings", "roleCardId", "role_cards", "id", "SET NULL")
            assertForeignKey(db, "conversation_rp_settings", "worldSettingId", "world_settings", "id", "SET NULL")
            assertForeignKey(
                db,
                "conversation_rp_settings",
                "writingStyleId",
                "writing_style_profiles",
                "id",
                "SET NULL",
            )
            assertForeignKey(db, "conversation_context", "conversationId", "conversations", "id", "CASCADE")

            assertEquals("role", queryString(db, "SELECT roleCardId FROM conversation_rp_settings"))
            assertEquals("world", queryString(db, "SELECT worldSettingId FROM conversation_rp_settings"))
            assertNull(queryNullableString(db, "SELECT writingStyleId FROM conversation_rp_settings"))
            assertEquals("主角", queryString(db, "SELECT protagonistName FROM conversation_rp_settings"))
            assertEquals(1200L, queryLong(db, "SELECT narrativeMinChars FROM conversation_rp_settings"))
            assertEquals("THIRD_PERSON", queryString(db, "SELECT narrativePerspective FROM conversation_rp_settings"))
            assertEquals("OBSERVER", queryString(db, "SELECT plotSuggestionPerspective FROM conversation_rp_settings"))
            assertEquals("ENABLED", queryString(db, "SELECT rpMarkerMode FROM conversation_rp_settings"))

            db.execSQL(
                "INSERT INTO writing_style_profiles " +
                    "(id, name, content, createdAtMillis, updatedAtMillis) " +
                    "VALUES ('style', '文风', '简洁', 1, 1)",
            )
            db.execSQL(
                "INSERT INTO conversation_context " +
                    "(conversationId, summary, coveredMessageCount, updatedAtMillis) " +
                    "VALUES ('conversation', '摘要', 1, 20)",
            )
            db.execSQL("UPDATE conversation_rp_settings SET writingStyleId = 'style'")
            db.execSQL("DELETE FROM writing_style_profiles WHERE id = 'style'")
            assertNull(queryNullableString(db, "SELECT writingStyleId FROM conversation_rp_settings"))
            db.execSQL("DELETE FROM role_cards WHERE id = 'role'")
            db.execSQL("DELETE FROM world_settings WHERE id = 'world'")
            assertNull(queryNullableString(db, "SELECT roleCardId FROM conversation_rp_settings"))
            assertNull(queryNullableString(db, "SELECT worldSettingId FROM conversation_rp_settings"))

            db.execSQL("DELETE FROM conversations WHERE id = 'conversation'")
            assertEquals(0, rowCount(db, "messages"))
            assertEquals(0, rowCount(db, "conversation_rp_settings"))
            assertEquals(0, rowCount(db, "conversation_context"))
            assertEquals(0, rowCount(db, "rp_status_panels"))
        }

    @Test
    fun migrate8To9_addsTokenUsageTableWithNullableCountersAndCascade() =
        withReconstructedDatabase(8) { db ->
            insertCoreConversationAndMessage(db)

            migrate(db, 8, 9)

            assertColumns(
                db,
                "conversation_token_usage",
                column("conversationId", "TEXT", primaryKey = true),
                column("providerKey", "TEXT"),
                column("model", "TEXT"),
                // 三个计数列必须可空：上游不返回用量是常态，用 0 冒充会让界面撒谎。
                column("promptTokens", "INTEGER", notNull = false),
                column("completionTokens", "INTEGER", notNull = false),
                column("totalTokens", "INTEGER", notNull = false),
                column("effectiveContextTokens", "INTEGER"),
                column("capturedAtMillis", "INTEGER"),
            )
            assertForeignKey(db, "conversation_token_usage", "conversationId", "conversations", "id", "CASCADE")

            // v8 已有的数据不受影响。
            assertEquals(1, rowCount(db, "conversations"))
            assertEquals(1, rowCount(db, "messages"))
            assertEquals(0, rowCount(db, "conversation_token_usage"))

            db.execSQL(
                "INSERT INTO conversation_token_usage " +
                    "(conversationId, providerKey, model, promptTokens, completionTokens, totalTokens, " +
                    "effectiveContextTokens, capturedAtMillis) " +
                    "VALUES ('conversation', 'DEEPSEEK', 'deepseek-v4-flash', 13102, 816, 13918, 950000, 42)",
            )
            assertEquals(13102L, queryLong(db, "SELECT promptTokens FROM conversation_token_usage"))

            // 只拿到 total 的上游也要能存进来，不能被非空约束挡住。
            db.execSQL(
                "INSERT INTO conversation_token_usage " +
                    "(conversationId, providerKey, model, promptTokens, completionTokens, totalTokens, " +
                    "effectiveContextTokens, capturedAtMillis) " +
                    "VALUES ('conversation-2', 'p1', 'm', NULL, NULL, 100, 32768, 43)",
            )

            db.execSQL("DELETE FROM conversations WHERE id = 'conversation'")
            assertEquals(1, rowCount(db, "conversation_token_usage"))
        }

    @Test
    fun migrate8To9_fromExportedV8Schema_validatesAgainstExportedV9Schema() {
        val name = newDatabaseName("exported-8-9")
        exportedSchemaHelper.createDatabase(name, 8).use { db ->
            insertCoreConversationAndMessage(db)
        }

        // validateDroppedTables=true：v9 不应残留任何 9.json 之外的表。
        exportedSchemaHelper.runMigrationsAndValidate(
            name,
            9,
            true,
            findProductionMigration(8, 9),
        ).use { db ->
            assertEquals(1, rowCount(db, "conversations"))
            assertEquals(1, rowCount(db, "messages"))
            assertEquals(0, rowCount(db, "conversation_token_usage"))
            assertForeignKey(db, "conversation_token_usage", "conversationId", "conversations", "id", "CASCADE")
        }
    }

    @Test
    fun migrate1To9_opensWithRoomV9ValidationAndPreservesCoreRows() {
        val name = newDatabaseName("full-path")
        createReconstructedDatabase(name, 1).use { helper ->
            insertCoreConversationAndMessage(helper.writableDatabase)
        }

        val roomDatabase = Room.databaseBuilder(context, ChatDatabase::class.java, name)
            .addMigrations(*migrations.toTypedArray())
            .allowMainThreadQueries()
            .build()
        try {
            val db = roomDatabase.openHelper.writableDatabase
            // 成功打开已经触发 ChatDatabase_Impl 对 v9 表、列、索引和外键的完整校验。
            assertEquals(9L, queryLong(db, "PRAGMA user_version"))
            assertEquals("历史会话", queryString(db, "SELECT title FROM conversations WHERE id = 'conversation'"))
            assertEquals("历史消息", queryString(db, "SELECT content FROM messages WHERE id = 'message'"))
            assertFalse(tableExists(db, "conversation_rp_settings_new"))
            assertTrue(tableExists(db, "writing_style_profiles"))
            assertTrue(tableExists(db, "conversation_context"))
            assertTrue(tableExists(db, "conversation_token_usage"))
        } finally {
            roomDatabase.close()
        }
    }

    private fun withReconstructedDatabase(
        version: Int,
        block: (SupportSQLiteDatabase) -> Unit,
    ) {
        val name = newDatabaseName("v$version")
        createReconstructedDatabase(name, version).use { helper ->
            block(helper.writableDatabase)
        }
    }

    private fun createReconstructedDatabase(
        name: String,
        version: Int,
    ): SupportSQLiteOpenHelper {
        require(version in 1..8)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onConfigure(db: SupportSQLiteDatabase) {
                        db.setForeignKeyConstraintsEnabled(true)
                    }

                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createReconstructedV1Schema(db)
                        migrations.take(version - 1).forEach { it.migrate(db) }
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = error("测试建库不应触发升级：$oldVersion -> $newVersion")
                },
            )
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).also { it.writableDatabase }
    }

    private fun createReconstructedV1Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversations` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `updatedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `messages` (
                `id` TEXT NOT NULL,
                `conversationId` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `reasoningContent` TEXT,
                `status` TEXT NOT NULL,
                `errorMessage` TEXT,
                `createdAtMillis` INTEGER NOT NULL,
                `updatedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_messages_conversationId` " +
                "ON `messages` (`conversationId`)",
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        // 真实 v1 identity hash 已不可恢复；升级流程不依赖旧 hash，v8 打开后 Room 会写入当前 hash。
        db.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                "VALUES (42, 'unrecoverable-reconstructed-v1')",
        )
    }

    private fun migrate(
        db: SupportSQLiteDatabase,
        startVersion: Int,
        endVersion: Int,
    ) {
        val migration = migrations.single {
            it.startVersion == startVersion && it.endVersion == endVersion
        }
        db.beginTransaction()
        try {
            migration.migrate(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun findProductionMigration(
        startVersion: Int,
        endVersion: Int,
    ): Migration {
        val holders = listOf(
            ChatDatabase::class.java to null,
            ChatDatabase.Companion::class.java to ChatDatabase.Companion,
        )
        for ((holderClass, receiver) in holders) {
            for (field in holderClass.declaredFields) {
                if (!Migration::class.java.isAssignableFrom(field.type)) continue
                field.isAccessible = true
                val value = field.get(if (Modifier.isStatic(field.modifiers)) null else receiver) as? Migration
                if (value?.startVersion == startVersion && value.endVersion == endVersion) return value
            }
        }
        error("找不到生产 migration：$startVersion -> $endVersion")
    }

    private fun insertCoreConversationAndMessage(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO conversations (id, title, createdAtMillis, updatedAtMillis) " +
                "VALUES ('conversation', '历史会话', 1, 2)",
        )
        insertMessage(db, "message")
    }

    private fun insertMessage(
        db: SupportSQLiteDatabase,
        id: String,
    ) {
        db.execSQL(
            "INSERT INTO messages " +
                "(id, conversationId, role, content, reasoningContent, status, errorMessage, " +
                "createdAtMillis, updatedAtMillis) VALUES (?, 'conversation', 'ASSISTANT', " +
                "'历史消息', NULL, 'COMPLETED', NULL, 1, 2)",
            arrayOf<Any?>(id),
        )
    }

    private fun insertRoleCardV2(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO role_cards " +
                "(id, name, description, persona, speakingStyle, userIdentity, openingMessage, " +
                "boundaries, createdAtMillis, updatedAtMillis) " +
                "VALUES ('role', '角色', '', '', '', '', '', '', 1, 2)",
        )
    }

    private fun insertRoleCardV3(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO role_cards " +
                "(id, name, description, persona, speakingStyle, userIdentity, openingMessage, " +
                "boundaries, metricDefinitionsJson, createdAtMillis, updatedAtMillis) " +
                "VALUES ('role', '角色', '', '', '', '', '', '', '[]', 1, 2)",
        )
    }

    private fun insertWorldSetting(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO world_settings (id, title, content, createdAtMillis, updatedAtMillis) " +
                "VALUES ('world', '世界', '设定', 1, 2)",
        )
    }

    private fun insertRpSettingsV4(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO conversation_rp_settings " +
                "(conversationId, isRpEnabled, roleCardId, worldSettingId, updatedAtMillis) " +
                "VALUES ('conversation', 1, 'role', 'world', 10)",
        )
    }

    private fun insertRpSettingsV5(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO conversation_rp_settings " +
                "(conversationId, isRpEnabled, roleCardId, worldSettingId, protagonistName, " +
                "protagonistDescription, protagonistInitialState, updatedAtMillis) " +
                "VALUES ('conversation', 1, NULL, NULL, '主角', '描述', '初态', 10)",
        )
    }

    private fun insertRpSettingsV6(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO conversation_rp_settings " +
                "(conversationId, isRpEnabled, roleCardId, worldSettingId, protagonistName, " +
                "protagonistDescription, protagonistInitialState, narrativeMinChars, updatedAtMillis) " +
                "VALUES ('conversation', 1, NULL, NULL, '主角', '描述', '初态', 1200, 10)",
        )
    }

    private fun insertRpSettingsV7(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO conversation_rp_settings " +
                "(conversationId, isRpEnabled, roleCardId, worldSettingId, protagonistName, " +
                "protagonistDescription, protagonistInitialState, narrativeMinChars, " +
                "narrativePerspective, plotSuggestionPerspective, rpMarkerMode, updatedAtMillis) " +
                "VALUES ('conversation', 1, 'role', 'world', '主角', '描述', '初态', 1200, " +
                "'THIRD_PERSON', 'OBSERVER', 'ENABLED', 10)",
        )
    }

    private fun insertStatusPanel(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO rp_status_panels " +
                "(conversationId, metricsJson, relationship, scene, rawJson, updatedAtMillis) " +
                "VALUES ('conversation', '[]', '熟识', '庭院', '{}', 10)",
        )
    }

    private fun insertMessageMetadata(
        db: SupportSQLiteDatabase,
        messageId: String,
    ) {
        db.execSQL(
            "INSERT INTO message_rp_metadata " +
                "(messageId, conversationId, optionsJson, innerThought, metricsJson, relationship, " +
                "scene, rawJson, createdAtMillis, updatedAtMillis) " +
                "VALUES (?, 'conversation', '[]', '想法', '[]', '', '', '', 1, 2)",
            arrayOf<Any?>(messageId),
        )
    }

    private fun assertColumns(
        db: SupportSQLiteDatabase,
        table: String,
        vararg expected: Column,
    ) {
        assertEquals("$table 列定义不一致", expected.toList(), columns(db, table))
    }

    private fun assertColumn(
        db: SupportSQLiteDatabase,
        table: String,
        name: String,
        type: String,
        notNull: Boolean,
        defaultValue: String?,
    ) {
        val actual = columns(db, table).single { it.name == name }
        assertEquals(Column(name, type, notNull, defaultValue, primaryKey = false), actual)
    }

    private fun columns(
        db: SupportSQLiteDatabase,
        table: String,
    ): List<Column> = db.query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        val typeIndex = cursor.getColumnIndexOrThrow("type")
        val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
        val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
        val primaryKeyIndex = cursor.getColumnIndexOrThrow("pk")
        buildList {
            while (cursor.moveToNext()) {
                add(
                    Column(
                        name = cursor.getString(nameIndex),
                        type = cursor.getString(typeIndex),
                        notNull = cursor.getInt(notNullIndex) == 1,
                        defaultValue = if (cursor.isNull(defaultIndex)) null else cursor.getString(defaultIndex),
                        primaryKey = cursor.getInt(primaryKeyIndex) > 0,
                    ),
                )
            }
        }
    }

    private fun assertIndex(
        db: SupportSQLiteDatabase,
        table: String,
        indexName: String,
        vararg columnNames: String,
    ) {
        val indexExists = db.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }
                .any { it == indexName }
        }
        assertTrue("$table 缺少索引 $indexName", indexExists)
        val actualColumns = db.query("PRAGMA index_info(`$indexName`)").use { cursor ->
            val columnIndex = cursor.getColumnIndexOrThrow("name")
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(columnIndex))
            }
        }
        assertEquals("$indexName 索引列不一致", columnNames.toList(), actualColumns)
    }

    private fun assertForeignKey(
        db: SupportSQLiteDatabase,
        table: String,
        fromColumn: String,
        referencedTable: String,
        referencedColumn: String,
        onDelete: String,
    ) {
        val actualForeignKeys = db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            val tableIndex = cursor.getColumnIndexOrThrow("table")
            val fromIndex = cursor.getColumnIndexOrThrow("from")
            val toIndex = cursor.getColumnIndexOrThrow("to")
            val onUpdateIndex = cursor.getColumnIndexOrThrow("on_update")
            val onDeleteIndex = cursor.getColumnIndexOrThrow("on_delete")
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ForeignKey(
                            fromColumn = cursor.getString(fromIndex),
                            referencedTable = cursor.getString(tableIndex),
                            referencedColumn = cursor.getString(toIndex),
                            onUpdate = cursor.getString(onUpdateIndex),
                            onDelete = cursor.getString(onDeleteIndex),
                        ),
                    )
                }
            }
        }
        assertTrue(
            "$table 缺少外键 $fromColumn -> $referencedTable.$referencedColumn ($onDelete)",
            ForeignKey(fromColumn, referencedTable, referencedColumn, "NO ACTION", onDelete) in actualForeignKeys,
        )
    }

    private fun rowCount(
        db: SupportSQLiteDatabase,
        table: String,
    ): Int = queryLong(db, "SELECT COUNT(*) FROM `$table`").toInt()

    private fun tableExists(
        db: SupportSQLiteDatabase,
        table: String,
    ): Boolean = db.query(
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf<Any?>(table),
    ).use { cursor ->
        cursor.moveToFirst() && cursor.getInt(0) == 1
    }

    private fun queryString(
        db: SupportSQLiteDatabase,
        sql: String,
    ): String = db.query(sql).use { cursor ->
        assertTrue("查询没有返回行：$sql", cursor.moveToFirst())
        cursor.getString(0)
    }

    private fun queryNullableString(
        db: SupportSQLiteDatabase,
        sql: String,
    ): String? = db.query(sql).use { cursor ->
        assertTrue("查询没有返回行：$sql", cursor.moveToFirst())
        if (cursor.isNull(0)) null else cursor.getString(0)
    }

    private fun queryLong(
        db: SupportSQLiteDatabase,
        sql: String,
    ): Long = db.query(sql).use { cursor ->
        assertTrue("查询没有返回行：$sql", cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun newDatabaseName(label: String): String =
        "room-migration-$label-${UUID.randomUUID()}.db".also(databaseNames::add)

    private fun column(
        name: String,
        type: String,
        notNull: Boolean = true,
        defaultValue: String? = null,
        primaryKey: Boolean = false,
    ) = Column(name, type, notNull, defaultValue, primaryKey)

    private data class Column(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKey: Boolean,
    )

    private data class ForeignKey(
        val fromColumn: String,
        val referencedTable: String,
        val referencedColumn: String,
        val onUpdate: String,
        val onDelete: String,
    )
}
