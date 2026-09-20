package com.igirs.ai

import com.igirs.ai.tools.SearchResult
import com.igirs.ai.tools.WebSearchHelper
import com.igirs.ai.tools.WebSearchResponse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchUnitTest {

    @Test
    fun testShouldSearchWebHeuristics() {
        assertTrue(WebSearchHelper.shouldSearchWeb("what is the latest news on AI"))
        assertTrue(WebSearchHelper.shouldSearchWeb("who won the match today"))
        assertTrue(WebSearchHelper.shouldSearchWeb("current price of bitcoin"))
        assertTrue(WebSearchHelper.shouldSearchWeb("weather in Mumbai right now"))
        assertTrue(WebSearchHelper.shouldSearchWeb("search online for quantum computing"))
        assertTrue(WebSearchHelper.shouldSearchWeb("look up latest movies in 2026"))
        assertTrue(WebSearchHelper.shouldSearchWeb("who is the prime minister of UK"))
        assertTrue(WebSearchHelper.shouldSearchWeb("what happened to OpenAI"))

        // Standard personal / conversation prompts should not trigger web search
        assertFalse(WebSearchHelper.shouldSearchWeb("hello who are you"))
        assertFalse(WebSearchHelper.shouldSearchWeb("tell me a joke"))
        assertFalse(WebSearchHelper.shouldSearchWeb("remember that my keys are on the desk"))
    }

    @Test
    fun testFormatSearchContextForLLM() {
        val sampleResponse = WebSearchResponse(
            query = "latest Android release",
            results = listOf(
                SearchResult(
                    title = "Android 15 Released",
                    snippet = "Google has officially released Android 15 with new security features.",
                    url = "https://android.com/news"
                )
            ),
            instantAnswer = "Android 15 is the latest major release."
        )

        val formatted = WebSearchHelper.formatSearchContextForLLM(sampleResponse)
        assertTrue(formatted.contains("Android 15"))
        assertTrue(formatted.contains("Google has officially released"))
        assertTrue(formatted.contains("https://android.com/news"))
    }
}
