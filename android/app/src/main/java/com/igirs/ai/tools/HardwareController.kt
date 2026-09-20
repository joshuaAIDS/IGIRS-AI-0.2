package com.igirs.ai.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log

data class BatteryInfo(
    val percentage: Int,
    val isCharging: Boolean,
    val isPlugged: Boolean
)

object HardwareController {

    private const val TAG = "IGIRS.Hardware"
    private var isTorchOn = false

    fun isFlashlightOn(): Boolean = isTorchOn

    fun setFlashlight(context: Context, turnOn: Boolean): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return "I couldn't find a flashlight on this device."

            cameraManager.setTorchMode(cameraId, turnOn)
            isTorchOn = turnOn
            if (turnOn) "Flashlight is on." else "Flashlight is off."
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling flashlight: ${e.message}", e)
            "Couldn't turn on the flashlight right now."
        }
    }

    fun setVolumePercent(context: Context, percent: Int): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val clamped = percent.coerceIn(0, 100)
            val targetVol = (maxVol * (clamped / 100f)).toInt()

            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
            "Volume set to $clamped%."
        } catch (e: Exception) {
            Log.e(TAG, "Volume control error: ${e.message}")
            "Couldn't adjust the volume right now."
        }
    }

    fun getVolume(context: Context): Int {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) (current * 100) / max else 0
        } catch (_: Exception) {
            50
        }
    }

    fun adjustVolume(context: Context, increase: Boolean): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            if (increase) "Volume turned up." else "Volume turned down."
        } catch (e: Exception) {
            "Couldn't change the volume."
        }
    }

    fun setRingerMode(context: Context, mode: String): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            when (mode.lowercase()) {
                "silent" -> {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                    "Your phone is now on silent."
                }
                "vibrate" -> {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    "Vibration mode is on."
                }
                else -> {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    "Ringer is back to normal."
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ringer mode error: ${e.message}")
            "Couldn't change the ringer mode."
        }
    }

    fun openConnectivity(context: Context, type: String): String {
        return try {
            val intent = when (type.lowercase()) {
                "bluetooth" -> {
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                }
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                    } else {
                        Intent(Settings.ACTION_WIFI_SETTINGS)
                    }
                }
            }.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opening $type settings for you."
        } catch (e: Exception) {
            "Couldn't open $type settings."
        }
    }

    fun getBatteryInfo(context: Context): BatteryInfo {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = context.registerReceiver(null, filter)

            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val percent = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else 100

            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: 0
            val isPlugged = plugged > 0

            BatteryInfo(percent, isCharging, isPlugged)
        } catch (e: Exception) {
            Log.e(TAG, "Battery status read error: ${e.message}")
            BatteryInfo(100, isCharging = false, isPlugged = false)
        }
    }

    fun getVolumePercent(context: Context): Int {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) (current * 100 / max.toFloat()).toInt() else 0
        } catch (e: Exception) {
            50
        }
    }
}
