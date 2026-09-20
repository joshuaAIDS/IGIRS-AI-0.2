package com.igirs.ai.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.igirs.ai.tools.VisionManager

class CameraCaptureActivity : AppCompatActivity() {

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            VisionManager.latestScreenshot = bitmap
            onPhotoCapturedListener?.invoke(bitmap)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takePictureLauncher.launch(null)
    }

    companion object {
        var onPhotoCapturedListener: ((Bitmap) -> Unit)? = null
    }
}
