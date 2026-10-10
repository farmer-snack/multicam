package com.example.multicam

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val TAG = "AppLogger"
    private const val MAX_LINES = 2000

    private val buffer = ArrayDeque<String>()
    private val lock = Any()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, msg: String) {
        add("D", tag, msg); Log.d(tag, msg)
    }
    fun w(tag: String, msg: String) {
        add("W", tag, msg); Log.w(tag, msg)
    }
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        add("E", tag, "$msg${tr?.let { "\n" + Log.getStackTraceString(it) } ?: ""}")
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
    }

    private fun add(level: String, tag: String, msg: String) {
        synchronized(lock) {
            buffer.addLast("${fmt.format(Date())} $level/$tag: $msg")
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
    }

    fun dump(): String = synchronized(lock) { buffer.joinToString("\n") }
    fun clear() = synchronized(lock) { buffer.clear() }

    fun exportToFile(context: Context): Uri? {
        val content = buildString {
            appendLine("=== MultiCam Log ===")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("====================")
            appendLine()
            append(dump())
        }

        val name = "multicam_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/MultiCam"
                    )
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return null
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    ), "MultiCam"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, name)
                FileOutputStream(file).use { it.write(content.toByteArray()) }
                Uri.fromFile(file)
            }
        } catch (e: Exception) { null }
    }
}
