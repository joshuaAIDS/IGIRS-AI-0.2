package com.igirs.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.igirs.ai.R

sealed class MessageSegment {
    data class Text(val content: String) : MessageSegment()
    data class Code(val language: String, val code: String) : MessageSegment()
}

object MarkdownRenderer {

    /**
     * Splits raw markdown into prose text segments and code block segments.
     */
    fun parseSegments(raw: String): List<MessageSegment> {
        val segments = mutableListOf<MessageSegment>()
        val codeFenceRegex = Regex("```([a-zA-Z0-9_+-]*)\\s*\\n?([\\s\\S]*?)```")
        var lastIndex = 0

        for (match in codeFenceRegex.findAll(raw)) {
            val range = match.range
            if (range.first > lastIndex) {
                val textPart = raw.substring(lastIndex, range.first)
                if (textPart.isNotBlank()) {
                    segments.add(MessageSegment.Text(textPart.trimEnd()))
                }
            }

            val lang = match.groupValues[1].ifBlank { "code" }
            val code = match.groupValues[2].trimEnd()
            segments.add(MessageSegment.Code(lang, code))
            lastIndex = range.last + 1
        }

        if (lastIndex < raw.length) {
            val remaining = raw.substring(lastIndex)
            if (remaining.isNotBlank()) {
                segments.add(MessageSegment.Text(remaining))
            }
        }

        if (segments.isEmpty() && raw.isNotEmpty()) {
            segments.add(MessageSegment.Text(raw))
        }

        return segments
    }

    /**
     * Converts markdown syntax (headers, bold, italics, inline code, bullet points)
     * into a rich Android SpannableStringBuilder.
     */
    fun renderMarkdown(text: String): CharSequence {
        val builder = SpannableStringBuilder()
        val lines = text.split("\n")

        for ((idx, line) in lines.withIndex()) {
            val isHeader1 = line.startsWith("# ")
            val isHeader2 = line.startsWith("## ")
            val isHeader3 = line.startsWith("### ")
            val isBullet = line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") || line.trimStart().startsWith("• ")

            val cleanLine = when {
                isHeader1 -> line.removePrefix("# ")
                isHeader2 -> line.removePrefix("## ")
                isHeader3 -> line.removePrefix("### ")
                isBullet -> {
                    val indent = "    ".repeat((line.indexOfFirst { !it.isWhitespace() } / 2).coerceAtLeast(0))
                    val content = line.trimStart().removePrefix("- ").removePrefix("* ").removePrefix("• ")
                    "$indent• $content"
                }
                else -> line
            }

            val startPos = builder.length
            val lineSpannable = formatInlineMarkdown(cleanLine)
            builder.append(lineSpannable)

            // Apply line-level styles
            val endPos = builder.length
            if (isHeader1) {
                builder.setSpan(StyleSpan(Typeface.BOLD), startPos, endPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(RelativeSizeSpan(1.25f), startPos, endPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(ForegroundColorSpan(Color.parseColor("#FFFFFF")), startPos, endPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (isHeader2) {
                builder.setSpan(StyleSpan(Typeface.BOLD), startPos, endPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(RelativeSizeSpan(1.15f), startPos, endPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(ForegroundColorSpan(Color.parseColor("#00F0FF")), startPos, endPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (isHeader3) {
                builder.setSpan(StyleSpan(Typeface.BOLD), startPos, endPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(RelativeSizeSpan(1.05f), startPos, endPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(ForegroundColorSpan(Color.parseColor("#A0AEC0")), startPos, endPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            if (idx < lines.size - 1) {
                builder.append("\n")
            }
        }

        return builder
    }

    private fun formatInlineMarkdown(input: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder(input)

        // 1. Inline code: `code`
        val codeRegex = Regex("`([^`]+)`")
        var match = codeRegex.find(sb)
        while (match != null) {
            val start = match.range.first
            val end = match.range.last + 1
            val innerText = match.groupValues[1]

            sb.replace(start, end, innerText)
            val newEnd = start + innerText.length
            sb.setSpan(TypefaceSpan("monospace"), start, newEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(BackgroundColorSpan(Color.parseColor("#1C2538")), start, newEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(Color.parseColor("#00F0FF")), start, newEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            match = codeRegex.find(sb, newEnd)
        }

        // 2. Bold: **text**
        val boldRegex = Regex("\\*\\*([^*]+)\\*\\*")
        var boldMatch = boldRegex.find(sb)
        while (boldMatch != null) {
            val start = boldMatch.range.first
            val end = boldMatch.range.last + 1
            val innerText = boldMatch.groupValues[1]

            sb.replace(start, end, innerText)
            val newEnd = start + innerText.length
            sb.setSpan(StyleSpan(Typeface.BOLD), start, newEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            boldMatch = boldRegex.find(sb, newEnd)
        }

        // 3. Italics: *text*
        val italicRegex = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")
        var italicMatch = italicRegex.find(sb)
        while (italicMatch != null) {
            val start = italicMatch.range.first
            val end = italicMatch.range.last + 1
            val innerText = italicMatch.groupValues[1]

            sb.replace(start, end, innerText)
            val newEnd = start + innerText.length
            sb.setSpan(StyleSpan(Typeface.ITALIC), start, newEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            italicMatch = italicRegex.find(sb, newEnd)
        }

        return sb
    }

    /**
     * Renders all segments into a container LinearLayout dynamically.
     */
    fun populateMessageContainer(
        container: LinearLayout,
        rawContent: String,
        context: Context,
        onCopyCode: ((String) -> Unit)? = null
    ) {
        container.removeAllViews()
        val segments = parseSegments(rawContent)
        val inflater = LayoutInflater.from(context)

        for (seg in segments) {
            when (seg) {
                is MessageSegment.Text -> {
                    val tv = TextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 4, 0, 4)
                        }
                        setTextColor(Color.parseColor("#E2E8F0"))
                        textSize = 15f
                        setLineSpacing(12f, 1f)
                        text = renderMarkdown(seg.content)
                        setTextIsSelectable(true)
                    }
                    container.addView(tv)
                }
                is MessageSegment.Code -> {
                    val codeView = inflater.inflate(R.layout.item_code_block, container, false)
                    val tvLang = codeView.findViewById<TextView>(R.id.tvCodeLanguage)
                    val tvCode = codeView.findViewById<TextView>(R.id.tvCodeContent)
                    val btnCopy = codeView.findViewById<View>(R.id.btnCopyCode)

                    tvLang.text = seg.language.lowercase()
                    tvCode.text = seg.code

                    btnCopy.setOnClickListener {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Code", seg.code)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Code copied to clipboard!", Toast.LENGTH_SHORT).show()
                        onCopyCode?.invoke(seg.code)
                    }

                    container.addView(codeView)
                }
            }
        }
    }
}
