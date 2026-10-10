package com.example.multicam

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        val ok = try { OpenCVLoader.initDebug() } catch (e: Throwable) {
            Log.e("App", "OpenCV 初始化异常: ${e.message}", e); false
        }
        Log.d("App", "OpenCV init = $ok")

        // 注：OpenCV 4.5.3.0 Java 绑定无 Core.setUseOptimized，已移除该调用
        try {
            Core.setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
        } catch (_: Throwable) {}
    }
}
