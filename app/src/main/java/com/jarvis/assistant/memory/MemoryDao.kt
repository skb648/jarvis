package com.jarvis.assistant.memory

import androidx.room.*
import com.jarvis.assistant.data.local.MessageEntity

/**
 * MemoryDao — Data Access Object for semantic memory tags.
 *
 * Stores metadata about conversations that makes them searchable by
 * keyword and tag. This powers the "Sir, apne kal bola tha..." memory
 * system, allowing JARVIS to recall past conversations contextually.
 *
 * Memory tags are extracted from conversations and stored with keywords
 * so that relevant past messages can be retrieved even when the user
 * doesn't use the exact same words.
 *
 * Examples:
 *   - tag="preference", keyword="dark mode" → "Sir likes dark mode"
 *   - tag="schedule", keyword="meeting" → "Sir has a meeting at 3pm"
 *   - tag="person_name", keyword="Priya" → "Priya is Sir's colleague"
 *   - tag="important", keyword="birthday" → "Sir's birthday is on 15th"
 */
@Entity(
    tableName = "memory_tags",
    indices = [
        Index(value = ["keyword"], name = "idx_memory_keyword"),
        Index(value = ["tag"], name = "idx_memory_tag"),
        Index(value = ["messageId"], name = "idx_memory_message_id"),
        Index(value = ["createdAt"], name = "idx_memory_created_at")
    ]
)
data class MemoryTag(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Reference to the message this tag was extracted from */
    val messageId: Long,

    /** Category tag: "preference", "schedule", "important", "person_name", "fact", "task" */
    val tag: String,

    /** Search keyword extracted from the message */
    val keyword: String,

    /** When this memory tag was created */
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * DAO for memory tags — enables semantic search over conversation history.
 */
@Dao
interface MemoryDao {

    /**
     * Insert a new memory tag.
     */
    @Insert
    suspend fun insertMemoryTag(tag: MemoryTag): Long

    /**
     * Insert multiple memory tags at once.
     */
    @Insert
    suspend fun insertMemoryTags(tags: List<MemoryTag>): List<Long>

    /**
     * Search memory tags by keyword (case-insensitive via LIKE).
     * Returns matching tags ordered by most recent first.
     */
    @Query("""
        SELECT * FROM memory_tags 
        WHERE keyword LIKE '%' || :keyword || '%'
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun searchByKeyword(keyword: String, limit: Int = 10): List<MemoryTag>

    /**
     * Search memory tags by tag category.
     */
    @Query("""
        SELECT * FROM memory_tags 
        WHERE tag = :tag
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun searchByTag(tag: String, limit: Int = 10): List<MemoryTag>

    /**
     * Search memory tags by keyword OR tag.
     * Used for broad semantic recall.
     */
    @Query("""
        SELECT * FROM memory_tags 
        WHERE keyword LIKE '%' || :query || '%' 
           OR tag LIKE '%' || :query || '%'
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun searchByKeywordOrTag(query: String, limit: Int = 10): List<MemoryTag>

    /**
     * Get memory tags created within a specific time range.
     * Used for time-based recall ("yesterday", "last week").
     */
    @Query("""
        SELECT * FROM memory_tags 
        WHERE createdAt BETWEEN :fromTimestamp AND :toTimestamp
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun searchByTimeRange(
        fromTimestamp: Long,
        toTimestamp: Long,
        limit: Int = 10
    ): List<MemoryTag>

    /**
     * Get all memory tags ordered by most recent.
     */
    @Query("SELECT * FROM memory_tags ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getAllTags(limit: Int = 50): List<MemoryTag>

    /**
     * Delete memory tags older than the given timestamp.
     */
    @Query("DELETE FROM memory_tags WHERE createdAt < :cutoffTimestamp")
    suspend fun deleteOldTags(cutoffTimestamp: Long)

    /**
     * Delete all memory tags.
     */
    @Query("DELETE FROM memory_tags")
    suspend fun deleteAllTags()

    /**
     * Get count of memory tags.
     */
    @Query("SELECT COUNT(*) FROM memory_tags")
    suspend fun getTagCount(): Int
}
