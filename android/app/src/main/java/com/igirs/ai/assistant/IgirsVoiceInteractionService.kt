package com.igirs.ai.assistant

import android.service.voice.VoiceInteractionService
import android.util.Log

class IgirsVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "IGIRS VoiceInteractionService is READY as Default Assistant.")
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.i(TAG, "IGIRS VoiceInteractionService shut down.")
    }

    companion object {
        private const val TAG = "IGIRS.VoiceService"
    }
}
