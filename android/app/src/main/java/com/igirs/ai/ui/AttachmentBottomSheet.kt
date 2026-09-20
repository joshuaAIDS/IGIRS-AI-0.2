package com.igirs.ai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.igirs.ai.R

class AttachmentBottomSheet(
    private val onCameraVisionSelected: () -> Unit,
    private val onDocumentSelected: (() -> Unit)? = null,
    private val onMorningRoutineSelected: () -> Unit,
    private val onNightRoutineSelected: () -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.sheet_attachment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<LinearLayout>(R.id.btnAttachCamera).setOnClickListener {
            dismiss()
            onCameraVisionSelected()
        }

        view.findViewById<LinearLayout>(R.id.btnAttachDocument).setOnClickListener {
            dismiss()
            onDocumentSelected?.invoke()
        }

        view.findViewById<LinearLayout>(R.id.btnAttachMorningRoutine).setOnClickListener {
            dismiss()
            onMorningRoutineSelected()
        }

        view.findViewById<LinearLayout>(R.id.btnAttachNightRoutine).setOnClickListener {
            dismiss()
            onNightRoutineSelected()
        }
    }
}
