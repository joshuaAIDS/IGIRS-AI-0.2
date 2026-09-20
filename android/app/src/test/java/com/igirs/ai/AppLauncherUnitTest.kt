package com.igirs.ai

import com.igirs.ai.tools.AppLauncherHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLauncherUnitTest {

    @Test
    fun testNormalizeTargetName() {
        assertEquals("spotify", AppLauncherHelper.normalizeTargetName("the spotify app"))
        assertEquals("calculator", AppLauncherHelper.normalizeTargetName("my calculator application"))
        assertEquals("camera", AppLauncherHelper.normalizeTargetName("app camera"))
        assertEquals("gallery", AppLauncherHelper.normalizeTargetName("  gallery app  "))
        assertEquals("instagram", AppLauncherHelper.normalizeTargetName("the instagram"))
        assertEquals("youtube", AppLauncherHelper.normalizeTargetName("youtube for me"))
        assertEquals("chrome", AppLauncherHelper.normalizeTargetName("chrome right now"))
    }

    @Test
    fun testCommonAliasesDefined() {
        val aliases = AppLauncherHelper.COMMON_ALIASES

        assertTrue(aliases.containsKey("camera"))
        assertTrue(aliases["camera"]!!.contains("cam"))

        assertTrue(aliases.containsKey("photos"))
        assertTrue(aliases["photos"]!!.contains("gallery"))

        assertTrue(aliases.containsKey("browser"))
        assertTrue(aliases["browser"]!!.contains("chrome"))

        assertTrue(aliases.containsKey("music"))
        assertTrue(aliases["music"]!!.contains("spotify"))

        assertTrue(aliases.containsKey("calculator"))
        assertTrue(aliases["calculator"]!!.contains("calc"))

        assertTrue(aliases.containsKey("youtube"))
        assertTrue(aliases["youtube"]!!.contains("yt"))

        assertTrue(aliases.containsKey("whatsapp"))
        assertTrue(aliases["whatsapp"]!!.contains("wa"))

        assertTrue(aliases.containsKey("instagram"))
        assertTrue(aliases["instagram"]!!.contains("insta"))

        assertTrue(aliases.containsKey("settings"))
        assertTrue(aliases.containsKey("files"))
        assertTrue(aliases.containsKey("maps"))
    }

    @Test
    fun testWellKnownPackagesDefined() {
        val packages = AppLauncherHelper.WELL_KNOWN_PACKAGES

        assertTrue(packages.containsKey("youtube"))
        assertTrue(packages["youtube"]!!.contains("com.google.android.youtube"))

        assertTrue(packages.containsKey("whatsapp"))
        assertTrue(packages["whatsapp"]!!.contains("com.whatsapp"))

        assertTrue(packages.containsKey("spotify"))
        assertTrue(packages["spotify"]!!.contains("com.spotify.music"))

        assertTrue(packages.containsKey("instagram"))
        assertTrue(packages["instagram"]!!.contains("com.instagram.android"))

        assertTrue(packages.containsKey("chrome"))
        assertTrue(packages["chrome"]!!.contains("com.android.chrome"))

        assertTrue(packages.containsKey("calculator"))
        assertTrue(packages["calculator"]!!.contains("com.sec.android.app.popupcalculator"))

        assertTrue(packages.containsKey("camera"))
        assertTrue(packages["camera"]!!.contains("com.sec.android.app.camera"))

        assertTrue(packages.containsKey("gallery"))
        assertTrue(packages["gallery"]!!.contains("com.sec.android.gallery3d"))

        assertTrue(packages.containsKey("settings"))
        assertTrue(packages["settings"]!!.contains("com.android.settings"))
    }

    @Test
    fun testDeepLinksDefined() {
        val links = AppLauncherHelper.DEEP_LINKS
        assertEquals("vnd.youtube:", links["youtube"])
        assertEquals("spotify:", links["spotify"])
        assertEquals("whatsapp://send", links["whatsapp"])
        assertEquals("instagram://user", links["instagram"])
    }

    @Test
    fun testConversationalAppLaunchExtractor() {
        assertEquals("spotify", AppLauncherHelper.extractAppLaunchTarget("open spotify"))
        assertEquals("youtube", AppLauncherHelper.extractAppLaunchTarget("can you open youtube"))
        assertEquals("calculator", AppLauncherHelper.extractAppLaunchTarget("please open up calculator"))
        assertEquals("camera", AppLauncherHelper.extractAppLaunchTarget("bring up the camera"))
        assertEquals("whatsapp", AppLauncherHelper.extractAppLaunchTarget("could you please open whatsapp for me"))
        assertEquals("instagram", AppLauncherHelper.extractAppLaunchTarget("hey igirs launch instagram right now"))
        assertEquals("chrome", AppLauncherHelper.extractAppLaunchTarget("start the chrome app please"))
        assertEquals("settings", AppLauncherHelper.extractAppLaunchTarget("go to settings"))
        assertEquals("clash of clans", AppLauncherHelper.extractAppLaunchTarget("open clash of clans"))
        assertEquals("netflix", AppLauncherHelper.extractAppLaunchTarget("load up netflix for me"))
        assertEquals("files", AppLauncherHelper.extractAppLaunchTarget("bring up my files"))

        // Dedicated hardware/system actions should be excluded
        assertNull(AppLauncherHelper.extractAppLaunchTarget("turn on wifi"))
        assertNull(AppLauncherHelper.extractAppLaunchTarget("open wifi"))
        assertNull(AppLauncherHelper.extractAppLaunchTarget("open flashlight"))
        assertNull(AppLauncherHelper.extractAppLaunchTarget("open torch"))
        assertNull(AppLauncherHelper.extractAppLaunchTarget("open memory vault"))
    }
}
