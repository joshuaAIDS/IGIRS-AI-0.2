package com.igirs.ai.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.igirs.ai.R
import com.igirs.ai.session.ChatSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SidebarChatAdapter(
    private val onSessionSelected: (ChatSession) -> Unit,
    private val onSessionRename: (ChatSession) -> Unit,
    private val onSessionDelete: (ChatSession) -> Unit
) : ListAdapter<ChatSession, SidebarChatAdapter.SessionViewHolder>(DiffCallback) {

    private var activeSessionId: String? = null

    fun setActiveSession(sessionId: String?) {
        activeSessionId = sessionId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sidebar_chat, parent, false)
        return SessionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SessionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvChatTitle)
        private val tvDate: TextView = itemView.findViewById(R.id.tvChatDate)
        private val btnOptions: ImageView = itemView.findViewById(R.id.btnChatOptions)

        fun bind(session: ChatSession) {
            tvTitle.text = session.title

            val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
            tvDate.text = sdf.format(Date(session.updatedAt))

            // Highlight if active session
            if (session.id == activeSessionId) {
                itemView.setBackgroundColor(0x3300F0FF.toInt())
            } else {
                itemView.setBackgroundResource(android.R.drawable.list_selector_background)
            }

            itemView.setOnClickListener {
                onSessionSelected(session)
            }

            btnOptions.setOnClickListener { v ->
                val popup = PopupMenu(v.context, v)
                popup.menu.add(0, 1, 0, "Rename")
                popup.menu.add(0, 2, 1, "Delete")
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> {
                            onSessionRename(session)
                            true
                        }
                        2 -> {
                            onSessionDelete(session)
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ChatSession>() {
        override fun areItemsTheSame(oldItem: ChatSession, newItem: ChatSession): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatSession, newItem: ChatSession): Boolean {
            return oldItem == newItem
        }
    }
}
