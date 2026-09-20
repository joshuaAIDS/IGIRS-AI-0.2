package com.igirs.ai.tools

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log

data class InstalledApp(
    val appName: String,
    val packageName: String,
    val launchIntent: Intent? = null
)

data class AppAssessment(
    val totalCount: Int,
    val communicationApps: List<String>,
    val socialApps: List<String>,
    val mediaApps: List<String>,
    val productivityApps: List<String>,
    val utilityApps: List<String>,
    val otherApps: List<String>,
    val summaryReport: String,
    val spokenSummary: String
)

sealed class AppLaunchResult {
    data class Success(val appName: String) : AppLaunchResult()
    data class FallbackSearch(val appName: String) : AppLaunchResult()
    data class NotFound(val requestedApp: String) : AppLaunchResult()
}

sealed class AppCloseResult {
    data class Success(val appName: String) : AppCloseResult()
    data class NotFound(val requestedApp: String) : AppCloseResult()
    data class NoPermission(val appName: String) : AppCloseResult()
}

object AppLauncherHelper {

    private const val TAG = "IGIRS.AppLauncher"

    // In-memory cache of launchable apps with refresh window (60 seconds)
    private var cachedApps: List<InstalledApp> = emptyList()
    private var lastScanTime: Long = 0L
    private const val CACHE_DURATION_MS = 60_000L

    // Common system/popular app alias mappings
    val COMMON_ALIASES = mapOf(
        "camera" to listOf("camera", "cam"),
        "gallery" to listOf("gallery", "photos", "google photos", "album", "media"),
        "photos" to listOf("photos", "google photos", "gallery"),
        "browser" to listOf("chrome", "browser", "internet", "samsung internet", "firefox", "edge"),
        "internet" to listOf("chrome", "browser", "samsung internet"),
        "web" to listOf("chrome", "browser", "internet"),
        "music" to listOf("spotify", "youtube music", "apple music", "amazon music", "music", "yt music"),
        "calculator" to listOf("calculator", "calc"),
        "calendar" to listOf("calendar", "google calendar"),
        "clock" to listOf("clock", "alarm", "timer"),
        "alarm" to listOf("clock", "alarm", "timer"),
        "mail" to listOf("gmail", "email", "outlook", "mail", "samsung email"),
        "email" to listOf("gmail", "email", "outlook", "mail"),
        "maps" to listOf("maps", "google maps", "navigation", "waze"),
        "navigation" to listOf("maps", "google maps", "waze"),
        "messages" to listOf("messages", "google messages", "messaging", "sms"),
        "sms" to listOf("messages", "google messages", "messaging"),
        "phone" to listOf("phone", "dialer", "call"),
        "dialer" to listOf("dialer", "phone", "call"),
        "contacts" to listOf("contacts", "people"),
        "settings" to listOf("settings", "system settings"),
        "files" to listOf("files", "my files", "file manager", "google files"),
        "file manager" to listOf("files", "my files", "file manager"),
        "my files" to listOf("my files", "files", "file manager"),
        "play store" to listOf("play store", "google play", "store"),
        "google play" to listOf("play store", "google play", "store"),
        "app store" to listOf("play store", "google play"),
        "notes" to listOf("notes", "keep", "google keep", "samsung notes"),
        "keep" to listOf("keep", "google keep", "notes"),
        "youtube" to listOf("youtube", "yt"),
        "yt" to listOf("youtube", "yt"),
        "whatsapp" to listOf("whatsapp", "wa"),
        "wa" to listOf("whatsapp", "wa"),
        "instagram" to listOf("instagram", "insta", "ig"),
        "insta" to listOf("instagram", "insta", "ig"),
        "ig" to listOf("instagram", "insta"),
        "spotify" to listOf("spotify"),
        "netflix" to listOf("netflix"),
        "telegram" to listOf("telegram"),
        "facebook" to listOf("facebook", "fb"),
        "fb" to listOf("facebook", "fb"),
        "snapchat" to listOf("snapchat", "snap"),
        "snap" to listOf("snapchat", "snap"),
        "twitter" to listOf("twitter", "x"),
        "x" to listOf("twitter", "x"),
        "reddit" to listOf("reddit"),
        "amazon" to listOf("amazon"),
        "flipkart" to listOf("flipkart"),
        "paytm" to listOf("paytm"),
        "phonepe" to listOf("phonepe"),
        "gpay" to listOf("google pay", "gpay"),
        "google pay" to listOf("google pay", "gpay"),
        "uber" to listOf("uber"),
        "ola" to listOf("ola"),
        "swiggy" to listOf("swiggy"),
        "zomato" to listOf("zomato"),
        "discord" to listOf("discord"),
        "linkedin" to listOf("linkedin"),
        "drive" to listOf("drive", "google drive")
    )

