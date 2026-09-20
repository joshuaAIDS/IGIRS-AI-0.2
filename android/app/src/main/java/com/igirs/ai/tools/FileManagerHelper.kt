package com.igirs.ai.tools

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LocalFile(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val extension: String
)

object FileManagerHelper {
    private const val TAG = "IGIRS.FileManager"

    private val SUPPORTED_TEXT_EXTENSIONS = setOf(
        "txt", "md", "csv", "json", "log", "xml", "html", "kt", "java", "py", 
        "js", "ts", "c", "cpp", "h", "yaml", "yml", "toml", "properties", 
        "ini", "cfg", "sh", "bat", "ps1"
    )

    fun searchFiles(context: Context, query: String, extension: String? = null, limit: Int = 20): List<LocalFile> {
        if (!hasFileAccess(context)) {
            Log.w(TAG, "Storage permission not granted")
            return emptyList()
        }

        val roots = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStorageDirectory(),
            File(Environment.getExternalStorageDirectory(), "Download")
        ).distinct()

        val results = mutableListOf<File>()
        val queryLower = query.lowercase(Locale.getDefault())
        val extLower = extension?.lowercase(Locale.getDefault())?.removePrefix(".")

        for (root in roots) {
            if (root.exists() && root.isDirectory) {
                searchRecursively(root, queryLower, extLower, 0, 4, results)
            }
        }

        return results.distinctBy { it.absolutePath }
            .map { toLocalFile(it) }
            .sortedByDescending { it.lastModified }
            .take(limit)
    }

    private fun searchRecursively(dir: File, query: String, ext: String?, depth: Int, maxDepth: Int, results: MutableList<File>) {
        if (depth > maxDepth) return
        val files = dir.listFiles() ?: return

        for (file in files) {
            if (file.name.lowercase(Locale.getDefault()).contains(query)) {
                if (ext == null || file.extension.lowercase(Locale.getDefault()) == ext) {
                    results.add(file)
                }
            }
            if (file.isDirectory) {
                searchRecursively(file, query, ext, depth + 1, maxDepth, results)
            }
        }
    }

    fun listFiles(context: Context, directory: String? = null, limit: Int = 25): List<LocalFile> {
        if (!hasFileAccess(context)) {
            Log.w(TAG, "Storage permission not granted")
            return emptyList()
        }

        val dirPath = directory ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) {
            Log.w(TAG, "Directory does not exist or is not a directory: $dirPath")
            return emptyList()
        }

        val files = dir.listFiles() ?: return emptyList()
        return files.map { toLocalFile(it) }
            .sortedByDescending { it.lastModified }
            .take(limit)
    }

    fun readFileContent(context: Context, filePath: String, maxBytes: Int = 50_000): String? {
        val file = File(filePath)
        if (!file.exists() || file.isDirectory) {
            Log.w(TAG, "File not found or is a directory: $filePath")
            return null
        }

        val ext = file.extension.lowercase(Locale.getDefault())

        if (ext == "pdf") {
            return try {
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(fd)
                val pageCount = renderer.pageCount
                renderer.close()
                fd.close()
                "PDF File Metadata:\nName: ${file.name}\nSize: ${humanReadableSize(file.length())}\nPages: $pageCount\n(Full-text extraction requires a PDF viewer/library)"
            } catch (e: Exception) {
                Log.e(TAG, "Error reading PDF metadata: ${e.message}")
                "PDF File Metadata:\nName: ${file.name}\nSize: ${humanReadableSize(file.length())}\n(Could not read page count)"
            }
        }

        if (SUPPORTED_TEXT_EXTENSIONS.contains(ext)) {
            return try {
                val bytesToRead = minOf(file.length(), maxBytes.toLong()).toInt()
                val content = file.inputStream().use {
                    val bytes = ByteArray(bytesToRead)
                    val read = it.read(bytes)
                    if (read > 0) String(bytes, 0, read, Charsets.UTF_8) else ""
                }
                if (file.length() > maxBytes) {
                    content + "\n... [truncated]"
                } else {
                    content
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading file: ${e.message}")
                null
            }
        }

        Log.w(TAG, "Unsupported file extension for reading: $ext")
        return null
    }

    fun getFileInfo(filePath: String): LocalFile? {
        val file = File(filePath)
        return if (file.exists()) toLocalFile(file) else null
    }

    fun hasFileAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    fun formatFileListing(files: List<LocalFile>): String {
        if (files.isEmpty()) return "No files found."
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return buildString {
            appendLine("Found ${files.size} file(s):")
            files.forEach {
                val type = if (it.isDirectory) "[DIR]" else "[FILE]"
                val date = sdf.format(Date(it.lastModified))
                appendLine("$type ${it.name} - ${humanReadableSize(it.size)} - $date")
                appendLine("  Path: ${it.path}")
            }
        }
    }

    fun humanReadableSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format(Locale.getDefault(), "%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    private fun toLocalFile(file: File): LocalFile {
        return LocalFile(
            name = file.name,
            path = file.absolutePath,
            size = if (file.isDirectory) 0L else file.length(),
            lastModified = file.lastModified(),
            isDirectory = file.isDirectory,
            extension = file.extension
        )
    }
}
