package com.igirs.ai

import com.igirs.ai.llm.GroqClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqClientUnitTest {

    @Test
    fun testGroqClientInstantiation() {
        val client = GroqClient()
        assertNotNull(client)
    }

    @Test
    fun testThinkTagRemoval() {
        val client = GroqClient()
        val method = GroqClient::class.java.getDeclaredMethod("cleanContent", String::class.java)
        method.isAccessible = true

        val withThink = "<think>Analyzing user query...</think>Hello Joshua, how can I assist you?"
        val cleaned = method.invoke(client, withThink) as String
        assertTrue(!cleaned.contains("<think>"))
        assertTrue(!cleaned.contains("</think>"))
        assertTrue(cleaned == "Hello Joshua, how can I assist you?")

        val unclosedThink = "Greeting before<think>Unclosed thinking..."
        val cleanedUnclosed = method.invoke(client, unclosedThink) as String
        assertTrue(cleanedUnclosed == "Greeting before")
    }

    @Test
    fun testKeyPoolCount() {
        val client = GroqClient()
        val field = GroqClient::class.java.getDeclaredField("apiKeys")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val keys = field.get(client) as List<String>

        // 5 dedicated mobile keys configured in key pool
        assertTrue(keys.size == 5)
        for (key in keys) {
            assertTrue(key.isNotEmpty())
        }
    }
}
