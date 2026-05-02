package com.jarvis.assistant.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * MessageEntity — Room Database entity for persistent chat history.
 *
 * Every user message and AI response is stored as a row in this table.
 * On app restart, the last 20 messages are loaded into the Groq
 * messages array so JARVIS never forgets the conversation context.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * DESIGN DECISIONS:
 *
 *   - sessionId: Groups messages into conversation sessions. Each "New Chat"
 *     creates a new session ID. This allows the chat history drawer to
 *     display past sessions and the user to switch between them.
 *
 *   - role: Either "user" or "model" (legacy from Gemini). Mapped to "assistant"
 *     when sent to the Groq messages array for API compatibility.
 *
 *   - emotion: The detected emotion tag for the message (for UI coloring).
 *
 *   - Index on [sessionId, timestamp]: Allows efficient queries for
 *     "give me the last 20 messages in session X" without scanning the
 *     entire table.
 * ═══════════════════════════════════════════════════════════════════════
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["sessionId", "timestamp"], name = "idx_session_timestamp"),
        Index(value = ["sessionId"], name = "idx_session")
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val sessionId: Long,

    /** "user" or "model" — mapped to "assistant" when sent to Groq API */
    val role: String,

    /** The message text content */
    val content: String,

    /** Detected emotion tag (for UI bubble coloring) */
    val emotion: String = "neutral",

    /** Epoch millis when the message was created */
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * SessionEntity — Represents a chat session for the history drawer.
 *
 * Each session has a title (derived from the first user message),
 * a preview (last message snippet), and timestamps. The drawer
 * displays sessions sorted by lastActivityTimestamp descending.
 */
@Entity(
    tableName = "sessions",
    indices = [
        Index(value = ["lastActivityTimestamp"], name = "idx_session_last_activity")
    ]
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Title shown in drawer — derived from first user message */
    val title: String,

    /** Preview text — last message snippet */
    val preview: String = "",

    /** When this session was created */
    val createdAt: Long = System.currentTimeMillis(),

    /** Updated whenever a message is added to this session */
    val lastActivityTimestamp: Long = System.currentTimeMillis(),

    /** Number of messages in this session */
    val messageCount: Int = 0
)
