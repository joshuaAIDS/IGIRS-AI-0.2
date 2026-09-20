package com.igirs.ai

import com.igirs.ai.tools.FileManagerHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileManagerUnitTest {

    @Test
    fun testHumanReadableSize() {
        assertEquals("500 B", FileManagerHelper.humanReadableSize(500L))
        assertTrue(FileManagerHelper.humanReadableSize(1024L).contains("1.0 KB"))
        assertTrue(FileManagerHelper.humanReadableSize(1024L * 1024L).contains("1.0 MB"))
        assertTrue(FileManagerHelper.humanReadableSize(1024L * 1024L * 50L).contains("50.0 MB"))
        assertTrue(FileManagerHelper.humanReadableSize(1024L * 1024L * 1024L * 2L).contains("2.0 GB"))
    }
}
