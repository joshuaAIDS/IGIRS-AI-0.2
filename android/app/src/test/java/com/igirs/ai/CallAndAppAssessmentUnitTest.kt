package com.igirs.ai

import com.igirs.ai.tools.AppLauncherHelper
import com.igirs.ai.tools.ContactsHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAndAppAssessmentUnitTest {

    @Test
    fun testConvertSpokenWordsToDigits() {
        assertEquals("9876543210", ContactsHelper.convertSpokenWordsToDigits("nine eight seven six five four three two one zero"))
        assertEquals("100", ContactsHelper.convertSpokenWordsToDigits("one zero zero"))
        assertEquals("911", ContactsHelper.convertSpokenWordsToDigits("nine one one"))
        assertEquals("112", ContactsHelper.convertSpokenWordsToDigits("one one two"))
        assertEquals("+917397411351", ContactsHelper.convertSpokenWordsToDigits("plus 91 7397411351"))

        // Regular words should remain unconverted
        assertEquals("mom", ContactsHelper.convertSpokenWordsToDigits("mom"))
        assertEquals("john doe", ContactsHelper.convertSpokenWordsToDigits("john doe"))
    }

    @Test
    fun testIsPotentialPhoneNumber() {
        assertTrue(ContactsHelper.isPotentialPhoneNumber("100"))
        assertTrue(ContactsHelper.isPotentialPhoneNumber("911"))
        assertTrue(ContactsHelper.isPotentialPhoneNumber("112"))
        assertTrue(ContactsHelper.isPotentialPhoneNumber("9876543210"))
        assertTrue(ContactsHelper.isPotentialPhoneNumber("+917397411351"))
        assertTrue(ContactsHelper.isPotentialPhoneNumber("+1-800-555-0199"))

        assertFalse(ContactsHelper.isPotentialPhoneNumber("mom"))
        assertFalse(ContactsHelper.isPotentialPhoneNumber("call"))
        assertFalse(ContactsHelper.isPotentialPhoneNumber("12")) // Too short
    }

    @Test
    fun testIsAppAssessmentQuery() {
        assertTrue(AppLauncherHelper.isAppAssessmentQuery("assess the local apps in my phone"))
        assertTrue(AppLauncherHelper.isAppAssessmentQuery("assess my local apps"))
        assertTrue(AppLauncherHelper.isAppAssessmentQuery("assess local apps"))
        assertTrue(AppLauncherHelper.isAppAssessmentQuery("assess the apps on my phone"))
        assertTrue(AppLauncherHelper.isAppAssessmentQuery("access the local apps in my phone"))
        assertTrue(AppLauncherHelper.isAppAssessmentQuery("access local apps"))
        assertTrue(AppLauncherHelper.isAppAssessmentQuery("access my apps"))
        assertTrue(AppLauncherHelper.isAppAssessmentQuery("what local apps do i have"))
        assertTrue(AppLauncherHelper.isAppAssessmentQuery("what apps are installed on my phone"))
        assertTrue(AppLauncherHelper.isAppAssessmentQuery("scan my apps"))
        assertTrue(AppLauncherHelper.isAppAssessmentQuery("check my local apps"))
        assertTrue(AppLauncherHelper.isAppAssessmentQuery("please assess the local apps in my phone"))
        assertTrue(AppLauncherHelper.isAppAssessmentQuery("can you access the local apps on my phone"))

        // Actual app launches must NOT be classified as assessment queries
        assertFalse(AppLauncherHelper.isAppAssessmentQuery("open spotify"))
        assertFalse(AppLauncherHelper.isAppAssessmentQuery("launch youtube"))
        assertFalse(AppLauncherHelper.isAppAssessmentQuery("access whatsapp"))
        assertFalse(AppLauncherHelper.isAppAssessmentQuery("access camera"))
    }

    @Test
    fun testExtractAppLaunchTargetWithAccess() {
        assertEquals("whatsapp", AppLauncherHelper.extractAppLaunchTarget("access whatsapp"))
        assertEquals("camera", AppLauncherHelper.extractAppLaunchTarget("access the camera"))
        assertEquals("spotify", AppLauncherHelper.extractAppLaunchTarget("access my spotify"))
        assertEquals("youtube", AppLauncherHelper.extractAppLaunchTarget("open youtube"))

        // Assessment queries should return null (preventing false app launch)
        assertNull(AppLauncherHelper.extractAppLaunchTarget("assess the local apps in my phone"))
        assertNull(AppLauncherHelper.extractAppLaunchTarget("access the local apps in my phone"))
        assertNull(AppLauncherHelper.extractAppLaunchTarget("access local apps"))
        assertNull(AppLauncherHelper.extractAppLaunchTarget("assess apps"))
    }

    @Test
    fun testExtractCallTargetConversationalPhrases() {
        // Direct context-free extraction testing using synthetic queries

        // 1. Basic call (resolves via fallback dictionary for "mom")
        val t1 = ContactsHelper.extractCallTarget("call Mom", null)
        assertNotNull(t1)
        assertEquals("mom", t1?.rawTarget)
        assertEquals("+917397411351", t1?.resolvedNumber)

        // 2. Polite prefix
        val t2 = ContactsHelper.extractCallTarget("can you please call 9876543210", null)
        assertNotNull(t2)
        assertTrue(t2?.isDirectNumber == true)
        assertEquals("9876543210", t2?.resolvedNumber)

        // 3. Reverse phrasing: "give [target] a call"
        val t3 = ContactsHelper.extractCallTarget("give Mom a call", null)
        assertNotNull(t3)
        assertEquals("mom", t3?.rawTarget)
        assertEquals("+917397411351", t3?.resolvedNumber)

        // 4. "make a call to" (target extracted as "dad", no contact permission without context)
        val t4 = ContactsHelper.extractCallTarget("make a call to Dad", null)
        assertNotNull(t4)
        assertEquals("dad", t4?.rawTarget)
        assertFalse(t4?.isDirectNumber == true)
        assertNull(t4?.resolvedNumber)

        // 5. Emergency number
        val t5 = ContactsHelper.extractCallTarget("dial 911", null)
        assertNotNull(t5)
        assertTrue(t5?.isDirectNumber == true)
        assertEquals("911", t5?.resolvedNumber)

        // 6. Spoken word number
        val t6 = ContactsHelper.extractCallTarget("call nine eight seven six five four three two one zero", null)
        assertNotNull(t6)
        assertTrue(t6?.isDirectNumber == true)
        assertEquals("9876543210", t6?.resolvedNumber)

        // 7. Blank target (opens dialer)
        val t7 = ContactsHelper.extractCallTarget("make a call", null)
        assertNotNull(t7)
        assertTrue(t7?.isBlankTarget == true)

        val t8 = ContactsHelper.extractCallTarget("call", null)
        assertNotNull(t8)
        assertTrue(t8?.isBlankTarget == true)

        // 8. Non-call queries should return null
        assertNull(ContactsHelper.extractCallTarget("what is the weather", null))
        assertNull(ContactsHelper.extractCallTarget("open youtube", null))
    }
}
