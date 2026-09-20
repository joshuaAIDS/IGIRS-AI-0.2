package com.igirs.ai.tools

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.igirs.ai.routines.RoutineManager
import com.igirs.ai.tools.AppCloseResult
import com.igirs.ai.tools.FileManagerHelper
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class ToolResult {
    data class Executed(val spokenFeedback: String) : ToolResult()
    data class StopListening(val spokenFeedback: String) : ToolResult()
    object NotMatched : ToolResult()
}

data class CallTarget(
    val rawTarget: String,
    val resolvedNumber: String?,
    val isDirectNumber: Boolean,
    val isBlankTarget: Boolean = false
)

class MobileToolsDispatcher(private val context: Context) {

    suspend fun checkAndExecute(userQuery: String): ToolResult {
        val query = userQuery.lowercase().trim()

        // 0. Stop Listening Command
        if (query == "stop listening" || query == "stop listen" || query == "mute mic" || query == "standby" || query == "cancel") {
            return ToolResult.StopListening("Standing by! Just say my name whenever you need me.")
        }

        // 1. Voice Memory Vault & Personal Knowledge (Context-free, instant local execution)
        val memoryResult = handleMemoryVault(userQuery, query)
        if (memoryResult != null) {
            return memoryResult
        }

        // 0.1 Morning Routine
        if (query == "good morning" || query.contains("good morning") || query == "morning routine" || query == "start my day" || query == "wake up jarvis" || query == "wake up") {
            val response = RoutineManager.executeMorningRoutine(context)
            return ToolResult.Executed(response)
        }

        // 0.2 Night Routine
        if (query == "good night" || query.contains("good night") || query == "night routine" || query == "sleep mode" || query == "going to sleep" || query == "going to bed" || query == "bedtime") {
            val response = RoutineManager.executeNightRoutine(context)
            return ToolResult.Executed(response)
        }

        // 0.3 System Diagnostic Briefing
        if (query == "status report" || query == "system status" || query == "brief me" || query == "daily briefing" || query == "full briefing") {
            val response = RoutineManager.executeSystemBriefing(context)
            return ToolResult.Executed(response)
        }

        // 0.4 Battery Status Query
        if (query.contains("battery status") || query.contains("battery level") || query == "what is my battery" || query == "battery" || query.contains("how much battery") || query.contains("battery percentage")) {
            val response = RoutineManager.getBatteryReport(context)
            return ToolResult.Executed(response)
        }

        // 0.5 Real-Time Time & Date Query
        if (query.contains("what time") || query.contains("current time") || query.contains("what is the time") ||
            query == "time" || query.contains("today's date") || query.contains("what is the date") ||
            query.contains("what date is it") || query.contains("what day is it") || query.contains("what day is today") || query == "what's the time") {
            val now = Date()
            val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
            val dateFmt = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
            val feedback = if (query.contains("date") || query.contains("day")) {
                "Today is ${dateFmt.format(now)}, and the time is ${timeFmt.format(now)}."
            } else {
                "It's ${timeFmt.format(now)}."
            }
            return ToolResult.Executed(feedback)
        }

        // 2. Hardware: Flashlight / Torch
        if (query.contains("flashlight") || query.contains("torch")) {
            if (query.contains("on") || query.contains("enable") || query.contains("activate")) {
                val feedback = HardwareController.setFlashlight(context, true)
                return ToolResult.Executed(feedback)
            } else if (query.contains("off") || query.contains("disable") || query.contains("deactivate")) {
                val feedback = HardwareController.setFlashlight(context, false)
                return ToolResult.Executed(feedback)
            }
        }

        // 3. Hardware: Volume Control
        if (query.contains("volume") || query == "mute" || query == "unmute") {
            val volumeResult = handleVolume(query)
            if (volumeResult != null) return volumeResult
        }

        // 4. Hardware: Silent / Vibrate Mode
        if (query.contains("silent mode") || query.contains("mute phone") || query.contains("vibrate mode") || query.contains("normal mode")) {
            val mode = when {
                query.contains("silent") || query.contains("mute") -> "silent"
                query.contains("vibrate") -> "vibrate"
                else -> "normal"
            }
            val feedback = HardwareController.setRingerMode(context, mode)
            return ToolResult.Executed(feedback)
        }

        // 5. Hardware: Connectivity (Wi-Fi & Bluetooth)
        if (query.contains("bluetooth")) {
            val feedback = HardwareController.openConnectivity(context, "bluetooth")
            return ToolResult.Executed(feedback)
        }
        if (query.contains("wifi") || query.contains("wi-fi") || query.contains("internet settings")) {
            val feedback = HardwareController.openConnectivity(context, "wifi")
            return ToolResult.Executed(feedback)
        }

        // 5.5 Phone Call Intent (High-priority conversational telephony matching)
        val callTarget = extractCallTarget(userQuery)
        if (callTarget != null) {
            return handleCall(callTarget)
        }

        // 6. Local Installed App Access & Assessment Intent
        val appResult = handleAppLaunch(userQuery, query)
        if (appResult != null) {
            return appResult
        }

        // 6.5 App Closing Intent
        val closeTarget = AppLauncherHelper.extractAppCloseTarget(query)
        if (closeTarget != null) {
            if (closeTarget == "__ALL_APPS__") {
                val count = AppLauncherHelper.closeAllApps(context)
                com.igirs.ai.assistant.IgirsAccessibilityService.closeForegroundApp()
                return ToolResult.Executed("Done! I've closed $count background apps and sent you to the home screen.")
            }
            return when (val result = AppLauncherHelper.closeApp(context, closeTarget)) {
                is AppCloseResult.Success -> {
                    com.igirs.ai.assistant.IgirsAccessibilityService.closeForegroundApp()
                    ToolResult.Executed("Closed ${result.appName} for you.")
                }
                is AppCloseResult.NotFound -> {
                    ToolResult.Executed("I couldn't find '${closeTarget.replaceFirstChar { it.uppercase() }}' running on your phone.")
                }
                is AppCloseResult.NoPermission -> {
                    ToolResult.Executed("I don't have permission to close ${result.appName}.")
                }
            }
        }

        // 6.7 Local File Access
        val fileResult = handleFileAccess(userQuery, query)
        if (fileResult != null) return fileResult

        // 7. WhatsApp Intent
        if (query.contains("whatsapp") || query.startsWith("send message to") || query.startsWith("message to")) {
            return handleWhatsApp(query)
        }

        // 9. YouTube Music / Video Direct Playback
        if (query.startsWith("play ") || (query.contains("youtube") && query.contains("play"))) {
            return handleMediaPlay(query)
        }

        // 10. Timer Intent
        if (query.contains("timer for") || query.startsWith("set timer") || query.contains("set a timer")) {
            return handleTimer(query)
        }

        // 11. Alarm Intent
        if (query.contains("alarm for") || query.startsWith("set alarm") || query.contains("set an alarm")) {
            return handleAlarm(query)
        }

        // 12. Web Search Intent
        if (query.startsWith("search for ") || query.startsWith("google ") || query.startsWith("search google for ")) {
            return handleSearch(query)
        }

        return ToolResult.NotMatched
    }

