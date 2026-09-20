package com.igirs.ai.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.igirs.ai.R
import com.igirs.ai.llm.ChatMessage
import com.igirs.ai.llm.GroqClient
import com.igirs.ai.memory.MemoryManager
import com.igirs.ai.security.AIFirewall
import com.igirs.ai.security.SanitizationResult
import com.igirs.ai.tools.MobileToolsDispatcher
import com.igirs.ai.tools.ToolResult
import com.igirs.ai.tools.VisionManager
import com.igirs.ai.ui.CameraCaptureActivity
import com.igirs.ai.ui.CyberOrbView
import com.igirs.ai.ui.OrbState
import com.igirs.ai.voice.SpeechRecognizerManager
import com.igirs.ai.voice.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IgirsVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private lateinit var cyberOrb: CyberOrbView
    private lateinit var tvState: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var tvResponse: TextView
    private lateinit var btnClose: ImageView
    private lateinit var fabMic: FloatingActionButton
    private lateinit var btnCamera: ImageButton
    private lateinit var btnContinuous: ImageButton

    private var speechRecognizer: SpeechRecognizerManager? = null
    private val groqClient = GroqClient()
    private val toolsDispatcher = MobileToolsDispatcher(context)

    private val sessionScope = CoroutineScope(Dispatchers.Main + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateContentView(): View {
        val view = LayoutInflater.from(context).inflate(R.layout.assistant_overlay, null)

        cyberOrb = view.findViewById(R.id.overlayCyberOrb)
        tvState = view.findViewById(R.id.tvOverlayState)
        tvTranscript = view.findViewById(R.id.tvUserTranscript)
        tvResponse = view.findViewById(R.id.tvAssistantResponse)
        btnClose = view.findViewById(R.id.btnCloseOverlay)
        fabMic = view.findViewById(R.id.fabMicToggle)
        btnCamera = view.findViewById(R.id.btnCameraVision)
        btnContinuous = view.findViewById(R.id.btnContinuousToggle)

        btnClose.setOnClickListener {
            TtsManager.stop()
            speechRecognizer?.stopListening()
            finish()
        }

        fabMic.setOnClickListener {
            startListeningSession()
        }

        btnCamera.setOnClickListener {
            capturePhotoAndAnalyze()
        }

        updateContinuousButtonVisual()
        btnContinuous.setOnClickListener {
            MemoryManager.continuousListeningEnabled = !MemoryManager.continuousListeningEnabled
            updateContinuousButtonVisual()
        }

        return view
    }

    private fun updateContinuousButtonVisual() {
        val enabled = MemoryManager.continuousListeningEnabled
        if (enabled) {
            btnContinuous.setColorFilter(Color.parseColor("#00F0FF")) // Cyan glow when active
        } else {
            btnContinuous.setColorFilter(Color.parseColor("#4E5B70")) // Muted gray when inactive
        }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (screenshot != null) {
            VisionManager.latestScreenshot = screenshot
            Log.i(TAG, "Screen vision bitmap cached: ${screenshot.width}x${screenshot.height}")
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "IGIRS Assistant overlay shown via system trigger.")
        tvResponse.visibility = View.GONE
        tvTranscript.text = "Listening..."
        startListeningSession()
    }

    override fun onHide() {
        super.onHide()
        speechRecognizer?.stopListening()
        TtsManager.stop()
    }

    private fun startListeningSession() {
        cyberOrb.setState(OrbState.LISTENING)
        tvState.text = context.getString(R.string.listening_state)

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizerManager(
                context = context,
                onPartialText = { partial ->
                    tvTranscript.text = partial
                },
                onFinalText = { text ->
                    tvTranscript.text = text
                    processUserSpeech(text)
                },
                onRmsUpdate = { rms ->
                    cyberOrb.setAudioLevel(rms)
                },
                onErrorOccurred = { errorMsg ->
                    cyberOrb.setState(OrbState.STANDBY)
                    tvState.text = errorMsg
                }
            )
        }

        speechRecognizer?.startListening()
    }

    private fun processUserSpeech(query: String) {
        cyberOrb.setState(OrbState.THINKING)
        tvState.text = context.getString(R.string.thinking_state)

        sessionScope.launch {
            // Layer 2 Security: Prompt Injection Firewall
            val sanitizeResult = AIFirewall.sanitizePrompt(query)
            val cleanQuery = when (sanitizeResult) {
                is SanitizationResult.Allowed -> sanitizeResult.cleanPrompt
                is SanitizationResult.Blocked -> {
                    speakAssistantResponse("Security Protocol: That command is blocked by the AI firewall.", autoClose = true)
                    return@launch
                }
            }

            // 1. Check local Android tool intents first (sub-millisecond routing)
            val toolResult = toolsDispatcher.checkAndExecute(cleanQuery)
            when (toolResult) {
                is ToolResult.StopListening -> {
                    speakAssistantResponse(toolResult.spokenFeedback, autoClose = true)
                    return@launch
                }
                is ToolResult.Executed -> {
                    speakAssistantResponse(toolResult.spokenFeedback, autoClose = true)
                    return@launch
                }
                ToolResult.NotMatched -> {
                    // Continue to Groq LPU Cloud LLM
                }
            }

            // 2. Check for Screen Vision intent
            val lower = cleanQuery.lowercase()
            val wantsScreen = lower.contains("on my screen") || lower.contains("my screen") || lower.contains("this screen")
            val screenImage = if (wantsScreen && VisionManager.latestScreenshot != null) {
                VisionManager.bitmapToBase64DataUri(VisionManager.latestScreenshot!!)
            } else null

            // 3. Query Cloud LLM
            val messages = listOf(ChatMessage(role = "user", content = cleanQuery, imageDataUri = screenImage))
            val result = groqClient.chatCompletion(messages)

            result.onSuccess { rawReply ->
                val safeReply = AIFirewall.sanitizeOutput(rawReply)
                speakAssistantResponse(safeReply, autoClose = false)
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    val errorMsg = error.message ?: "Sorry, I'm having trouble connecting right now."
                    tvResponse.visibility = View.VISIBLE
                    tvResponse.text = errorMsg
                    cyberOrb.setState(OrbState.STANDBY)
                    tvState.text = "Communication Error"
                    TtsManager.speak(errorMsg)
                }
            }
        }
    }

    private fun capturePhotoAndAnalyze() {
        cyberOrb.setState(OrbState.THINKING)
        tvState.text = "Activating camera vision..."
        speechRecognizer?.stopListening()

        CameraCaptureActivity.onPhotoCapturedListener = { photo ->
            sessionScope.launch {
                val dataUri = VisionManager.bitmapToBase64DataUri(photo)
                tvTranscript.text = "[Optical Capture Secured]"
                tvState.text = "Analyzing visual data..."

                val prompt = "Describe what you see in this image for Joshua in a natural, conversational way."
                val messages = listOf(ChatMessage(role = "user", content = prompt, imageDataUri = dataUri))
                val result = groqClient.chatCompletion(messages)

                result.onSuccess { reply ->
                    speakAssistantResponse(reply, autoClose = false)
                }.onFailure { error ->
                    withContext(Dispatchers.Main) {
                        val errorMsg = error.message ?: "Sorry, couldn't analyze the image right now."
                        tvResponse.visibility = View.VISIBLE
                        tvResponse.text = errorMsg
                        cyberOrb.setState(OrbState.STANDBY)
                        TtsManager.speak(errorMsg)
                    }
                }
            }
        }

        val cameraIntent = Intent(context, CameraCaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(cameraIntent)
    }

    private fun speakAssistantResponse(text: String, autoClose: Boolean) {
        tvResponse.visibility = View.VISIBLE
        tvResponse.text = text
        tvState.text = context.getString(R.string.speaking_state)
        cyberOrb.setState(OrbState.SPEAKING)

        TtsManager.speak(
            text = text,
            onStart = {
                mainHandler.post {
                    cyberOrb.setState(OrbState.SPEAKING)
                }
            },
            onComplete = {
                mainHandler.post {
                    cyberOrb.setState(OrbState.STANDBY)
                    tvState.text = context.getString(R.string.standby_state)

                    if (autoClose) {
                        mainHandler.postDelayed({ finish() }, 1800)
                    } else if (MemoryManager.continuousListeningEnabled) {
                        // Continuous conversation: automatically resume listening after speaking!
                        mainHandler.postDelayed({
                            tvTranscript.text = "Listening..."
                            startListeningSession()
                        }, 500)
                    }
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        speechRecognizer = null
        TtsManager.stop()
    }

    companion object {
        private const val TAG = "IGIRS.VoiceSession"
    }
}
