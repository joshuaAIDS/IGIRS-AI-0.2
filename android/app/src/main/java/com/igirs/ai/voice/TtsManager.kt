package com.igirs.ai.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

object TtsManager : TextToSpeech.OnInitListener {

    private const val TAG = "IGIRS.TtsManager"
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var appContext: Context? = null

    private var onSpeechStarted: (() -> Unit)? = null
    private var onSpeechDone: (() -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "English US language is not supported on device TTS engine.")
                tts?.setLanguage(Locale.getDefault())
            }

            configureBestHumanVoice()

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onSpeechStarted?.invoke()
                }

                override fun onDone(utteranceId: String?) {
                    onSpeechDone?.invoke()
                }

                override fun onError(utteranceId: String?) {
                    onSpeechDone?.invoke()
                }
            })

            isInitialized = true
            Log.i(TAG, "Android TTS engine initialized successfully.")
        } else {
            Log.e(TAG, "Failed to initialize Android TextToSpeech.")
        }
    }

    private fun configureBestHumanVoice() {
        try {
            val available = tts?.voices ?: return
            val englishVoices = available.filter { it.locale.language.equals("en", ignoreCase = true) }

            // Priority: Natural English voice
            val naturalVoice = englishVoices.firstOrNull { voice ->
                val name = voice.name.lowercase()
                name.contains("en-us") && (name.contains("female") || name.contains("network") || name.contains("neural"))
            } ?: englishVoices.firstOrNull()

            if (naturalVoice != null) {
                tts?.voice = naturalVoice
                Log.i(TAG, "Selected native voice: ${naturalVoice.name}")
            }

            // Calibrate to natural human conversational cadence
            tts?.setSpeechRate(1.0f)
            tts?.setPitch(1.0f)
        } catch (e: Exception) {
            Log.w(TAG, "Voice calibration error: ${e.message}")
        }
    }

    fun speak(
        text: String,
        onStart: (() -> Unit)? = null,
        onComplete: (() -> Unit)? = null
    ) {
        Log.i(TAG, "TtsManager.speak called with: '$text'")
        val ctx = appContext
        if (ctx != null) {
            // Tier 1: Microsoft Edge Neural Voice (High-Fidelity Natural Voices like Ava / ChatGPT Sky)
            EdgeTtsClient.speak(
                context = ctx,
                text = text,
                onStart = onStart,
                onComplete = onComplete,
                onFallbackNeeded = {
                    Log.i(TAG, "Edge TTS unavailable, engaging native Android voice.")
                    // Tier 2: Android Native TTS
                    speakNative(text, onStart, onComplete)
                }
            )
        } else {
            speakNative(text, onStart, onComplete)
        }
    }

    private fun speakNative(
        text: String,
        onStart: (() -> Unit)?,
        onComplete: (() -> Unit)?
    ) {
        this.onSpeechStarted = onStart
        this.onSpeechDone = onComplete

        if (!isInitialized || tts == null) {
            Log.w(TAG, "Native TTS not ready yet; skipping voice playback.")
            onComplete?.invoke()
            return
        }

        val utteranceId = "igirs_utterance_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        try {
            EdgeTtsClient.stop()
            tts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS: ${e.message}")
        }
    }
}