    private fun handleMemoryVault(rawQuery: String, lowerQuery: String): ToolResult? {
        // A. Clear / Reset Memory Vault
        val clearTriggers = listOf(
            "clear memory", "clear my memory", "clear memory vault", "clear my memory vault",
            "clear facts", "clear all facts", "clear all memories", "delete memories",
            "delete all memories", "wipe memory", "wipe memories", "reset memory",
            "reset memory vault", "forget everything", "forget all facts", "forget facts",
            "forget memory", "erase memory", "erase memories"
        )
        if (clearTriggers.any { lowerQuery == it || lowerQuery == "please $it" }) {
            com.igirs.ai.memory.MemoryManager.clearFacts()
            return ToolResult.Executed("Your memory vault has been cleared. Starting fresh with a blank slate!")
        }

        // B. Query / Show / List Memory Vault
        val listTriggers = listOf(
            "what do you remember", "what do you remember about me", "what is in my memory",
            "what's in my memory", "what is in my memory vault", "what's in my memory vault",
            "what's in the memory vault", "what is in the memory vault", "show my memory vault",
            "show memory vault", "show my memories", "show memories", "open memory vault",
            "list my memories", "list memories", "list facts", "list my facts", "list my notes",
            "what are my saved facts", "what are my memories", "what facts do you have",
            "what have you saved", "what did i tell you to remember", "what did i ask you to remember",
            "check memory", "check my memory", "check memory vault", "do you remember anything",
            "what do you know about me", "view memory vault", "view memories", "memory vault"
        )
        if (listTriggers.any { lowerQuery == it || lowerQuery.startsWith("$it ") || lowerQuery == "please $it" }) {
            val facts = com.igirs.ai.memory.MemoryManager.getUserFacts()
            val response = if (facts.isEmpty()) {
                "Your memory vault is currently empty! Just say 'remember that...' and I'll keep track of it for you."
            } else {
                val bulletList = facts.mapIndexed { idx, item -> "${idx + 1}. $item" }.joinToString("\n")
                "Here's what I have saved in your memory vault:\n$bulletList\n\nYou can say 'clear memory' anytime to reset."
            }
            return ToolResult.Executed(response)
        }

        // C. Forget a specific memory / fact
        val forgetPrefixes = listOf(
            "forget that ", "forget ", "remove from memory ", "remove memory ",
            "delete memory ", "delete fact ", "remove fact "
        )
        for (prefix in forgetPrefixes) {
            if (lowerQuery.startsWith(prefix)) {
                val target = lowerQuery.removePrefix(prefix).trim()
                if (target.isNotEmpty() && target != "everything" && target != "facts" && target != "memory" && target != "all") {
                    val removed = com.igirs.ai.memory.MemoryManager.removeFact(target)
                    return if (removed) {
                        ToolResult.Executed("I've removed that from your memory vault.")
                    } else {
                        ToolResult.Executed("I couldn't find '$target' in your memory vault.")
                    }
                }
            }
        }

        // D. Save to Memory Vault
        val savePrefixes = listOf(
            "remember that ", "remember: ", "remember ",
            "please remember that ", "please remember: ", "please remember ",
            "can you remember that ", "can you remember: ", "can you remember ",
            "could you remember that ", "could you remember ",
            "save to memory that ", "save to memory: ", "save to memory ",
            "save to my memory that ", "save to my memory ",
            "save that ", "save note that ", "save note: ",
            "note down that ", "note down: ", "note down ",
            "take a note that ", "take a note: ", "take a note ",
            "keep in mind that ", "keep in mind: ", "keep in mind ",
            "don't forget that ", "dont forget that ", "don't forget ", "dont forget ",
            "store in memory that ", "store in memory: ", "store in memory ",
            "add to memory that ", "add to memory: ", "add to memory ",
            "memory vault remember ", "memory vault: ", "memory vault "
        )

        for (prefix in savePrefixes) {
            if (lowerQuery.startsWith(prefix)) {
                val rawFact = if (rawQuery.length >= prefix.length) {
                    rawQuery.substring(prefix.length).trim()
                } else {
                    lowerQuery.removePrefix(prefix).trim()
                }

                val cleanFact = rawFact
                    .trimStart(':', '-', '>', ' ', '"', '\'')
                    .trimEnd('"', '\'')
                    .trim()

                if (cleanFact.isNotEmpty()) {
                    com.igirs.ai.memory.MemoryManager.addFact(cleanFact)
                    return ToolResult.Executed("Got it! I've saved that in your memory vault: \"$cleanFact\"")
                } else {
                    return ToolResult.Executed("What would you like me to remember?")
                }
            }
        }

        // E. Direct question lookup against Memory Vault
        val questionKeywords = listOf("where", "what", "when", "who", "which", "how")
        val isQuestion = questionKeywords.any { lowerQuery.startsWith("$it ") } || lowerQuery.endsWith("?")
        if (isQuestion) {
            val facts = com.igirs.ai.memory.MemoryManager.getUserFacts()
            if (facts.isNotEmpty()) {
                val queryWords = lowerQuery
                    .replace(Regex("[^a-zA-Z0-9 ]"), " ")
                    .split("\\s+".toRegex())
                    .filter { it.length > 2 && it !in listOf("where", "what", "when", "who", "which", "how", "the", "are", "did", "put", "was", "has", "can", "you", "tell", "show") }

                if (queryWords.isNotEmpty()) {
                    val match = facts.firstOrNull { fact ->
                        val lowerFact = fact.lowercase()
                        val matchCount = queryWords.count { lowerFact.contains(it) }
                        matchCount >= 1 && (matchCount >= (queryWords.size / 2).coerceAtLeast(1))
                    }
                    if (match != null) {
                        return ToolResult.Executed("According to your memory vault: $match")
                    }
                }
            }
        }

        return null
    }

