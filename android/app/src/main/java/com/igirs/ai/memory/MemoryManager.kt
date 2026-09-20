package com.igirs.ai.memory

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object MemoryManager {

    private const val PREFS_NAME = "igirs_ai_prefs"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_FACTS = "user_facts_json"
    private const val KEY_TTS_SPEECH_RATE = "tts_speech_rate"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "Joshua") ?: "Joshua"
        set(value) = prefs.edit().putString(KEY_USER_NAME, value.trim()).apply()

    var speechRate: Float
        get() = prefs.getFloat(KEY_TTS_SPEECH_RATE, 1.15f)
        set(value) = prefs.edit().putFloat(KEY_TTS_SPEECH_RATE, value).apply()

    private const val KEY_CONTINUOUS_LISTENING = "continuous_listening_enabled"

    var continuousListeningEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_CONTINUOUS_LISTENING, true) else true
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_CONTINUOUS_LISTENING, value).apply()
        }

    private val memoryCache = mutableListOf<String>()
    private var isCacheLoaded = false

    @Synchronized
    fun getUserFacts(): List<String> {
        if (isCacheLoaded) {
            return memoryCache.toList()
        }

        if (!::prefs.isInitialized) {
            return memoryCache.toList()
        }

        val raw = prefs.getString(KEY_FACTS, "[]") ?: "[]"
        val decrypted = com.igirs.ai.security.SecureKeystoreVault.decrypt(raw)
        val validJson = when {
            decrypted.isNotBlank() && decrypted.trim().startsWith("[") -> decrypted.trim()
            raw.trim().startsWith("[") -> raw.trim()
            else -> "[]"
        }

        val loaded = parseJsonFacts(validJson)

        memoryCache.clear()
        memoryCache.addAll(loaded)
        isCacheLoaded = true
        return memoryCache.toList()
    }

    @Synchronized
    fun addFact(fact: String) {
        val cleanFact = fact.trim()
            .trimStart(':', '-', '>', ' ', '"', '\'')
            .trimEnd('"', '\'')
            .trim()
        if (cleanFact.isEmpty()) return

        // Ensure cache is loaded
        getUserFacts()

        val exists = memoryCache.any { it.equals(cleanFact, ignoreCase = true) }
        if (!exists) {
            memoryCache.add(cleanFact)
            saveFacts(memoryCache)
        }
    }

    @Synchronized
    fun removeFact(target: String): Boolean {
        getUserFacts()
        val lowerTarget = target.lowercase().trim()
        val removed = memoryCache.removeAll {
            it.lowercase().contains(lowerTarget) || lowerTarget.contains(it.lowercase())
        }
        if (removed) {
            saveFacts(memoryCache)
        }
        return removed
    }

    @Synchronized
    fun clearFacts() {
        memoryCache.clear()
        isCacheLoaded = true
        if (::prefs.isInitialized) {
            prefs.edit().putString(KEY_FACTS, "[]").apply()
        }
    }

    private fun saveFacts(facts: List<String>) {
        val jsonStr = serializeFacts(facts)
        val encrypted = com.igirs.ai.security.SecureKeystoreVault.encrypt(jsonStr)
        if (::prefs.isInitialized) {
            val payload = if (encrypted.isNotBlank()) encrypted else jsonStr
            prefs.edit().putString(KEY_FACTS, payload).apply()
        }
    }

    private fun serializeFacts(facts: List<String>): String {
        return try {
            val json = JSONArray()
            facts.forEach { json.put(it) }
            val s = json.toString()
            if (!s.isNullOrEmpty() && s != "null") s else fallbackSerialize(facts)
        } catch (_: Exception) {
            fallbackSerialize(facts)
        }
    }

    private fun fallbackSerialize(facts: List<String>): String {
        return "[" + facts.joinToString(",") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" } + "]"
    }

    private fun parseJsonFacts(jsonStr: String): List<String> {
        val trimmed = jsonStr.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
        return try {
            val json = JSONArray(trimmed)
            val list = mutableListOf<String>()
            for (i in 0 until json.length()) {
                val item = json.optString(i, "").trim()
                if (item.isNotEmpty()) list.add(item)
            }
            if (list.isNotEmpty() || trimmed == "[]") list else fallbackParse(trimmed)
        } catch (_: Exception) {
            fallbackParse(trimmed)
        }
    }

    private fun fallbackParse(jsonStr: String): List<String> {
        val inner = jsonStr.trim().removePrefix("[").removeSuffix("]").trim()
        if (inner.isEmpty()) return emptyList()
        val regex = Regex("\"((?:\\\\\"|[^\"])*)\"")
        return regex.findAll(inner).map { match ->
            match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\").trim()
        }.filter { it.isNotBlank() }.toList()
    }
}
