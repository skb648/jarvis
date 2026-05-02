package com.jarvis.assistant.search

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * WebSearchEngine — Real-time Web Search for JARVIS.
 *
 * Provides live web search capabilities so JARVIS can answer questions
 * about current events, look up facts, and retrieve up-to-date information.
 *
 * Supports multiple search backends:
 *   1. Google Custom Search API (if API key + CX provided)
 *   2. DuckDuckGo HTML scraping (fallback, no API key needed)
 *
 * ═══════════════════════════════════════════════════════════════════════
 * USAGE:
 *
 *   val engine = WebSearchEngine()
 *
 *   // With Google Custom Search API
 *   val results = engine.search("latest Android 16 features", googleApiKey, googleCx)
 *
 *   // Without API key (DuckDuckGo fallback)
 *   val results = engine.search("weather in Delhi today")
 *
 * Returns formatted results:
 *   "1. [Title](URL)\n   Snippet text...\n\n2. ..."
 * ═══════════════════════════════════════════════════════════════════════
 */
class WebSearchEngine {

    companion object {
        private const val TAG = "WebSearchEngine"
        private const val MAX_RESULTS = 5
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 15_000

        // Google Custom Search API endpoint
        private const val GOOGLE_SEARCH_URL = "https://www.googleapis.com/customsearch/v1"

        // DuckDuckGo HTML search endpoint (no API key needed)
        private const val DUCKDUCKGO_URL = "https://html.duckduckgo.com/html/"
    }

    /**
     * Data class representing a single search result.
     */
    data class SearchResult(
        val title: String,
        val snippet: String,
        val url: String
    )

    /**
     * Perform a web search.
     *
     * Uses Google Custom Search API if apiKey is provided, otherwise
     * falls back to DuckDuckGo HTML scraping.
     *
     * @param query The search query
     * @param apiKey Google Custom Search API key (optional)
     * @param cx Google Custom Search engine ID (optional)
     * @return Formatted search results string
     */
    suspend fun search(
        query: String,
        apiKey: String = "",
        cx: String = ""
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                val results = if (apiKey.isNotBlank() && cx.isNotBlank()) {
                    searchGoogle(query, apiKey, cx)
                } else if (apiKey.isNotBlank()) {
                    // Try Google with just API key (default search engine)
                    searchGoogle(query, apiKey, cx.ifBlank { "017576662512468239146:omuauf_lfve" })
                } else {
                    searchDuckDuckGo(query)
                }

                if (results.isEmpty()) {
                    "No search results found for '$query'."
                } else {
                    formatResults(query, results)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[search] Error: ${e.message}")
                "Search failed: ${e.message?.take(100)}. Please check your internet connection."
            }
        }
    }

    /**
     * Search using Google Custom Search API.
     * Requires an API key and Custom Search Engine ID (cx).
     */
    private fun searchGoogle(
        query: String,
        apiKey: String,
        cx: String
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlStr = "$GOOGLE_SEARCH_URL?key=$apiKey&cx=$cx&q=$encodedQuery&num=$MAX_RESULTS"
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.setRequestProperty("User-Agent", "JARVIS-Assistant/1.0")

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "unknown"
                Log.e(TAG, "[searchGoogle] HTTP $responseCode: ${errorBody.take(300)}")
                connection.disconnect()
                return emptyList()
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            // Parse JSON response
            val root = JSONObject(responseBody)
            val items = root.optJSONArray("items") ?: return emptyList()

            for (i in 0 until minOf(items.length(), MAX_RESULTS)) {
                val item = items.getJSONObject(i)
                results.add(SearchResult(
                    title = item.optString("title", ""),
                    snippet = item.optString("snippet", ""),
                    url = item.optString("link", "")
                ))
            }

            Log.i(TAG, "[searchGoogle] Found ${results.size} results for '$query'")
        } catch (e: Exception) {
            Log.e(TAG, "[searchGoogle] Error: ${e.message}")
        }

        return results
    }

    /**
     * Search using DuckDuckGo HTML scraping.
     * No API key required — this is the fallback.
     */
    private fun searchDuckDuckGo(query: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = URL(DUCKDUCKGO_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val postData = "q=$encodedQuery&b=&kl=wt-wt"
            connection.outputStream.use { it.write(postData.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.e(TAG, "[searchDuckDuckGo] HTTP $responseCode")
                connection.disconnect()
                return emptyList()
            }

            val html = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            // Parse DuckDuckGo HTML results
            // Pattern: <a rel="nofollow" class="result__a" href="URL">TITLE</a>
            val titlePattern = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""")
            val snippetPattern = Regex("""<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""")

            val titleMatches = titlePattern.findAll(html).toList()
            val snippetMatches = snippetPattern.findAll(html).toList()

            for (i in titleMatches.indices) {
                if (results.size >= MAX_RESULTS) break

                val titleHtml = titleMatches[i].groupValues[2]
                val urlStr = titleMatches[i].groupValues[1]
                val snippetHtml = if (i < snippetMatches.size) snippetMatches[i].groupValues[1] else ""

                // Strip HTML tags
                val title = stripHtml(titleHtml).trim()
                val snippet = stripHtml(snippetHtml).trim()

                if (title.isNotBlank()) {
                    results.add(SearchResult(
                        title = title,
                        snippet = snippet,
                        url = urlStr
                    ))
                }
            }

            Log.i(TAG, "[searchDuckDuckGo] Found ${results.size} results for '$query'")
        } catch (e: Exception) {
            Log.e(TAG, "[searchDuckDuckGo] Error: ${e.message}")
        }

        return results
    }

    /**
     * Strip HTML tags from a string.
     */
    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()
    }

    /**
     * Format search results into a readable string.
     */
    private fun formatResults(query: String, results: List<SearchResult>): String {
        val sb = StringBuilder()
        sb.appendLine("Web search results for \"$query\":")
        sb.appendLine()

        for ((index, result) in results.withIndex()) {
            sb.appendLine("${index + 1}. ${result.title}")
            if (result.snippet.isNotBlank()) {
                sb.appendLine("   ${result.snippet}")
            }
            if (result.url.isNotBlank()) {
                sb.appendLine("   Source: ${result.url}")
            }
            sb.appendLine()
        }

        return sb.toString().trimEnd()
    }
}
