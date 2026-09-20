package com.igirs.ai.voice

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

object EdgeTtsClient {

    private const val TAG = "IGIRS.EdgeTTS"
    private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val BASE_WSS = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TRUSTED_TOKEN"

    const val DEFAULT_VOICE = "en-US-AvaNeural"
    private const val PREFS_NAME = "igirs_voice_prefs"
    private const val KEY_SELECTED_VOICE = "selected_edge_voice"

    data class VoiceOption(
        val id: String,
        val name: String,
        val description: String,
        val gender: String
    )

    val AVAILABLE_VOICES = listOf(
        VoiceOption("en-US-AvaNeural", "Ava", "Warm, expressive & friendly female (ChatGPT Sky)", "Female"),
        VoiceOption("en-US-AndrewNeural", "Andrew", "Calm, natural & conversational male (ChatGPT Breeze)", "Male"),
        VoiceOption("en-US-EmmaMultilingualNeural", "Emma", "Crisp, articulate & lively female (ChatGPT Sol)", "Female"),
        VoiceOption("en-US-BrianNeural", "Brian", "Deep, reassuring & relaxed male (ChatGPT Cove)", "Male"),
        VoiceOption("en-US-JennyNeural", "Jenny", "Upbeat, friendly & conversational female (ChatGPT Juniper)", "Female"),
        VoiceOption("en-US-GuyNeural", "Guy", "Casual, friendly & articulate male (ChatGPT Ember)", "Male")
    )

    fun getSelectedVoice(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
    }

    fun setSelectedVoice(context: Context, voiceId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED_VOICE, voiceId).apply()
        Log.i(TAG, "Selected Edge TTS voice updated to: $voiceId")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private var activeMediaPlayer: MediaPlayer? = null
    private var activeWebSocket: WebSocket? = null
    private var currentPlayJob: Job? = null

    fun isSpeaking(): Boolean {
        return activeMediaPlayer?.isPlaying == true
    }

    fun stop() {
        try {
            activeWebSocket?.close(1000, "User cancelled")
            activeWebSocket = null
        } catch (_: Exception) {}

        try {
            activeMediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            activeMediaPlayer = null
        } catch (_: Exception) {}

        currentPlayJob?.cancel()
    }

