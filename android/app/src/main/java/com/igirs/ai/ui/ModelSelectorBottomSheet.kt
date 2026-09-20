package com.igirs.ai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.igirs.ai.R

enum class ActiveAiModel(val id: String, val displayName: String, val modelId: String) {
    FAST("fast", "IGIRS 2.0 Fast (Ultra-Low Latency)", "openai/gpt-oss-20b"),
    REASONING("reasoning", "IGIRS 2.0 Reasoning", "openai/gpt-oss-120b"),
    INSTANT("instant", "IGIRS 2.0 Instant", "groq/compound")
}

class ModelSelectorBottomSheet(
    private val currentModel: ActiveAiModel,
    private val onModelSelected: (ActiveAiModel) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.sheet_model_selector, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val optionFast = view.findViewById<LinearLayout>(R.id.optionModelFast)
        val optionReasoning = view.findViewById<LinearLayout>(R.id.optionModelReasoning)
        val optionInstant = view.findViewById<LinearLayout>(R.id.optionModelInstant)

        val checkFast = view.findViewById<TextView>(R.id.checkModelFast)
        val checkReasoning = view.findViewById<TextView>(R.id.checkModelReasoning)
        val checkInstant = view.findViewById<TextView>(R.id.checkModelInstant)

        // Set initial checkmark
        checkFast.visibility = if (currentModel == ActiveAiModel.FAST) View.VISIBLE else View.GONE
        checkReasoning.visibility = if (currentModel == ActiveAiModel.REASONING) View.VISIBLE else View.GONE
        checkInstant.visibility = if (currentModel == ActiveAiModel.INSTANT) View.VISIBLE else View.GONE

        optionFast.setOnClickListener {
            onModelSelected(ActiveAiModel.FAST)
            dismiss()
        }

        optionReasoning.setOnClickListener {
            onModelSelected(ActiveAiModel.REASONING)
            dismiss()
        }

        optionInstant.setOnClickListener {
            onModelSelected(ActiveAiModel.INSTANT)
            dismiss()
        }
    }
}
