package com.igirs.ai.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.util.Log

class IgirsRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        Log.d(TAG, "IgirsRecognitionService: onStartListening")
    }

    override fun onStopListening(listener: Callback?) {
        Log.d(TAG, "IgirsRecognitionService: onStopListening")
    }

    override fun onCancel(listener: Callback?) {
        Log.d(TAG, "IgirsRecognitionService: onCancel")
    }

    companion object {
        private const val TAG = "IGIRS.RecognitionService"
    }
}
