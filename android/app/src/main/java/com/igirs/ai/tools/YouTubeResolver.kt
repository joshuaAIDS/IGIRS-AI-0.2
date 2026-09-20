package com.igirs.ai.tools

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object YouTubeResolver {

    private const val TAG = "IGIRS.YouTubeResolver"

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val videoIdPattern = Pattern.compile(""""videoId":"([a-zA-Z0-9_-]{11})"""")
    private val watchUrlPattern = Pattern.compile("""/watch\?v=([a-zA-Z0-9_-]{11})""")

    suspend fun resolveFirstVideoId(query: String): String? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://www.youtube.com/results?search_query=$encoded"

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val html = response.body?.string().orEmpty()

                // Try "videoId":"..."
                val matcher1 = videoIdPattern.matcher(html)
                while (matcher1.find()) {
                    val id = matcher1.group(1)
                    if (id != null && id.length == 11 && !id.startsWith("AA")) {
                        return@withContext id
                    }
                }

                // Fallback to /watch?v=...
                val matcher2 = watchUrlPattern.matcher(html)
                while (matcher2.find()) {
                    val id = matcher2.group(1)
                    if (id != null && id.length == 11 && !id.startsWith("AA")) {
                        return@withContext id
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve YouTube video ID for '$query': ${e.message}")
        }
        null
    }
}
