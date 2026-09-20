package com.igirs.ai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.igirs.ai.R
import com.igirs.ai.memory.MemoryManager

class MemoryManagementBottomSheet(
    private val onMemoryChanged: (() -> Unit)? = null
) : BottomSheetDialogFragment() {

    private lateinit var rvMemories: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnClearAll: Button
    private lateinit var adapter: MemoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.sheet_memory_management, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvMemories = view.findViewById(R.id.rvMemories)
        tvEmpty = view.findViewById(R.id.tvEmptyMemories)
        btnClearAll = view.findViewById(R.id.btnClearAllMemories)

        adapter = MemoryAdapter(
            onDelete = { fact ->
                MemoryManager.removeFact(fact)
                refreshList()
                onMemoryChanged?.invoke()
            }
        )

        rvMemories.layoutManager = LinearLayoutManager(requireContext())
        rvMemories.adapter = adapter

        btnClearAll.setOnClickListener {
            MemoryManager.clearFacts()
            refreshList()
            onMemoryChanged?.invoke()
        }

        refreshList()
    }

    private fun refreshList() {
        val facts = MemoryManager.getUserFacts()
        if (facts.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvMemories.visibility = View.GONE
            btnClearAll.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rvMemories.visibility = View.VISIBLE
            btnClearAll.visibility = View.VISIBLE
            adapter.setFacts(facts)
        }
    }

    class MemoryAdapter(
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<MemoryAdapter.ViewHolder>() {

        private val items = mutableListOf<String>()

        fun setFacts(newFacts: List<String>) {
            items.clear()
            items.addAll(newFacts)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_memory_fact, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val fact = items[position]
            holder.tvFact.text = fact
            holder.btnDelete.setOnClickListener {
                onDelete(fact)
            }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvFact: TextView = view.findViewById(R.id.tvFactText)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteFact)
        }
    }
}
