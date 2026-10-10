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
        strokeWidth = 1.5f
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