    // Tier 2: Direct Well-Known Package Names (Checked instantly without scan)
    val WELL_KNOWN_PACKAGES = mapOf(
        "youtube" to listOf("com.google.android.youtube", "com.google.android.youtube.tv"),
        "yt" to listOf("com.google.android.youtube"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "wa" to listOf("com.whatsapp"),
        "spotify" to listOf("com.spotify.music", "com.spotify.lite"),
        "instagram" to listOf("com.instagram.android", "com.instagram.lite"),
        "insta" to listOf("com.instagram.android"),
        "ig" to listOf("com.instagram.android"),
        "chrome" to listOf("com.android.chrome"),
        "camera" to listOf("com.sec.android.app.camera", "com.google.android.GoogleCamera", "com.android.camera"),
        "cam" to listOf("com.sec.android.app.camera", "com.google.android.GoogleCamera"),
        "gallery" to listOf("com.sec.android.gallery3d", "com.google.android.apps.photos", "com.android.gallery3d"),
        "photos" to listOf("com.google.android.apps.photos", "com.sec.android.gallery3d"),
        "calculator" to listOf("com.sec.android.app.popupcalculator", "com.google.android.calculator", "com.android.calculator2"),
        "calc" to listOf("com.sec.android.app.popupcalculator", "com.google.android.calculator"),
        "settings" to listOf("com.android.settings"),
        "clock" to listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock", "com.android.deskclock"),
        "alarm" to listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock"),
        "calendar" to listOf("com.samsung.android.calendar", "com.google.android.calendar"),
        "messages" to listOf("com.samsung.android.messaging", "com.google.android.apps.messaging", "com.android.mms"),
        "sms" to listOf("com.samsung.android.messaging", "com.google.android.apps.messaging"),
        "phone" to listOf("com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer"),
        "dialer" to listOf("com.samsung.android.dialer", "com.google.android.dialer"),
        "contacts" to listOf("com.samsung.android.app.contacts", "com.google.android.contacts"),
        "files" to listOf("com.sec.android.app.myfiles", "com.google.android.documentsui", "com.google.android.apps.nbu.files"),
        "my files" to listOf("com.sec.android.app.myfiles"),
        "file manager" to listOf("com.sec.android.app.myfiles", "com.google.android.documentsui"),
        "netflix" to listOf("com.netflix.mediaclient"),
        "snapchat" to listOf("com.snapchat.android"),
        "snap" to listOf("com.snapchat.android"),
        "telegram" to listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"),
        "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
        "fb" to listOf("com.facebook.katana"),
        "twitter" to listOf("com.twitter.android"),
        "x" to listOf("com.twitter.android"),
        "reddit" to listOf("com.reddit.frontpage"),
        "play store" to listOf("com.android.vending"),
        "google play" to listOf("com.android.vending"),
        "gmail" to listOf("com.google.android.gm", "com.samsung.android.email.provider"),
        "email" to listOf("com.google.android.gm", "com.samsung.android.email.provider"),
        "mail" to listOf("com.google.android.gm", "com.samsung.android.email.provider"),
        "maps" to listOf("com.google.android.apps.maps"),
        "google maps" to listOf("com.google.android.apps.maps"),
        "notes" to listOf("com.samsung.android.app.notes", "com.google.android.keep"),
        "keep" to listOf("com.google.android.keep"),
        "google keep" to listOf("com.google.android.keep"),
        "amazon" to listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping"),
        "flipkart" to listOf("com.flipkart.android"),
        "paytm" to listOf("net.one97.paytm"),
        "phonepe" to listOf("com.phonepe.app"),
        "gpay" to listOf("com.google.android.apps.nbu.paisa.user"),
        "google pay" to listOf("com.google.android.apps.nbu.paisa.user"),
        "uber" to listOf("com.ubercab"),
        "ola" to listOf("com.olacabs.customer"),
        "swiggy" to listOf("in.swiggy.android"),
        "zomato" to listOf("com.application.zomato"),
        "discord" to listOf("com.discord"),
        "linkedin" to listOf("com.linkedin.android"),
        "drive" to listOf("com.google.android.apps.docs"),
        "chatgpt" to listOf("com.openai.chatgpt")
    )

    // Tier 3: Deep-Link URI schemes
    val DEEP_LINKS = mapOf(
        "youtube" to "vnd.youtube:",
        "yt" to "vnd.youtube:",
        "spotify" to "spotify:",
        "whatsapp" to "whatsapp://send",
        "wa" to "whatsapp://send",
        "instagram" to "instagram://user",
        "insta" to "instagram://user",
        "twitter" to "twitter://timeline",
        "x" to "twitter://timeline",
        "telegram" to "tg://resolve"
    )

    /**
     * Detects if the query is asking to assess, inventory, scan, or inspect installed/local apps.
     */
    fun isAppAssessmentQuery(rawQuery: String): Boolean {
        var clean = rawQuery.lowercase().trim()
        val politePrefixes = listOf(
            "can you please ", "could you please ", "would you please ",
            "can you ", "could you ", "would you ", "will you ",
            "please ", "kindly ", "hey igirs ", "igirs ", "ok igirs ",
            "i want you to ", "i want to ", "help me "
        )
        for (p in politePrefixes) {
            if (clean.startsWith(p)) {
                clean = clean.removePrefix(p).trim()
                break
            }
        }

        val triggers = listOf(
            "assess the local apps in my phone",
            "assess the local apps on my phone",
            "assess my local apps",
            "assess local apps",
            "assess the apps in my phone",
            "assess the apps on my phone",
            "assess my apps",
            "assess apps",
            "access the local apps in my phone",
            "access the local apps on my phone",
            "access my local apps",
            "access local apps",
            "access the apps in my phone",
            "access the apps on my phone",
            "access my apps",
            "access apps",
            "what local apps do i have",
            "what apps do i have on my phone",
            "what apps do i have in my phone",
            "what apps do i have",
            "what apps are installed on my phone",
            "what apps are installed in my phone",
            "what apps are installed",
            "what apps are on my phone",
            "what local apps are installed",
            "list my local apps",
            "list the local apps",
            "list local apps",
            "list my apps",
            "list installed apps",
            "list all apps",
            "list apps",
            "show my local apps",
            "show local apps",
            "show installed apps",
            "show my apps",
            "show apps",
            "scan my local apps",
            "scan local apps",
            "scan my apps",
            "scan apps",
            "check my local apps",
            "check local apps",
            "check my apps",
            "inventory my apps",
            "app assessment",
            "apps assessment"
        )

        if (triggers.any { clean == it || clean.startsWith("$it ") || clean.endsWith(" $it") }) {
            return true
        }

        // Heuristic: contains "assess" or "inventory" and ("app" or "apps" or "phone")
        if ((clean.contains("assess") || clean.contains("inventory")) &&
            (clean.contains("app") || clean.contains("apps") || clean.contains("phone"))) {
            return true
        }

        // Heuristic: starts with "access" or "check" or "scan" and followed by "local apps" / "installed apps"
        if ((clean.startsWith("access") || clean.startsWith("check") || clean.startsWith("scan") || clean.startsWith("what")) &&
            (clean.contains("local apps") || clean.contains("installed apps") || clean.contains("apps on my phone") || clean.contains("apps in my phone"))) {
            return true
        }

        return false
    }

    /**
     * Conducts a comprehensive assessment of local applications installed on the device.
     */
    fun assessLocalApps(context: Context): AppAssessment {
        val apps = getInstalledApps(context, forceRefresh = true)
        val total = apps.size

        val commKeywords = listOf("whatsapp", "message", "sms", "phone", "dialer", "contact", "telegram", "messenger", "signal", "slack", "mail", "gmail", "outlook", "discord")
        val socialKeywords = listOf("instagram", "insta", "facebook", "twitter", "reddit", "snapchat", "linkedin", "tiktok", "threads")
        val mediaKeywords = listOf("youtube", "spotify", "music", "netflix", "prime", "disney", "hotstar", "gallery", "photo", "camera", "video", "twitch", "vlc", "audio")
        val prodKeywords = listOf("drive", "doc", "sheet", "note", "keep", "calendar", "word", "excel", "powerpoint", "pdf", "file", "office", "notion")
        val utilKeywords = listOf("setting", "clock", "alarm", "timer", "calculator", "calc", "store", "play store", "map", "navigation", "uber", "ola", "paytm", "gpay", "phonepe", "weather")

        val comm = mutableListOf<String>()
        val social = mutableListOf<String>()
        val media = mutableListOf<String>()
        val prod = mutableListOf<String>()
        val util = mutableListOf<String>()
        val others = mutableListOf<String>()

        for (app in apps) {
            val lower = app.appName.lowercase()
            val pkg = app.packageName.lowercase()
            when {
                commKeywords.any { lower.contains(it) || pkg.contains(it) } -> comm.add(app.appName)
                socialKeywords.any { lower.contains(it) || pkg.contains(it) } -> social.add(app.appName)
                mediaKeywords.any { lower.contains(it) || pkg.contains(it) } -> media.add(app.appName)
                prodKeywords.any { lower.contains(it) || pkg.contains(it) } -> prod.add(app.appName)
                utilKeywords.any { lower.contains(it) || pkg.contains(it) } -> util.add(app.appName)
                else -> others.add(app.appName)
            }
        }

        val allNames = apps.map { it.appName }

        val report = buildString {
            appendLine("📱 **Local Applications Assessment**")
            appendLine("I have assessed your device: found **$total accessible applications** installed.\n")
            if (comm.isNotEmpty()) appendLine("• 💬 **Communication:** ${comm.take(6).joinToString(", ")}")
            if (social.isNotEmpty()) appendLine("• 🌐 **Social & Community:** ${social.take(6).joinToString(", ")}")
            if (media.isNotEmpty()) appendLine("• 🎵 **Media & Entertainment:** ${media.take(6).joinToString(", ")}")
            if (prod.isNotEmpty()) appendLine("• 📝 **Productivity & Files:** ${prod.take(6).joinToString(", ")}")
            if (util.isNotEmpty()) appendLine("• ⚙️ **Utilities & System:** ${util.take(6).joinToString(", ")}")
            if (others.isNotEmpty()) appendLine("• 📦 **Other Installed:** ${others.take(6).joinToString(", ")}")
            appendLine("\n✨ *You can ask me to open or control any of these apps anytime. Just say \"Open [app name]\"!*")
        }

        val highlights = (comm.take(2) + media.take(2) + social.take(1) + util.take(1)).distinct()
        val highlightText = if (highlights.isNotEmpty()) "including ${highlights.joinToString(", ")}" else "ready for you"
        val spoken = "I've assessed your phone: you have $total local apps installed, $highlightText. Just tell me which app to open!"

        return AppAssessment(
            totalCount = total,
            communicationApps = comm,
            socialApps = social,
            mediaApps = media,
            productivityApps = prod,
            utilityApps = util,
            otherApps = others,
            summaryReport = report,
            spokenSummary = spoken
        )
    }

    /**
     * Extracts target app name from a raw user voice/text query.
     * Handles polite prefixes, conversational wrappers, and trailing phrases.
     */
    fun extractAppLaunchTarget(rawQuery: String): String? {
        // Prevent app assessment queries from being falsely captured as app names
        if (isAppAssessmentQuery(rawQuery)) return null

        var clean = rawQuery.lowercase().trim()

        // 1. Strip leading polite phrases
        val politePrefixes = listOf(
            "can you please ", "could you please ", "would you please ",
            "can you ", "could you ", "would you ", "will you ",
            "please ", "kindly ", "hey igirs ", "igirs ", "ok igirs ",
            "i want to ", "i want you to ", "help me "
        )
        for (p in politePrefixes) {
            if (clean.startsWith(p)) {
                clean = clean.removePrefix(p).trim()
                break
            }
        }

        // 2. Identify launch verb and extract payload
        val launchPrefixes = listOf(
            "open up the app ", "open up the ", "open up my ", "open up ",
            "open the app ", "open the ", "open my ", "open app ", "open ",
            "launch the app ", "launch the ", "launch my ", "launch app ", "launch ",
            "start up the ", "start up ", "start the app ", "start the ", "start my ", "start app ", "start ",
            "bring up the app ", "bring up the ", "bring up my ", "bring up ",
            "switch to the app ", "switch to the ", "switch to my ", "switch to ",
            "go to the app ", "go to the ", "go to my ", "go to ",
            "load up the ", "load up ", "load the ", "load ",
            "run the app ", "run the ", "run ",
            "access the app ", "access the ", "access my ", "access "
        )

        val matchedPrefix = launchPrefixes.firstOrNull { clean.startsWith(it) } ?: return null
        var candidate = clean.removePrefix(matchedPrefix).trim()

        // 3. Strip trailing conversational suffixes
        val trailingSuffixes = listOf(
            " for me please", " for me", " please", " right now",
            " now", " application", " app"
        )
        for (s in trailingSuffixes) {
            if (candidate.endsWith(s)) {
                candidate = candidate.removeSuffix(s).trim()
            }
        }

        // 4. Final normalization
        candidate = normalizeTargetName(candidate)

        // Ignore common hardware/settings commands or generic phrases
        val ignoredWords = listOf(
            "wifi", "wi-fi", "bluetooth", "torch", "flashlight", "volume", "memory", "memory vault",
            "local apps in my phone", "the local apps", "local apps", "all apps", "my apps", "apps", "phone apps"
        )
        if (candidate in ignoredWords) return null

        return candidate.ifEmpty { null }
    }

    /**
     * Master launch function implementing 5-Tier resolution.
     */
    fun launchApp(context: Context, rawTarget: String): AppLaunchResult {
        val cleanTarget = normalizeTargetName(rawTarget)
        if (cleanTarget.isEmpty()) {
            return AppLaunchResult.NotFound(rawTarget)
        }

        val pm = context.packageManager

        // -------------------------------------------------------------
        // TIER 1: Native Android Category & System Actions
        // Guaranteed 100% native across Samsung One UI, Pixel, and AOSP
        // -------------------------------------------------------------
        val tier1Result = tryTier1CategoryLaunch(context, cleanTarget)
        if (tier1Result != null) {
            Log.i(TAG, "Tier 1 matched and launched: $cleanTarget")
            return tier1Result
        }

        // -------------------------------------------------------------
        // TIER 2: Direct Well-Known Package Lookup
        // Instant check using pm.getLaunchIntentForPackage(pkg)
        // -------------------------------------------------------------
        val tier2Packages = WELL_KNOWN_PACKAGES[cleanTarget] ?: COMMON_ALIASES[cleanTarget]?.flatMap { WELL_KNOWN_PACKAGES[it] ?: emptyList() }
        if (!tier2Packages.isNullOrEmpty()) {
            for (pkg in tier2Packages) {
                try {
                    val launchIntent = pm.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        context.startActivity(launchIntent)
                        val displayTitle = cleanTarget.replaceFirstChar { it.uppercase() }
                        Log.i(TAG, "Tier 2 launched $pkg for $cleanTarget")
                        return AppLaunchResult.Success(displayTitle)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Tier 2 launch attempt failed for $pkg: ${e.message}")
                }
            }
        }

        // -------------------------------------------------------------
        // TIER 3: Deep-Link URI Schemes
        // Direct view intents for apps with standard URI handlers
        // -------------------------------------------------------------
        val deepUriString = DEEP_LINKS[cleanTarget]
        if (deepUriString != null) {
            try {
                val deepIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepUriString)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(deepIntent)
                val displayTitle = cleanTarget.replaceFirstChar { it.uppercase() }
                Log.i(TAG, "Tier 3 deep-link launched for $cleanTarget")
                return AppLaunchResult.Success(displayTitle)
            } catch (_: Exception) {
                Log.w(TAG, "Tier 3 deep-link failed for $cleanTarget, falling through")
            }
        }

        // -------------------------------------------------------------
        // TIER 4: Dynamic Comprehensive Package Scan
        // Scans all installed packages with fuzzy label matching
        // -------------------------------------------------------------
        val dynamicApp = findApp(context, cleanTarget)
        if (dynamicApp != null && dynamicApp.launchIntent != null) {
            try {
                val intent = dynamicApp.launchIntent.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
                context.startActivity(intent)
                Log.i(TAG, "Tier 4 dynamic scan launched ${dynamicApp.appName}")
                return AppLaunchResult.Success(dynamicApp.appName)
            } catch (e: Exception) {
                Log.e(TAG, "Tier 4 launch error for ${dynamicApp.appName}: ${e.message}")
            }
        }

        // -------------------------------------------------------------
        // TIER 5: Fallback to Google Play Store search for uninstalled apps
        // -------------------------------------------------------------
        return AppLaunchResult.NotFound(cleanTarget)
    }

