package com.example.multicam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 专业环形调色盘（vivo 风格）：
 * - 外圈：色相环，可点可拖，跟随指示器
 * - 内圈：二维色度／影调拖点，横轴 = 冷暖（青↔橙），纵轴 = 影调（暗↔亮）
 * - 拖拽时实时回调，松手后保持
 */
class ColorWheelView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** 内圈色度/影调变化：x/-1..1 偏色相，y/-1..1 影调 */
    var onColorChanged: ((x: Float, y: Float) -> Unit)? = null

    /** 外圈色相环变化：angle 0..360 */
    var onHueRingChanged: ((angle: Float) -> Unit)? = null

    private var centerX = 0f
    private var centerY = 0f
    private var outerR = 0f      // 色相环外半径
    private var ringWidth = 0f
    private var innerR = 0f      // 内圈半径

    // 内圈拖点（归一化坐标 -1..1）
    private var innerX = 0f
    private var innerY = 0f
    // 外圈角度（度）
    private var ringAngle = 0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#33FFFFFF")
    }
    private val innerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DFFFFFF")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
    }
    private val pointHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#CCFFFFFF")
    }
    private val ringCursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF")
        style = Paint.Style.FILL
    }

    /** 拖拽模式：0 无、1 内圈、2 外圈 */
    private var dragMode = 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        outerR = minOf(w, h) / 2f - 6f
        ringWidth = (outerR * 0.20f).coerceAtLeast(18f)
        innerR = outerR - ringWidth - 6f
        buildShaders()
    }

    private fun buildShaders() {
        // 色相环：HSV 扫描
        val hueColors = IntArray(361)
        for (i in 0..360) {
            hueColors[i] = Color.HSVToColor(floatArrayOf(i.toFloat() % 360f, 1f, 1f))
        }
        ringPaint.shader = SweepGradient(centerX, centerY, hueColors, null)

        // 内圈：横向 青蓝→白→暖橙，纵向叠加明暗
        val horiz = LinearGradient(
            centerX - innerR, centerY, centerX + innerR, centerY,
            intArrayOf(
                Color.parseColor("#2E7BF6"),  // 冷
                Color.parseColor("#F2F2F2"),
                Color.parseColor("#FF9F0A")   // 暖
            ), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        val vertAlpha = LinearGradient(
            centerX, centerY - innerR, centerX, centerY + innerR,
            intArrayOf(
                Color.parseColor("#B3000000"),
                Color.parseColor("#00FFFFFF"),
                Color.parseColor("#66FFFFFF")
            ), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        innerBgPaint.shader = ComposeShader(horiz, vertAlpha, PorterDuff.Mode.SRC_OVER)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (outerR <= 0f) return

        // 外圈色相环
        val ringRect = RectF(
            centerX - (outerR - ringWidth / 2f),
            centerY - (outerR - ringWidth / 2f),
            centerX + (outerR - ringWidth / 2f),
            centerY + (outerR - ringWidth / 2f)
        )
        ringPaint.strokeWidth = ringWidth
        canvas.drawArc(ringRect, 0f, 360f, false, ringPaint)
        canvas.drawCircle(centerX, centerY, outerR, ringBorderPaint)
        canvas.drawCircle(centerX, centerY, innerR, ringBorderPaint)

        // 色相环游标
        val rad = Math.toRadians(ringAngle.toDouble())
        val cursorR = outerR - ringWidth / 2f
        val cx = centerX + (cursorR * Math.cos(rad)).toFloat()
        val cy = centerY + (cursorR * Math.sin(rad)).toFloat()
        val half = ringWidth / 2f - 3f
        val tx = (half * Math.cos(rad)).toFloat()
        val ty = (half * Math.sin(rad)).toFloat()
        canvas.drawLine(cx - tx, cy - ty, cx + tx, cy + ty, ringCursorPaint)

        // 内圈背景
        canvas.drawCircle(centerX, centerY, innerR, innerBgPaint)
        canvas.drawCircle(centerX, centerY, innerR, ringBorderPaint)

        // 十字准星
        canvas.drawLine(centerX - innerR, centerY, centerX + innerR, centerY, crossPaint)
        canvas.drawLine(centerX, centerY - innerR, centerX, centerY + innerR, crossPaint)
        canvas.drawCircle(centerX, centerY, 3f, centerDotPaint)

        // 内圈拖点
        val px = centerX + innerX * innerR
        val py = centerY + innerY * innerR
        canvas.drawCircle(px, py, 16f, pointHaloPaint)
        canvas.drawCircle(px, py, 12f, pointPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val dx = event.x - centerX
        val dy = event.y - centerY
        val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = when {
                    dist >= innerR - 4f -> 2   // 环上
                    dist <= innerR - 4f -> 1   // 内圈
                    else -> 0
                }
                handleDrag(event.x, event.y, dist, dx, dy)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode != 0) handleDrag(event.x, event.y, dist, dx, dy)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleDrag(x: Float, y: Float, dist: Float, dx: Float, dy: Float) {
        if (dragMode == 2) {
            var ang = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
            if (ang < 0f) ang += 360f
            ringAngle = ang
            onHueRingChanged?.invoke(ang)
        } else {
            val nx = (dx / innerR).coerceIn(-1f, 1f)
            val ny = (dy / innerR).coerceIn(-1f, 1f)
            // 限制在圆内
            val len = Math.hypot(nx.toDouble(), ny.toDouble()).toFloat()
            if (len > 1f) {
                innerX = nx / len * 1f
                innerY = ny / len * 1f
            } else {
                innerX = nx
                innerY = ny
            }
            onColorChanged?.invoke(innerX, innerY)
        }
        invalidate()
    }

    /** 外部设定色度/影调（不触发回调） */
    fun setInner(x: Float, y: Float) {
        innerX = x.coerceIn(-1f, 1f)
        innerY = y.coerceIn(-1f, 1f)
        invalidate()
    }

    /** 外部设定色相环角度（不触发回调） */
    fun setRingAngle(angle: Float) {
        ringAngle = ((angle % 360f) + 360f) % 360f
        invalidate()
    }

    fun reset() {
        innerX = 0f; innerY = 0f; ringAngle = 0f
        invalidate()
        onColorChanged?.invoke(0f, 0f)
        onHueRingChanged?.invoke(0f)
    }
}
