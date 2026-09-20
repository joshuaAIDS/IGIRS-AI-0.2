package com.igirs.ai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.igirs.ai.R
import com.igirs.ai.voice.EdgeTtsClient

class VoiceSelectorBottomSheet(
    private val onVoiceChanged: ((String) -> Unit)? = null
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.sheet_voice_selector, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val container = view.findViewById<LinearLayout>(R.id.layoutVoiceOptions)
        val currentVoiceId = EdgeTtsClient.getSelectedVoice(requireContext())

        for (voice in EdgeTtsClient.AVAILABLE_VOICES) {
            val itemView = layoutInflater.inflate(R.layout.item_voice_option, container, false)

            val ivCheck = itemView.findViewById<ImageView>(R.id.ivVoiceCheck)
            val tvName = itemView.findViewById<TextView>(R.id.tvVoiceName)
            val tvDesc = itemView.findViewById<TextView>(R.id.tvVoiceDesc)
            val btnPreview = itemView.findViewById<ImageView>(R.id.btnPreviewVoice)

            val isSelected = voice.id == currentVoiceId
            tvName.text = "${voice.name} (${voice.gender})"
            tvDesc.text = voice.description

            ivCheck.setImageResource(
                if (isSelected) android.R.drawable.radiobutton_on_background
                else android.R.drawable.radiobutton_off_background
            )
            ivCheck.setColorFilter(
                ContextCompat.getColor(requireContext(), if (isSelected) R.color.electric_cyan else R.color.text_secondary)
            )

            itemView.setOnClickListener {
                EdgeTtsClient.setSelectedVoice(requireContext(), voice.id)
                onVoiceChanged?.invoke(voice.id)
                Toast.makeText(requireContext(), "Selected voice: ${voice.name}", Toast.LENGTH_SHORT).show()
                dismiss()
            }

            btnPreview.setOnClickListener {
                Toast.makeText(requireContext(), "Previewing ${voice.name}...", Toast.LENGTH_SHORT).show()
                EdgeTtsClient.previewVoice(requireContext(), voice.id)
            }

            container.addView(itemView)
        }
    }
}
