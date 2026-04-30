package com.jarvis.assistant.data.local

import androidx.room.*

/**
 * MessageDao — Data Access Object for the messages and sessions tables.
 *
 * Provides all CRUD operations needed for JARVIS's persistent memory:
 *   - Insert messages and sessions
 *   - Load the last N messages for Gemini context (never forget)
 *   - List sessions for the chat history drawer
 *   - Delete old messages to keep the DB lean
 *   - Clear all history
 */
@Dao
interface MessageDao {

    // ═══════════════════════════════════════════════════════════════════════
    // MESSAGE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Insert a new message. Returns the generated row ID.
     */
    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    /**
     * Get the last N messages in a session, ordered by timestamp ascending.
     * This is what gets fed into the Gemini contents array for context.
     *
     * @param sessionId The session to query
     * @param limit Maximum number of messages to return (default 20)
     * @return List of MessageEntity ordered oldest-to-newest
     */
    @Query("""
        SELECT * FROM messages 
        WHERE sessionId = :sessionId 
        ORDER BY timestamp ASC 
        LIMIT :limit
    """)
    suspend fun getLastMessages(sessionId: Long, limit: Int = 20): List<MessageEntity>

    /**
     * Get ALL messages for a session (for loading a past session).
     */
    @Query("""
        SELECT * FROM messages 
        WHERE sessionId = :sessionId 
        ORDER BY timestamp ASC
    """)
    suspend fun getAllMessagesForSession(sessionId: Long): List<MessageEntity>

    /**
     * Count messages in a session.
     */
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun getMessageCount(sessionId: Long): Int

    /**
     * Delete messages older than the given timestamp.
     * Used for housekeeping to prevent the DB from growing indefinitely.
     */
    @Query("DELETE FROM messages WHERE timestamp < :cutoffTimestamp AND sessionId != :activeSessionId")
    suspend fun deleteOldMessages(cutoffTimestamp: Long, activeSessionId: Long)

    /**
     * Delete all messages for a specific session.
     */
    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: Long)

    /**
     * Delete all messages.
     */
    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    // ═══════════════════════════════════════════════════════════════════════
    // SESSION OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Insert a new session. Returns the generated row ID.
     */
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    /**
     * Update an existing session.
     */
    @Update
    suspend fun updateSession(session: SessionEntity)

    /**
     * Get a session by ID.
     */
    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: Long): SessionEntity?

    /**
     * Get all sessions ordered by most recent activity first.
     * Used by the chat history drawer.
     */
    @Query("""
        SELECT * FROM sessions 
        ORDER BY lastActivityTimestamp DESC
    """)
    suspend fun getAllSessions(): List<SessionEntity>

    /**
     * Get the most recently active session.
     */
    @Query("""
        SELECT * FROM sessions 
        ORDER BY lastActivityTimestamp DESC 
        LIMIT 1
    """)
    suspend fun getLatestSession(): SessionEntity?

    /**
     * Delete a specific session and all its messages.
     */
    @Transaction
    suspend fun deleteSession(sessionId: Long) {
        deleteMessagesForSession(sessionId)
        deleteSessionById(sessionId)
    }

    /**
     * Delete all sessions and messages.
     */
    @Transaction
    suspend fun deleteAllSessions() {
        deleteAllMessages()
        deleteAllSessionRows()
    }

    /**
     * Internal: Delete a session row by ID.
     */
    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: Long)

    /**
     * Internal: Delete all session rows.
     */
    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessionRows()

    /**
     * Update session metadata (title, preview, message count, timestamp).
     * Called after each message is inserted.
     */
    @Query("""
        UPDATE sessions 
        SET preview = :preview, 
            messageCount = :messageCount, 
            lastActivityTimestamp = :lastActivityTimestamp
        WHERE id = :sessionId
    """)
    suspend fun updateSessionMetadata(
        sessionId: Long,
        preview: String,
        messageCount: Int,
        lastActivityTimestamp: Long
    )

    /**
     * Update session title (set from first user message).
     */
    @Query("UPDATE sessions SET title = :title WHERE id = :sessionId")
    suspend fun updateSessionTitle(sessionId: Long, title: String)

    // ═══════════════════════════════════════════════════════════════════════
    // MEMORY SYSTEM — Additional queries for conversation memory
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Get a message by its ID.
     * Used by ConversationMemory to look up tagged messages.
     */
    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: Long): MessageEntity?

    /**
     * Find the first model reply after a user message in the same session.
     * Used by ConversationMemory to pair user messages with JARVIS responses.
     */
    @Query("""
        SELECT * FROM messages 
        WHERE sessionId = :sessionId 
          AND role = 'model' 
          AND timestamp > :afterTimestamp 
        ORDER BY timestamp ASC 
        LIMIT 1
    """)
    suspend fun getReplyAfterMessage(sessionId: Long, afterTimestamp: Long): MessageEntity?

    /**
     * Search messages by content keyword (case-insensitive).
     * Used by ConversationMemory for direct content-based recall.
     */
    @Query("""
        SELECT * FROM messages 
        WHERE content LIKE '%' || :keyword || '%'
          AND role = 'user'
        ORDER BY timestamp DESC 
        LIMIT :limit
    """)
    suspend fun searchMessagesByContent(keyword: String, limit: Int = 5): List<MessageEntity>
}
