package com.igirs.ai.routines

import android.content.Context
import com.igirs.ai.memory.MemoryManager
import com.igirs.ai.security.SecurityFirewall
import com.igirs.ai.tools.HardwareController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RoutineManager {

    fun executeMorningRoutine(context: Context): String {
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
        val now = Date()
        val timeStr = timeFormat.format(now)
        val dateStr = dateFormat.format(now)

        val userName = MemoryManager.userName
        val battery = HardwareController.getBatteryInfo(context)
        val batteryStr = if (battery.isCharging) {
            "Your battery is at ${battery.percentage}% and charging."
        } else {
            "Your battery is at ${battery.percentage}%."
        }

        val facts = MemoryManager.getUserFacts()
        val factsBriefing = when {
            facts.isEmpty() -> ""
            facts.size == 1 -> "Quick note from your memory: '${facts.first()}'."
            else -> {
                val highlights = facts.take(2).joinToString("; ") { "'$it'" }
                "You have a couple notes saved: $highlights."
            }
        }

        return "Good morning, $userName! It's $timeStr on $dateStr. $batteryStr $factsBriefing Hope you have a great day ahead! What can I do for you?"
    }

    fun executeNightRoutine(context: Context): String {
        val userName = MemoryManager.userName
        val battery = HardwareController.getBatteryInfo(context)
        val batteryWarning = if (battery.percentage < 40 && !battery.isCharging) {
            "Heads up, your battery is down to ${battery.percentage}%—might want to plug in before you go to sleep. "
        } else {
            ""
        }

        // 1. Turn off flashlight if on
        if (HardwareController.isFlashlightOn()) {
            HardwareController.setFlashlight(context, false)
        }

        // 2. Silence phone
        HardwareController.setRingerMode(context, "silent")

        return "Good night, $userName! ${batteryWarning}I've set your phone to silent so you won't be disturbed. Sleep well and have a restful night!"
    }

    fun executeSystemBriefing(context: Context): String {
        val timeFormat = SimpleDateFormat("h:mm a, EEEE, MMMM d", Locale.getDefault())
        val timeStr = timeFormat.format(Date())

        val battery = HardwareController.getBatteryInfo(context)
        val batteryStatus = if (battery.isCharging) "${battery.percentage}% and charging" else "${battery.percentage}%"

        val volume = HardwareController.getVolume(context)

        val secReport = SecurityFirewall.runDiagnostics(context)
        val secStatus = if (secReport.isSecure) "your 6-layer firewall is fully locked and secure" else "heads up: ${secReport.activeThreats.firstOrNull()} was detected"

        return "Everything is running smoothly! It's $timeStr. Your battery is at $batteryStatus, volume is at $volume%, and $secStatus. What's on your mind?"
    }

    fun getBatteryReport(context: Context): String {
        val battery = HardwareController.getBatteryInfo(context)
        return if (battery.isCharging) {
            "Your battery is at ${battery.percentage}% and charging."
        } else {
            "Your battery is at ${battery.percentage}%."
        }
    }
}
