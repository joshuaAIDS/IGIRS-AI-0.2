package com.igirs.ai.security

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

data class SecurityReport(
    val isRooted: Boolean,
    val isDebuggerAttached: Boolean,
    val isTracerPidActive: Boolean,
    val isHookFrameworkDetected: Boolean,
    val isFridaDetected: Boolean,
    val isEmulator: Boolean,
    val isProxyOrVpnDetected: Boolean,
    val isTestKeysBuild: Boolean,
    val isSecure: Boolean,
    val activeThreats: List<String>
)

object SecurityFirewall {

    private const val TAG = "IGIRS.Firewall"

    private val KNOWN_ROOT_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/magisk/su",
        "/system/app/Superuser.apk",
        "/system/app/SuperSU.apk",
        "/system/app/Magisk.apk"
    )

    private val KNOWN_HOOK_LIBS = arrayOf(
        "frida-agent",
        "frida-gadget",
        "gadget.so",
        "libfrida",
        "xposed",
        "substrate",
        "libinject",
        "edxposed",
        "sandhook"
    )

    private val SUSPICIOUS_THREADS = arrayOf(
        "gum-js-loop",
        "gmain",
        "frida-server"
    )

    fun isDeviceRooted(): Boolean {
        // 1. Check Build Tags
        if (Build.TAGS != null && Build.TAGS.contains("test-keys")) {
            return true
        }

        // 2. Check known root binaries
        for (path in KNOWN_ROOT_PATHS) {
            try {
                if (File(path).exists()) return true
            } catch (_: Exception) {}
        }

        // 3. Scan /proc/mounts for Magisk/KernelSU mount masking
        try {
            val mountsFile = File("/proc/mounts")
            if (mountsFile.exists()) {
                mountsFile.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.contains("magisk") || line!!.contains("core/mirror") || line!!.contains("ksu")) {
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 4. Check execution of which su
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            process.inputStream.bufferedReader().use { it.readLine() != null }
        } catch (_: Exception) {
            false
        }
    }

    fun isDebuggerActive(): Boolean {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }

    fun isTracerPidActive(): Boolean {
        return try {
            val statusFile = File("/proc/self/status")
            if (statusFile.exists()) {
                BufferedReader(FileReader(statusFile)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.startsWith("TracerPid:")) {
                            val pidStr = line!!.substringAfter("TracerPid:").trim()
                            val pid = pidStr.toIntOrNull() ?: 0
                            return pid > 0
                        }
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun isFridaOrHookDetected(): Boolean {
        // 1. Check memory maps for injected dynamic libraries
        try {
            val mapsFile = File("/proc/self/maps")
            if (mapsFile.exists()) {
                BufferedReader(FileReader(mapsFile)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val lower = line!!.lowercase()
                        for (hook in KNOWN_HOOK_LIBS) {
                            if (lower.contains(hook)) {
                                Log.w(TAG, "Hook library detected in memory maps: $hook")
                                return true
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Check thread names in /proc/self/task
        try {
            val taskDir = File("/proc/self/task")
            if (taskDir.exists() && taskDir.isDirectory) {
                val tasks = taskDir.listFiles()
                if (tasks != null) {
                    for (task in tasks) {
                        val commFile = File(task, "comm")
                        if (commFile.exists()) {
                            val threadName = commFile.readText().trim()
                            for (suspicious in SUSPICIOUS_THREADS) {
                                if (threadName.contains(suspicious, ignoreCase = true)) {
                                    Log.w(TAG, "Suspicious hook thread detected: $threadName")
                                    return true
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Scan open TCP ports for Frida default server (27042 = 0x69A2, 27043 = 0x69A3)
        try {
            for (path in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
                val netFile = File(path)
                if (netFile.exists()) {
                    netFile.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (line!!.contains(":69A2") || line!!.contains(":69A3")) {
                                Log.w(TAG, "Frida active listening port detected in $path")
                                return true
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 4. Classloader check for Xposed / Substrate
        try {
            Class.forName("de.robv.android.xposed.XposedBridge")
            return true
        } catch (_: ClassNotFoundException) {}

        return false
    }

    fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk" == Build.PRODUCT
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.BOARD.lowercase().contains("nox")
                || Build.BOOTLOADER.lowercase().contains("nox"))
    }

    fun isProxyOrVpnActive(context: Context): Boolean {
        // 1. System HTTP Proxy Properties
        val host = System.getProperty("http.proxyHost")
        val port = System.getProperty("http.proxyPort")
        if (!host.isNullOrBlank() && !port.isNullOrBlank()) {
            return true
        }

        // 2. ConnectivityManager VPN Transport
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Exception) {
            false
        }
    }

    fun runDiagnostics(context: Context? = null): SecurityReport {
        val rooted = isDeviceRooted()
        val debugger = isDebuggerActive()
        val tracer = isTracerPidActive()
        val frida = isFridaOrHookDetected()
        val emulator = isEmulator()
        val proxyOrVpn = if (context != null) isProxyOrVpnActive(context) else false
        val testKeys = Build.TAGS != null && Build.TAGS.contains("test-keys")

        val threats = mutableListOf<String>()
        if (rooted) threats.add("Root / Superuser Access")
        if (debugger) threats.add("Active Debugger Attached")
        if (tracer) threats.add("PTRACE Process Inspection")
        if (frida) threats.add("Dynamic Hook / Frida Framework")
        if (emulator) threats.add("Virtual / Emulator Sandbox")
        if (proxyOrVpn) threats.add("Active Proxy / VPN Tunnel")
        if (testKeys) threats.add("Test-Keys Firmware Build")

        val isSecure = threats.isEmpty()

        Log.i(TAG, "Multi-Layer Security Diagnostic: Secure=$isSecure, Threats=${threats.joinToString(", ")}")
        return SecurityReport(
            isRooted = rooted,
            isDebuggerAttached = debugger,
            isTracerPidActive = tracer,
            isHookFrameworkDetected = frida,
            isFridaDetected = frida,
            isEmulator = emulator,
            isProxyOrVpnDetected = proxyOrVpn,
            isTestKeysBuild = testKeys,
            isSecure = isSecure,
            activeThreats = threats
        )
    }
}
