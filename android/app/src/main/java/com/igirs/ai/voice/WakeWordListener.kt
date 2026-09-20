package com.igirs.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class WakeWordListener(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private val handler = Handler(Looper.getMainLooper())

    fun start() {
        if (isListening) return
        initRecognizer()
        listen()
    }

    private fun initRecognizer() {
        if (recognizer == null && SpeechRecognizer.isRecognitionAvailable(context)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        isListening = false
                        // Silently restart after brief pause
                        handler.postDelayed({
                            if (!isListening) listen()
                        }, 500)
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.lowercase()?.trim().orEmpty()

                        if (text.contains("jarvis") || text.contains("igirs") || text.contains("hey jarvis")) {
                            Log.i(TAG, "Wake word triggered: '$text'")
                            onWakeWordDetected()
                        } else {
                            // Resume listening
                            listen()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.lowercase()?.trim().orEmpty()

                        if (text.contains("jarvis") || text.contains("igirs") || text.contains("hey jarvis")) {
                            Log.i(TAG, "Wake word triggered (partial): '$text'")
                            recognizer?.stopListening()
                            isListening = false
                            onWakeWordDetected()
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    private fun listen() {
        if (recognizer == null) initRecognizer()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            isListening = true
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            isListening = false
        }
    }

    fun stop() {
        isListening = false
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
            recognizer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping wake word listener: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "IGIRS.WakeWord"
    }
}
