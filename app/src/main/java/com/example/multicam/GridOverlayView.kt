package com.example.multicam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** 参考线（3×3 九宫格）——vivo 风格网格开关 */
class GridOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#59FFFFFF")
        // 【修复】strokeWidth 单位是 px。原来固定 1.5px，在 3x 屏上经 GPU 缩放后
        // 只剩半个物理像素 + 抗锯齿灰边，九宫格淡到几乎看不见。
        // 改为按密度换算（约 1.5dp），各密度屏粗细一致。
        strokeWidth = 1.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val stepX = w / 3f
        val stepY = h / 3f
        for (i in 1..2) {
            canvas.drawLine(stepX * i, 0f, stepX * i, h, paint)
            canvas.drawLine(0f, stepY * i, w, stepY * i, paint)
        }
    }
}
