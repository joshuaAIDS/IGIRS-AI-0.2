package com.igirs.ai.tools

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SearchResult(val title: String, val snippet: String, val url: String)
data class WebSearchResponse(val query: String, val results: List<SearchResult>, val instantAnswer: String?)

object WebSearchHelper {
    private const val TAG = "IGIRS.WebSearch"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun shouldSearchWeb(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.length < 3) return false

        // 1. Exclude local device commands unless explicitly asking to search/look up
        val isExplicitSearch = q.contains("search") || q.contains("google") || q.contains("look up") ||
                q.contains("find online") || q.contains("browse")
        if (!isExplicitSearch) {
            val localCommandPrefixes = listOf(
                "turn on", "turn off", "open ", "launch ", "start ", "close ", "kill ", "exit ",
                "flashlight", "torch", "volume", "mute", "unmute", "timer", "alarm",
                "call ", "dial ", "whatsapp", "text ", "message ", "take a photo", "take photo",
                "take screenshot", "battery", "brightness", "bluetooth", "wifi", "hotspot"
            )
            if (localCommandPrefixes.any { q.startsWith(it) || q.contains(it) }) {
                // Allow if asking about price, news, release date of a product
                if (!q.contains("price") && !q.contains("news") && !q.contains("cost") && !q.contains("when")) {
                    return false
                }
            }

            // Exclude conversational chat, memory commands, and voice mode controls
            val conversationalExcludes = listOf(
                "hello", "hi", "hey", "how are you", "good morning", "good night", "good evening",
                "who are you", "what can you do", "tell me a joke", "tell a joke",
                "remember that", "my name is", "my favorite", "my keys", "do you remember",
                "stop listening", "stop voice", "mute mic", "exit voice", "close voice", "goodbye",
                "thank you", "thanks", "ok", "okay", "cool", "nice"
            )
            if (conversationalExcludes.any { q == it || q.startsWith("$it ") }) {
                return false
            }
        }

        // 2. Real-time temporal & current event triggers
        val searchTriggers = listOf(
            "search", "google", "look up", "find online", "search online", "search web", "browse",
            "latest", "today", "current", "recent", "breaking", "news", "weather", "temperature",
            "score", "match", "standing", "points table", "winner", "who won", "did win",
            "price", "stock", "shares", "crypto", "bitcoin", "rate", "cost", "market",
            "release date", "when will", "upcoming", "schedule", "election", "update"
        )
        if (searchTriggers.any { q.contains(it) }) return true

        // 3. Year references (2024–2029+)
        val yearRegex = Regex("""\b202[4-9]\b|\b20[3-9][0-9]\b""")
        if (yearRegex.containsMatchIn(q)) return true

        // 4. Real-world factual question queries (people, places, events, facts)
        val factualQuestionPrefixes = listOf(
            "who is ", "who was ", "who became ", "who created ", "who made ", "who invented ",
            "who is the ", "who was the ", "who won ", "who plays ",
            "what happened ", "what is happening ", "what's happening ", "what is the latest ",
            "what is the status ", "what is the net worth ", "what is the population ",
            "what is the capital ", "what are the specs of ",
            "when is ", "when did ", "where is ", "how much is ", "why did "
        )
        if (factualQuestionPrefixes.any { q.startsWith(it) }) {
            return true
        }

