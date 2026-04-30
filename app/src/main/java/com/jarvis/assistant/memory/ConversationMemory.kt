package com.jarvis.assistant.memory

import android.content.Context
import android.util.Log
import com.jarvis.assistant.data.local.JarvisDatabase
import com.jarvis.assistant.data.local.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * ConversationMemory — JARVIS's Long-Term Memory System.
 *
 * "Sir, apne kal bola tha..." — This module enables JARVIS to recall
 * past conversations with contextual relevance, powered by:
 *
 *   1. KEYWORD SEARCH: Searches past messages by keyword matching
 *      against both message content and memory tags.
 *
 *   2. TIME-BASED RECALL: Understands relative time expressions
 *      like "yesterday", "kal", "last week", "pichle hafte".
 *
 *   3. SEMANTIC TAGGING: Extracts important keywords from conversations
 *      and stores them as MemoryTag entries for fast retrieval.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * USAGE:
 *
 *   // Get relevant memory context for a query
 *   val memoryContext = ConversationMemory.getRelevantMemory(query, context)
 *   // Returns: "Sir, apne kal bola tha [what they said] aur maine kaha tha [what JARVIS replied]"
 *
 *   // Store memory tags after a conversation turn
 *   ConversationMemory.extractAndStoreTags(userMessage, modelResponse, messageId, context)
 * ═══════════════════════════════════════════════════════════════════════
 */
object ConversationMemory {

    private const val TAG = "ConversationMemory"
    private const val MAX_MEMORY_RESULTS = 3

    // ─── Time Expression Patterns ──────────────────────────────────────

    /** Maps time expressions (English + Hinglish) to time range calculators */
    private val timeExpressions = mapOf(
        // English
        "today" to { Pair(startOfDay(), System.currentTimeMillis()) },
        "yesterday" to { Pair(startOfDay() - DAY_MS, startOfDay()) },
        "last week" to { Pair(startOfDay() - 7 * DAY_MS, startOfDay()) },
        "last month" to { Pair(startOfDay() - 30 * DAY_MS, startOfDay()) },
        "this week" to { Pair(startOfDay() - 7 * DAY_MS, System.currentTimeMillis()) },
        "this month" to { Pair(startOfDay() - 30 * DAY_MS, System.currentTimeMillis()) },

        // Hinglish / Hindi
        "aaj" to { Pair(startOfDay(), System.currentTimeMillis()) },
        "kal" to { Pair(startOfDay() - DAY_MS, startOfDay()) },
        "parso" to { Pair(startOfDay() - 2 * DAY_MS, startOfDay() - DAY_MS) },
        "pichle hafte" to { Pair(startOfDay() - 7 * DAY_MS, startOfDay()) },
        "pichle mahine" to { Pair(startOfDay() - 30 * DAY_MS, startOfDay()) },
        "pichle week" to { Pair(startOfDay() - 7 * DAY_MS, startOfDay()) }
    )

    private const val DAY_MS = 86_400_000L

    // ─── Memory Tag Categories ─────────────────────────────────────────

    /** Patterns that indicate important information worth tagging */
    private val tagPatterns = mapOf(
        "preference" to listOf(
            Regex("""(?i)(?:i like|i love|i prefer|i enjoy|meri pasand|mujhe pasand)"""),
            Regex("""(?i)(?:i don'?t like|i hate|i dislike|mujhe nahi pasand)""")
        ),
        "schedule" to listOf(
            Regex("""(?i)(?:meeting at|appointment|schedule|kal at|tomorrow at|meri meeting)"""),
            Regex("""(?i)(?:\d{1,2}(?::\d{2})?\s*(?:am|pm))""")  // time mentions
        ),
        "person_name" to listOf(
            Regex("""(?i)(?:my (?:friend|colleague|boss|mom|dad|brother|sister|wife|husband))\s+(\w+)"""),
            Regex("""(?i)(?:called|named|naam)\s+(\w+)""")
        ),
        "important" to listOf(
            Regex("""(?i)(?:remember this|important|don'?t forget|yaad rakhna|zaruri)"""),
            Regex("""(?i)(?:birthday|anniversary|deadline|exam)""")
        ),
        "fact" to listOf(
            Regex("""(?i)(?:my name is|i am|i live in|i work at|meri umar|mera naam)"""),
            Regex("""(?i)(?:my (?:phone|email|address|number)\s*(?:is)?:?\s*\S+)""")
        ),
        "task" to listOf(
            Regex("""(?i)(?:remind me to|i need to|i have to|i should|mujhe yaad dilana)"""),
            Regex("""(?i)(?:todo|task|pending|baki hai)""")
        )
    )

    // ─── Public API ────────────────────────────────────────────────────

