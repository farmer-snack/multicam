package com.example.multicam

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileSaver {

    /**
     * 【关键修复】原来这里是进程级共享的单例 SimpleDateFormat 且无锁。
     * 一次拍摄会并发触发至少 4 条写盘路径（raw_original / ai_processed /
     * composed / DNG 各自 new Thread），并发 format() 会互相踩内部 Calendar
     * 与数字缓冲区 → 文件名时间戳错乱、重名覆盖，或抛
     * ArrayIndexOutOfBoundsException / NumberFormatException 导致存图失败。
     * 改为每次调用新建实例（SimpleDateFormat 构造开销可忽略），
     * 并对格式化本身兜一层 try。
     */
    private fun stamp(): String =
        try {
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        } catch (_: Throwable) {
            System.currentTimeMillis().toString()
        }

    /**
     * 录像输出目录（供 MultiVideoSession 直接写文件，不走 MediaStore）：
     *  - API 29+ 用 app 专属外部目录 `Android/data/<pkg>/files/Movies`，
     *    免存储权限、免扫描，MediaRecorder 可写；
     *  - API 26–28 用公有 `DCIM/MultiCam`（已声明 WRITE_EXTERNAL_STORAGE）。
     */
    fun videoDir(context: Context): File {
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "MultiCam")
        } else {
            @Suppress("DEPRECATION")
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "MultiCam"
            )
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveJpeg(context: Context, bytes: ByteArray, tag: String): Uri? {
        if (bytes.isEmpty()) return null
        val safeTag = tag.replace(Regex("[^A-Za-z0-9_]"), "_").ifEmpty { "img" }
        val name = "IMG_${stamp()}_$safeTag.jpg"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, bytes, name)
            } else saveViaFile(bytes, name)
        } catch (_: Throwable) {
            // OOM 等 Error 也必须被接住，否则裸 Thread 里直接崩进程
            null
        }
    }

    /** 保存 DNG 到 DCIM/MultiCam（走 MediaStore.Files，MIME image/x-adobe-dng） */
    fun saveDng(context: Context, bytes: ByteArray, tag: String): Uri? {
        if (bytes.isEmpty()) return null
        val safeTag = tag.replace(Regex("[^A-Za-z0-9_]"), "_").ifEmpty { "dng" }
        val name = "IMG_${stamp()}_$safeTag.dng"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveDngViaMediaStore(context, bytes, name)
            } else saveDngViaFile(bytes, name)
        } catch (_: Throwable) {
            null
        }
    }

    private fun saveDngViaMediaStore(
        context: Context, bytes: ByteArray, name: String
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DCIM + "/MultiCam")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
        ) ?: return null
        return try {
            // 【修复】openOutputStream 返回 null 时原来什么都不写，却照样把
            // IS_PENDING 置 0 → 相册里出现 0 字节坏图且对外报告"保存成功"。
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("openOutputStream 返回 null")
            out.use { it.write(bytes); it.flush() }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (_: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun saveViaMediaStore(
        context: Context, bytes: ByteArray, name: String
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/MultiCamCapture")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return null
        return try {
            // 同上：必须确认真的写进去了，不能"无论如何都返回成功"
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("openOutputStream 返回 null")
            out.use { it.write(bytes); it.flush() }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (_: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun saveViaFile(bytes: ByteArray, name: String): Uri? {
        val dir = jpegDir()
        return try {
            // 【修复】原来只判断 exists() 不判断 mkdirs() 结果，也不确认是目录，
            // 失败要等到 FileOutputStream 才抛。
            if (!dir.exists() && !dir.mkdirs()) return null
            if (!dir.isDirectory) return null
            val file = File(dir, name)
            FileOutputStream(file).use { it.write(bytes); it.flush() }
            Uri.fromFile(file)
        } catch (_: Throwable) {
            // 【修复】原路径异常时只 return null，已创建的 File 留在磁盘上 →
            // 相册里残留半张图/0 字节图且删不掉。这里主动清理。
            runCatching { File(dir, name).delete() }
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun saveDngViaFile(bytes: ByteArray, name: String): Uri? {
        val dir = dngDir()
        return try {
            if (!dir.exists() && !dir.mkdirs()) return null
            if (!dir.isDirectory) return null
            val file = File(dir, name)
            FileOutputStream(file).use { it.write(bytes); it.flush() }
            Uri.fromFile(file)
        } catch (_: Throwable) {
            runCatching { File(dir, name).delete() }
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun jpegDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "MultiCamCapture"
    )

    @Suppress("DEPRECATION")
    private fun dngDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        "MultiCam"
    )
}
