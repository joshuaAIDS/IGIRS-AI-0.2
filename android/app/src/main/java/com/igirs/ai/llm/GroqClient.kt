package com.igirs.ai.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class ChatMessage(
    val role: String,
    val content: String,
    val imageDataUri: String? = null
)

/**
 * Dedicated Groq LPU Cloud Client for IGIRS AI Mobile.
 *
 * Features:
 * - 100% Groq Powered (Zero NVIDIA dependency).
 * - Real-time SSE word-by-word streaming at 300+ tokens/sec.
 * - Multi-Key Auto-Rotation Pool using dedicated mobile keys.
 * - Automatic retry across keys on rate limits (HTTP 429) and auth errors.
 * - Multimodal vision support via qwen/qwen3.8-27b.
 * - Model selector support (Fast, Deep Reasoning, Instant).
 * - Automatic <think> tag cleansing for reasoning models.
 * - Zero UI key exposure for complete user safety.
 */
class GroqClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    // Dedicated Groq API keys strictly for the mobile APK (100% Groq Powered)
    // Replace with your Groq API keys from https://console.groq.com/keys
    private val apiKeys = listOf(
        "YOUR_GROQ_API_KEY_1",
        "YOUR_GROQ_API_KEY_2",
        "YOUR_GROQ_API_KEY_3",
        "YOUR_GROQ_API_KEY_4",
        "YOUR_GROQ_API_KEY_5"
    )

    private val currentKeyIndex = AtomicInteger(0)

    val defaultPrimaryModel = "openai/gpt-oss-20b"
    val visionModel = "qwen/qwen3.8-27b"
    val reasoningModel = "openai/gpt-oss-120b"
    val instantModel = "groq/compound"

    private val fallbackModels = listOf(
        "groq/compound",
        "openai/gpt-oss-20b",
        "openai/gpt-oss-120b",
        "qwen/qwen3.8-27b"
    )

    private fun rotateKey(): Int {
        val next = currentKeyIndex.incrementAndGet()
        val index = Math.floorMod(next, apiKeys.size)
        Log.w(TAG, "⚡ [Groq LPU Auto-Switch] Switched to Key #${index + 1} (Pool: ${apiKeys.size} keys)")
        return index
    }

    private fun getActiveKey(): String {
        val index = Math.floorMod(currentKeyIndex.get(), apiKeys.size)
        return apiKeys[index]
    }

    fun cleanContent(raw: String): String {
        if (raw.isEmpty()) return ""
        var text = raw
        if (text.contains("<think>") && text.contains("</think>")) {
            text = text.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
        } else if (text.contains("<think>")) {
            text = text.substringBefore("<think>").trim()
        }
        return text
    }

    suspend fun chatCompletion(
        messages: List<ChatMessage>,
        preferredModel: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKeys.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No Groq API keys available for mobile companion."))
        }

        val hasImage = messages.any { !it.imageDataUri.isNullOrEmpty() }
        val targetPrimary = if (!preferredModel.isNullOrBlank()) preferredModel else defaultPrimaryModel
        val modelsToTry = if (hasImage) {
            listOf(visionModel) // qwen/qwen3.8-27b handles multimodal vision natively on Groq
        } else {
            val list = mutableListOf(targetPrimary)
            fallbackModels.forEach { if (it != targetPrimary) list.add(it) }
            list
        }

        var lastException: Exception? = null

        for (model in modelsToTry) {
            val poolSize = apiKeys.size
            for (keyAttempt in 0 until poolSize) {
                val activeKey = getActiveKey()
                val keyNum = Math.floorMod(currentKeyIndex.get(), poolSize) + 1

                try {
                    val jsonPayload = JSONObject().apply {
                        put("model", model)
                        put("temperature", 0.7)
                        put("max_tokens", 4096)
                        put("stream", false)

                        val msgArray = JSONArray()
                        val systemPrompt = Prompts.buildSystemPrompt()
                        msgArray.put(JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        })

                        for (m in messages) {
                            if (!m.imageDataUri.isNullOrEmpty()) {
                                val contentArray = JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", m.content)
                                    })
                                    put(JSONObject().apply {
                                        put("type", "image_url")
                                        put("image_url", JSONObject().apply {
                                            put("url", m.imageDataUri)
                                        })
                                    })
                                }
                                msgArray.put(JSONObject().apply {
                                    put("role", m.role)
                                    put("content", contentArray)
                                })
                            } else {
                                msgArray.put(JSONObject().apply {
                                    put("role", m.role)
                                    put("content", m.content)
                                })
                            }
                        }
                        put("messages", msgArray)
                    }

                    val cleanKey = activeKey.trim().filter { it.code in 32..126 }
                    val body = jsonPayload.toString().toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url(ENDPOINT)
                        .addHeader("Authorization", "Bearer $cleanKey")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept", "application/json")
                        .addHeader("User-Agent", USER_AGENT)
                        .post(body)
                        .build()

                    val (statusCode, responseBody) = client.newCall(request).execute().use { response ->
                        Pair(response.code, response.body?.string().orEmpty())
                    }

                    if (statusCode == 429 || statusCode == 401 || statusCode == 403) {
                        Log.w(TAG, "Groq Key #$keyNum returned HTTP $statusCode. Auto-rotating key...")
                        rotateKey()
                        lastException = Exception("Groq LPU HTTP $statusCode: $responseBody")
                        continue
                    }

                    if (statusCode !in 200..299) {
                        Log.w(TAG, "Groq model $model returned HTTP $statusCode: $responseBody")
                        lastException = Exception("Groq LPU HTTP $statusCode: $responseBody")
                        if (statusCode == 400 || statusCode == 404) {
                            break
                        }
                        rotateKey()
                        continue
                    }

                    val json = JSONObject(responseBody)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val firstChoice = choices.getJSONObject(0)
                        val messageObj = firstChoice.optJSONObject("message")
                        val content = messageObj?.optString("content")?.trim()
                        if (!content.isNullOrEmpty()) {
                            val cleaned = cleanContent(content)
                            return@withContext Result.success(cleaned)
                        }
                    }
                    lastException = Exception("Empty completion response from Groq LPU")
                } catch (e: Exception) {
                    Log.e(TAG, "Error invoking Groq model $model on Key #$keyNum: ${e.message}", e)
                    lastException = e
                    rotateKey()
                }
            }
        }

        Result.failure(lastException ?: Exception("All Groq LPU keys and models exhausted."))
    }

    /**
     * Real-time Server-Sent Events (SSE) streaming completion.
     * Emits token chunks via [onChunk] in real time at 300+ words/sec.
     */
    suspend fun streamChatCompletion(
        messages: List<ChatMessage>,
        preferredModel: String? = null,
        onChunk: suspend (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKeys.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No Groq API keys available for mobile companion."))
        }

        val hasImage = messages.any { !it.imageDataUri.isNullOrEmpty() }
        val targetPrimary = if (!preferredModel.isNullOrBlank()) preferredModel else defaultPrimaryModel
        val modelsToTry = if (hasImage) {
            listOf(visionModel) // qwen/qwen3.8-27b handles multimodal vision natively on Groq
        } else {
            val list = mutableListOf(targetPrimary)
            fallbackModels.forEach { if (it != targetPrimary) list.add(it) }
            list
        }

        var lastException: Exception? = null

        for (model in modelsToTry) {
            val poolSize = apiKeys.size
            for (keyAttempt in 0 until poolSize) {
                val activeKey = getActiveKey()
                val keyNum = Math.floorMod(currentKeyIndex.get(), poolSize) + 1

                try {
                    val jsonPayload = JSONObject().apply {
                        put("model", model)
                        put("temperature", 0.7)
                        put("max_tokens", 4096)
                        put("stream", true)

                        val msgArray = JSONArray()
                        val systemPrompt = Prompts.buildSystemPrompt()
                        msgArray.put(JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        })

                        for (m in messages) {
                            if (!m.imageDataUri.isNullOrEmpty()) {
                                val contentArray = JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", m.content)
                                    })
                                    put(JSONObject().apply {
                                        put("type", "image_url")
                                        put("image_url", JSONObject().apply {
                                            put("url", m.imageDataUri)
                                        })
                                    })
                                }
                                msgArray.put(JSONObject().apply {
                                    put("role", m.role)
                                    put("content", contentArray)
                                })
                            } else {
                                msgArray.put(JSONObject().apply {
                                    put("role", m.role)
                                    put("content", m.content)
                                })
                            }
                        }
                        put("messages", msgArray)
                    }

                    val cleanKey = activeKey.trim().filter { it.code in 32..126 }
                    val body = jsonPayload.toString().toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url(ENDPOINT)
                        .addHeader("Authorization", "Bearer $cleanKey")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept", "text/event-stream")
                        .addHeader("User-Agent", USER_AGENT)
                        .post(body)
                        .build()

                    val response = client.newCall(request).execute()
                    val statusCode = response.code

                    if (statusCode == 429 || statusCode == 401 || statusCode == 403) {
                        response.close()
                        Log.w(TAG, "Groq Key #$keyNum returned HTTP $statusCode on stream. Auto-rotating key...")
                        rotateKey()
                        lastException = Exception("Groq LPU HTTP $statusCode")
                        continue
                    }

                    if (statusCode !in 200..299) {
                        val errBody = response.body?.string().orEmpty()
                        response.close()
                        Log.w(TAG, "Groq model $model returned HTTP $statusCode: $errBody")
                        lastException = Exception("Groq LPU HTTP $statusCode: $errBody")
                        if (statusCode == 400 || statusCode == 404) {
                            break
                        }
                        rotateKey()
                        continue
                    }

                    val fullContent = StringBuilder()
                    var insideThink = false

                    response.body?.use { resBody ->
                        val source = resBody.source()
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            val trimmed = line.trim()
                            if (!trimmed.startsWith("data: ")) continue
                            val data = trimmed.removePrefix("data: ").trim()
                            if (data == "[DONE]") break

                            try {
                                val chunkObj = JSONObject(data)
                                val delta = chunkObj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                                val contentPart = delta?.optString("content").orEmpty()
                                if (contentPart.isNotEmpty()) {
                                    if (contentPart.contains("<think>")) {
                                        insideThink = true
                                    }
                                    if (contentPart.contains("</think>")) {
                                        insideThink = false
                                        continue
                                    }
                                    if (!insideThink && !contentPart.contains("<think>")) {
                                        fullContent.append(contentPart)
                                        onChunk(contentPart)
                                    }
                                }
                            } catch (_: Exception) {
                                // Skip malformed SSE chunk
                            }
                        }
                    }

                    val finalResult = cleanContent(fullContent.toString())
                    if (finalResult.isNotBlank()) {
                        return@withContext Result.success(finalResult)
                    }
                    lastException = Exception("Empty streaming response from Groq LPU")
                } catch (e: Exception) {
                    Log.e(TAG, "Streaming error on Groq model $model Key #$keyNum: ${e.message}", e)
                    lastException = e
                    rotateKey()
                }
            }
        }

        Result.failure(lastException ?: Exception("All Groq LPU streaming attempts failed."))
    }

    companion object {
        private const val TAG = "IGIRS.GroqClient"
        private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        private const val USER_AGENT = "IGIRS-AI/2.0 (Linux; Android)"
    }
}
