package com.igirs.ai

import com.igirs.ai.ui.ActiveAiModel
import com.igirs.ai.ui.MarkdownRenderer
import com.igirs.ai.ui.MessageSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererUnitTest {

    @Test
    fun testCodeBlockParsing() {
        val markdown = """Here is a Python function:
```python
def add(a, b):
    return a + b
```
Hope this helps!"""

        val segments = MarkdownRenderer.parseSegments(markdown)
        assertTrue(segments.size == 3)
        assertTrue(segments[0] is MessageSegment.Text)
        assertTrue(segments[1] is MessageSegment.Code)
        assertTrue(segments[2] is MessageSegment.Text)

        val codeSeg = segments[1] as MessageSegment.Code
        assertEquals("python", codeSeg.language)
        assertTrue(codeSeg.code.contains("def add(a, b):"))
        assertTrue(codeSeg.code.contains("return a + b"))
    }

    @Test
    fun testModelSelectorEnums() {
        assertEquals("openai/gpt-oss-20b", ActiveAiModel.FAST.modelId)
        assertEquals("openai/gpt-oss-120b", ActiveAiModel.REASONING.modelId)
        assertEquals("groq/compound", ActiveAiModel.INSTANT.modelId)
    }
}
