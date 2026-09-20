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
import java.util.Locale

class SpeechRecognizerManager(
    private val context: Context,
    private val onPartialText: (String) -> Unit,
    private val onFinalText: (String) -> Unit,
    private val onRmsUpdate: (Float) -> Unit,
    private val onErrorOccurred: (errorMsg: String) -> Unit = {},
    private val onDetailedError: ((errorMsg: String, isTransient: Boolean, errorCode: Int) -> Unit)? = null,
    private val onSpeechDetected: (() -> Unit)? = null
) {

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var lastRecognizedText: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        initRecognizer()
    }

    private fun dispatchError(errorMsg: String, isTransient: Boolean, errorCode: Int) {
        if (onDetailedError != null) {
            onDetailedError.invoke(errorMsg, isTransient, errorCode)
        } else {
            onErrorOccurred(errorMsg)
        }
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition is NOT available on this device.")
            dispatchError("Speech recognition service unavailable on device.", false, -1)
            return
        }

        try {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
        } catch (_: Exception) {}

        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "SpeechRecognizer: Ready for speech")
                        lastRecognizedText = ""
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "SpeechRecognizer: User began speaking")
                        onSpeechDetected?.invoke()
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        onRmsUpdate(rmsdB)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d(TAG, "SpeechRecognizer: End of speech detected")
                        isListening = false
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        val isTransient = error == SpeechRecognizer.ERROR_NO_MATCH ||
                                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

                        // Speech recovery: If we have captured partial speech before timeout/no-match, deliver it!
                        if (lastRecognizedText.isNotBlank() && isTransient) {
                            val recovered = lastRecognizedText
                            lastRecognizedText = ""
                            Log.i(TAG, "Recovered speech before timeout: '$recovered'")
                            onFinalText(recovered)
                            return
                        }

                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech input timed out."
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
                            SpeechRecognizer.ERROR_NETWORK -> "Network error during speech recognition."
                            SpeechRecognizer.ERROR_CLIENT -> "Client recognition error."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy."
                            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech service disconnected."
                            else -> "Recognition error: code $error"
                        }
                        Log.d(TAG, "SpeechRecognizer onError: $errorMsg (code=$error, isTransient=$isTransient)")
                        dispatchError(errorMsg, isTransient, error)
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim().orEmpty()
                        val finalText = if (text.isNotEmpty()) text else lastRecognizedText
                        lastRecognizedText = ""

                        if (finalText.isNotEmpty()) {
                            onFinalText(finalText)
                        } else {
                            dispatchError("No speech input received.", true, SpeechRecognizer.ERROR_NO_MATCH)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            lastRecognizedText = text
                            onPartialText(text)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SpeechRecognizer: ${e.message}", e)
            recognizer = null
            dispatchError("Speech engine initialization failed: ${e.message}", false, -1)
        }
    }

    fun startListening() {
        mainHandler.post {
            if (recognizer == null) {
                initRecognizer()
            }

            try {
                recognizer?.cancel()
            } catch (_: Exception) {}

            lastRecognizedText = ""

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // Ultra-fast conversational turnaround (ChatGPT style: ~900ms silence detection)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
            }

            try {
                isListening = true
                recognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening: ${e.message}", e)
                isListening = false
                dispatchError("Failed to initialize microphone: ${e.message}", false, -1)
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            if (isListening) {
                try {
                    recognizer?.stopListening()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping recognizer: ${e.message}")
                }
                isListening = false
            }
        }
    }

    fun cancel() {
        mainHandler.post {
            isListening = false
            try {
                recognizer?.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling recognizer: ${e.message}")
            }
        }
    }

    fun destroy() {
        mainHandler.post {
            isListening = false
            try {
                recognizer?.cancel()
                recognizer?.destroy()
                recognizer = null
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying recognizer: ${e.message}")
            }
        }
    }

    fun isCurrentlyListening(): Boolean = isListening

    companion object {
        private const val TAG = "IGIRS.SpeechRecognizer"
    }
}