    private fun handleAppLaunch(rawQuery: String, lowerQuery: String): ToolResult? {
        // 1. Comprehensive Local App Assessment & Inventory Intent
        if (AppLauncherHelper.isAppAssessmentQuery(rawQuery) || AppLauncherHelper.isAppAssessmentQuery(lowerQuery)) {
            val assessment = AppLauncherHelper.assessLocalApps(context)
            val fullFeedback = "${assessment.spokenSummary}\n\n${assessment.summaryReport}"
            return ToolResult.Executed(fullFeedback)
        }

        // 2. Quick List / Query Installed Apps
        val listTriggers = listOf(
            "what apps do i have", "what apps are installed", "list my apps", "list apps",
            "show installed apps", "show my apps", "show apps", "what apps can you open",
            "what can you open"
        )
        if (listTriggers.any { lowerQuery == it || lowerQuery == "please $it" }) {
            val assessment = AppLauncherHelper.assessLocalApps(context)
            return ToolResult.Executed("${assessment.spokenSummary}\n\n${assessment.summaryReport}")
        }

        // 2. Check if specific app is installed: "do i have [app]", "is [app] installed"
        if (lowerQuery.startsWith("do i have ") || (lowerQuery.startsWith("is ") && lowerQuery.contains(" installed"))) {
            val candidate = lowerQuery
                .removePrefix("do i have ")
                .removePrefix("is ")
                .removeSuffix(" installed")
                .removeSuffix(" app installed")
                .removeSuffix(" installed on my phone")
                .trim()
            if (candidate.isNotEmpty()) {
                val app = AppLauncherHelper.findApp(context, candidate)
                return if (app != null) {
                    ToolResult.Executed("Yes, ${app.appName} is installed on your phone. Say 'open ${app.appName}' to launch it!")
                } else {
                    ToolResult.Executed("I didn't find '$candidate' among your installed apps.")
                }
            }
        }

        // 3. Launch App Intent (Conversational & 5-Tier Resolution)
        val candidate = AppLauncherHelper.extractAppLaunchTarget(rawQuery) ?: return null

        // Pass-through checks for composite actions:
        if (candidate.startsWith("whatsapp") && (rawQuery.contains("message") || rawQuery.contains("send ") || rawQuery.contains("tell "))) {
            return null // Route to dedicated WhatsApp message composer
        }
        if (rawQuery.contains("play ") && (candidate == "youtube" || candidate == "spotify" || candidate == "music")) {
            return null // Route to dedicated media playback
        }

        return when (val result = AppLauncherHelper.launchApp(context, candidate)) {
            is AppLaunchResult.Success -> {
                ToolResult.StopListening("Opening ${result.appName} for you.")
            }
            is AppLaunchResult.FallbackSearch -> {
                ToolResult.Executed("Opening ${result.appName}...")
            }
            is AppLaunchResult.NotFound -> {
                val nonAppWords = listOf("door", "window", "mouth", "mind", "eyes", "heart", "folder", "link", "url", "browser tab")
                if (nonAppWords.any { candidate.contains(it) }) {
                    null
                } else {
                    ToolResult.Executed("I couldn't find '${candidate.replaceFirstChar { it.uppercase() }}' installed on your phone.")
                }
            }
        }
    }

