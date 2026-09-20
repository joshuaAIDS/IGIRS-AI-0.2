package com.igirs.ai

import com.igirs.ai.memory.MemoryManager
import com.igirs.ai.tools.MobileToolsDispatcher
import com.igirs.ai.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MemoryVaultUnitTest {

    private lateinit var dispatcher: MobileToolsDispatcher

    @Before
    fun setUp() {
        MemoryManager.clearFacts()
        val dummyContext = object : android.content.ContextWrapper(null) {}
        dispatcher = MobileToolsDispatcher(dummyContext)
    }

    @Test
    fun testMemoryManagerAddAndGet() {
        MemoryManager.clearFacts()
        assertTrue(MemoryManager.getUserFacts().isEmpty())

        MemoryManager.addFact("My car keys are on the kitchen table")
        val facts = MemoryManager.getUserFacts()
        assertEquals(1, facts.size)
        assertEquals("My car keys are on the kitchen table", facts[0])
    }

    @Test
    fun testDuplicatePrevention() {
        MemoryManager.clearFacts()
        MemoryManager.addFact("Joshua likes pizza")
        MemoryManager.addFact("joshua likes pizza") // duplicate case-insensitive
        MemoryManager.addFact("  Joshua likes pizza  ") // duplicate trimmed

        val facts = MemoryManager.getUserFacts()
        assertEquals(1, facts.size)
    }

    @Test
    fun testRemoveIndividualFact() {
        MemoryManager.clearFacts()
        MemoryManager.addFact("Meeting at 3 PM")
        MemoryManager.addFact("WiFi password is SecretPassword123")

        assertEquals(2, MemoryManager.getUserFacts().size)

        val removed = MemoryManager.removeFact("meeting")
        assertTrue(removed)

        val remaining = MemoryManager.getUserFacts()
        assertEquals(1, remaining.size)
        assertEquals("WiFi password is SecretPassword123", remaining[0])
    }

    @Test
    fun testClearAllFacts() {
        MemoryManager.clearFacts()
        MemoryManager.addFact("Fact 1")
        MemoryManager.addFact("Fact 2")
        assertEquals(2, MemoryManager.getUserFacts().size)

        MemoryManager.clearFacts()
        assertTrue(MemoryManager.getUserFacts().isEmpty())
    }

    @Test
    fun testPunctuationAndWhitespaceCleaning() {
        MemoryManager.clearFacts()
        MemoryManager.addFact(": - \"Joshua is an engineer\" ")

        val facts = MemoryManager.getUserFacts()
        assertEquals(1, facts.size)
        assertEquals("Joshua is an engineer", facts[0])
    }

    @Test
    fun testDispatcherRememberCommands() = runBlocking {
        MemoryManager.clearFacts()

        // 1. "remember that ..."
        val res1 = dispatcher.checkAndExecute("remember that my car keys are on the kitchen table")
        assertTrue(res1 is ToolResult.Executed)
        assertTrue((res1 as ToolResult.Executed).spokenFeedback.contains("kitchen table"))

        // 2. "please remember that ..."
        val res2 = dispatcher.checkAndExecute("please remember that I like iced latte")
        assertTrue(res2 is ToolResult.Executed)
        assertTrue((res2 as ToolResult.Executed).spokenFeedback.contains("iced latte"))

        // 3. "save to memory: ..."
        val res3 = dispatcher.checkAndExecute("save to memory: wifi password is Alpha123")
        assertTrue(res3 is ToolResult.Executed)
        assertTrue((res3 as ToolResult.Executed).spokenFeedback.contains("Alpha123"))

        val facts = MemoryManager.getUserFacts()
        assertEquals(3, facts.size)
    }

    @Test
    fun testDispatcherListAndLookupMemory() = runBlocking {
        MemoryManager.clearFacts()

        // Empty vault query
        val emptyRes = dispatcher.checkAndExecute("what is in my memory vault")
        assertTrue(emptyRes is ToolResult.Executed)
        assertTrue((emptyRes as ToolResult.Executed).spokenFeedback.contains("empty"))

        // Save facts
        dispatcher.checkAndExecute("remember that my car keys are on the kitchen table")
        dispatcher.checkAndExecute("save to memory: passport is in the blue backpack")

        // List memories
        val listRes = dispatcher.checkAndExecute("show my memories")
        assertTrue(listRes is ToolResult.Executed)
        val listFeedback = (listRes as ToolResult.Executed).spokenFeedback
        assertTrue(listFeedback.contains("kitchen table"))
        assertTrue(listFeedback.contains("blue backpack"))

        // Direct question lookup
        val lookupRes = dispatcher.checkAndExecute("where are my car keys?")
        assertTrue(lookupRes is ToolResult.Executed)
        assertTrue((lookupRes as ToolResult.Executed).spokenFeedback.contains("kitchen table"))

        // Direct question lookup for passport
        val lookupRes2 = dispatcher.checkAndExecute("where is my passport?")
        assertTrue(lookupRes2 is ToolResult.Executed)
        assertTrue((lookupRes2 as ToolResult.Executed).spokenFeedback.contains("blue backpack"))
    }

    @Test
    fun testDispatcherForgetAndClear() = runBlocking {
        MemoryManager.clearFacts()

        dispatcher.checkAndExecute("remember that appointment is at 4pm")
        dispatcher.checkAndExecute("remember that favorite food is sushi")
        assertEquals(2, MemoryManager.getUserFacts().size)

        // Forget appointment
        val forgetRes = dispatcher.checkAndExecute("forget that appointment")
        assertTrue(forgetRes is ToolResult.Executed)
        assertTrue((forgetRes as ToolResult.Executed).spokenFeedback.contains("removed"))
        assertEquals(1, MemoryManager.getUserFacts().size)

        // Clear all
        val clearRes = dispatcher.checkAndExecute("clear memory vault")
        assertTrue(clearRes is ToolResult.Executed)
        assertTrue(MemoryManager.getUserFacts().isEmpty())
    }
}