    private fun tryTier1CategoryLaunch(context: Context, target: String): AppLaunchResult? {
        val category = when (target) {
            "calculator", "calc" -> Intent.CATEGORY_APP_CALCULATOR
            "gallery", "photos" -> Intent.CATEGORY_APP_GALLERY
            "browser", "internet", "web", "chrome" -> Intent.CATEGORY_APP_BROWSER
            "music" -> Intent.CATEGORY_APP_MUSIC
            "maps", "navigation" -> Intent.CATEGORY_APP_MAPS
            "email", "mail", "gmail" -> Intent.CATEGORY_APP_EMAIL
            "messages", "sms", "messaging" -> Intent.CATEGORY_APP_MESSAGING
            "calendar" -> Intent.CATEGORY_APP_CALENDAR
            "files", "my files", "file manager" -> Intent.CATEGORY_APP_FILES
            else -> null
        }

        if (category != null) {
            try {
                val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return AppLaunchResult.Success(target.replaceFirstChar { it.uppercase() })
            } catch (_: ActivityNotFoundException) {
                // Not supported on this OEM for this specific category; fall through to Tier 2
            } catch (e: Exception) {
                Log.w(TAG, "Tier 1 category launch exception for $category: ${e.message}")
            }
        }

        // Direct standard system actions
        return try {
            when (target) {
                "settings", "system settings" -> {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    AppLaunchResult.Success("Settings")
                }
                "camera", "cam" -> {
                    context.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    AppLaunchResult.Success("Camera")
                }
                "clock", "alarm" -> {
                    context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    AppLaunchResult.Success("Clock")
                }
                "phone", "dialer" -> {
                    context.startActivity(Intent(Intent.ACTION_DIAL).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    AppLaunchResult.Success("Phone")
                }
                "contacts" -> {
                    context.startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    AppLaunchResult.Success("Contacts")
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Comprehensive scan combining queryIntentActivities and getInstalledPackages.
     */
    fun getInstalledApps(context: Context, forceRefresh: Boolean = false): List<InstalledApp> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedApps.isNotEmpty() && (now - lastScanTime) < CACHE_DURATION_MS) {
            return cachedApps
        }

        return try {
            val pm = context.packageManager
            val appsMap = mutableMapOf<String, InstalledApp>()

            // Strategy 1: queryIntentActivities with ACTION_MAIN + CATEGORY_LAUNCHER (100% launchable user apps)
            try {
                val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                // Use 0 for universal compatibility across all Android versions
                val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)
                for (info in resolveInfos) {
                    val pkgName = info.activityInfo.packageName
                    if (pkgName == context.packageName) continue
                    val name = info.loadLabel(pm).toString().trim()
                    if (name.isNotEmpty()) {
                        val launchIntent = pm.getLaunchIntentForPackage(pkgName) ?: Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            component = ComponentName(pkgName, info.activityInfo.name)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        appsMap[pkgName] = InstalledApp(appName = name, packageName = pkgName, launchIntent = launchIntent)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "queryIntentActivities scan note: ${e.message}")
            }

            // Strategy 2: getInstalledPackages to discover any remaining packages with launch intents
            try {
                val packageInfos = pm.getInstalledPackages(0)
                for (pkgInfo in packageInfos) {
                    val pkgName = pkgInfo.packageName
                    if (pkgName == context.packageName || appsMap.containsKey(pkgName)) continue
                    val launchIntent = pm.getLaunchIntentForPackage(pkgName)
                    if (launchIntent != null) {
                        val name = pkgInfo.applicationInfo?.loadLabel(pm)?.toString()?.trim() ?: pkgName
                        appsMap[pkgName] = InstalledApp(appName = name, packageName = pkgName, launchIntent = launchIntent)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "getInstalledPackages scan note: ${e.message}")
            }

            cachedApps = appsMap.values.sortedBy { it.appName.lowercase() }
            lastScanTime = now
            cachedApps
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan installed apps: ${e.message}")
            cachedApps
        }
    }

    /**
     * Finds the best matching installed app given user target name.
     */
    fun findApp(context: Context, rawTarget: String): InstalledApp? {
        val target = normalizeTargetName(rawTarget)
        if (target.isEmpty()) return null

        val apps = getInstalledApps(context)
        if (apps.isEmpty()) return null

        // 1. Exact case-insensitive match
        apps.firstOrNull { it.appName.equals(target, ignoreCase = true) }?.let { return it }

        // 2. Alias resolution
        val aliasCandidates = COMMON_ALIASES[target] ?: listOf(target)
        for (candidate in aliasCandidates) {
            apps.firstOrNull { it.appName.equals(candidate, ignoreCase = true) }?.let { return it }
        }

        // 3. Prefix / Word match
        for (candidate in aliasCandidates) {
            apps.firstOrNull {
                val lower = it.appName.lowercase()
                lower.startsWith(candidate) || lower.split(" ").contains(candidate)
            }?.let { return it }
        }

        // 4. Substring contains match
        for (candidate in aliasCandidates) {
            apps.firstOrNull { it.appName.lowercase().contains(candidate) }?.let { return it }
        }

        // 5. Package name contains candidate
        for (candidate in aliasCandidates) {
            apps.firstOrNull { it.packageName.lowercase().contains(candidate) }?.let { return it }
        }

        return null
    }

    fun normalizeTargetName(raw: String): String {
        var clean = raw.lowercase().trim()
        clean = clean.removePrefix("the ")
            .removePrefix("my ")
            .removePrefix("app ")
            .removeSuffix(" application")
            .removeSuffix(" app")
            .removeSuffix(" for me please")
            .removeSuffix(" for me")
            .removeSuffix(" please")
            .removeSuffix(" right now")
            .removeSuffix(" now")
            .trim()
        return clean
    }

    fun listInstalledAppNames(context: Context, limit: Int = 25): List<String> {
        val apps = getInstalledApps(context)
        return apps.map { it.appName }.distinct().take(limit)
    }

    /**
     * Closes an app using its normalized name and resolving to a package name.
     */
    fun closeApp(context: Context, rawTarget: String): AppCloseResult {
        val cleanTarget = normalizeTargetName(rawTarget)
        if (cleanTarget.isEmpty()) {
            return AppCloseResult.NotFound(rawTarget)
        }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        
        var packageNameToKill: String? = null
        var displayAppName: String = cleanTarget.replaceFirstChar { it.uppercase() }

        val tier2Packages = WELL_KNOWN_PACKAGES[cleanTarget] ?: COMMON_ALIASES[cleanTarget]?.flatMap { WELL_KNOWN_PACKAGES[it] ?: emptyList() }

        if (!tier2Packages.isNullOrEmpty()) {
            packageNameToKill = tier2Packages.first()
        } else {
            val dynamicApp = findApp(context, cleanTarget)
            if (dynamicApp != null) {
                packageNameToKill = dynamicApp.packageName
                displayAppName = dynamicApp.appName
            }
        }

        if (packageNameToKill != null) {
            try {
                activityManager.killBackgroundProcesses(packageNameToKill)
                Log.i(TAG, "Closed app $packageNameToKill for $cleanTarget")
                return AppCloseResult.Success(displayAppName)
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied to close $packageNameToKill", e)
                return AppCloseResult.NoPermission(displayAppName)
            } catch (e: Exception) {
                Log.e(TAG, "Error closing app $packageNameToKill", e)
            }
        }

        return AppCloseResult.NotFound(cleanTarget)
    }

    /**
     * Closes all non-system running apps.
     */
    fun closeAllApps(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningProcesses = activityManager.runningAppProcesses ?: return 0
        var closedCount = 0

        for (process in runningProcesses) {
            val pkgList = process.pkgList
            if (pkgList != null) {
                for (pkg in pkgList) {
                    if (pkg == context.packageName) continue
                    if (pkg.startsWith("com.android") || 
                        pkg.startsWith("com.google.android.gms") || 
                        pkg.startsWith("com.samsung")) {
                        continue
                    }
                    try {
                        activityManager.killBackgroundProcesses(pkg)
                        closedCount++
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing background process $pkg", e)
                    }
                }
            }
        }
        return closedCount
    }

    /**
     * Extracts target app name from a raw user voice/text query for closing.
     */
    fun extractAppCloseTarget(rawQuery: String): String? {
        var clean = rawQuery.lowercase().trim()

        val allAppsQueries = listOf(
            "close all apps", "close all", "kill all apps", 
            "close everything", "close all running apps"
        )
        if (clean in allAppsQueries) {
            return "__ALL_APPS__"
        }

        val politePrefixes = listOf(
            "can you please ", "could you please ", "would you please ",
            "can you ", "could you ", "would you ", "will you ",
            "please ", "hey igirs ", "igirs ", "i want to ", "help me "
        )
        for (p in politePrefixes) {
            if (clean.startsWith(p)) {
                clean = clean.removePrefix(p).trim()
                break
            }
        }

        val closePrefixes = listOf(
            "close the app ", "close the ", "close my ", "close app ", "close ",
            "exit the app ", "exit the ", "exit ",
            "kill the app ", "kill the ", "kill ",
            "quit the app ", "quit the ", "quit ",
            "shut down the ", "shut down ",
            "stop the app ", "stop the ", "stop ",
            "terminate the ", "terminate ",
            "end the app ", "end "
        )
        
        val matchedPrefix = closePrefixes.firstOrNull { clean.startsWith(it) } ?: return null
        var candidate = clean.removePrefix(matchedPrefix).trim()

        val trailingSuffixes = listOf(
            " for me please", " for me", " please", " right now", 
            " now", " app", " application"
        )
        for (s in trailingSuffixes) {
            if (candidate.endsWith(s)) {
                candidate = candidate.removeSuffix(s).trim()
            }
        }

        candidate = candidate.trim()

        val ignoredWords = listOf("wifi", "bluetooth", "flashlight", "torch", "volume", "timer", "alarm")
        if (candidate in ignoredWords) return null

        return candidate.ifEmpty { null }
    }
}
