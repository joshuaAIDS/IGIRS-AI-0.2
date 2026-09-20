package com.igirs.ai.tools

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

object ContactsHelper {

    private const val TAG = "IGIRS.Contacts"

    // Default fallback contacts known to the user
    private val fallbackContacts = mapOf(
        "mom" to "+917397411351",
        "mother" to "+917397411351",
        "josephine" to "+917397411351"
    )

    // Relationship aliases for smart contact matching
    private val relationshipAliases = mapOf(
        "mom" to listOf("mom", "mother", "mummy", "mama", "josephine"),
        "mother" to listOf("mom", "mother", "mummy", "mama", "josephine"),
        "mama" to listOf("mom", "mother", "mummy", "mama", "josephine"),
        "mummy" to listOf("mom", "mother", "mummy", "mama", "josephine"),
        "dad" to listOf("dad", "father", "papa", "daddy", "pops"),
        "father" to listOf("dad", "father", "papa", "daddy", "pops"),
        "papa" to listOf("dad", "father", "papa", "daddy"),
        "daddy" to listOf("dad", "father", "papa", "daddy"),
        "brother" to listOf("brother", "bro"),
        "bro" to listOf("brother", "bro"),
        "sister" to listOf("sister", "sis"),
        "sis" to listOf("sister", "sis"),
        "wife" to listOf("wife", "wifey"),
        "wifey" to listOf("wife", "wifey"),
        "husband" to listOf("husband", "hubby"),
        "hubby" to listOf("husband", "hubby")
    )

    private val numberWordMap = mapOf(
        "zero" to "0", "oh" to "0", "null" to "0",
        "one" to "1",
        "two" to "2", "to" to "2", "too" to "2",
        "three" to "3",
        "four" to "4", "for" to "4",
        "five" to "5",
        "six" to "6",
        "seven" to "7",
        "eight" to "8", "ate" to "8",
        "nine" to "9",
        "plus" to "+"
    )

    /**
     * Converts spoken digit words into actual numeric digits.
     * E.g.: "nine eight seven six five four three two one zero" -> "9876543210"
     * Also cleans dashes, spaces, and brackets.
     */
    fun convertSpokenWordsToDigits(query: String): String {
        val tokens = query.lowercase().trim().split("\\s+".toRegex())
        if (tokens.isEmpty()) return query

        // Check how many tokens are recognizable number words or digits
        val digitCount = tokens.count { token ->
            numberWordMap.containsKey(token) || token.all { it.isDigit() || it == '+' || it == '-' }
        }

        // If at least half the tokens look like numbers/digits, convert words to numbers
        if (digitCount >= (tokens.size / 2).coerceAtLeast(1)) {
            val sb = StringBuilder()
            for (token in tokens) {
                val mapped = numberWordMap[token]
                if (mapped != null) {
                    sb.append(mapped)
                } else {
                    val cleanDigits = token.filter { it.isDigit() || it == '+' }
                    if (cleanDigits.isNotEmpty()) {
                        sb.append(cleanDigits)
                    } else {
                        sb.append(token)
                    }
                }
            }
            val result = sb.toString()
            // If it formed a clean phone number, return it
            if (isPotentialPhoneNumber(result)) {
                return result
            }
        }

        return query
    }

    /**
     * Checks whether a string represents a valid direct phone number (>= 3 digits or starting with +)
     */
    fun isPotentialPhoneNumber(target: String): Boolean {
        val clean = cleanPhoneNumber(target)
        // Emergency numbers like 100, 911, 112, 108 or normal phone numbers
        return clean.length >= 3 && clean.count { it.isDigit() } >= 3
    }

