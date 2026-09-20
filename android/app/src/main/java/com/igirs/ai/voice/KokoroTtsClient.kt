package com.igirs.ai.voice

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object KokoroTtsClient {
    private const val TAG = "IGIRS.KokoroTTS"
    private const val PREFS_NAME = "kokoro_tts_prefs"
    private const val KEY_ENDPOINT = "kokoro_endpoint_url"
    private const val KEY_VOICE = "kokoro_voice_id"
    
    private const val DEFAULT_ENDPOINT = "https://api-inference.huggingface.co/models/hexgrad/Kokoro-82M"
    private const val DEFAULT_VOICE = "am_adam"

    private val availableVoices = listOf(
        "am_adam" to "Adam (American Male)",
        "am_michael" to "Michael (American Male)",
        "af_heart" to "Heart (American Female)",
        "af_bella" to "Bella (American Female)",
        "af_sarah" to "Sarah (American Female)",
        "bf_emma" to "Emma (British Female)",
        "bm_george" to "George (British Male)",
        "bm_lewis" to "Lewis (British Male)"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private var activeMediaPlayer: MediaPlayer? = null
    private var currentPlayJob: Job? = null

    // Circuit breaker: avoid repeated stalls if endpoint host is down
    private var isEndpointAvailable = true
    private var lastFailureTime = 0L

    fun getAvailableVoices(): List<Pair<String, String>> {
        return availableVoices
    }

    fun setEndpoint(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENDPOINT, url)
            .apply()
        isEndpointAvailable = true
    }

    fun setVoice(context: Context, voiceId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VOICE, voiceId)
            .apply()
    }

    private fun getEndpoint(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
    }

    private fun getVoice(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
    }

    fun stop() {
        try {
            activeMediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            activeMediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaPlayer: ${e.message}", e)
        }
        currentPlayJob?.cancel()
    }

    fun speak(
        context: Context,
        text: String,
        onStart: (() -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
        onFallbackNeeded: (() -> Unit)? = null
    ) {
        stop()

        if (text.isBlank()) {
            onComplete?.invoke()
            return
        }

        val now = System.currentTimeMillis()
        if (!isEndpointAvailable && (now - lastFailureTime < 300_000L)) {
            Log.d(TAG, "Kokoro endpoint currently in backoff cooldown, engaging fallback immediately.")
            onFallbackNeeded?.invoke()
            return
        }

        currentPlayJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val endpoint = getEndpoint(context)
                val voice = getVoice(context)

                val jsonBody = JSONObject().apply {
                    put("text", text)
                    put("voice", voice)
                    put("speed", 1.0)
                }.toString()

                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val requestBody = jsonBody.toRequestBody(mediaType)
                
                val request = Request.Builder()
                    .url(endpoint)
                    .post(requestBody)
                    .build()

                Log.i(TAG, "Sending request to Kokoro TTS: $endpoint with voice $voice")

                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    Log.w(TAG, "Kokoro TTS request failed with code ${response.code}: ${response.message}")
                    response.close()
                    isEndpointAvailable = false
                    lastFailureTime = System.currentTimeMillis()
                    withContext(Dispatchers.Main) { onFallbackNeeded?.invoke() }
                    return@launch
                }

                val audioBytes = response.body?.bytes()
                if (audioBytes == null || audioBytes.isEmpty()) {
                    Log.w(TAG, "Kokoro TTS returned empty body")
                    isEndpointAvailable = false
                    lastFailureTime = System.currentTimeMillis()
                    withContext(Dispatchers.Main) { onFallbackNeeded?.invoke() }
                    return@launch
                }

                isEndpointAvailable = true
                val tempFile = File.createTempFile("kokoro_tts_", ".wav", context.cacheDir)
                FileOutputStream(tempFile).use { fos ->
                    fos.write(audioBytes)
                    fos.flush()
                }

                withContext(Dispatchers.Main) {
                    try {
                        activeMediaPlayer = MediaPlayer().apply {
                            setDataSource(tempFile.absolutePath)
                            setOnPreparedListener { mp ->
                                mp.start()
                                onStart?.invoke()
                            }
                            setOnCompletionListener { mp ->
                                mp.release()
                                activeMediaPlayer = null
                                tempFile.delete()
                                onComplete?.invoke()
                            }
                            setOnErrorListener { mp, what, extra ->
                                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                                mp.release()
                                activeMediaPlayer = null
                                tempFile.delete()
                                onFallbackNeeded?.invoke()
                                true
                            }
                            prepareAsync()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to initialize MediaPlayer for Kokoro: ${e.message}", e)
                        tempFile.delete()
                        onFallbackNeeded?.invoke()
                    }
                }

            } catch (e: Exception) {
                Log.w(TAG, "Kokoro TTS connection unavailable: ${e.message}")
                isEndpointAvailable = false
                lastFailureTime = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    onFallbackNeeded?.invoke()
                }
            }
        }
    }
}
