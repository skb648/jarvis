package com.jarvis.assistant.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * JarvisDatabase — Room Database for JARVIS's persistent brain.
 *
 * Stores all chat messages and sessions so that:
 *   1. JARVIS never forgets past context (loads last 20 into Gemini)
 *   2. The chat history drawer can display past sessions
 *   3. The user can switch between sessions and resume conversations
 *
 * ═══════════════════════════════════════════════════════════════════════
 * MIGRATION STRATEGY:
 *
 * If you need to change the schema, increment the version number and
 * add a migration. Example:
 *
 *   val MIGRATION_1_2 = object : Migration(1, 2) {
 *       override fun migrate(db: SupportSQLiteDatabase) {
 *           db.execSQL("ALTER TABLE messages ADD COLUMN newColumn TEXT DEFAULT ''")
 *       }
 *   }
 *
 *   Room.databaseBuilder(...)
 *       .addMigrations(MIGRATION_1_2)
 *       .build()
 *
 * For now, fallbackToDestructiveMigration is used during active development.
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
                    .fallbackToDestructiveMigration()
                    .setJournalMode(JournalMode.TRUNCATE)  // More compatible across devices
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