    fun hasContactsPermission(context: Context?): Boolean {
        if (context == null) return false
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Resolves a phone number given a user contact query or raw number.
     */
    fun findPhoneNumber(context: Context? = null, contactQuery: String): String? {
        val rawConverted = convertSpokenWordsToDigits(contactQuery)
        val target = rawConverted.trim().lowercase()

        // 1. Direct phone number check
        if (isPotentialPhoneNumber(target)) {
            return cleanPhoneNumber(target)
        }

        // 2. Fallback contacts lookup for known nicknames
        if (fallbackContacts.containsKey(target)) {
            return fallbackContacts[target]
        }

        // 3. Check relationship aliases against fallback contacts
        val targetAliases = relationshipAliases[target] ?: listOf(target)
        for (alias in targetAliases) {
            if (fallbackContacts.containsKey(alias)) {
                return fallbackContacts[alias]
            }
        }

        // 4. Check Android system contacts if permission is granted
        if (context != null && hasContactsPermission(context)) {
            try {
                val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )

                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                    if (nameIdx == -1 || numIdx == -1) return@use

                    var exactMatch: String? = null
                    var aliasMatch: String? = null
                    var wordMatch: String? = null
                    var substringMatch: String? = null

                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIdx)?.trim()?.lowercase() ?: ""
                        val number = cursor.getString(numIdx) ?: continue
                        if (name.isEmpty() || number.isEmpty()) continue

                        val cleanedNum = cleanPhoneNumber(number)
                        if (cleanedNum.length < 3) continue

                        // Exact match
                        if (name == target) {
                            exactMatch = cleanedNum
                            break
                        }

                        // Relationship alias match (e.g. "Mom" in contacts when asking for "Mother")
                        if (aliasMatch == null && targetAliases.any { name == it }) {
                            aliasMatch = cleanedNum
                        }

                        // Word-level match (e.g. "John Doe" matches "John")
                        if (wordMatch == null) {
                            val words = name.split("\\s+".toRegex())
                            if (words.any { it == target || targetAliases.contains(it) }) {
                                wordMatch = cleanedNum
                            }
                        }

                        // Contains match
                        if (substringMatch == null && (name.contains(target) || target.contains(name))) {
                            substringMatch = cleanedNum
                        }
                    }

                    return exactMatch ?: aliasMatch ?: wordMatch ?: substringMatch
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying system contacts: ${e.message}", e)
            }
        }

        // 5. Final attempt: extract any embedded digits
        val digits = cleanPhoneNumber(target)
        if (digits.length >= 3 && digits.count { it.isDigit() } >= 3) {
            return digits
        }

        return null
    }

    fun cleanPhoneNumber(raw: String): String {
        return raw.filter { it.isDigit() || it == '+' }
    }

    /**
     * Extracts a call target from conversational queries.
     * Supports reverse phrases ("give Mom a call"), polite prefixes, spoken word numbers,
     * and direct digits.
     */
    fun extractCallTarget(rawQuery: String, context: Context? = null): CallTarget? {
        var clean = rawQuery.lowercase().trim()

        // 1. Strip polite conversational prefixes
        val politePrefixes = listOf(
            "can you please ", "could you please ", "would you please ",
            "can you ", "could you ", "would you ", "will you ",
            "please ", "kindly ", "hey igirs ", "igirs ", "ok igirs ",
            "i want you to ", "i want to ", "i need you to ", "i need to ", "help me "
        )
        for (p in politePrefixes) {
            if (clean.startsWith(p)) {
                clean = clean.removePrefix(p).trim()
                break
            }
        }

        // Check standalone call commands (e.g. "call", "make a call", "open dialer")
        val standaloneCallWords = listOf("call", "make a call", "make call", "dial", "open phone", "open dialer", "phone call")
        if (clean in standaloneCallWords) {
            return CallTarget(rawTarget = "", resolvedNumber = null, isDirectNumber = false, isBlankTarget = true)
        }

        // 2. Reverse pattern: "give [target] a call"
        var target: String? = null
        if (clean.startsWith("give ") && clean.endsWith(" a call")) {
            target = clean.removePrefix("give ").removeSuffix(" a call").trim()
        }

        // 3. Forward call prefixes
        if (target == null) {
            val callPrefixes = listOf(
                "make a phone call to ", "make a phone call ",
                "make a call to ", "make a call ",
                "place a phone call to ", "place a phone call ",
                "place a call to ", "place a call ",
                "give a call to ", "give a call ",
                "call to my ", "call to ",
                "call up my ", "call up ",
                "call on my ", "call on ",
                "call my ", "call ",
                "dial to ", "dial my ", "dial ",
                "phone to ", "phone my ", "phone "
            )
            val matchedPrefix = callPrefixes.firstOrNull { clean.startsWith(it) }
            if (matchedPrefix != null) {
                target = clean.removePrefix(matchedPrefix).trim()
            }
        }

        val foundTarget = target ?: return null
        var targetStr: String = foundTarget

        // 4. Strip trailing conversational suffixes
        val trailingSuffixes = listOf(
            " for me please", " for me", " please", " right now",
            " now", " on phone", " on call", " immediately"
        )
        for (s in trailingSuffixes) {
            if (targetStr.endsWith(s)) {
                targetStr = targetStr.removeSuffix(s).trim()
            }
        }

        targetStr = targetStr.removePrefix("my ").removePrefix("to ").trim()

        if (targetStr.isEmpty()) {
            return CallTarget(rawTarget = "", resolvedNumber = null, isDirectNumber = false, isBlankTarget = true)
        }

        // 5. Spoken word number conversion (e.g. "nine eight seven six...")
        val convertedNumber = convertSpokenWordsToDigits(targetStr)
        if (isPotentialPhoneNumber(convertedNumber)) {
            val cleanNum = cleanPhoneNumber(convertedNumber)
            return CallTarget(rawTarget = targetStr, resolvedNumber = cleanNum, isDirectNumber = true)
        }

        // 6. Direct phone number check
        if (isPotentialPhoneNumber(targetStr)) {
            val cleanNum = cleanPhoneNumber(targetStr)
            return CallTarget(rawTarget = targetStr, resolvedNumber = cleanNum, isDirectNumber = true)
        }

        // 7. Resolve against contacts
        val resolved = findPhoneNumber(context, targetStr)
        return CallTarget(rawTarget = targetStr, resolvedNumber = resolved, isDirectNumber = false)
    }
}
