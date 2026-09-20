package com.igirs.ai.security

import android.util.Log

sealed class SanitizationResult {
    data class Allowed(val cleanPrompt: String) : SanitizationResult()
    data class Blocked(val reason: String) : SanitizationResult()
}

object AIFirewall {

    private const val TAG = "IGIRS.AIFirewall"
    private const val MAX_PROMPT_LENGTH = 50_000

    // Prompt Injection, System Override & Jailbreak Patterns
    private val JAILBREAK_REGEXES = listOf(
        Regex("ignore\\s+(?:all|any|the|previous|prior|past|system|initial|your|above|earlier|\\s+)*\\s*(instructions|prompts|rules|commands)", RegexOption.IGNORE_CASE),
        Regex("disregard\\s+(?:all|any|the|previous|prior|past|system|initial|your|above|earlier|\\s+)*\\s*(instructions|prompts|rules|commands)?", RegexOption.IGNORE_CASE),
        Regex("system\\s+override", RegexOption.IGNORE_CASE),
        Regex("dan\\s+mode", RegexOption.IGNORE_CASE),
        Regex("developer\\s+mode\\s+(output|enabled|on|activate)", RegexOption.IGNORE_CASE),
        Regex("(reveal|print|show|output|leak|dump)\\s+(your|the)?\\s*(system\\s*prompt|initial\\s*instructions|api\\s*key|master\\s*key)", RegexOption.IGNORE_CASE),
        Regex("you\\s+are\\s+now\\s+in\\s+(god|unrestricted|jailbreak|debug)\\s+mode", RegexOption.IGNORE_CASE),
        Regex("act\\s+as\\s+(an?\\s+)?(unfiltered|evil|malicious|jailbroken|unrestricted)", RegexOption.IGNORE_CASE),
        Regex("pretend\\s+you\\s+have\\s+no\\s+(rules|guidelines|ethics|safety)", RegexOption.IGNORE_CASE)
    )

    // Secret credential patterns to prevent output leakage
    private val SENSITIVE_OUTPUT_REGEXES = listOf(
        Regex("nvapi-[a-zA-Z0-9_\\-]{20,}", RegexOption.IGNORE_CASE),
        Regex("AIza[0-9A-Za-z\\-_]{25,50}", RegexOption.IGNORE_CASE),
        Regex("Bearer\\s+[a-zA-Z0-9_\\-\\.]+", RegexOption.IGNORE_CASE),
        Regex("igirs_master_vault_key", RegexOption.IGNORE_CASE)
    )

    fun sanitizePrompt(rawInput: String): SanitizationResult {
        val trimmed = rawInput.trim()

        if (trimmed.isEmpty()) {
            return SanitizationResult.Allowed("")
        }

        // 1. Enforce length bounds to prevent context-stuffing DoS
        if (trimmed.length > MAX_PROMPT_LENGTH) {
            Log.w(TAG, "Input query exceeded maximum length boundary (${trimmed.length} chars).")
            return SanitizationResult.Blocked("Query exceeds maximum allowed length.")
        }

        // 2. Scan for Jailbreak / Prompt Injection patterns
        for (pattern in JAILBREAK_REGEXES) {
            if (pattern.containsMatchIn(trimmed)) {
                Log.w(TAG, "Prompt injection attempt intercepted: matched '${pattern.pattern}'")
                return SanitizationResult.Blocked("Security Protocol Active: System override or prompt injection attempt blocked.")
            }
        }

        // 3. Normalize whitespace and clean control characters
        val clean = trimmed
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "") // Remove ASCII control characters
            .trim()

        return SanitizationResult.Allowed(clean)
    }

    fun sanitizeOutput(rawReply: String): String {
        var clean = rawReply
        for (pattern in SENSITIVE_OUTPUT_REGEXES) {
            if (pattern.containsMatchIn(clean)) {
                Log.w(TAG, "Sensitive credential leak suppressed in LLM output!")
                clean = pattern.replace(clean, "[REDACTED_BY_SECURITY_FIREWALL]")
            }
        }
        return clean
    }
}
