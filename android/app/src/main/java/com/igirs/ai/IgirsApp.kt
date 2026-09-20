package com.igirs.ai

import android.app.Application
import com.igirs.ai.memory.MemoryManager
import com.igirs.ai.voice.TtsManager

class IgirsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        MemoryManager.init(this)
        TtsManager.init(this)
    }

    companion object {
        lateinit var instance: IgirsApp
            private set
    }
}
