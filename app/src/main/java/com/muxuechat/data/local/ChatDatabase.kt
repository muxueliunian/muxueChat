package com.muxuechat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

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
    ],
    version = 9,
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