    /**
     * Get relevant memory context for a user query.
     *
     * Searches past conversations by keyword and time references,
     * then returns a formatted memory context string that can be
     * prepended to the system prompt for contextual responses.
     *
     * Returns formatted string like:
     * "Sir, apne kal bola tha [what they said] aur maine kaha tha [what JARVIS replied]"
     *
     * @param query The user's current query
     * @param context Android context for DB access
     * @return Formatted memory context, or empty string if no relevant memories
     */
    suspend fun getRelevantMemory(query: String, context: Context): String {
        return withContext(Dispatchers.IO) {
            try {
                val db = JarvisDatabase.getInstance(context)
                val messageDao = db.messageDao()
                val memoryDao = db.memoryDao()

                val memories = mutableListOf<MemoryEntry>()
                val normalizedQuery = query.lowercase(Locale.getDefault()).trim()

                // 1. Check for time-based references in the query
                val timeRange = extractTimeRange(normalizedQuery)
                if (timeRange != null) {
                    val timeTags = memoryDao.searchByTimeRange(
                        timeRange.first, timeRange.second, MAX_MEMORY_RESULTS
                    )
                    for (tag in timeTags) {
                        val message = messageDao.getMessageById(tag.messageId)
                        if (message != null) {
                            val reply = findReplyToMessage(messageDao, message)
                            memories.add(MemoryEntry(message, reply, tag.tag))
                        }
                    }
                }

                // 2. Keyword-based search from memory tags
                val keywords = extractKeywords(normalizedQuery)
                for (keyword in keywords) {
                    if (memories.size >= MAX_MEMORY_RESULTS) break
                    val tags = memoryDao.searchByKeyword(keyword, MAX_MEMORY_RESULTS - memories.size)
                    for (tag in tags) {
                        if (memories.none { it.userMessage.id == tag.messageId }) {
                            val message = messageDao.getMessageById(tag.messageId)
                            if (message != null) {
                                val reply = findReplyToMessage(messageDao, message)
                                memories.add(MemoryEntry(message, reply, tag.tag))
                            }
                        }
                    }
                }

                // 3. Direct keyword search in message content (fallback)
                if (memories.size < MAX_MEMORY_RESULTS) {
                    val contentMatches = messageDao.searchMessagesByContent(
                        keywords.joinToString(" "), MAX_MEMORY_RESULTS - memories.size
                    )
                    for (msg in contentMatches) {
                        if (msg.role == "user" && memories.none { it.userMessage.id == msg.id }) {
                            val reply = findReplyToMessage(messageDao, msg)
                            memories.add(MemoryEntry(msg, reply, "recall"))
                        }
                    }
                }

                // 4. Format the memory context
                if (memories.isEmpty()) {
                    ""
                } else {
                    formatMemoryContext(memories)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[getRelevantMemory] Error: ${e.message}")
                ""
            }
        }
    }

    /**
     * Extract and store memory tags from a conversation turn.
     * Called after each user-model exchange to build the memory index.
     *
     * @param userMessage The user's message text
     * @param modelResponse JARVIS's response text
     * @param messageId The Room DB ID of the user message
     * @param context Android context for DB access
     */
    suspend fun extractAndStoreTags(
        userMessage: String,
        modelResponse: String,
        messageId: Long,
        context: Context
    ) {
        withContext(Dispatchers.IO) {
            try {
                val db = JarvisDatabase.getInstance(context)
                val memoryDao = db.memoryDao()
                val tags = mutableListOf<MemoryTag>()

                for ((tagType, patterns) in tagPatterns) {
                    for (pattern in patterns) {
                        val matches = pattern.findAll(userMessage)
                        for (match in matches) {
                            val keyword = if (match.groupValues.size > 1 && match.groupValues[1].isNotBlank()) {
                                match.groupValues[1].lowercase(Locale.getDefault())
                            } else {
                                // Use the matched phrase as keyword
                                extractKeyPhrase(userMessage, match.range)
                            }

                            if (keyword.isNotBlank() && keyword.length > 1) {
                                tags.add(MemoryTag(
                                    messageId = messageId,
                                    tag = tagType,
                                    keyword = keyword
                                ))
                            }
                        }
                    }
                }

                // Also extract noun phrases as general "fact" tags
                extractNounPhrases(userMessage).forEach { phrase ->
                    if (tags.none { it.keyword.equals(phrase, ignoreCase = true) }) {
                        tags.add(MemoryTag(
                            messageId = messageId,
                            tag = "fact",
                            keyword = phrase
                        ))
                    }
                }

                if (tags.isNotEmpty()) {
                    memoryDao.insertMemoryTags(tags)
                    Log.d(TAG, "[extractAndStoreTags] Stored ${tags.size} memory tags for message $messageId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[extractAndStoreTags] Error: ${e.message}")
            }
        }
    }

    // ─── Internal Helpers ──────────────────────────────────────────────

    /**
     * Extract time range from query using pattern matching.
     */
    private fun extractTimeRange(query: String): Pair<Long, Long>? {
        for ((expression, calculator) in timeExpressions) {
            if (query.contains(expression)) {
                return calculator()
            }
        }
        return null
    }

    /**
     * Extract meaningful keywords from a query.
     * Removes stop words and short words.
     */
    private fun extractKeywords(query: String): List<String> {
        val stopWords = setOf(
            "what", "did", "i", "say", "tell", "me", "about", "the", "a", "an",
            "is", "was", "were", "are", "am", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would",
            "can", "could", "should", "shall", "may", "might", "must",
            "and", "or", "but", "if", "then", "else", "when", "how",
            "who", "whom", "whose", "which", "that", "this", "these", "those",
            "my", "your", "his", "her", "its", "our", "their",
            "in", "on", "at", "to", "for", "with", "by", "from", "of",
            "kya", "maine", "kaha", "tha", "thi", "bola", "mujhe",
            "kya", "hai", "ke", "ki", "ka", "ko", "se", "me", "par"
        )

        return query.split(Regex("\\s+"))
            .filter { it.length > 2 && it !in stopWords }
            .distinct()
            .take(5)
    }

    /**
     * Extract a key phrase around a match range.
     */
    private fun extractKeyPhrase(text: String, range: IntRange): String {
        val start = maxOf(0, range.first - 10)
        val end = minOf(text.length, range.last + 20)
        return text.substring(start, end)
            .trim()
            .lowercase(Locale.getDefault())
            .take(50)
    }

    /**
     * Extract simple noun phrases from text.
     * Uses basic heuristics: words after "my", "the", capitalized words, etc.
     */
    private fun extractNounPhrases(text: String): List<String> {
        val phrases = mutableListOf<String>()

        // Pattern: "my X" → extract X
        Regex("""(?i)(?:my|mera|meri)\s+(\w+)""").findAll(text).forEach { match ->
            if (match.groupValues[1].length > 2) {
                phrases.add(match.groupValues[1].lowercase(Locale.getDefault()))
            }
        }

        // Pattern: "X is/was Y" → extract X and Y
        Regex("""(?i)(\w+)\s+(?:is|was|hai|tha|thi)\s+(\w+)""").findAll(text).forEach { match ->
            if (match.groupValues[1].length > 2) phrases.add(match.groupValues[1].lowercase(Locale.getDefault()))
            if (match.groupValues[2].length > 2) phrases.add(match.groupValues[2].lowercase(Locale.getDefault()))
        }

        return phrases.distinct().take(3)
    }

    /**
     * Find JARVIS's reply to a given user message.
     * Looks for the next model message in the same session, after the user message.
     */
    private suspend fun findReplyToMessage(
        messageDao: com.jarvis.assistant.data.local.MessageDao,
        userMessage: MessageEntity
    ): MessageEntity? {
        return try {
            messageDao.getReplyAfterMessage(
                sessionId = userMessage.sessionId,
                afterTimestamp = userMessage.timestamp
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Format memory entries into a context string.
     *
     * Output format:
     * "MEMORY CONTEXT:
     * 1. Sir, apne [time] bola tha: "[what they said]" — aur maine kaha tha: "[what JARVIS replied]"
     * 2. ..."
     */
    private fun formatMemoryContext(memories: List<MemoryEntry>): String {
        val sb = StringBuilder()
        sb.appendLine("MEMORY CONTEXT (relevant past conversations):")

        for ((index, memory) in memories.take(MAX_MEMORY_RESULTS).withIndex()) {
            val timeAgo = formatTimeAgo(memory.userMessage.timestamp)
            val userText = memory.userMessage.content.take(150)
            val replyText = memory.modelReply?.content?.take(150) ?: "..."
            val tagLabel = memory.tag

            sb.appendLine("${index + 1}. [${tagLabel}] Sir, apne ${timeAgo} bola tha: \"$userText\" — aur maine kaha tha: \"$replyText\"")
        }

        return sb.toString()
    }

    /**
     * Format a timestamp as a relative time string (Hinglish).
     */
    private fun formatTimeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < HOUR_MS -> "abhi"  // just now
            diff < DAY_MS -> "${diff / HOUR_MS} ghante pehle"  // X hours ago
            diff < 2 * DAY_MS -> "kal"  // yesterday
            diff < 7 * DAY_MS -> "${diff / DAY_MS} din pehle"  // X days ago
            diff < 30 * DAY_MS -> "${diff / DAY_MS} din pehle"  // X days ago
            diff < 365 * DAY_MS -> "${diff / (30 * DAY_MS)} mahine pehle"  // X months ago
            else -> "bahut pehle"  // long ago
        }
    }

    private const val HOUR_MS = 3_600_000L

    private fun startOfDay(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    // ─── Data Classes ──────────────────────────────────────────────────

    /**
     * Represents a single memory entry: user message + JARVIS reply + tag.
     */
    private data class MemoryEntry(
        val userMessage: MessageEntity,
        val modelReply: MessageEntity?,
        val tag: String
    )
}