        return false
    }

    suspend fun search(query: String, maxResults: Int = 4): WebSearchResponse = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<SearchResult>()
        var instantAnswer: String? = null

        // 1. DuckDuckGo HTML Live Search
        try {
            val htmlUrl = "https://html.duckduckgo.com/html/?q=$encodedQuery"
            val htmlRequest = Request.Builder()
                .url(htmlUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val htmlResponse = client.newCall(htmlRequest).execute()
            if (htmlResponse.isSuccessful) {
                val html = htmlResponse.body?.string() ?: ""

                // Match all result blocks robustly across DDG HTML layouts
                val blockRegex = Regex("""<div[^>]*class="[^"]*result\b[^"]*"""", RegexOption.IGNORE_CASE)
                val blocks = html.split(blockRegex)

                var count = 0
                for (i in 1 until blocks.size) {
                    if (count >= maxResults) break
                    val block = blocks[i]

                    val titleMatch = Regex("""<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]*)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE).find(block)
                    val snippetMatch = Regex("""<(?:a|td|div)[^>]*class="[^"]*result__snippet[^"]*"[^>]*>([\s\S]*?)</(?:a|td|div)>""", RegexOption.IGNORE_CASE).find(block)

                    if (titleMatch != null) {
                        val rawUrl = titleMatch.groupValues[1]
                        val rawTitle = titleMatch.groupValues[2]

                        var cleanUrl = rawUrl
                        if (cleanUrl.contains("uddg=")) {
                            val uddgPart = cleanUrl.substringAfter("uddg=").substringBefore("&")
                            cleanUrl = try { URLDecoder.decode(uddgPart, "UTF-8") } catch (_: Exception) { uddgPart }
                        } else if (cleanUrl.startsWith("//")) {
                            cleanUrl = "https:$cleanUrl"
                        }

                        val cleanTitle = cleanHtml(rawTitle)
                        val cleanSnippet = if (snippetMatch != null) cleanHtml(snippetMatch.groupValues[1]) else ""

                        if (cleanTitle.isNotBlank() && cleanUrl.isNotBlank()) {
                            results.add(SearchResult(cleanTitle, cleanSnippet, cleanUrl))
                            count++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DuckDuckGo HTML search notice: ${e.message}")
        }

        // 2. Wikipedia Summary REST API (Fast factual encyclopedia fallback for entities/topics)
        if (results.isEmpty()) {
            try {
                val cleanTopic = query
                    .replace(Regex("""^(who is|who was|what is|what was|tell me about|search for|look up)\s+""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""^(the|a|an)\s+""", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (cleanTopic.length >= 2) {
                    val encodedTopic = URLEncoder.encode(cleanTopic.replace(" ", "_"), "UTF-8")
                    val wikiUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/$encodedTopic"
                    val wikiRequest = Request.Builder()
                        .url(wikiUrl)
                        .header("User-Agent", "IGIRS-AI/2.0 (Mobile Assistant)")
                        .build()
                    val wikiResponse = client.newCall(wikiRequest).execute()
                    if (wikiResponse.isSuccessful) {
                        val wikiJson = wikiResponse.body?.string()
                        if (!wikiJson.isNullOrBlank()) {
                            val obj = JSONObject(wikiJson)
                            val title = obj.optString("title", "")
                            val extract = obj.optString("extract", "")
                            val pageUrl = obj.optJSONObject("content_urls")
                                ?.optJSONObject("desktop")
                                ?.optString("page", "https://en.wikipedia.org/wiki/$encodedTopic")
                                ?: "https://en.wikipedia.org/wiki/$encodedTopic"
                            if (extract.isNotBlank()) {
                                results.add(SearchResult(title, extract, pageUrl))
                                instantAnswer = extract
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Wikipedia summary fallback notice: ${e.message}")
            }
        }

        // 3. DuckDuckGo Instant Answer API (Additional factual supplement)
        if (instantAnswer.isNullOrBlank()) {
            try {
                val apiUrl = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1"
                val apiRequest = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", USER_AGENT)
                    .build()
                val apiResponse = client.newCall(apiRequest).execute()
                if (apiResponse.isSuccessful) {
                    val jsonStr = apiResponse.body?.string()
                    if (jsonStr != null) {
                        val jsonObj = JSONObject(jsonStr)
                        val abstractText = jsonObj.optString("AbstractText", "")
                        if (abstractText.isNotBlank()) {
                            instantAnswer = abstractText
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DuckDuckGo Instant Answer API notice: ${e.message}")
            }
        }

        WebSearchResponse(query, results, instantAnswer)
    }

    private fun cleanHtml(html: String): String {
        return html
            .replace(Regex("""<[^>]*>"""), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun formatSearchContextForLLM(response: WebSearchResponse): String {
        val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
        val todayStr = dateFormat.format(Date())
        val sb = StringBuilder()
        sb.append("[REAL-TIME LIVE WEB DATA — Verified as of $todayStr]\n")
        sb.append("User Query: \"${response.query}\"\n\n")

        if (!response.instantAnswer.isNullOrBlank()) {
            sb.append("Direct Answer / Summary:\n${response.instantAnswer}\n\n")
        }

        if (response.results.isNotEmpty()) {
            sb.append("Live Web Search Results:\n")
            response.results.forEachIndexed { index, result ->
                sb.append("${index + 1}. ${result.title}\n")
                if (result.snippet.isNotBlank()) {
                    sb.append("   ${result.snippet}\n")
                }
                sb.append("   Source: ${result.url}\n\n")
            }
        }

        sb.append("INSTRUCTIONS FOR IGIRS AI:\n")
        sb.append("- Today is $todayStr. The live web results above are current and verified.\n")
        sb.append("- Prioritize these real-time web results over any older static knowledge.\n")
        sb.append("- Answer the user's question directly, accurately, and factually citing sources when appropriate.\n")
        sb.append("- If in Voice Mode, keep your answer conversational, punchy, and natural (1 to 3 spoken sentences).")
        return sb.toString()
    }
}
