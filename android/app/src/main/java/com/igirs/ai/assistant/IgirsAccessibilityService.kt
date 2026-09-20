package com.igirs.ai.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class IgirsAccessibilityService : AccessibilityService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isPendingAutoSend) return
        if (event == null) return

        // Expire auto-send after 12 seconds to prevent accidental sends later
        if (System.currentTimeMillis() - autoSendTimestamp > 12000) {
            isPendingAutoSend = false
            return
        }

        val pkg = event.packageName?.toString() ?: ""
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val root = rootInActiveWindow ?: return

        try {
            // 1. Find by view ID (standard WhatsApp send button)
            val sendById = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
            if (sendById.isNotEmpty()) {
                for (node in sendById) {
                    if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "WhatsApp Send button clicked successfully via viewId!")
                        isPendingAutoSend = false
                        lastSendCompletedTimestamp = System.currentTimeMillis()
                        return
                    }
                }
            }

            // 2. Find by content description ("Send")
            val sendByDesc = root.findAccessibilityNodeInfosByText("Send")
            for (node in sendByDesc) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "WhatsApp Send button clicked successfully via content description!")
                    isPendingAutoSend = false
                    lastSendCompletedTimestamp = System.currentTimeMillis()
                    return
                }
            }

            // 3. Fallback recursive search for clickable send icon
            val clicked = clickSendRecursively(root)
            if (clicked) {
                Log.i(TAG, "WhatsApp Send button clicked via recursive node inspection!")
                isPendingAutoSend = false
                lastSendCompletedTimestamp = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking WhatsApp send button: ${e.message}", e)
        }
    }

    private fun clickSendRecursively(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""

        if (desc == "send" || desc.contains("send message") || text == "send") {
            if (node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else if (node.parent != null && node.parent.isClickable) {
                return node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickSendRecursively(child)) {
                return true
            }
        }
        return false
    }

    /**
     * Sends the user to the home screen, effectively closing the foreground app.
     */
    fun goHome(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (e: Exception) {
            Log.e(TAG, "goHome failed: ${e.message}")
            false
        }
    }

    /**
     * Opens the recent apps overview screen.
     */
    fun openRecents(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
        } catch (e: Exception) {
            Log.e(TAG, "openRecents failed: ${e.message}")
            false
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "IgirsAccessibilityService interrupted.")
    }

    companion object {
        private const val TAG = "IGIRS.Accessibility"

        @Volatile
        private var instance: IgirsAccessibilityService? = null

        private var isPendingAutoSend = false
        private var autoSendTimestamp = 0L
        private var lastSendCompletedTimestamp = 0L

        fun requestAutoSend(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastSendCompletedTimestamp < 3000) {
                Log.w(TAG, "Rate Limiter Active: Auto-send requests must be spaced by at least 3 seconds.")
                return false
            }
            isPendingAutoSend = true
            autoSendTimestamp = now
            Log.i(TAG, "Auto-send requested for WhatsApp.")
            return true
        }

        fun closeForegroundApp(): Boolean {
            return instance?.goHome() ?: false
        }

        fun openRecentApps(): Boolean {
            return instance?.openRecents() ?: false
        }

        fun isEnabled(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${IgirsAccessibilityService::class.java.canonicalName}"
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)

            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedServiceName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }
    }
}

