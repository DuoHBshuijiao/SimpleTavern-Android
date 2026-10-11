package com.simpletavern.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatEntity::class,
        MessageNodeEntity::class,
        MessageCandidateEntity::class,
        CharacterEntity::class,
        PersonaEntity::class,
        WorldBookEntity::class,
        LongTermMemoryEntity::class,
        MemorySummaryJobEntity::class,
        MvuStateEntity::class,
        KnowledgeGraphEntity::class,
        MvuWorkLogEntity::class,
        AttachmentEntity::class,
        AssistantEntity::class,
        AssistantMessageEntity::class,
        ModelPresetEntity::class,
        AppSettingsEntity::class,
        ImportBatchEntity::class,
        IdMapEntity::class,
        TaskEntity::class,
        ApprovalEntity::class,
        FileChangeEntity::class,
        SandboxEntity::class,
        UsageEventEntity::class,
        SourceBlobEntity::class,
        ContentRegexRecordEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chats(): ChatDao
    abstract fun messages(): MessageDao
    abstract fun characters(): CharacterDao
    abstract fun personas(): PersonaDao
    abstract fun worldBooks(): WorldBookDao
    abstract fun memory(): MemoryDao
    abstract fun mvu(): MvuDao
    abstract fun attachments(): AttachmentDao
    abstract fun config(): ConfigDao
    abstract fun imports(): ImportDao
    abstract fun tasks(): TaskDao
    abstract fun approvals(): ApprovalDao
    abstract fun fileChanges(): FileChangeDao
    abstract fun sandboxes(): SandboxDao
    abstract fun usage(): UsageDao
    abstract fun contentRegex(): ContentRegexDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * Destructive fallback is intentionally not used.
         * Future schema upgrades must add explicit Migration objects.
         */
        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "simpletavern.db",
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS content_regex_records (
                    messageId TEXT NOT NULL PRIMARY KEY, chatId TEXT NOT NULL, nodeId TEXT NOT NULL,
                    orderIndex INTEGER NOT NULL, revision INTEGER NOT NULL, sourceText TEXT NOT NULL,
                    rulesJson TEXT NOT NULL, outputJson TEXT NOT NULL, queueStatus TEXT NOT NULL,
                    consumedByTaskId TEXT, updatedAt TEXT NOT NULL)""".trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_content_regex_records_chatId_queueStatus_orderIndex ON content_regex_records (chatId, queueStatus, orderIndex)")
            }
        })
    }
}

/** Placeholder migration slot documenting no destructive upgrade path. */
object SchemaPolicy {
    const val FORBID_DESTRUCTIVE_FALLBACK = true
}
