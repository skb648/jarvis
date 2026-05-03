package com.jarvis.assistant.search

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * WebSearchEngine — Real-time Web Search for JARVIS.
 *
 * Provides live web search capabilities so JARVIS can answer questions
 * about current events, look up facts, and retrieve up-to-date information.
 *
 * Supports multiple search backends (in priority order):
 *   1. Google Custom Search API (if API key + CX provided)
 *   2. DuckDuckGo Instant Answer API (fallback, no API key needed)
 *   3. Google search scraping (secondary fallback)
 *
 * All HTTP calls use OkHttp for consistency with the rest of the codebase,
 * with proper timeouts, connection pooling, and error handling.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * USAGE:
 *
 *   // With Google Custom Search API
 *   val results = WebSearchEngine.search("latest Android 16 features", googleApiKey, googleCx)
 *
 *   // Without API key (DuckDuckGo + Google fallback)
 *   val results = WebSearchEngine.search("weather in Delhi today")
 *
 * Returns formatted results:
 *   "1. [Title](URL)\n   Snippet text...\n\n2. ..."
 * ═══════════════════════════════════════════════════════════════════════
 */
object WebSearchEngine {

    private const val TAG = "WebSearchEngine"
    private const val MAX_RESULTS = 5

    // Google Custom Search API endpoint
    private const val GOOGLE_SEARCH_URL = "https://www.googleapis.com/customsearch/v1"

    // DuckDuckGo Instant Answer API endpoint (no API key needed)
    private const val DUCKDUCKGO_API_URL = "https://api.duckduckgo.com/"

    // Google search URL for scraping fallback
    private const val GOOGLE_SCRAPE_URL = "https://www.google.com/search"

    /**
     * Shared OkHttp client with proper timeouts for search requests.
     * Uses connection pooling and reasonable timeouts to avoid
     * hanging on unresponsive servers.
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
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
     * falls back to DuckDuckGo Instant Answer API, then Google scraping.
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
                    searchFallback(query)
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
     * Fallback search pipeline: DuckDuckGo Instant Answer API first,
     * then Google scraping if DuckDuckGo returns no useful results.
     */
    private fun searchFallback(query: String): List<SearchResult> {
        // 1. Try DuckDuckGo Instant Answer API
        val ddgResults = searchDuckDuckGo(query)
        if (ddgResults.isNotEmpty()) {
            Log.i(TAG, "[searchFallback] DuckDuckGo returned ${ddgResults.size} results")
            return ddgResults
        }

        // 2. Try Google scraping as secondary fallback
        val googleResults = searchGoogleScrape(query)
        if (googleResults.isNotEmpty()) {
            Log.i(TAG, "[searchFallback] Google scrape returned ${googleResults.size} results")
            return googleResults
        }

        Log.w(TAG, "[searchFallback] All fallback methods returned no results for '$query'")
        return emptyList()
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
            val request = Request.Builder()
                .url(urlStr)
                .header("User-Agent", "JARVIS-Assistant/1.0")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()?.take(300) ?: "unknown"
                    Log.e(TAG, "[searchGoogle] HTTP ${response.code}: $errorBody")
                    return emptyList()
                }

