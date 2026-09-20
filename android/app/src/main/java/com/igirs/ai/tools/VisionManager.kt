package com.igirs.ai.tools

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object VisionManager {

    private const val TAG = "IGIRS.Vision"

    var latestScreenshot: Bitmap? = null

    suspend fun bitmapToBase64DataUri(bitmap: Bitmap, quality: Int = 85): String = withContext(Dispatchers.IO) {
        try {
            // Resize if too large for mobile data efficiency
            val maxDimension = 1024
            val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val targetW = if (ratio > 1) maxDimension else (maxDimension * ratio).toInt()
                val targetH = if (ratio > 1) (maxDimension / ratio).toInt() else maxDimension
                Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            } else {
                bitmap
            }

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val bytes = outputStream.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            return@withContext "data:image/jpeg;base64,$base64"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode bitmap to base64: ${e.message}", e)
            return@withContext ""
        }
    }

    /**
     * Scale a bitmap down to a max dimension suitable for vision analysis.
     */
    fun scaleBitmapForVision(bitmap: Bitmap, maxDimension: Int = 1024): Bitmap {
        if (bitmap.width <= maxDimension && bitmap.height <= maxDimension) return bitmap
        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val targetW = if (ratio > 1) maxDimension else (maxDimension * ratio).toInt()
        val targetH = if (ratio > 1) (maxDimension / ratio).toInt() else maxDimension
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }
}
