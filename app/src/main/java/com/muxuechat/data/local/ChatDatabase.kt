package com.muxuechat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        RoleCardEntity::class,
        WorldSettingEntity::class,
        ConversationRpSettingsEntity::class,
        RpStatusPanelEntity::class,
        MessageRpMetadataEntity::class,
        WritingStyleProfileEntity::class,
        ConversationContextEntity::class,
        ConversationTokenUsageEntity::class,
        ProtagonistProfileEntity::class,
        SupportingCharacterEntity::class,
        ConversationSupportingCharacterEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile private var instance: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "muxuechat.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4)
                    .addMigrations(MIGRATION_4_5)
                    .addMigrations(MIGRATION_5_6)
                    .addMigrations(MIGRATION_6_7)
                    .addMigrations(MIGRATION_7_8)
                    .addMigrations(MIGRATION_8_9)
                    .addMigrations(MIGRATION_9_10)
                    .addCallback(STARTUP_GENERATION_RECOVERY_CALLBACK)
                    .build()
                    .also { instance = it }
            }

        /**
         * Room 单例首次打开数据库时执行一次进程恢复。
         *
         * history-replacement 行还不是正式历史，必须删除而不是保留为 CANCELLED；普通生成行则
         * 转成可见的取消态。两步处于同一 SQLite 事务，启动期间不会暴露半恢复状态。
         */
        internal val STARTUP_GENERATION_RECOVERY_CALLBACK = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                val recoveredAtMillis = System.currentTimeMillis()
                db.beginTransaction()
                try {
                    db.execSQL(
                        """
                        DELETE FROM `messages`
                        WHERE `id` LIKE 'history-replacement:%'
                            AND `status` != 'COMPLETED'
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        UPDATE `messages`
                        SET `status` = 'CANCELLED', `errorMessage` = NULL, `updatedAtMillis` = ?
                        WHERE `id` NOT LIKE 'history-replacement:%'
                            AND `status` IN ('PENDING', 'STREAMING')
                        """.trimIndent(),
                        arrayOf(recoveredAtMillis),
                    )
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `role_cards` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `persona` TEXT NOT NULL,
                        `speakingStyle` TEXT NOT NULL,
                        `userIdentity` TEXT NOT NULL,
                        `openingMessage` TEXT NOT NULL,
                        `boundaries` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `world_settings` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversation_rp_settings` (
                        `conversationId` TEXT NOT NULL,
                        `isRpEnabled` INTEGER NOT NULL,
                        `roleCardId` TEXT,
                        `worldSettingId` TEXT,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`conversationId`),
                        FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`roleCardId`) REFERENCES `role_cards`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`worldSettingId`) REFERENCES `world_settings`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_rp_settings_roleCardId` ON `conversation_rp_settings` (`roleCardId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_rp_settings_worldSettingId` ON `conversation_rp_settings` (`worldSettingId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `rp_status_panels` (
                        `conversationId` TEXT NOT NULL,
                        `metricsJson` TEXT NOT NULL,
                        `relationship` TEXT NOT NULL,
                        `scene` TEXT NOT NULL,
                        `rawJson` TEXT NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`conversationId`),
                        FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `role_cards` ADD COLUMN `metricDefinitionsJson` TEXT NOT NULL DEFAULT '[]'",
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `message_rp_metadata` (
                        `messageId` TEXT NOT NULL,
                        `conversationId` TEXT NOT NULL,
                        `optionsJson` TEXT NOT NULL,
                        `innerThought` TEXT NOT NULL,
                        `metricsJson` TEXT NOT NULL,
                        `relationship` TEXT NOT NULL,
                        `scene` TEXT NOT NULL,
                        `rawJson` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`messageId`),
                        FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_rp_metadata_conversationId` ON `message_rp_metadata` (`conversationId`)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `conversation_rp_settings` ADD COLUMN `protagonistName` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `conversation_rp_settings` ADD COLUMN `protagonistDescription` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `conversation_rp_settings` ADD COLUMN `protagonistInitialState` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `conversation_rp_settings` ADD COLUMN `narrativeMinChars` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v8：上下文摘要压缩 + 文风设定。
         *
         * 注意 `conversation_rp_settings` 这里是**整表重建**而不是 ALTER ADD COLUMN。
         * SQLite 的 ALTER TABLE 无法给已有表补外键约束，只加列会让实际 schema 与 Room
         * 期望的 schema 不一致，启动时直接抛 IllegalStateException。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `writing_style_profiles` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversation_context` (
                        `conversationId` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `coveredMessageCount` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`conversationId`),
                        FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )

                // 重建 conversation_rp_settings 以加入 writingStyleId 及其外键。
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversation_rp_settings_new` (
                        `conversationId` TEXT NOT NULL,
                        `isRpEnabled` INTEGER NOT NULL,
                        `roleCardId` TEXT,
                        `worldSettingId` TEXT,
                        `writingStyleId` TEXT,
                        `protagonistName` TEXT NOT NULL,
                        `protagonistDescription` TEXT NOT NULL,
                        `protagonistInitialState` TEXT NOT NULL,
                        `narrativeMinChars` INTEGER NOT NULL,
                        `narrativePerspective` TEXT NOT NULL,
                        `plotSuggestionPerspective` TEXT NOT NULL,
                        `rpMarkerMode` TEXT NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`conversationId`),
                        FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`roleCardId`) REFERENCES `role_cards`(`id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`worldSettingId`) REFERENCES `world_settings`(`id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`writingStyleId`) REFERENCES `writing_style_profiles`(`id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `conversation_rp_settings_new` (
                        `conversationId`, `isRpEnabled`, `roleCardId`, `worldSettingId`, `writingStyleId`,
                        `protagonistName`, `protagonistDescription`, `protagonistInitialState`,
                        `narrativeMinChars`, `narrativePerspective`, `plotSuggestionPerspective`,
                        `rpMarkerMode`, `updatedAtMillis`
                    )
                    SELECT
                        `conversationId`, `isRpEnabled`, `roleCardId`, `worldSettingId`, NULL,
                        `protagonistName`, `protagonistDescription`, `protagonistInitialState`,
                        `narrativeMinChars`, `narrativePerspective`, `plotSuggestionPerspective`,
                        `rpMarkerMode`, `updatedAtMillis`
                    FROM `conversation_rp_settings`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `conversation_rp_settings`")
                db.execSQL("ALTER TABLE `conversation_rp_settings_new` RENAME TO `conversation_rp_settings`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversation_rp_settings_roleCardId` " +
                        "ON `conversation_rp_settings` (`roleCardId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversation_rp_settings_worldSettingId` " +
                        "ON `conversation_rp_settings` (`worldSettingId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversation_rp_settings_writingStyleId` " +
                        "ON `conversation_rp_settings` (`writingStyleId`)",
                )
            }
        }

        /**
         * v9：保存每个会话最近一次正文请求的真实 token 用量。
         *
         * 只是新建一张侧表，不动任何既有表，因此不需要 v8 那样的整表重建。
         * 外键 CASCADE 保证删除会话时用量自动消失。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversation_token_usage` (
                        `conversationId` TEXT NOT NULL,
                        `providerKey` TEXT NOT NULL,
                        `model` TEXT NOT NULL,
                        `promptTokens` INTEGER,
                        `completionTokens` INTEGER,
                        `totalTokens` INTEGER,
                        `effectiveContextTokens` INTEGER NOT NULL,
                        `capturedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`conversationId`),
                        FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v10：RP 角色体系的数据层（RP-1）。
         *
         * 六步的顺序不能换：新表要先于引用它的外键建好；
         * `conversation_rp_settings` 要先重建出新列，主角档案才有地方回填 id。
         *
         * `role_cards` 用 ADD COLUMN 而不是整表重建——它是
         * `conversation_rp_settings.roleCardId` 的**父表**，DROP 会触发 SET_NULL，
         * 把所有会话的角色卡绑定悄悄清空（§8.5）。代价是旧的 `metricDefinitionsJson`
         * 留在表里，但那本来就是想要的：坏 JSON 的原文因此不会丢。
         *
         * 状态名迁移只改 `metricsJson`，**不碰 `rawJson`**：rawJson 是模型当时原话的留档，
         * 代码里只当「这条有没有状态」的非空标记用，从不回解；改写它等于伪造记录。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                createRpActorTables(db)
                migrateRoleCardEntries(db)
                rebuildConversationRpSettingsForV10(db)
                migrateProtagonistProfiles(db, now)
                prefixLegacyMetricNames(db)
                backfillMetricSnapshots(db)
            }
        }

        private fun createRpActorTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `protagonist_profiles` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `description` TEXT NOT NULL,
                    `entriesJson` TEXT NOT NULL,
                    `createdAtMillis` INTEGER NOT NULL,
                    `updatedAtMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `supporting_characters` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `description` TEXT NOT NULL,
                    `entriesJson` TEXT NOT NULL,
                    `createdAtMillis` INTEGER NOT NULL,
                    `updatedAtMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            // 复合主键让「同一个配角重复加进同一个会话」在库层面不可能发生。
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `conversation_supporting_characters` (
                    `conversationId` TEXT NOT NULL,
                    `supportingCharacterId` TEXT NOT NULL,
                    `orderIndex` INTEGER NOT NULL,
                    PRIMARY KEY(`conversationId`, `supportingCharacterId`),
                    FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`supportingCharacterId`) REFERENCES `supporting_characters`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_conversation_supporting_characters_supportingCharacterId` " +
                    "ON `conversation_supporting_characters` (`supportingCharacterId`)",
            )
        }

        /** 角色卡数值 → 词条（§8.2）。`id` 原样保留，它是改名后找回原值的唯一依据。 */
        private fun migrateRoleCardEntries(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `role_cards` ADD COLUMN `entriesJson` TEXT NOT NULL DEFAULT '[]'")

            // 先读完再写：游标遍历期间修改同一张表没有行为保证。
            val rows = mutableListOf<Pair<String, String>>()
            db.query("SELECT `id`, `metricDefinitionsJson` FROM `role_cards`").use { cursor ->
                while (cursor.moveToNext()) {
                    rows += cursor.getString(0) to cursor.getString(1).orEmpty()
                }
            }
            rows.forEach { (id, raw) ->
                val converted = RpMigrationV10.metricDefinitionsToEntriesJson(raw)
                db.execSQL(
                    "UPDATE `role_cards` SET `entriesJson` = ? WHERE `id` = ?",
                    arrayOf(converted.json, id),
                )
            }
        }

        /**
         * 重建 `conversation_rp_settings`，加入主角档案外键与定义快照列。
         *
         * 和 v8 一样必须整表重建而不是 ALTER：SQLite 无法给已有表补外键约束。
         * 它是子表，DROP 不会触发任何父表动作。
         */
        private fun rebuildConversationRpSettingsForV10(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `conversation_rp_settings_new` (
                    `conversationId` TEXT NOT NULL,
                    `isRpEnabled` INTEGER NOT NULL,
                    `roleCardId` TEXT,
                    `worldSettingId` TEXT,
                    `writingStyleId` TEXT,
                    `protagonistProfileId` TEXT,
                    `metricSnapshotJson` TEXT NOT NULL DEFAULT '[]',
                    `protagonistName` TEXT NOT NULL,
                    `protagonistDescription` TEXT NOT NULL,
                    `protagonistInitialState` TEXT NOT NULL,
                    `narrativeMinChars` INTEGER NOT NULL,
                    `narrativePerspective` TEXT NOT NULL,
                    `plotSuggestionPerspective` TEXT NOT NULL,
                    `rpMarkerMode` TEXT NOT NULL,
                    `updatedAtMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`conversationId`),
                    FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`roleCardId`) REFERENCES `role_cards`(`id`)
                        ON UPDATE NO ACTION ON DELETE SET NULL,
                    FOREIGN KEY(`worldSettingId`) REFERENCES `world_settings`(`id`)
                        ON UPDATE NO ACTION ON DELETE SET NULL,
                    FOREIGN KEY(`writingStyleId`) REFERENCES `writing_style_profiles`(`id`)
                        ON UPDATE NO ACTION ON DELETE SET NULL,
                    FOREIGN KEY(`protagonistProfileId`) REFERENCES `protagonist_profiles`(`id`)
                        ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `conversation_rp_settings_new` (
                    `conversationId`, `isRpEnabled`, `roleCardId`, `worldSettingId`, `writingStyleId`,
                    `protagonistProfileId`, `metricSnapshotJson`,
                    `protagonistName`, `protagonistDescription`, `protagonistInitialState`,
                    `narrativeMinChars`, `narrativePerspective`, `plotSuggestionPerspective`,
                    `rpMarkerMode`, `updatedAtMillis`
                )
                SELECT
                    `conversationId`, `isRpEnabled`, `roleCardId`, `worldSettingId`, `writingStyleId`,
                    NULL, '[]',
                    `protagonistName`, `protagonistDescription`, `protagonistInitialState`,
                    `narrativeMinChars`, `narrativePerspective`, `plotSuggestionPerspective`,
                    `rpMarkerMode`, `updatedAtMillis`
                FROM `conversation_rp_settings`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `conversation_rp_settings`")
            db.execSQL("ALTER TABLE `conversation_rp_settings_new` RENAME TO `conversation_rp_settings`")
            listOf("roleCardId", "worldSettingId", "writingStyleId", "protagonistProfileId").forEach { column ->
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversation_rp_settings_$column` " +
                        "ON `conversation_rp_settings` (`$column`)",
                )
            }
        }

        /**
         * 主角三字段 → 独立档案（§8.1）。
         *
         * **每个会话生成一条独立档案，不按内容去重**：旧三字段是会话私有数据，
         * 按内容合并会把它们变成共享对象，此后改一处会影响多个会话——那是语义变更，
         * 不是去重优化。合并留给用户显式执行。
         */
        private fun migrateProtagonistProfiles(db: SupportSQLiteDatabase, now: Long) {
            val rows = mutableListOf<LegacyProtagonist>()
            db.query(
                "SELECT `conversationId`, `protagonistName`, `protagonistDescription`, " +
                    "`protagonistInitialState` FROM `conversation_rp_settings` " +
                    "ORDER BY `updatedAtMillis` ASC, `conversationId` ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows += LegacyProtagonist(
                        conversationId = cursor.getString(0),
                        name = cursor.getString(1).orEmpty(),
                        description = cursor.getString(2).orEmpty(),
                        initialState = cursor.getString(3).orEmpty(),
                    )
                }
            }

            rows.forEach { row ->
                if (!RpMigrationV10.hasProtagonistContent(row.name, row.description, row.initialState)) {
                    return@forEach
                }
                val profileId = UUID.randomUUID().toString()
                db.execSQL(
                    "INSERT INTO `protagonist_profiles` " +
                        "(`id`, `name`, `description`, `entriesJson`, `createdAtMillis`, `updatedAtMillis`) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf(
                        profileId,
                        // 名字为空就留空（用户 2026-09-23 选定）：占位名会被当成主角的真名发给模型。
                        row.name,
                        row.description,
                        RpMigrationV10.protagonistEntriesJson(row.initialState),
                        now,
                        now,
                    ),
                )
                db.execSQL(
                    "UPDATE `conversation_rp_settings` SET `protagonistProfileId` = ? WHERE `conversationId` = ?",
                    arrayOf(profileId, row.conversationId),
                )
            }
        }

        /**
         * 状态名迁移（§8.3）：**两张表都要改**，v1 只写了面板，这是最容易造成线上事故的遗漏。
         *
         * 不改 `updatedAtMillis`——这是格式迁移，不是内容变化，动时间戳会让界面
         * 看起来像是用户改过什么。
         */
        private fun prefixLegacyMetricNames(db: SupportSQLiteDatabase) {
            rewriteMetricNames(db, table = "rp_status_panels", keyColumn = "conversationId")
            rewriteMetricNames(db, table = "message_rp_metadata", keyColumn = "messageId")
        }

        private fun rewriteMetricNames(db: SupportSQLiteDatabase, table: String, keyColumn: String) {
            val rows = mutableListOf<Pair<String, String>>()
            db.query("SELECT `$keyColumn`, `metricsJson` FROM `$table`").use { cursor ->
                while (cursor.moveToNext()) {
                    rows += cursor.getString(0) to cursor.getString(1).orEmpty()
                }
            }
            rows.forEach { (key, raw) ->
                val rewritten = RpMigrationV10.prefixLegacyMetricNames(raw)
                if (rewritten == raw) return@forEach
                db.execSQL(
                    "UPDATE `$table` SET `metricsJson` = ? WHERE `$keyColumn` = ?",
                    arrayOf(rewritten, key),
                )
            }
        }

        /**
         * 回填定义快照（§8.4）。
         *
         * 只能冻结**升级当刻**的角色卡定义，不是还原当初绑定时的定义——v9 没存过历史定义。
         *
         * 对**所有绑定了角色卡的会话**回填，而不只是 `isRpEnabled` 的：暂停 RP 会保留快照
         * （§4），若只给启用中的会话回填，暂停中的会话恢复后就没有定义可用。
         */
        private fun backfillMetricSnapshots(db: SupportSQLiteDatabase) {
            val rows = mutableListOf<Pair<String, String>>()
            db.query(
                "SELECT s.`conversationId`, c.`entriesJson` FROM `conversation_rp_settings` s " +
                    "INNER JOIN `role_cards` c ON c.`id` = s.`roleCardId`",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows += cursor.getString(0) to cursor.getString(1).orEmpty()
                }
            }
            rows.forEach { (conversationId, entriesJson) ->
                db.execSQL(
                    "UPDATE `conversation_rp_settings` SET `metricSnapshotJson` = ? WHERE `conversationId` = ?",
                    arrayOf(RpMigrationV10.roleCardSnapshotJson(entriesJson), conversationId),
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `conversation_rp_settings` ADD COLUMN `narrativePerspective` TEXT NOT NULL DEFAULT 'PROTAGONIST_SECOND_PERSON'",
                )
                db.execSQL(
                    "ALTER TABLE `conversation_rp_settings` ADD COLUMN `plotSuggestionPerspective` TEXT NOT NULL DEFAULT 'PROTAGONIST'",
                )
                db.execSQL(
                    "ALTER TABLE `conversation_rp_settings` ADD COLUMN `rpMarkerMode` TEXT NOT NULL DEFAULT 'DISABLED'",
                )
            }
        }
    }
}

/** 迁移期间从 v9 三字段读出的主角内容。 */
private data class LegacyProtagonist(
    val conversationId: String,
    val name: String,
    val description: String,
    val initialState: String,
)