                val responseBody = response.body?.string() ?: return emptyList()

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
            }

            Log.i(TAG, "[searchGoogle] Found ${results.size} results for '$query'")
        } catch (e: Exception) {
            Log.e(TAG, "[searchGoogle] Error: ${e.message}")
        }

        return results
    }

    /**
     * Search using DuckDuckGo Instant Answer API.
     *
     * This is the primary fallback (no API key needed). Uses the JSON
     * API endpoint which is far more reliable than HTML scraping.
     *
     * The API returns:
     *   - "Abstract*" fields: instant answer summary
     *   - "RelatedTopics": related topics with text and URLs
     *   - "Results": direct results (usually empty for general queries)
     *
     * @param query The search query
     * @return List of search results from DuckDuckGo
     */
    private fun searchDuckDuckGo(query: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlStr = "$DUCKDUCKGO_API_URL?q=$encodedQuery&format=json&no_html=1"
            val request = Request.Builder()
                .url(urlStr)
                .header("User-Agent", "JARVIS-Assistant/1.0")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "[searchDuckDuckGo] HTTP ${response.code}")
                    return emptyList()
                }

                val responseBody = response.body?.string() ?: return emptyList()
                val root = JSONObject(responseBody)

                // 1. Check for an instant answer (Abstract)
                val abstract = root.optString("Abstract", "").trim()
                val abstractSource = root.optString("AbstractSource", "").trim()
                val abstractUrl = root.optString("AbstractURL", "").trim()
                val abstractTitle = root.optString("Heading", query).trim()

                if (abstract.isNotBlank()) {
                    results.add(SearchResult(
                        title = abstractTitle,
                        snippet = abstract,
                        url = abstractUrl.ifBlank { abstractSource }
                    ))
                }

                // 2. Check for infobox result
                val infoboxContent = root.optString("Infobox", "")
                if (infoboxContent.isNotBlank() && results.isEmpty()) {
                    try {
                        val infobox = JSONObject(infoboxContent)
                        val contentArr = infobox.optJSONArray("content")
                        if (contentArr != null && contentArr.length() > 0) {
                            val snippetBuilder = StringBuilder()
                            for (i in 0 until minOf(contentArr.length(), 5)) {
                                val item = contentArr.getJSONObject(i)
                                val label = item.optString("label", "")
                                val value = item.optString("value", "")
                                if (label.isNotBlank() && value.isNotBlank()) {
                                    snippetBuilder.append("$label: $value\n")
                                }
                            }
                            if (snippetBuilder.isNotBlank()) {
                                results.add(SearchResult(
                                    title = "$abstractTitle — Info",
                                    snippet = snippetBuilder.toString().trim(),
                                    url = abstractUrl.ifBlank { "https://duckduckgo.com/?q=$encodedQuery" }
                                ))
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[searchDuckDuckGo] Failed to parse infobox: ${e.message}")
                    }
                }

                // 3. Parse RelatedTopics for additional results
                val relatedTopics = root.optJSONArray("RelatedTopics") ?: JSONArray()
                for (i in 0 until relatedTopics.length()) {
                    if (results.size >= MAX_RESULTS) break

                    try {
                        val topic = relatedTopics.getJSONObject(i)
                        val text = topic.optString("Text", "").trim()
                        val firstUrl = topic.optString("FirstURL", "").trim()

                        if (text.isNotBlank() && firstUrl.isNotBlank()) {
                            // Extract a title from the first part of the text
                            val title = if (text.contains(" - ")) {
                                text.substringBefore(" - ").trim()
                            } else {
                                text.take(60).trim()
                            }
                            results.add(SearchResult(
                                title = title,
                                snippet = text,
                                url = firstUrl
                            ))
                        }
                    } catch (e: Exception) {
                        // Some entries are sub-categories (have "Name" + "Topics" instead of "Text")
                        // Skip those gracefully
                        Log.d(TAG, "[searchDuckDuckGo] Skipping non-standard topic entry: ${e.message}")
                    }
                }
            }

            Log.i(TAG, "[searchDuckDuckGo] Found ${results.size} results for '$query'")
        } catch (e: Exception) {
            Log.e(TAG, "[searchDuckDuckGo] Error: ${e.message}")
        }

        return results
    }

    /**
     * Search using Google web scraping as a secondary fallback.
     *
     * This is used when the DuckDuckGo Instant Answer API returns no
     * results (which can happen for very specific or time-sensitive queries).
     * Scrapes the first result snippets from Google search.
     *
     * NOTE: This is intentionally a lightweight scraper that extracts
     * just titles and snippets. It may break if Google changes their
     * HTML structure, but it serves as a last-resort fallback.
     */
    private fun searchGoogleScrape(query: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlStr = "$GOOGLE_SCRAPE_URL?q=$encodedQuery&num=$MAX_RESULTS"
            val request = Request.Builder()
                .url(urlStr)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "[searchGoogleScrape] HTTP ${response.code}")
                    return emptyList()
                }

                val html = response.body?.string() ?: return emptyList()

                // Parse Google search results
                // Multiple patterns to handle different Google HTML layouts

                // Pattern 1: <h3> tags for titles (most common in modern Google)
                val h3Pattern = Regex("""<h3[^>]*>(.*?)</h3>""")

                // Pattern 2: Data-URL attribute in result divs
                val dataUrlPattern = Regex("""data-url="([^"]+)"""")

                // Pattern 3: Extract URLs from <a> tags within result blocks
                val resultBlockPattern = Regex(
                    """<div[^>]*class="[^"]*g[^"]*"[^>]*>.*?</div>.*?</div>""",
                    RegexOption.DOT_MATCHES_ALL
                )

                // Simple approach: extract all <h3> titles and match with URLs
                val titles = h3Pattern.findAll(html).map { match ->
                    stripHtml(match.groupValues[1]).trim()
                }.filter { it.isNotBlank() }.toList()

                // Extract URLs from result links
                val urls = dataUrlPattern.findAll(html).map { match ->
                    match.groupValues[1].trim()
                }.filter { it.startsWith("http") }.toList()

                // Try to extract snippets from <span> tags with specific class patterns
                val snippetPattern = Regex("""<span[^>]*>([^<]{30,}?)</span>""")
                val snippets = snippetPattern.findAll(html).map { match ->
                    stripHtml(match.groupValues[1]).trim()
                }.filter { it.isNotBlank() }.take(MAX_RESULTS * 2).toList()

                // Combine titles, URLs, and snippets
                val count = minOf(titles.size, MAX_RESULTS)
                for (i in 0 until count) {
                    val title = titles[i]
                    val url = if (i < urls.size) urls[i] else ""
                    val snippet = if (i < snippets.size) snippets[i] else ""

                    results.add(SearchResult(
                        title = title,
                        snippet = snippet,
                        url = url
                    ))
                }

                // If the structured approach failed, try a broader pattern
                if (results.isEmpty()) {
                    val broadTitlePattern = Regex("""<a[^>]*href="(/url\?q=|https?://)([^"&]+)[^"]*"[^>]*>(.*?)</a>""")
                    for ((index, match) in broadTitlePattern.findAll(html).withIndex()) {
                        if (results.size >= MAX_RESULTS) break
                        val url = match.groupValues[2]
                        val title = stripHtml(match.groupValues[3]).trim()
                        if (title.isNotBlank() && url.startsWith("http")) {
                            results.add(SearchResult(
                                title = title,
                                snippet = "",
                                url = url
                            ))
                        }
                    }
                }
            }

            Log.i(TAG, "[searchGoogleScrape] Found ${results.size} results for '$query'")
        } catch (e: Exception) {
            Log.e(TAG, "[searchGoogleScrape] Error: ${e.message}")
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
