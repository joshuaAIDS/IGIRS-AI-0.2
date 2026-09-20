package com.igirs.ai

import com.igirs.ai.tools.AppLauncherHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppCloseUnitTest {

    @Test
    fun testExtractAppCloseTarget() {
        assertEquals("youtube", AppLauncherHelper.extractAppCloseTarget("close youtube"))
        assertEquals("spotify", AppLauncherHelper.extractAppCloseTarget("can you please exit spotify"))
        assertEquals("calculator", AppLauncherHelper.extractAppCloseTarget("kill calculator"))
        assertEquals("camera", AppLauncherHelper.extractAppCloseTarget("close the camera app please"))
        assertEquals("chrome", AppLauncherHelper.extractAppCloseTarget("shut down chrome"))
        assertEquals("instagram", AppLauncherHelper.extractAppCloseTarget("quit instagram"))
        assertEquals("whatsapp", AppLauncherHelper.extractAppCloseTarget("please close my whatsapp app"))
        assertEquals("netflix", AppLauncherHelper.extractAppCloseTarget("terminate netflix for me"))
    }

    @Test
    fun testExtractCloseAllApps() {
        assertEquals("__ALL_APPS__", AppLauncherHelper.extractAppCloseTarget("close all apps"))
        assertEquals("__ALL_APPS__", AppLauncherHelper.extractAppCloseTarget("close all"))
        assertEquals("__ALL_APPS__", AppLauncherHelper.extractAppCloseTarget("kill all apps"))
        assertEquals("__ALL_APPS__", AppLauncherHelper.extractAppCloseTarget("close everything"))
        assertEquals("__ALL_APPS__", AppLauncherHelper.extractAppCloseTarget("close all running apps"))
    }

    @Test
    fun testExcludedCommandsFromClose() {
        assertNull(AppLauncherHelper.extractAppCloseTarget("close wifi"))
        assertNull(AppLauncherHelper.extractAppCloseTarget("close bluetooth"))
        assertNull(AppLauncherHelper.extractAppCloseTarget("close flashlight"))
        assertNull(AppLauncherHelper.extractAppCloseTarget("close torch"))
        assertNull(AppLauncherHelper.extractAppCloseTarget("close volume"))
    }
}