    private fun generateSecMsGec(): String {
        val winEpoch = 11644473600L
        var ticks = (System.currentTimeMillis() / 1000.0) + winEpoch
        ticks -= ticks % 300.0
        ticks *= 10000000.0 // Convert to 100-nanosecond intervals (Windows file time)
        val strToHash = "${ticks.toLong()}$TRUSTED_TOKEN"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(strToHash.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    fun speak(
        context: Context,
        text: String,
        onStart: (() -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
        onFallbackNeeded: () -> Unit
    ) {
        val voiceId = getSelectedVoice(context)
        speakWithVoice(context, text, voiceId, onStart, onComplete, onFallbackNeeded)
    }

    fun previewVoice(
        context: Context,
        voiceId: String,
        onStart: (() -> Unit)? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val sampleText = "Hello! I am your IGIRS voice assistant. How does my voice sound?"
        speakWithVoice(context, sampleText, voiceId, onStart, onComplete, onFallbackNeeded = {
            onComplete?.invoke()
        })
    }

    private fun speakWithVoice(
        context: Context,
        text: String,
        voiceName: String,
        onStart: (() -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
        onFallbackNeeded: () -> Unit
    ) {
        stop()

        val cleanText = cleanTextForSpeech(text)
        if (cleanText.isEmpty()) {
            onComplete?.invoke()
            return
        }
        Log.i(TAG, "EdgeTtsClient.speak invoked with voice '$voiceName' for: '$cleanText'")

        currentPlayJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val audioBytes = synthesize(cleanText, voiceName)
                if (audioBytes == null || audioBytes.isEmpty()) {
                    Log.w(TAG, "Edge TTS returned empty stream. Falling back to native voice.")
                    withContext(Dispatchers.Main) { onFallbackNeeded() }
                    return@launch
                }
                Log.i(TAG, "Audio synthesized (${audioBytes.size} bytes). Preparing MediaPlayer...")

                // Write MP3 to cache
                val cacheFile = File(context.cacheDir, "igirs_voice_${System.currentTimeMillis()}.mp3")
                FileOutputStream(cacheFile).use { it.write(audioBytes) }

                withContext(Dispatchers.Main) {
                    playAudioFile(cacheFile, onStart, onComplete)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Edge TTS synthesis error: ${e.message}", e)
                withContext(Dispatchers.Main) { onFallbackNeeded() }
            }
        }
    }

    private suspend fun synthesize(text: String, voiceName: String): ByteArray? = withContext(Dispatchers.IO) {
        val audioStream = ByteArrayOutputStream()
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val secMsGec = generateSecMsGec()
        val secMsGecVersion = "1-143.0.3650.75"
        val fullUrl = "$BASE_WSS&Sec-MS-GEC=$secMsGec&Sec-MS-GEC-Version=$secMsGecVersion&ConnectionId=$connectionId"

        val requestId = UUID.randomUUID().toString().replace("-", "")
        val dateFormat = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US)
        val timestamp = dateFormat.format(Date())

        var isFinished = false
        var hasFailed = false
        val lock = Object()

        val muid = UUID.randomUUID().toString().replace("-", "").uppercase()
        val request = Request.Builder()
            .url(fullUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
            .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .addHeader("Pragma", "no-cache")
            .addHeader("Cache-Control", "no-cache")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader("Cookie", "muid=$muid;")
            .build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 1. Send speech.config
                val configMsg = "X-Timestamp:$timestamp\r\n" +
                        "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                webSocket.send(configMsg)

                // 2. Send SSML request with selected voice
                val escapedText = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                val ssmlPayload = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                        "<voice name='$voiceName'>" +
                        "<prosody pitch='+0Hz' rate='+2%' volume='+0%'>$escapedText</prosody>" +
                        "</voice></speak>"

                val ssmlMsg = "X-RequestId:$requestId\r\n" +
                        "Content-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:$timestamp\r\n" +
                        "Path:ssml\r\n\r\n" +
                        ssmlPayload
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    synchronized(lock) {
                        isFinished = true
                        lock.notifyAll()
                    }
                    webSocket.close(1000, "Done")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size > 2) {
                    val headerLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    val offset = 2 + headerLength
                    if (offset < data.size) {
                        audioStream.write(data, offset, data.size - offset)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Edge TTS WebSocket failure: ${t.message} (code=${response?.code})")
                synchronized(lock) {
                    hasFailed = true
                    lock.notifyAll()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(lock) {
                    isFinished = true
                    lock.notifyAll()
                }
            }
        })

        activeWebSocket = ws

        synchronized(lock) {
            val startTime = System.currentTimeMillis()
            while (!isFinished && !hasFailed && (System.currentTimeMillis() - startTime) < 7000) {
                try {
                    lock.wait(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        if (hasFailed || audioStream.size() == 0) {
            null
        } else {
            audioStream.toByteArray()
        }
    }

    private fun playAudioFile(
        file: File,
        onStart: (() -> Unit)?,
        onComplete: (() -> Unit)?
    ) {
        try {
            val mp = MediaPlayer()
            activeMediaPlayer = mp
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener {
                onStart?.invoke()
                mp.start()
            }
            mp.setOnCompletionListener {
                mp.release()
                activeMediaPlayer = null
                file.delete()
                onComplete?.invoke()
            }
            mp.setOnErrorListener { _, _, _ ->
                mp.release()
                activeMediaPlayer = null
                file.delete()
                onComplete?.invoke()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer initialization error: ${e.message}")
            file.delete()
            onComplete?.invoke()
        }
    }

    private fun cleanTextForSpeech(raw: String): String {
        var text = raw
        text = text.replace(Regex("```[\\s\\S]*?```"), " code omitted ")
        text = text.replace(Regex("`[^`]*`"), "")
        text = text.replace(Regex("https?://\\S+|www\\.\\S+"), " link ")
        text = text.replace(Regex("[#*_~>]+"), "")
        text = text.replace(Regex("^\\s*[-*•]\\s+", RegexOption.MULTILINE), "")
        text = text.replace(Regex("[\\[\\]\\(\\)\\{\\}]"), "")
        return text.replace(Regex("\\s+"), " ").trim()
    }
}
