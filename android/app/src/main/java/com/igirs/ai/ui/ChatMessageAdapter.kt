package com.igirs.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.igirs.ai.R

enum class MessageType {
    USER, ASSISTANT, THINKING
}

data class UiChatMessage(
    val type: MessageType,
    var content: String,
    val image: Bitmap? = null,
    val imageDataUri: String? = null,
    var isMemoryUpdated: Boolean = false,
    var searchedWebQuery: String? = null
)

class ChatMessageAdapter(
    val messages: MutableList<UiChatMessage>,
    private val onSpeakClicked: (String) -> Unit,
    private val onCopyClicked: (String) -> Unit,
    private val onRegenerateClicked: ((Int) -> Unit)? = null,
    private val onMemoryBadgeClicked: (() -> Unit)? = null,
    private val onUserMessageEdit: ((Int, String) -> Unit)? = null,
    private val onThumbsFeedback: ((Int, Boolean) -> Unit)? = null
) : RecyclerView.Adapter<ChatMessageAdapter.ChatViewHolder>() {

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val containerUser: LinearLayout = itemView.findViewById(R.id.containerUserMessage)
        val ivUserAttachment: ImageView = itemView.findViewById(R.id.ivUserAttachment)
        val tvUserContent: TextView = itemView.findViewById(R.id.tvUserContent)
        val btnEditUser: ImageButton = itemView.findViewById(R.id.btnEditUserMessage)
        val btnCopyUser: ImageButton = itemView.findViewById(R.id.btnCopyUserMessage)

        val containerAi: LinearLayout = itemView.findViewById(R.id.containerAiMessage)
        val layoutWebSearchBadge: LinearLayout = itemView.findViewById(R.id.layoutWebSearchBadge)
        val tvWebSearchBadge: TextView = itemView.findViewById(R.id.tvWebSearchBadge)
        val layoutMemoryUpdatedBadge: LinearLayout = itemView.findViewById(R.id.layoutMemoryUpdatedBadge)
        val tvAiContent: TextView = itemView.findViewById(R.id.tvAiContent)
        val containerAiDynamicContent: LinearLayout = itemView.findViewById(R.id.containerAiDynamicContent)
        val btnSpeak: ImageButton = itemView.findViewById(R.id.btnSpeakAiMessage)
        val btnCopy: ImageButton = itemView.findViewById(R.id.btnCopyAiMessage)
        val btnRegenerate: ImageButton = itemView.findViewById(R.id.btnRegenerateAiMessage)
        val btnThumbsUp: ImageButton = itemView.findViewById(R.id.btnThumbsUp)
        val btnThumbsDown: ImageButton = itemView.findViewById(R.id.btnThumbsDown)

        val containerThinking: LinearLayout = itemView.findViewById(R.id.containerThinking)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val message = messages[position]

        when (message.type) {
            MessageType.USER -> {
                holder.containerUser.visibility = View.VISIBLE
                holder.containerAi.visibility = View.GONE
                holder.containerThinking.visibility = View.GONE

                holder.tvUserContent.text = message.content
                if (message.image != null) {
                    holder.ivUserAttachment.visibility = View.VISIBLE
                    holder.ivUserAttachment.setImageBitmap(message.image)
                } else {
                    holder.ivUserAttachment.visibility = View.GONE
                }

                holder.btnEditUser.setOnClickListener {
                    onUserMessageEdit?.invoke(position, message.content)
                }
                holder.btnCopyUser.setOnClickListener {
                    onCopyClicked(message.content)
                }
            }
            MessageType.ASSISTANT -> {
                holder.containerUser.visibility = View.GONE
                holder.containerAi.visibility = View.VISIBLE
                holder.containerThinking.visibility = View.GONE

                // Web Search Badge
                if (!message.searchedWebQuery.isNullOrBlank()) {
                    holder.layoutWebSearchBadge.visibility = View.VISIBLE
                    holder.tvWebSearchBadge.text = "Searched the web for \"${message.searchedWebQuery}\""
                } else {
                    holder.layoutWebSearchBadge.visibility = View.GONE
                }

                // Memory Updated Badge
                if (message.isMemoryUpdated) {
                    holder.layoutMemoryUpdatedBadge.visibility = View.VISIBLE
                    holder.layoutMemoryUpdatedBadge.setOnClickListener {
                        onMemoryBadgeClicked?.invoke()
                    }
                } else {
                    holder.layoutMemoryUpdatedBadge.visibility = View.GONE
                }

                // Markdown & Syntax Code Block Rendering
                if (message.content.contains("```")) {
                    holder.tvAiContent.visibility = View.GONE
                    holder.containerAiDynamicContent.visibility = View.VISIBLE
                    MarkdownRenderer.populateMessageContainer(
                        container = holder.containerAiDynamicContent,
                        rawContent = message.content,
                        context = holder.itemView.context
                    )
                } else {
                    holder.containerAiDynamicContent.visibility = View.GONE
                    holder.tvAiContent.visibility = View.VISIBLE
                    holder.tvAiContent.text = MarkdownRenderer.renderMarkdown(message.content)
                }

                holder.btnSpeak.setOnClickListener { onSpeakClicked(message.content) }
                holder.btnCopy.setOnClickListener { onCopyClicked(message.content) }
                holder.btnRegenerate.setOnClickListener { onRegenerateClicked?.invoke(position) }

                holder.btnThumbsUp.setOnClickListener {
                    holder.btnThumbsUp.setColorFilter(ContextCompat.getColor(holder.itemView.context, R.color.electric_cyan))
                    holder.btnThumbsDown.setColorFilter(ContextCompat.getColor(holder.itemView.context, R.color.text_muted))
                    Toast.makeText(holder.itemView.context, "Thanks for the feedback!", Toast.LENGTH_SHORT).show()
                    onThumbsFeedback?.invoke(position, true)
                }

                holder.btnThumbsDown.setOnClickListener {
                    holder.btnThumbsDown.setColorFilter(ContextCompat.getColor(holder.itemView.context, R.color.flame_neon))
                    holder.btnThumbsUp.setColorFilter(ContextCompat.getColor(holder.itemView.context, R.color.text_muted))
                    Toast.makeText(holder.itemView.context, "Thanks, we'll improve this.", Toast.LENGTH_SHORT).show()
                    onThumbsFeedback?.invoke(position, false)
                }
            }
            MessageType.THINKING -> {
                holder.containerUser.visibility = View.GONE
                holder.containerAi.visibility = View.GONE
                holder.containerThinking.visibility = View.VISIBLE
            }
        }
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: UiChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessage(newContent: String) {
        if (messages.isNotEmpty()) {
            val lastIdx = messages.size - 1
            messages[lastIdx].content = newContent
            notifyItemChanged(lastIdx)
        }
    }

    fun removeThinking() {
        val index = messages.indexOfFirst { it.type == MessageType.THINKING }
        if (index != -1) {
            messages.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    fun clearAll() {
        val count = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, count)
    }
}
