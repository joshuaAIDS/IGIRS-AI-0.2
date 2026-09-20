package com.igirs.ai.ui

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

class IgirsQuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "IGIRS AI"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        Log.i(TAG, "IGIRS Quick Settings Tile tapped.")

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_START_VOICE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        if (isLocked) {
            unlockAndRun {
                startActivityAndCollapse(launchIntent)
            }
        } else {
            startActivityAndCollapse(launchIntent)
        }
    }

    companion object {
        private const val TAG = "IGIRS.QuickTile"
        const val ACTION_START_VOICE = "com.igirs.ai.ACTION_START_VOICE"
    }
}