    private fun handleVolume(query: String): ToolResult? {
        if (query == "mute") {
            return ToolResult.Executed(HardwareController.setVolumePercent(context, 0))
        }
        if (query == "unmute") {
            return ToolResult.Executed(HardwareController.setVolumePercent(context, 60))
        }
        if (query.contains("up") || query.contains("increase") || query.contains("raise")) {
            return ToolResult.Executed(HardwareController.adjustVolume(context, true))
        }
        if (query.contains("down") || query.contains("decrease") || query.contains("lower")) {
            return ToolResult.Executed(HardwareController.adjustVolume(context, false))
        }

        // Look for numbers like "50%", "80 percent", "70"
        val clean = query.replace("%", "").replace("percent", "")
        for (part in clean.split(" ")) {
            val num = part.toIntOrNull()
            if (num != null && num in 0..100) {
                return ToolResult.Executed(HardwareController.setVolumePercent(context, num))
            }
        }
        return null
    }

    private fun handleWhatsApp(query: String): ToolResult {
        try {
            var targetContact = ""
            var messageText = ""

            var text = query.trim()

            val triggers = listOf(
                "send a whatsapp message to",
                "send whatsapp message to",
                "send a message to",
                "send message to",
                "whatsapp message to",
                "message to",
                "whatsapp to",
                "tell",
                "message",
                "whatsapp"
            )

            for (trigger in triggers) {
                if (text.lowercase().startsWith(trigger)) {
                    text = text.substring(trigger.length).trim()
                    break
                }
            }

            if (text.contains("in whatsapp", ignoreCase = true)) {
                targetContact = text.substringBefore("in whatsapp").trim()
                messageText = text.substringAfter("in whatsapp").trim()
            } else if (text.contains("on whatsapp", ignoreCase = true)) {
                targetContact = text.substringBefore("on whatsapp").trim()
                messageText = text.substringAfter("on whatsapp").trim()
            } else if (text.contains("via whatsapp", ignoreCase = true)) {
                targetContact = text.substringBefore("via whatsapp").trim()
                messageText = text.substringAfter("via whatsapp").trim()
            } else if (text.contains("saying", ignoreCase = true)) {
                targetContact = text.substringBefore("saying").trim()
                messageText = text.substringAfter("saying").trim()
            } else if (text.contains("that", ignoreCase = true)) {
                targetContact = text.substringBefore("that").trim()
                messageText = text.substringAfter("that").trim()
            } else {
                val words = text.split("\\s+".toRegex())
                if (words.size >= 2) {
                    if (words[0].equals("my", ignoreCase = true) && words.size >= 3) {
                        targetContact = "${words[0]} ${words[1]}"
                        messageText = words.subList(2, words.size).joinToString(" ")
                    } else {
                        targetContact = words[0]
                        messageText = words.subList(1, words.size).joinToString(" ")
                    }
                } else if (words.size == 1) {
                    targetContact = words[0]
                    messageText = ""
                }
            }

            targetContact = targetContact
                .removePrefix("my ")
                .removePrefix("to ")
                .removeSuffix(" in whatsapp")
                .removeSuffix(" on whatsapp")
                .trim()

            messageText = messageText
                .removePrefix("saying ")
                .removePrefix("that ")
                .trim()

            // Resolve contact phone number
            val phone = if (targetContact.isNotEmpty()) {
                ContactsHelper.findPhoneNumber(context, targetContact)
            } else null

            val uriBuilder = StringBuilder("https://api.whatsapp.com/send?")
            if (phone != null) {
                // Strip '+' or non-digits for WhatsApp URL
                val cleanPhone = phone.filter { it.isDigit() }
                uriBuilder.append("phone=").append(cleanPhone)
            }

            if (messageText.isNotEmpty()) {
                if (phone != null) uriBuilder.append("&")
                uriBuilder.append("text=").append(URLEncoder.encode(messageText, "UTF-8"))
            }

            val uri = Uri.parse(uriBuilder.toString())
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Arm auto-send if message text is present
            if (messageText.isNotEmpty()) {
                com.igirs.ai.assistant.IgirsAccessibilityService.requestAutoSend()
            }

            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // Try without package if WhatsApp Business or mod
                val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            }

            val recipient = if (targetContact.isNotEmpty()) targetContact else "your contact"
            val isA11yActive = com.igirs.ai.assistant.IgirsAccessibilityService.isEnabled(context)
            val spoken = if (messageText.isNotEmpty()) {
                if (isA11yActive) {
                    "Sending that WhatsApp message to $recipient."
                } else {
                    "Drafting WhatsApp message for $recipient. Turn on WhatsApp Auto-Send in IGIRS settings to send automatically."
                }
            } else {
                "Opening WhatsApp chat with $recipient."
            }
            return ToolResult.Executed(spoken)
        } catch (e: Exception) {
            Log.e(TAG, "WhatsApp error: ${e.message}", e)
            return ToolResult.Executed("Couldn't open WhatsApp right now.")
        }
    }

    /**
     * Extracts a call target from conversational queries.
     * Supports reverse phrases ("give Mom a call"), polite prefixes, spoken word numbers,
     * and direct digits by delegating to ContactsHelper.
     */
    fun extractCallTarget(rawQuery: String): CallTarget? {
        return ContactsHelper.extractCallTarget(rawQuery, context)
    }


    private fun handleCall(target: CallTarget): ToolResult {
        if (target.isBlankTarget) {
            return try {
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                ToolResult.Executed("Opening the phone dialer.")
            } catch (e: Exception) {
                ToolResult.Executed("Couldn't open the phone app.")
            }
        }

        val phone = target.resolvedNumber
        if (phone != null) {
            val hasCallPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            val displayName = if (target.isDirectNumber) phone else target.rawTarget.replaceFirstChar { it.uppercase() }

            return try {
                if (hasCallPermission) {
                    // ACTION_CALL directly initiates the phone call without stopping at dialer
                    val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(callIntent)
                    ToolResult.Executed("Calling $displayName now.")
                } else {
                    // Fallback to dialer if CALL_PHONE permission is pending
                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(dialIntent)
                    ToolResult.Executed("Opening the dialer to call $displayName.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Call execution error: ${e.message}", e)
                ToolResult.Executed("Couldn't place the call right now.")
            }
        }

        // If target was unknown or cannot be resolved, open dialer pre-populated with target
        val displayName = target.rawTarget.replaceFirstChar { it.uppercase() }
        return try {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(target.rawTarget)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            ToolResult.Executed("I couldn't find '$displayName' in your contacts. Opening the dialer for you.")
        } catch (e: Exception) {
            try {
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                ToolResult.Executed("Opening the dialer.")
            } catch (e2: Exception) {
                ToolResult.Executed("Couldn't open the phone app.")
            }
        }
    }

    private suspend fun handleMediaPlay(query: String): ToolResult {
        val song = query
            .removePrefix("play")
            .removePrefix("on youtube")
            .removePrefix("in youtube")
            .removePrefix("on spotify")
            .removeSuffix("on youtube")
            .removeSuffix("in youtube")
            .removeSuffix("on spotify")
            .trim()

        if (song.isEmpty()) {
            return ToolResult.Executed("What song or video would you like to play?")
        }

        // 1. First attempt: resolve first video ID to launch direct video playback
        val videoId = YouTubeResolver.resolveFirstVideoId(song)
        if (videoId != null) {
            try {
                // Opening vnd.youtube:$id launches YouTube and starts playback immediately!
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return ToolResult.Executed("Playing $song on YouTube.")
            } catch (e: Exception) {
                // Fallback to browser watch link
                try {
                    val browserIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/watch?v=$videoId&autoplay=1")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(browserIntent)
                    return ToolResult.Executed("Playing $song for you.")
                } catch (e2: Exception) {
                    Log.e(TAG, "Error playing video: ${e2.message}")
                }
            }
        }

        // 2. Second attempt: Android Standard Media Playback Intent (auto-starts playback in default music app)
        try {
            val mediaIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                putExtra(SearchManager.QUERY, song)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(mediaIntent)
            return ToolResult.Executed("Playing $song.")
        } catch (e: Exception) {
            Log.w(TAG, "MEDIA_PLAY_FROM_SEARCH failed: ${e.message}")
        }

        // 3. Fallback: YouTube Search
        val searchUri = Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(song, "UTF-8"))
        val intent = Intent(Intent.ACTION_VIEW, searchUri).apply {
            setPackage("com.google.android.youtube")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, searchUri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(webIntent)
        }
        return ToolResult.Executed("Here's $song on YouTube.")
    }

    private fun handleTimer(query: String): ToolResult {
        val seconds = parseDurationSeconds(query)
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, "IGIRS Timer")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false) // false opens clock timer screen visibly and reliably
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val unitStr = if (seconds < 60) "$seconds seconds" else "${seconds / 60} minutes"
            ToolResult.Executed("Timer set for $unitStr.")
        } catch (e: Exception) {
            Log.e(TAG, "ACTION_SET_TIMER failed: ${e.message}")
            try {
                // Fallback: Open clock timers interface
                val clockIntent = Intent(AlarmClock.ACTION_SHOW_TIMERS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(clockIntent)
                ToolResult.Executed("Opening the timer for you.")
            } catch (e2: Exception) {
                ToolResult.Executed("Couldn't start the timer.")
            }
        }
    }

    private fun handleAlarm(query: String): ToolResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_MESSAGE, "IGIRS Alarm")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Executed("Setting your alarm.")
        } catch (e: Exception) {
            Log.e(TAG, "Set alarm error: ${e.message}")
            ToolResult.Executed("Couldn't set the alarm.")
        }
    }

    private fun handleSearch(query: String): ToolResult {
        val term = query
            .removePrefix("search google for ")
            .removePrefix("search for ")
            .removePrefix("google ")
            .trim()
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, term)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Executed("Here are the Google search results for $term.")
        } catch (e: Exception) {
            ToolResult.Executed("Couldn't open web search.")
        }
    }

    private fun parseDurationSeconds(query: String): Int {
        val words = query.split(" ")
        for (i in words.indices) {
            val num = words[i].toIntOrNull()
            if (num != null && i + 1 < words.size) {
                val unit = words[i + 1].lowercase()
                return when {
                    unit.startsWith("second") || unit == "sec" || unit == "secs" -> num
                    unit.startsWith("minute") || unit == "min" || unit == "mins" -> num * 60
                    unit.startsWith("hour") || unit == "hr" || unit == "hrs" -> num * 3600
                    else -> num
                }
            }
        }
        return 300 // default 5 minutes
    }

    private fun handleFileAccess(rawQuery: String, lowerQuery: String): ToolResult? {
        // List files in a directory
        val listFileTriggers = listOf(
            "list my files", "list files", "show my files", "show files",
            "what files do i have", "what's in my downloads", "show my downloads",
            "list my downloads", "list downloads", "show downloads"
        )
        if (listFileTriggers.any { lowerQuery == it || lowerQuery.startsWith("$it ") }) {
            if (!FileManagerHelper.hasFileAccess(context)) {
                return ToolResult.Executed("I need file access permission. Please go to Settings > Apps > IGIRS AI > Permissions and enable 'All files access'.")
            }
            val dir = if (lowerQuery.contains("download")) "Download" else null
            val files = FileManagerHelper.listFiles(context, dir, limit = 15)
            return if (files.isEmpty()) {
                ToolResult.Executed("I didn't find any files in that location.")
            } else {
                ToolResult.Executed("Here are your files:\n" + FileManagerHelper.formatFileListing(files))
            }
        }

        // Search for a file
        val searchPrefixes = listOf(
            "find file ", "find my file ", "search for file ", "search file ",
            "find ", "look for ", "where is ", "locate "
        )
        val fileSearchSuffixes = listOf(" file", " document", " in my files", " in downloads", " in my downloads")
        for (prefix in searchPrefixes) {
            if (lowerQuery.startsWith(prefix) && (lowerQuery.contains("file") || lowerQuery.contains("document") || lowerQuery.contains("download") || lowerQuery.contains("resume") || lowerQuery.contains("pdf") || lowerQuery.contains("photo"))) {
                if (!FileManagerHelper.hasFileAccess(context)) {
                    return ToolResult.Executed("I need file access permission to search your files. Go to Settings > Apps > IGIRS AI > Permissions and enable 'All files access'.")
                }
                var searchQuery = lowerQuery.removePrefix(prefix).trim()
                for (suffix in fileSearchSuffixes) {
                    searchQuery = searchQuery.removeSuffix(suffix).trim()
                }
                if (searchQuery.isEmpty()) return null
                val files = FileManagerHelper.searchFiles(context, searchQuery)
                return if (files.isEmpty()) {
                    ToolResult.Executed("I couldn't find any files matching '$searchQuery'.")
                } else {
                    ToolResult.Executed("Found ${files.size} file(s) matching '$searchQuery':\n" + FileManagerHelper.formatFileListing(files))
                }
            }
        }

        // Read a file
        if (lowerQuery.startsWith("read file ") || lowerQuery.startsWith("open file ") || lowerQuery.startsWith("show file ") || lowerQuery.startsWith("read my ")) {
            if (!FileManagerHelper.hasFileAccess(context)) {
                return ToolResult.Executed("I need file access permission to read your files.")
            }
            val fileName = lowerQuery
                .removePrefix("read file ").removePrefix("open file ")
                .removePrefix("show file ").removePrefix("read my ")
                .trim()
            if (fileName.isEmpty()) return null
            val files = FileManagerHelper.searchFiles(context, fileName, limit = 1)
            if (files.isEmpty()) {
                return ToolResult.Executed("I couldn't find a file named '$fileName'.")
            }
            val file = files.first()
            val content = FileManagerHelper.readFileContent(context, file.path)
            return if (content != null) {
                ToolResult.Executed("Contents of ${file.name} (${FileManagerHelper.humanReadableSize(file.size)}):\n\n$content")
            } else {
                ToolResult.Executed("Found ${file.name} (${FileManagerHelper.humanReadableSize(file.size)}) but I can't read this file type directly.")
            }
        }

        return null
    }

    companion object {
        private const val TAG = "IGIRS.MobileTools"
    }
}
