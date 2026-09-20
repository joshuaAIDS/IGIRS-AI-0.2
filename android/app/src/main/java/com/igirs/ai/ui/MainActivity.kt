package com.igirs.ai.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.igirs.ai.R
import com.igirs.ai.llm.ChatMessage
import com.igirs.ai.llm.GroqClient
import com.igirs.ai.security.AIFirewall
import com.igirs.ai.security.AppIntegrityGuard
import com.igirs.ai.security.SanitizationResult
import com.igirs.ai.security.SecurityFirewall
import com.igirs.ai.session.ChatSession
import com.igirs.ai.session.ChatSessionManager
import com.igirs.ai.tools.MobileToolsDispatcher
import com.igirs.ai.tools.ToolResult
import com.igirs.ai.tools.VisionManager
import com.igirs.ai.tools.WebSearchHelper
import com.igirs.ai.voice.EdgeTtsClient
import com.igirs.ai.voice.KokoroTtsClient
import com.igirs.ai.voice.SpeechRecognizerManager
import com.igirs.ai.voice.TtsManager
import com.igirs.ai.voice.WakeWordListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var sessionManager: ChatSessionManager
    private lateinit var sidebarAdapter: SidebarChatAdapter
    private var currentSessionId: String = ""

    private lateinit var rvChat: RecyclerView
    private lateinit var chatAdapter: ChatMessageAdapter
    private val uiMessages = mutableListOf<UiChatMessage>()
    private val conversationHistory = mutableListOf<ChatMessage>()

    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var etChatInput: EditText
    private lateinit var btnMicDictation: ImageButton
    private lateinit var btnSendChat: ImageButton
    private lateinit var btnAddAttachment: ImageButton
    private lateinit var btnWaveformVoice: ImageButton
    private lateinit var btnMenu: ImageButton
    private lateinit var btnModelPill: LinearLayout
    private lateinit var tvModelName: TextView
    private lateinit var btnNewChat: ImageButton

    // Attachment Preview Chip Bar
    private lateinit var layoutAttachmentPreview: LinearLayout
    private lateinit var ivAttachmentThumb: ImageView
    private lateinit var tvAttachmentName: TextView
    private lateinit var btnRemoveAttachment: ImageView
    private var pendingAttachmentBitmap: Bitmap? = null
    private var pendingAttachmentDataUri: String? = null
    private var pendingAttachmentName: String? = null
    private var pendingAttachmentTextContent: String? = null

    // Model Selection (ChatGPT-style)
    private var currentAiModel = ActiveAiModel.FAST

    // Quick action prompt buttons in empty state
    private lateinit var actionVisionPrompt: LinearLayout
    private lateinit var actionBriefingPrompt: LinearLayout
    private lateinit var actionToolsPrompt: LinearLayout

    // Live Voice Mode Overlay (Exact ChatGPT Advanced Voice Interface)
    private lateinit var layoutVoiceOverlay: RelativeLayout
    private lateinit var voiceOverlayOrb: CyberOrbView
    private lateinit var tvVoiceOverlayStatus: TextView
    private lateinit var tvVoiceOverlayTranscript: TextView
    private lateinit var tvVoiceOverlayResponse: TextView
    private lateinit var btnCloseVoiceOverlay: ImageView
    private lateinit var fabVoiceOverlayMic: ImageButton
    private lateinit var btnVoiceCameraVision: ImageButton
    private lateinit var btnVoicePersonality: ImageButton
    private lateinit var tvVoiceInterruptHint: TextView
    private lateinit var btnVoiceMenu: ImageButton
    private lateinit var layoutVoiceAskPill: LinearLayout
    private var speechCadenceJob: Job? = null

    private val groqClient = GroqClient()
    private lateinit var toolsDispatcher: MobileToolsDispatcher

    private var dictationRecognizer: SpeechRecognizerManager? = null
    private var isDictating = false

    private var voiceOverlayRecognizer: SpeechRecognizerManager? = null
    private var wakeWordListener: WakeWordListener? = null

    // Continuous Voice Mode state tracking
    private var isVoiceOverlayActive = false
    private var isAiSpeaking = false
    private var isAiReasoning = false
    private val voiceLoopHandler = Handler(Looper.getMainLooper())

    // General system permissions (phone, contacts, camera, notifications)
    private val generalPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* General permissions callback */ }

    // Dedicated on-demand microphone permission launcher with pending action callback
    private var pendingAudioAction: (() -> Unit)? = null

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Microphone permission granted!", Toast.LENGTH_SHORT).show()
            val action = pendingAudioAction
            pendingAudioAction = null
            action?.invoke()
        } else {
            pendingAudioAction = null
            Toast.makeText(this, "Microphone permission is required to use Voice features.", Toast.LENGTH_LONG).show()
        }
    }

    private fun ensureAudioPermission(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            pendingAudioAction = onGranted
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private var pendingCallAction: (() -> Unit)? = null
    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val action = pendingCallAction
        pendingCallAction = null
        action?.invoke()
    }

    fun ensureCallPermissions(onComplete: () -> Unit) {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CALL_PHONE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_CONTACTS)
        }
        if (needed.isEmpty()) {
            onComplete()
        } else {
            pendingCallAction = onComplete
            callPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private val documentPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            handleSelectedDocument(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sessionManager = ChatSessionManager(this)
        toolsDispatcher = MobileToolsDispatcher(this)

        initViews()
        setupChatRecycler()
        setupSidebarDrawer()
        setupListeners()
        setupBackNavigation()
        checkPermissions()
        // Wake-word background listening intentionally disabled to prevent keeping the mic constantly on!
        handleLaunchIntent(intent)
        runSecurityScan()

        // Load most recent chat session or create a new one
        initializeActiveSession()
    }

    private fun initializeActiveSession() {
        val sessions = sessionManager.getAllSessions()
        if (sessions.isNotEmpty()) {
            loadSession(sessions.first().id)
        } else {
            startNewChat()
        }
    }

    private fun runSecurityScan() {
        lifecycleScope.launch(Dispatchers.Default) {
            val secReport = SecurityFirewall.runDiagnostics(this@MainActivity)
            val integrity = AppIntegrityGuard.verifyIntegrity(this@MainActivity)
            android.util.Log.i("IGIRS.Security", "Security Scan: Secure=${secReport.isSecure}, Active Threats=${secReport.activeThreats}")
            android.util.Log.i("IGIRS.Security", "Integrity Scan: Package=${integrity.isPackageValid}, Sig=${integrity.isSignatureValid}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.action == IgirsQuickTileService.ACTION_START_VOICE) {
            ensureAudioPermission {
                openVoiceModeOverlay()
            }
        }
    }

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)

        rvChat = findViewById(R.id.rvChat)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        etChatInput = findViewById(R.id.etChatInput)
        btnMicDictation = findViewById(R.id.btnMicDictation)
        btnSendChat = findViewById(R.id.btnSendChat)
        btnAddAttachment = findViewById(R.id.btnAddAttachment)
        btnWaveformVoice = findViewById(R.id.btnWaveformVoice)
        btnMenu = findViewById(R.id.btnMenu)
        btnModelPill = findViewById(R.id.btnModelPill)
        tvModelName = findViewById(R.id.tvModelName)
        btnNewChat = findViewById(R.id.btnNewChat)

        // Attachment preview chip bar
        layoutAttachmentPreview = findViewById(R.id.layoutAttachmentPreview)
        ivAttachmentThumb = findViewById(R.id.ivAttachmentThumb)
        tvAttachmentName = findViewById(R.id.tvAttachmentName)
        btnRemoveAttachment = findViewById(R.id.btnRemoveAttachment)

        actionVisionPrompt = findViewById(R.id.actionVisionPrompt)
        actionBriefingPrompt = findViewById(R.id.actionBriefingPrompt)
        actionToolsPrompt = findViewById(R.id.actionToolsPrompt)

        layoutVoiceOverlay = findViewById(R.id.layoutVoiceOverlay)
        voiceOverlayOrb = findViewById(R.id.voiceOverlayOrb)
        tvVoiceOverlayStatus = findViewById(R.id.tvVoiceOverlayStatus)
        tvVoiceOverlayTranscript = findViewById(R.id.tvVoiceOverlayTranscript)
        tvVoiceOverlayResponse = findViewById(R.id.tvVoiceOverlayResponse)
        btnCloseVoiceOverlay = findViewById(R.id.btnCloseVoiceOverlay)
        fabVoiceOverlayMic = findViewById(R.id.fabVoiceOverlayMic)
        btnVoiceCameraVision = findViewById(R.id.btnVoiceCameraVision)
        btnVoicePersonality = findViewById(R.id.btnVoicePersonality)
        tvVoiceInterruptHint = findViewById(R.id.tvVoiceInterruptHint)
        btnVoiceMenu = findViewById(R.id.btnVoiceMenu)
        layoutVoiceAskPill = findViewById(R.id.layoutVoiceAskPill)

        // Initialize model name text
        tvModelName.text = currentAiModel.displayName
    }

    private fun setupSidebarDrawer() {
        val rvHistory = findViewById<RecyclerView>(R.id.rvChatHistory)
        val etSearch = findViewById<EditText>(R.id.etSidebarSearch)
        val btnNewSidebar = findViewById<View>(R.id.btnNewChatSidebar)
        val btnSecurity = findViewById<View>(R.id.btnSidebarSecurity)
        val btnMemory = findViewById<View>(R.id.btnSidebarMemory)
        val btnClearAll = findViewById<View>(R.id.btnSidebarClearAll)

        sidebarAdapter = SidebarChatAdapter(
            onSessionSelected = { session ->
                loadSession(session.id)
                drawerLayout.closeDrawer(GravityCompat.START)
            },
            onSessionRename = { session ->
                showRenameSessionDialog(session)
            },
            onSessionDelete = { session ->
                sessionManager.deleteSession(session.id)
                if (currentSessionId == session.id) {
                    startNewChat()
                } else {
                    refreshSidebarList()
                }
            }
        )

        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = sidebarAdapter

        btnNewSidebar.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startNewChat()
        }

        btnSecurity.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            val sheet = CyberCommandCenterBottomSheet()
            sheet.show(supportFragmentManager, "CyberCommandCenterSheet")
        }

        btnMemory.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            openMemoryManagementSheet()
        }

        btnClearAll.setOnClickListener {
            sessionManager.deleteAllSessions()
            startNewChat()
            Toast.makeText(this, "All chat history cleared.", Toast.LENGTH_SHORT).show()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                val results = if (query.isEmpty()) sessionManager.getAllSessions() else sessionManager.searchSessions(query)
                sidebarAdapter.submitList(results)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        refreshSidebarList()
    }

    private fun refreshSidebarList() {
        val sessions = sessionManager.getAllSessions()
        sidebarAdapter.submitList(sessions)
        sidebarAdapter.setActiveSession(currentSessionId)
    }

    private fun loadSession(sessionId: String) {
        currentSessionId = sessionId
        sidebarAdapter.setActiveSession(sessionId)
        chatAdapter.clearAll()
        conversationHistory.clear()

        val messages = sessionManager.getMessages(sessionId)
        if (messages.isEmpty()) {
            layoutEmptyState.visibility = View.VISIBLE
            rvChat.visibility = View.GONE
        } else {
            layoutEmptyState.visibility = View.GONE
            rvChat.visibility = View.VISIBLE
            for (msg in messages) {
                val type = if (msg.role == "user") MessageType.USER else MessageType.ASSISTANT
                val uiMsg = UiChatMessage(type, msg.content, imageDataUri = msg.imageDataUri)
                chatAdapter.addMessage(uiMsg)
                conversationHistory.add(ChatMessage(msg.role, msg.content, msg.imageDataUri))
            }
            rvChat.scrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    private fun startNewChat() {
        val newSession = sessionManager.createNewSession()
        currentSessionId = newSession.id
        sidebarAdapter.setActiveSession(newSession.id)
        chatAdapter.clearAll()
        conversationHistory.clear()
        clearPendingAttachment()
        layoutEmptyState.visibility = View.VISIBLE
        rvChat.visibility = View.GONE
        refreshSidebarList()
    }

    private fun showRenameSessionDialog(session: ChatSession) {
        val input = EditText(this).apply {
            setText(session.title)
            setSelection(session.title.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Chat")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    sessionManager.renameSession(session.id, newTitle)
                    refreshSidebarList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openMemoryManagementSheet() {
        val sheet = MemoryManagementBottomSheet(
            onMemoryChanged = {
                // Fact list updated
            }
        )
        sheet.show(supportFragmentManager, "MemoryManagementSheet")
    }

    private fun setupChatRecycler() {
        chatAdapter = ChatMessageAdapter(
            messages = uiMessages,
            onSpeakClicked = { text ->
                TtsManager.speak(text)
            },
            onCopyClicked = { text ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("IGIRS AI Response", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Copied to clipboard.", Toast.LENGTH_SHORT).show()
            },
            onRegenerateClicked = { position ->
                regenerateResponse(position)
            },
            onMemoryBadgeClicked = {
                openMemoryManagementSheet()
            },
            onUserMessageEdit = { position, content ->
                etChatInput.setText(content)
                etChatInput.setSelection(content.length)
                etChatInput.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(etChatInput, InputMethodManager.SHOW_IMPLICIT)
            },
            onThumbsFeedback = { position, isUp ->
                android.util.Log.i("IGIRS.Feedback", "Message #$position rated ${if (isUp) "Thumbs Up" else "Thumbs Down"}")
            }
        )

        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvChat.layoutManager = layoutManager
        rvChat.adapter = chatAdapter
    }

    private fun regenerateResponse(position: Int) {
        if (position <= 0) return
        val prevUserMessage = uiMessages.take(position).lastOrNull { it.type == MessageType.USER }
        if (prevUserMessage != null) {
            sendUserMessage(prevUserMessage.content)
        }
    }

    private fun setupListeners() {
        // Top Bar: Hamburger opens the ChatGPT sidebar drawer
        btnMenu.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        // Model Selector Dropdown Pill
        btnModelPill.setOnClickListener {
            val sheet = ModelSelectorBottomSheet(currentAiModel) { selectedModel ->
                currentAiModel = selectedModel
                tvModelName.text = selectedModel.displayName
                Toast.makeText(this, "Switched to ${selectedModel.displayName}", Toast.LENGTH_SHORT).show()
            }
            sheet.show(supportFragmentManager, "ModelSelectorSheet")
        }

        btnNewChat.setOnClickListener {
            startNewChat()
        }

        // Empty state quick prompts
        actionVisionPrompt.setOnClickListener {
            openCameraVision()
        }

        actionBriefingPrompt.setOnClickListener {
            sendUserMessage("Good morning, brief me on my day and status.")
        }

        actionToolsPrompt.setOnClickListener {
            sendUserMessage("System status report and diagnostic check.")
        }

        // Input text watcher (toggles between Mic and Send buttons)
        etChatInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrBlank() || pendingAttachmentBitmap != null || !pendingAttachmentTextContent.isNullOrBlank()
                btnSendChat.visibility = if (hasText) View.VISIBLE else View.GONE
                btnMicDictation.visibility = if (hasText) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Send message triggers
        btnSendChat.setOnClickListener {
            submitInputMessage()
        }

        etChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitInputMessage()
                true
            } else false
        }

        // Dictation mic button
        btnMicDictation.setOnClickListener {
            toggleSpeechDictation()
        }

        // Attachment Sheet
        btnAddAttachment.setOnClickListener {
            openAttachmentSheet()
        }

        // Remove Attachment button
        btnRemoveAttachment.setOnClickListener {
            clearPendingAttachment()
        }

        // Blue circular Waveform button -> Live Voice Mode
        btnWaveformVoice.setOnClickListener {
            ensureAudioPermission {
                openVoiceModeOverlay()
            }
        }

        // Voice Overlay controls (Exact ChatGPT Advanced Voice Style)
        btnCloseVoiceOverlay.setOnClickListener {
            closeVoiceModeOverlay()
        }

        btnVoiceMenu.setOnClickListener {
            closeVoiceModeOverlay()
            drawerLayout.openDrawer(GravityCompat.START)
        }

        layoutVoiceAskPill.setOnClickListener {
            closeVoiceModeOverlay()
            etChatInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etChatInput, InputMethodManager.SHOW_IMPLICIT)
        }

        fabVoiceOverlayMic.setOnClickListener {
            if (isAiSpeaking) {
                handleVoiceInterruption()
            } else {
                if (voiceOverlayRecognizer?.isCurrentlyListening() == true) {
                    voiceOverlayRecognizer?.stopListening()
                    fabVoiceOverlayMic.setImageResource(R.drawable.ic_mic_muted)
                    fabVoiceOverlayMic.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
                    tvVoiceOverlayTranscript.text = "Microphone paused"
                } else {
                    fabVoiceOverlayMic.setImageResource(R.drawable.ic_mic)
                    fabVoiceOverlayMic.setColorFilter(Color.WHITE)
                    startVoiceOverlayListening()
                }
            }
        }

        voiceOverlayOrb.setOnClickListener {
            if (isAiSpeaking) {
                handleVoiceInterruption()
            }
        }

        layoutVoiceOverlay.setOnClickListener {
            if (isAiSpeaking) {
                handleVoiceInterruption()
            }
        }

        // Voice Personality Selector (ChatGPT Voices Bottom Sheet)
        btnVoicePersonality.setOnClickListener {
            val sheet = VoiceSelectorBottomSheet { voiceId ->
                if (isVoiceOverlayActive) {
                    val voiceName = EdgeTtsClient.AVAILABLE_VOICES.firstOrNull { it.id == voiceId }?.name ?: "Ava"
                    speakVoiceOverlayResponse("Voice updated to $voiceName.")
                }
            }
            sheet.show(supportFragmentManager, "VoiceSelectorSheet")
        }
    }

    private fun showAttachmentPreview(bitmap: Bitmap?, name: String) {
        layoutAttachmentPreview.visibility = View.VISIBLE
        tvAttachmentName.text = name
        if (bitmap != null) {
            ivAttachmentThumb.setImageBitmap(bitmap)
        } else {
            ivAttachmentThumb.setImageResource(R.drawable.ic_document)
        }
        btnSendChat.visibility = View.VISIBLE
        btnMicDictation.visibility = View.GONE
        etChatInput.requestFocus()
    }

    private fun clearPendingAttachment() {
        pendingAttachmentBitmap = null
        pendingAttachmentDataUri = null
        pendingAttachmentName = null
        pendingAttachmentTextContent = null
        layoutAttachmentPreview.visibility = View.GONE
        val hasText = !etChatInput.text.isNullOrBlank()
        btnSendChat.visibility = if (hasText) View.VISIBLE else View.GONE
        btnMicDictation.visibility = if (hasText) View.GONE else View.VISIBLE
    }

    private fun submitInputMessage() {
        var text = etChatInput.text.toString().trim()
        val hasAttachment = pendingAttachmentBitmap != null || !pendingAttachmentTextContent.isNullOrBlank()

        if (text.isEmpty() && !hasAttachment) {
            return
        }

        if (text.isEmpty()) {
            text = if (pendingAttachmentBitmap != null) {
                "Describe what you see in this photo in detail."
            } else {
                "Please analyze and explain this attached document."
            }
        }

        // Capture document content separately — don't stuff it into user text
        val docContent = pendingAttachmentTextContent
        val docName = pendingAttachmentName

        val photo = pendingAttachmentBitmap
        val dataUri = pendingAttachmentDataUri

        etChatInput.setText("")
        clearPendingAttachment()

        // If there's document content, inject it as a separate system context message
        // before sending the user message, so it doesn't hit the firewall
        if (!docContent.isNullOrBlank()) {
            val docContextMsg = "[Attached Document: ${docName ?: "File"}]\n```\n$docContent\n```\nThe user has attached this document. Use its contents to answer their query."
            conversationHistory.add(ChatMessage(role = "system", content = docContextMsg))
        }

        sendUserMessage(text, photo, dataUri)
    }

    private fun sendUserMessage(text: String, image: Bitmap? = null, imageDataUri: String? = null) {
        layoutEmptyState.visibility = View.GONE
        rvChat.visibility = View.VISIBLE

        val userUiMsg = UiChatMessage(MessageType.USER, text, image, imageDataUri)
        chatAdapter.addMessage(userUiMsg)

        // Save user message to persistent session
        if (currentSessionId.isNotEmpty()) {
            sessionManager.saveMessage(currentSessionId, "user", text, imageDataUri)
            val session = sessionManager.getSessionById(currentSessionId)
            if (session != null && session.title == "New Chat") {
                sessionManager.autoTitleSession(currentSessionId, text)
            }
            refreshSidebarList()
        }

        // Layer 2 Security: Prompt Injection & Jailbreak Firewall
        val sanitizeResult = AIFirewall.sanitizePrompt(text)
        val cleanText = when (sanitizeResult) {
            is SanitizationResult.Allowed -> sanitizeResult.cleanPrompt
            is SanitizationResult.Blocked -> {
                val blockReply = "🛡️ ${sanitizeResult.reason}"
                chatAdapter.addMessage(UiChatMessage(MessageType.ASSISTANT, blockReply))
                conversationHistory.add(ChatMessage(role = "user", content = text))
                conversationHistory.add(ChatMessage(role = "assistant", content = blockReply))
                if (currentSessionId.isNotEmpty()) {
                    sessionManager.saveMessage(currentSessionId, "assistant", blockReply)
                }
                rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
                return
            }
        }

        conversationHistory.add(ChatMessage(role = "user", content = cleanText, imageDataUri = imageDataUri))

        val thinkingMsg = UiChatMessage(MessageType.THINKING, "")
        chatAdapter.addMessage(thinkingMsg)
        rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)

        lifecycleScope.launch {
            // 1. Check local Android tool execution
            val toolResult = toolsDispatcher.checkAndExecute(cleanText)
            when (toolResult) {
                is ToolResult.Executed -> {
                    chatAdapter.removeThinking()
                    val reply = toolResult.spokenFeedback
                    val isMemory = reply.contains("memory vault", ignoreCase = true)
                    val aiMsg = UiChatMessage(MessageType.ASSISTANT, reply, isMemoryUpdated = isMemory)
                    chatAdapter.addMessage(aiMsg)
                    conversationHistory.add(ChatMessage(role = "assistant", content = reply))
                    if (currentSessionId.isNotEmpty()) {
                        sessionManager.saveMessage(currentSessionId, "assistant", reply)
                    }
                    rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
                    return@launch
                }
                is ToolResult.StopListening -> {
                    chatAdapter.removeThinking()
                    val reply = toolResult.spokenFeedback
                    val aiMsg = UiChatMessage(MessageType.ASSISTANT, reply)
                    chatAdapter.addMessage(aiMsg)
                    conversationHistory.add(ChatMessage(role = "assistant", content = reply))
                    if (currentSessionId.isNotEmpty()) {
                        sessionManager.saveMessage(currentSessionId, "assistant", reply)
                    }
                    rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
                    return@launch
                }
                ToolResult.NotMatched -> {
                    // Forward to Groq LPU Cloud LLM with streaming and Web Search
                }
            }

            // 2. Check if live online web search is needed
            var searchedQuery: String? = null
            if (WebSearchHelper.shouldSearchWeb(cleanText)) {
                try {
                    val webResponse = WebSearchHelper.search(cleanText, maxResults = 4)
                    if (webResponse.results.isNotEmpty() || !webResponse.instantAnswer.isNullOrBlank()) {
                        searchedQuery = cleanText
                        val searchContext = WebSearchHelper.formatSearchContextForLLM(webResponse)
                        conversationHistory.add(ChatMessage(role = "system", content = searchContext))
                    }
                } catch (e: Exception) {
                    android.util.Log.w("IGIRS.WebSearch", "Search error: ${e.message}")
                }
            }

            // 3. Real-Time Token Streaming from Groq LPU (ChatGPT Style)
            val streamingAiMsg = UiChatMessage(
                type = MessageType.ASSISTANT,
                content = "",
                isMemoryUpdated = false,
                searchedWebQuery = searchedQuery
            )
            var hasReplacedThinking = false
            val fullAccumulatedReply = StringBuilder()
            var lastUiUpdateTime = 0L

            val result = groqClient.streamChatCompletion(
                messages = conversationHistory,
                preferredModel = currentAiModel.modelId
            ) { chunk ->
                fullAccumulatedReply.append(chunk)
                val now = System.currentTimeMillis()
                if (!hasReplacedThinking || now - lastUiUpdateTime >= 30L) {
                    lastUiUpdateTime = now
                    val currentText = fullAccumulatedReply.toString()
                    withContext(Dispatchers.Main) {
                        if (!hasReplacedThinking) {
                            chatAdapter.removeThinking()
                            streamingAiMsg.content = currentText
                            chatAdapter.addMessage(streamingAiMsg)
                            hasReplacedThinking = true
                        } else {
                            chatAdapter.updateLastMessage(currentText)
                        }
                        rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (!hasReplacedThinking) {
                    chatAdapter.removeThinking()
                }
                result.onSuccess { finalReply ->
                    val safeReply = AIFirewall.sanitizeOutput(finalReply)
                    val isMemory = safeReply.contains("memory vault", ignoreCase = true) || cleanText.contains("remember", ignoreCase = true)
                    streamingAiMsg.content = safeReply
                    streamingAiMsg.isMemoryUpdated = isMemory
                    if (!hasReplacedThinking) {
                        chatAdapter.addMessage(streamingAiMsg)
                    } else {
                        chatAdapter.updateLastMessage(safeReply)
                    }
                    conversationHistory.add(ChatMessage(role = "assistant", content = safeReply))
                    if (currentSessionId.isNotEmpty()) {
                        sessionManager.saveMessage(currentSessionId, "assistant", safeReply)
                    }
                    rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
                }.onFailure { error ->
                    if (!hasReplacedThinking) {
                        val errMsg = error.message ?: "Failed to connect to IGIRS AI Groq LPU cluster."
                        chatAdapter.addMessage(UiChatMessage(MessageType.ASSISTANT, "⚠️ Error: $errMsg\n\nGroq LPU cluster temporary connection error. Auto-rotating key pool..."))
                        rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
                    }
                }
            }
        }
    }

    private fun openAttachmentSheet() {
        val sheet = AttachmentBottomSheet(
            onCameraVisionSelected = {
                CameraCaptureActivity.onPhotoCapturedListener = { photo ->
                    pendingAttachmentBitmap = photo
                    lifecycleScope.launch(Dispatchers.Default) {
                        pendingAttachmentDataUri = VisionManager.bitmapToBase64DataUri(photo)
                        pendingAttachmentName = "Photo_${System.currentTimeMillis() % 10000}.jpg"
                        pendingAttachmentTextContent = null
                        withContext(Dispatchers.Main) {
                            showAttachmentPreview(photo, pendingAttachmentName ?: "Photo.jpg")
                        }
                    }
                }
                val intent = Intent(this, CameraCaptureActivity::class.java)
                startActivity(intent)
            },
            onDocumentSelected = {
                documentPickerLauncher.launch("*/*")
            },
            onMorningRoutineSelected = {
                sendUserMessage("Good morning, activate my morning protocol.")
            },
            onNightRoutineSelected = {
                sendUserMessage("Good night, activate sleep mode.")
            }
        )
        sheet.show(supportFragmentManager, "AttachmentSheet")
    }

    private fun handleSelectedDocument(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cursor = contentResolver.query(uri, null, null, null, null)
                var displayName = "Attached Document"
                var fileSize = 0L
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) displayName = it.getString(nameIndex) ?: "Attached Document"
                        val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex != -1) fileSize = it.getLong(sizeIndex)
                    }
                }

                val mimeType = contentResolver.getType(uri) ?: ""
                val extension = displayName.substringAfterLast('.', "").lowercase()

                when {
                    // Handle images — send through vision pipeline
                    mimeType.startsWith("image/") || extension in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp") -> {
                        val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
                            android.graphics.BitmapFactory.decodeStream(stream)
                        }
                        if (bitmap != null) {
                            val scaled = VisionManager.scaleBitmapForVision(bitmap)
                            val dataUri = VisionManager.bitmapToBase64DataUri(scaled)
                            withContext(Dispatchers.Main) {
                                pendingAttachmentBitmap = scaled
                                pendingAttachmentDataUri = dataUri
                                pendingAttachmentName = displayName
                                pendingAttachmentTextContent = null
                                showAttachmentPreview(scaled, displayName)
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Unable to load image", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    // Handle PDFs — extract page count and basic text info
                    mimeType == "application/pdf" || extension == "pdf" -> {
                        val pfd = contentResolver.openFileDescriptor(uri, "r")
                        var pdfInfo = ""
                        if (pfd != null) {
                            try {
                                val renderer = android.graphics.pdf.PdfRenderer(pfd)
                                val pageCount = renderer.pageCount
                                pdfInfo = "[PDF Document: $displayName]\nPages: $pageCount\n"

                                // Try to render first page as image for vision analysis
                                if (pageCount > 0) {
                                    val page = renderer.openPage(0)
                                    val bmp = android.graphics.Bitmap.createBitmap(
                                        page.width * 2, page.height * 2,
                                        android.graphics.Bitmap.Config.ARGB_8888
                                    )
                                    bmp.eraseColor(android.graphics.Color.WHITE)
                                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    page.close()

                                    val scaled = VisionManager.scaleBitmapForVision(bmp)
                                    val dataUri = VisionManager.bitmapToBase64DataUri(scaled)
                                    
                                    withContext(Dispatchers.Main) {
                                        pendingAttachmentBitmap = scaled
                                        pendingAttachmentDataUri = dataUri
                                        pendingAttachmentName = displayName
                                        pendingAttachmentTextContent = pdfInfo + "The first page has been rendered as an image for visual analysis."
                                        showAttachmentPreview(scaled, displayName)
                                    }
                                }
                                renderer.close()
                            } catch (e: Exception) {
                                pfd.close()
                                // Fallback: read raw bytes for any extractable text
                                val rawContent = contentResolver.openInputStream(uri)?.use { stream ->
                                    val bytes = stream.readBytes()
                                    val text = String(bytes, Charsets.ISO_8859_1)
                                    // Try to extract text between PDF stream markers
                                    val extracted = StringBuilder()
                                    val streamRegex = Regex("stream\\s*\\n([\\s\\S]*?)\\nendstream")
                                    streamRegex.findAll(text).forEach { match ->
                                        val chunk = match.groupValues[1]
                                            .filter { it.code in 32..126 || it == '\n' || it == '\r' || it == '\t' }
                                            .trim()
                                        if (chunk.length > 10) extracted.append(chunk).append("\n")
                                    }
                                    if (extracted.isNotEmpty()) extracted.toString().take(40_000) else "[PDF binary content — could not extract text. Use the rendered page image for analysis.]"
                                } ?: "[Unable to read PDF]"
                                
                                withContext(Dispatchers.Main) {
                                    pendingAttachmentBitmap = null
                                    pendingAttachmentDataUri = null
                                    pendingAttachmentName = displayName
                                    pendingAttachmentTextContent = "$pdfInfo\n$rawContent"
                                    showAttachmentPreview(null, displayName)
                                }
                            }
                        }
                    }

                    // Handle text-based documents
                    else -> {
                        val maxBytes = 200_000
                        val content = contentResolver.openInputStream(uri)?.use { stream ->
                            val bytes = stream.readBytes()
                            if (bytes.size > maxBytes) {
                                String(bytes.copyOfRange(0, maxBytes), Charsets.UTF_8) + "\n... [truncated at ${maxBytes / 1000}KB]"
                            } else {
                                String(bytes, Charsets.UTF_8)
                            }
                        } ?: "[Unable to read content]"

                        // Validate that it's actually readable text
                        val isReadable = content.count { it.code in 32..126 || it == '\n' || it == '\r' || it == '\t' } > content.length * 0.6
                        val finalContent = if (isReadable) {
                            content
                        } else {
                            "[Binary file detected: $displayName (${fileSize / 1024}KB)]\nThis file type cannot be read as text. Supported formats: .txt, .md, .csv, .json, .xml, .html, .kt, .java, .py, .js, .ts, .yaml, .yml, .log, .toml, .properties, .ini, .cfg, .sh, .bat"
                        }

                        withContext(Dispatchers.Main) {
                            pendingAttachmentBitmap = null
                            pendingAttachmentDataUri = null
                            pendingAttachmentName = displayName
                            pendingAttachmentTextContent = finalContent
                            showAttachmentPreview(null, displayName)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("IGIRS.Attachment", "Document handling error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to read document: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openCameraVision() {
        CameraCaptureActivity.onPhotoCapturedListener = { photo ->
            lifecycleScope.launch {
                val dataUri = VisionManager.bitmapToBase64DataUri(photo)
                sendUserMessage(
                    text = "Describe what you see in this photo in a natural, conversational way.",
                    image = photo,
                    imageDataUri = dataUri
                )
            }
        }
        val intent = Intent(this, CameraCaptureActivity::class.java)
        startActivity(intent)
    }

    private fun startDictation() {
        btnMicDictation.setColorFilter(ContextCompat.getColor(this, R.color.electric_cyan))
        isDictating = true
        dictationRecognizer = SpeechRecognizerManager(
            context = this,
            onPartialText = { partial ->
                etChatInput.setText(partial)
                etChatInput.setSelection(partial.length)
            },
            onFinalText = { final ->
                etChatInput.setText(final)
                etChatInput.setSelection(final.length)
                stopDictation()
            },
            onRmsUpdate = { _ -> },
            onErrorOccurred = { _ ->
                stopDictation()
            }
        )
        dictationRecognizer?.startListening()
    }

    private fun stopDictation() {
        dictationRecognizer?.stopListening()
        dictationRecognizer?.destroy()
        dictationRecognizer = null
        isDictating = false
        btnMicDictation.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
    }

    private fun toggleSpeechDictation() {
        if (isDictating) {
            stopDictation()
        } else {
            ensureAudioPermission {
                startDictation()
            }
        }
    }

    // =========================================================================
    // ChatGPT Live Voice Mode: Continuous Conversational Loop with Zero Acoustic Feedback
    // =========================================================================


    private fun startSpeechOrbAnimation() {
        speechCadenceJob?.cancel()
        speechCadenceJob = lifecycleScope.launch(Dispatchers.Main) {
            var t = 0f
            while (isAiSpeaking && isVoiceOverlayActive) {
                val harmonic1 = (kotlin.math.sin((t * 7.5).toDouble()) + 1.0) * 0.5
                val harmonic2 = (kotlin.math.cos((t * 14.0).toDouble()) + 1.0) * 0.5
                val pausePace = (kotlin.math.sin((t * 2.2).toDouble()) + 1.0) * 0.5
                val amplitude = ((harmonic1 * 0.6 + harmonic2 * 0.4) * (0.35 + 0.65 * pausePace)).toFloat()
                voiceOverlayOrb.setSpeechCadence(amplitude)
                t += 0.05f
                delay(33)
            }
            voiceOverlayOrb.setSpeechCadence(0f)
        }
    }

    private fun stopSpeechOrbAnimation() {
        speechCadenceJob?.cancel()
        speechCadenceJob = null
        voiceOverlayOrb.setSpeechCadence(0f)
    }

    private fun openVoiceModeOverlay() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ensureAudioPermission {
                openVoiceModeOverlay()
            }
            return
        }

        if (isVoiceOverlayActive) return
        isVoiceOverlayActive = true
        isAiSpeaking = false
        isAiReasoning = false

        layoutVoiceOverlay.visibility = View.VISIBLE
        tvVoiceOverlayStatus.text = "CONNECTING..."
        tvVoiceOverlayTranscript.text = ""
        tvVoiceOverlayResponse.visibility = View.GONE
        voiceOverlayOrb.setState(OrbState.STANDBY)
        fabVoiceOverlayMic.setImageResource(R.drawable.ic_mic)
        fabVoiceOverlayMic.setColorFilter(Color.WHITE)

        voiceLoopHandler.removeCallbacksAndMessages(null)
        voiceLoopHandler.postDelayed({
            if (isVoiceOverlayActive) {
                startVoiceOverlayListening()
            }
        }, 300)
    }

    private fun closeVoiceModeOverlay() {
        isVoiceOverlayActive = false
        isAiSpeaking = false
        isAiReasoning = false

        stopSpeechOrbAnimation()
        voiceLoopHandler.removeCallbacksAndMessages(null)
        voiceOverlayRecognizer?.stopListening()
        voiceOverlayRecognizer?.destroy()
        voiceOverlayRecognizer = null

        TtsManager.stop()
        voiceOverlayOrb.setState(OrbState.STANDBY)
        layoutVoiceOverlay.visibility = View.GONE
    }

    /**
     * Instant interruption: Stops speech immediately and opens microphone for user.
     */
    private fun handleVoiceInterruption() {
        if (!isVoiceOverlayActive) return
        android.util.Log.i("IGIRS.Voice", "User manual interruption triggered! Halting TTS.")
        stopSpeechOrbAnimation()
        TtsManager.stop()
        isAiSpeaking = false
        isAiReasoning = false
        voiceLoopHandler.removeCallbacksAndMessages(null)
        startVoiceOverlayListening()
    }

    private fun startVoiceOverlayListening() {
        if (!isVoiceOverlayActive) return

        isAiSpeaking = false
        isAiReasoning = false
        voiceOverlayOrb.setState(OrbState.LISTENING)
        tvVoiceOverlayStatus.text = "LISTENING..."
        tvVoiceOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.electric_cyan))
        tvVoiceOverlayTranscript.text = "Listening..."

        if (voiceOverlayRecognizer == null) {
            voiceOverlayRecognizer = SpeechRecognizerManager(
                context = this,
                onPartialText = { partial ->
                    if (isVoiceOverlayActive && !isAiSpeaking && !isAiReasoning) {
                        tvVoiceOverlayTranscript.text = partial
                    }
                },
                onFinalText = { text ->
                    if (isVoiceOverlayActive && !isAiSpeaking && !isAiReasoning) {
                        tvVoiceOverlayTranscript.text = text
                        processVoiceOverlaySpeech(text)
                    }
                },
                onRmsUpdate = { rms ->
                    if (isVoiceOverlayActive && !isAiSpeaking) {
                        voiceOverlayOrb.setAudioLevel(rms)
                    }
                },
                onErrorOccurred = { _ -> },
                onDetailedError = { errorMsg, isTransient, errorCode ->
                    android.util.Log.d("IGIRS.Voice", "Recognizer onDetailedError: $errorMsg, isTransient=$isTransient, code=$errorCode")
                    if (isVoiceOverlayActive && !isAiSpeaking && !isAiReasoning) {
                        if (isTransient) {
                            // Transient silence / pause: Gently continue listening on the existing instance
                            voiceLoopHandler.postDelayed({
                                if (isVoiceOverlayActive && !isAiSpeaking && !isAiReasoning) {
                                    startVoiceOverlayListening()
                                }
                            }, 300)
                        } else {
                            // Non-transient client/engine error: Recreate instance after safe backoff
                            voiceLoopHandler.postDelayed({
                                if (isVoiceOverlayActive && !isAiSpeaking && !isAiReasoning) {
                                    voiceOverlayRecognizer?.destroy()
                                    voiceOverlayRecognizer = null
                                    startVoiceOverlayListening()
                                }
                            }, 700)
                        }
                    }
                }
            )
        }

        voiceOverlayRecognizer?.startListening()
    }

    private fun processVoiceOverlaySpeech(query: String) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            voiceLoopHandler.postDelayed({
                if (isVoiceOverlayActive && !isAiSpeaking) startVoiceOverlayListening()
            }, 250)
            return
        }

        voiceOverlayRecognizer?.stopListening()
        isAiReasoning = true
        voiceOverlayOrb.setState(OrbState.THINKING)
        tvVoiceOverlayStatus.text = "REASONING..."
        tvVoiceOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.cyber_blue))

        lifecycleScope.launch {
            val lower = cleanQuery.lowercase()
            if (lower.contains("stop listening") || lower.contains("close voice mode") ||
                lower.contains("exit voice mode") || lower.contains("goodbye igirs") ||
                lower.contains("dismiss voice") || lower.contains("dismiss")) {
                isAiReasoning = false
                speakVoiceOverlayResponse("Got it, talk to you later!") {
                    closeVoiceModeOverlay()
                }
                return@launch
            }

            // Layer 2 Security: Prompt Injection Firewall for Voice
            val sanitizeResult = AIFirewall.sanitizePrompt(cleanQuery)
            val cleanSpeech = when (sanitizeResult) {
                is SanitizationResult.Allowed -> sanitizeResult.cleanPrompt
                is SanitizationResult.Blocked -> {
                    isAiReasoning = false
                    speakVoiceOverlayResponse("Security Protocol: That request was blocked by the AI firewall.")
                    return@launch
                }
            }

            // Check tools dispatcher
            val toolResult = toolsDispatcher.checkAndExecute(cleanSpeech)
            when (toolResult) {
                is ToolResult.Executed -> {
                    isAiReasoning = false
                    withContext(Dispatchers.Main) {
                        val userUiMsg = UiChatMessage(MessageType.USER, cleanQuery)
                        val aiUiMsg = UiChatMessage(MessageType.ASSISTANT, toolResult.spokenFeedback)
                        chatAdapter.addMessage(userUiMsg)
                        chatAdapter.addMessage(aiUiMsg)
                        conversationHistory.add(ChatMessage(role = "user", content = cleanSpeech))
                        conversationHistory.add(ChatMessage(role = "assistant", content = toolResult.spokenFeedback))
                        if (currentSessionId.isNotEmpty()) {
                            sessionManager.saveMessage(currentSessionId, "user", cleanSpeech)
                            sessionManager.saveMessage(currentSessionId, "assistant", toolResult.spokenFeedback)
                        }
                    }
                    speakVoiceOverlayResponse(toolResult.spokenFeedback)
                    return@launch
                }
                is ToolResult.StopListening -> {
                    isAiReasoning = false
                    speakVoiceOverlayResponse(toolResult.spokenFeedback) {
                        closeVoiceModeOverlay()
                    }
                    return@launch
                }
                ToolResult.NotMatched -> {}
            }

            // Continuous multi-turn chat history (ChatGPT-style)
            withContext(Dispatchers.Main) {
                val userUiMsg = UiChatMessage(MessageType.USER, cleanQuery)
                chatAdapter.addMessage(userUiMsg)
                conversationHistory.add(ChatMessage(role = "user", content = cleanSpeech))
                if (currentSessionId.isNotEmpty()) {
                    sessionManager.saveMessage(currentSessionId, "user", cleanSpeech)
                }
            }

            // Real-Time Web Search in Voice Mode
            var voiceSearchedQuery: String? = null
            if (WebSearchHelper.shouldSearchWeb(cleanSpeech)) {
                withContext(Dispatchers.Main) {
                    tvVoiceOverlayStatus.text = "SEARCHING LIVE WEB..."
                    tvVoiceOverlayStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.electric_cyan))
                }
                try {
                    val webResponse = WebSearchHelper.search(cleanSpeech, maxResults = 3)
                    if (webResponse.results.isNotEmpty() || !webResponse.instantAnswer.isNullOrBlank()) {
                        voiceSearchedQuery = cleanSpeech
                        val searchContext = WebSearchHelper.formatSearchContextForLLM(webResponse)
                        conversationHistory.add(ChatMessage(role = "system", content = searchContext))
                    }
                } catch (e: Exception) {
                    android.util.Log.w("IGIRS.WebSearch", "Voice web search notice: ${e.message}")
                }
            }

            val accumulatedVoiceReply = StringBuilder()
            var hasStartedDisplay = false

            val result = groqClient.streamChatCompletion(
                messages = conversationHistory,
                preferredModel = currentAiModel.modelId
            ) { chunk ->
                accumulatedVoiceReply.append(chunk)
                withContext(Dispatchers.Main) {
                    if (isVoiceOverlayActive) {
                        if (!hasStartedDisplay) {
                            tvVoiceOverlayResponse.visibility = View.VISIBLE
                            tvVoiceOverlayStatus.text = "RESPONDING..."
                            hasStartedDisplay = true
                        }
                        tvVoiceOverlayResponse.text = accumulatedVoiceReply.toString()
                    }
                }
            }

            withContext(Dispatchers.Main) {
                isAiReasoning = false
                result.onSuccess { rawReply ->
                    val finalReply = if (accumulatedVoiceReply.isNotEmpty()) accumulatedVoiceReply.toString() else rawReply
                    val safeReply = AIFirewall.sanitizeOutput(finalReply)
                    val aiUiMsg = UiChatMessage(
                        type = MessageType.ASSISTANT,
                        content = safeReply,
                        searchedWebQuery = voiceSearchedQuery
                    )
                    chatAdapter.addMessage(aiUiMsg)
                    conversationHistory.add(ChatMessage(role = "assistant", content = safeReply))
                    if (currentSessionId.isNotEmpty()) {
                        sessionManager.saveMessage(currentSessionId, "assistant", safeReply)
                    }
                    speakVoiceOverlayResponse(safeReply)
                }.onFailure { error ->
                    val errorMsg = error.message ?: "Sorry, I'm having trouble connecting right now."
                    speakVoiceOverlayResponse(errorMsg)
                }
            }
        }
    }

    private fun speakVoiceOverlayResponse(reply: String, onFinished: (() -> Unit)? = null) {
        if (!isVoiceOverlayActive) return
        isAiSpeaking = true
        tvVoiceOverlayResponse.visibility = View.VISIBLE
        tvVoiceOverlayResponse.text = reply
        tvVoiceOverlayStatus.text = "SPEAKING..."
        tvVoiceOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.electric_cyan))
        voiceOverlayOrb.setState(OrbState.SPEAKING)

        // Crucial: Stop microphone while speaker outputs audio to eliminate acoustic self-interruption
        stopSpeechOrbAnimation()
        voiceLoopHandler.removeCallbacksAndMessages(null)
        voiceOverlayRecognizer?.stopListening()

        TtsManager.speak(
            text = reply,
            onStart = {
                runOnUiThread {
                    if (isVoiceOverlayActive) {
                        voiceOverlayOrb.setState(OrbState.SPEAKING)
                        startSpeechOrbAnimation()
                    }
                }
            },
            onComplete = {
                runOnUiThread {
                    isAiSpeaking = false
                    stopSpeechOrbAnimation()
                    if (!isVoiceOverlayActive) return@runOnUiThread

                    if (onFinished != null) {
                        onFinished.invoke()
                        return@runOnUiThread
                    }

                    // Turn-taking handover: AI done speaking -> seamlessly open mic for user's next turn!
                    voiceOverlayOrb.setState(OrbState.LISTENING)
                    tvVoiceOverlayStatus.text = "LISTENING..."
                    tvVoiceOverlayTranscript.text = "Listening..."

                    voiceLoopHandler.postDelayed({
                        if (isVoiceOverlayActive && !isAiSpeaking && !isAiReasoning) {
                            startVoiceOverlayListening()
                        }
                    }, 250)
                }
            }
        )
    }

    private fun startWakeWord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            wakeWordListener = WakeWordListener(this) {
                runOnUiThread {
                    Toast.makeText(this, "Hey J.A.R.V.I.S. detected!", Toast.LENGTH_SHORT).show()
                    openVoiceModeOverlay()
                }
            }
            wakeWordListener?.start()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isVoiceOverlayActive -> closeVoiceModeOverlay()
                    drawerLayout.isDrawerOpen(GravityCompat.START) -> drawerLayout.closeDrawer(GravityCompat.START)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    private fun checkPermissions() {
        // Only request non-sensitive general permissions on startup.
        // Microphone permission is strictly requested ON-DEMAND when the user triggers Voice Mode or Dictation.
        val permissionsToRequest = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            generalPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onPause() {
        super.onPause()
        // Immediately release the microphone and stop speech synthesis when the app is backgrounded or minimized
        if (isDictating) {
            stopDictation()
        }
        if (isVoiceOverlayActive) {
            voiceLoopHandler.removeCallbacksAndMessages(null)
            voiceOverlayRecognizer?.stopListening()
            voiceOverlayRecognizer?.destroy()
            voiceOverlayRecognizer = null
            TtsManager.stop()
            voiceOverlayOrb.setState(OrbState.STANDBY)
            tvVoiceOverlayStatus.text = "PAUSED"
        }
        wakeWordListener?.stop()
    }

    override fun onStop() {
        super.onStop()
        // Guarantee that microphone hardware is 100% released whenever the app is not in the foreground
        stopDictation()
        if (isVoiceOverlayActive) {
            closeVoiceModeOverlay()
        }
        wakeWordListener?.stop()
        TtsManager.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceLoopHandler.removeCallbacksAndMessages(null)
        stopDictation()
        wakeWordListener?.stop()
        voiceOverlayRecognizer?.destroy()
        voiceOverlayRecognizer = null
        TtsManager.stop()
    }
}
