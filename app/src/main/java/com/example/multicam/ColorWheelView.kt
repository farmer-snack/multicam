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

    /**
     * 【新增】拖动结束（手指抬起）。
     * 调色参数持久化必须等松手再做：原来每次 ACTION_MOVE 都调
     * persistCustomGrade()（内部 2 次 SharedPreferences.edit().apply()），
     * 60~120Hz 拖动 = 每秒 120~240 次持锁写，把 UI 卡顿放大数倍。
     */
    var onDragFinished: (() -> Unit)? = null

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
    // 【修复】Paint 的 strokeWidth / setShadowLayer 单位都是 px。
    // 原来全部硬编码像素值，在 3x 屏上环线与光标细到几乎看不见。
    // 这里统一按屏幕密度换算（几何尺寸 outerR/ringWidth 由 onSizeChanged
    // 按实际 view 尺寸算，本身已经是正确的 px）。
    private val density: Float get() = resources.displayMetrics.density
    private fun dpF(v: Float): Float = v * density

    private val ringBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = Color.parseColor("#33FFFFFF")
    }
    private val innerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DFFFFFF")
        strokeWidth = 1f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(
            8f * resources.displayMetrics.density, 0f, 0f, Color.BLACK
        )
    }
    private val pointHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.parseColor("#CCFFFFFF")
    }
    private val ringCursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
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
        // 【修复】内边距与环宽下限原本是固定像素（6px / 18px），
        // 在 3x 屏上占比过小、环带挤在一起；在 1x 屏上又过宽。
        // 改为 dp 语义。
        val pad = dpF(6f)
        outerR = minOf(w, h) / 2f - pad
        ringWidth = (outerR * 0.20f).coerceAtLeast(dpF(18f))
        innerR = (outerR - ringWidth - pad).coerceAtLeast(dpF(8f))
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

        // 【关键修复】三个问题一起改：
        //  1. 用 event.action 而非 actionMasked —— ACTION_POINTER_DOWN/UP
        //     的 action 含 pointerIndex<<8 高位，不匹配任何分支 → 落到
        //     super.onTouchEvent() 返回 false，父容器会抢走手势，
        //     且第一根手指抬起时 dragMode 不复位，后续 MOVE 用第二根手指的
        //     坐标 → 色相跳变。
        //  2. dist 判定是 `dist >= innerR-4` 与 `dist <= innerR-4` 在同一阈值
        //     上互补，else -> 0 **永远不可达**；而且没有上界，
        //     View 是 220dp 正方形、outerR≈104dp，角点到圆心约 155dp
        //     > outerR 的那圈"盘外空白"也全部命中"拖色相环"
        //     → 点四角色相瞬间跳到 45°/135°。
        //  3. 现在明确区分 环带 / 内圈 / 盘外，盘外不吃事件（交还父容器）。
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val ringOuter = outerR + TOUCH_SLOP
                dragMode = when {
                    innerR <= 0f || outerR <= 0f -> 0
                    dist >= innerR && dist <= ringOuter -> 2   // 环带
                    dist < innerR -> 1                           // 内圈
                    else -> 0                                     // 盘外空白
                }
                if (dragMode == 0) return false
                handleDrag(dx, dy)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == 0) return false
                // 只跟随第一根手指，多指时不会被第二根手指"抢方向盘"
                handleDrag(
                    event.getX(0) - centerX,
                    event.getY(0) - centerY
                )
                return true
            }
            // 多指按下/抬起：吞掉但不改变 dragMode，手指交接时不会跳变
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> return true

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 【修复】只在真正拖动过（dragMode != 0）时通知结束，
                // 避免"点一下盘外空白"也触发一次 SharedPreferences 写
                if (dragMode != 0) onDragFinished?.invoke()
                dragMode = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 触摸判定容差（像素） */
    private val TOUCH_SLOP: Float
        get() = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    /**
     * 拖动处理。
     *
     * @param dx 相对圆心的 x 偏移（外圈分支算色相角、内圈分支算归一化坐标）
     * @param dy 相对圆心的 y 偏移
     * 注：x/y/dist 是绝对坐标，逻辑上用不到（相对量已由 dx/dy 给出），
     * 这里不再接收，避免调用方误传。
     */
    private fun handleDrag(dx: Float, dy: Float) {
        if (dragMode == 2) {
            var ang = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
            if (ang < 0f) ang += 360f
            ringAngle = ang
            onHueRingChanged?.invoke(ang)
        } else {
            if (innerR <= 0f) return
            val nx = (dx / innerR).coerceIn(-1f, 1f)
            val ny = (dy / innerR).coerceIn(-1f, 1f)
            // 限制在圆内
            val len = Math.hypot(nx.toDouble(), ny.toDouble()).toFloat()
            if (len > 1f) {
                innerX = nx / len
                innerY = ny / len
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
