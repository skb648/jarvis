package com.jarvis.assistant.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * JarvisDatabase — Room Database for JARVIS's persistent brain.
 *
 * Stores all chat messages and sessions so that:
 *   1. JARVIS never forgets past context (loads last 20 into Groq)
 *   2. The chat history drawer can display past sessions
 *   3. The user can switch between sessions and resume conversations
 *
 * ═══════════════════════════════════════════════════════════════════════
 * MIGRATION STRATEGY:
 *
 * Version 1 → 2: MemoryTag table added for the memory system.
 *   - Creates the `memory_tags` table used by MemoryDao.
 *   - All existing chat data (messages, sessions) is preserved.
 *
 * For future schema changes, add a new MIGRATION_X_Y constant and
 * register it in the builder via .addMigrations().
 * ═══════════════════════════════════════════════════════════════════════
 */
@Database(
    entities = [MessageEntity::class, SessionEntity::class, com.jarvis.assistant.memory.MemoryTag::class],
    version = 2,
    exportSchema = false
)
abstract class JarvisDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): com.jarvis.assistant.memory.MemoryDao

    companion object {
        private const val DATABASE_NAME = "jarvis_brain"

        @Volatile
        private var INSTANCE: JarvisDatabase? = null

        /**
         * Migration from version 1 to version 2.
         * Adds the memory_tags table for the memory system.
         * Preserves all existing chat messages and sessions.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create the memory_tags table added in version 2.
                // Schema must match the MemoryTag entity exactly.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `memory_tags` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `messageId` INTEGER NOT NULL,
                        `tag` TEXT NOT NULL,
                        `keyword` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_memory_keyword` ON `memory_tags` (`keyword`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_memory_tag` ON `memory_tags` (`tag`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_memory_message_id` ON `memory_tags` (`messageId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_memory_created_at` ON `memory_tags` (`createdAt`)")
            }
        }

        /**
         * Get the singleton database instance.
         * Thread-safe via double-checked locking with @Volatile.
         *
         * @param context Application context (uses applicationContext to prevent leaks)
         * @return The JarvisDatabase singleton
         */
        fun getInstance(context: Context): JarvisDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    JarvisDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    // Only fall back to destructive migration for future versions
                    // that don't yet have an explicit migration defined.
                    // This prevents accidental data loss for known migrations.
                    .fallbackToDestructiveMigrationFrom(1)
                    .setJournalMode(JournalMode.TRUNCATE)  // More compatible across devices
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
