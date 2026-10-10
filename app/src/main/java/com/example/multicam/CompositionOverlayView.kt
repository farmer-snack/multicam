package com.example.multicam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * 构图叠加层（DOKA 风格重写）。
 *
 * 参考 DOKA 的 AR 构图引导：
 *   1. 给出**推荐取景框**（虚线框），主体移进框内即达成构图
 *   2. 用**动态 AR 引导线**从主体当前位置指向推荐框中心，实时跟随
 *   3. 对齐后引导线**逐渐变绿**并收起，框变为实线绿色，提示"可以拍了"
 *   4. 框上方显示当前**构图法则名**，角落显示**智能焦段推荐**
 *   5. 检测到倾斜时叠加水平参考线
 *
 * 坐标映射修复（保留 2026-10 的修复）：
 *   分析坐标（640×480）→ 预览内容区 → View 坐标。
 *   预览 Surface 是 center-crop 填充的，与 View 宽高比不同，
 *   若直接按 View 宽高缩放会导致覆盖层整体偏移。
 */
class CompositionOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var suggestion: CompositionAnalyzer.Suggestion? = null

    /** 锁定后冻结 */
    private var frozen = false

    /** 是否显示地平线参考 */
    var showHorizon = true

    /** 是否显示 AR 引导线（可单独开关，对应 DOKA 的图标切换） */
    var showArGuides = true

    /**
     * 分析帧尺寸（由 MainActivity 在 onPreviewSize 时同步）。
     */
    var analysisW = 640
    var analysisH = 480

    /** 预览内容区（像素），由 MainActivity 计算后设置；默认铺满 */
    var contentRect = RectF(0f, 0f, 1f, 1f)

    fun setFrozen(v: Boolean) { frozen = v }

    // ---------- 调色板 ----------
    // 未对齐：琥珀色；已对齐：绿色（DOKA 的"变绿即达标"）
    private val AMBER = Color.parseColor("#FF9F0A")
    private val GREEN = Color.parseColor("#4CAF50")

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AMBER
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
    }
    private val boxSolidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AMBER
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val cornerSolidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AMBER
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(22f, 14f), 0f)
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AMBER
        style = Paint.Style.FILL
        alpha = 210
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AMBER
        textSize = 34f
        isFakeBoldText = true
    }
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        isFakeBoldText = true
        setShadowLayer(4f, 0f, 2f, Color.parseColor("#99000000"))
    }
    private val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6FFFFFF")
        textSize = 26f
        isFakeBoldText = true
    }
    private val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f)
    }
    private val horizonLevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7A4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val horizonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3FFFFFF")
        textSize = 28f
    }

    fun update(s: CompositionAnalyzer.Suggestion?) {
        if (frozen) return
        suggestion = s
        invalidate()
    }

    /** 冻结时仍允许清空（关闭构图辅助） */
    fun forceClear() {
        suggestion = null
        frozen = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 分析坐标 → 预览内容区 → View 坐标
        val cr = contentRect
        val hasContent = cr.width() > 1f && cr.height() > 1f
        val contentW = if (hasContent) cr.width() else width.toFloat()
        val contentH = if (hasContent) cr.height() else height.toFloat()
        val originX = if (hasContent) cr.left else 0f
        val originY = if (hasContent) cr.top else 0f
        val kw = contentW / analysisW.coerceAtLeast(1).toFloat()
        val kh = contentH / analysisH.coerceAtLeast(1).toFloat()

        val s = suggestion

        // ---------- 地平线参考线 ----------
        if (showHorizon && s != null && abs(s.tiltDeg) > 0.8f) {
            val cx = originX + contentW / 2f
            val cy = originY + contentH / 2f
            val halfW = contentW / 2f - 40f
            val rad = Math.toRadians(s.tiltDeg.toDouble())
            val dy = (halfW * Math.tan(rad)).toFloat()
            canvas.drawLine(
                cx - halfW, cy - dy,
                cx + halfW, cy + dy,
                if (abs(s.tiltDeg) < 2.5f) horizonLevelPaint else horizonPaint
            )
            val txt = "地平线 %s%.1f°".format(
                if (s.tiltDeg > 0) "右倾 " else "左倾 ", abs(s.tiltDeg)
            )
            canvas.drawText(txt, cx - txt.length * 7f, cy - dy - 18f, horizonTextPaint)
        }

        if (s == null) return

        val aligned = s.aligned
        val accent = if (aligned) GREEN else AMBER

        // ---------- 1. AR 推荐取景框 ----------
        val box = s.targetBox
        if (box != null) {
            val l = originX + box.left * contentW
            val t = originY + box.top * contentH
            val r = originX + box.right * contentW
            val b = originY + box.bottom * contentH

            // 对齐进度驱动透明度：越接近越实
            val prog = s.alignProgress.coerceIn(0f, 1f)
            boxPaint.alpha = (90 + 165 * prog).toInt()
            boxPaint.color = accent

            canvas.drawRoundRect(RectF(l, t, r, b), 16f, 16f, boxPaint)

            // 四角加粗 L 形（DOKA 取景框标志性外观）
            val cp = if (aligned) cornerSolidPaint else cornerPaint
            cp.color = accent
            val arm = minOf((r - l) * 0.16f, 54f)
            drawCorner(canvas, l, t, arm, 1, 1, cp)
            drawCorner(canvas, r, t, arm, -1, 1, cp)
            drawCorner(canvas, r, b, arm, -1, -1, cp)
            drawCorner(canvas, l, b, arm, 1, -1, cp)

            // 三分线（仅未对齐时显示，帮助理解法则）
            if (!aligned) {
                val thirds = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#33FFFFFF")
                    strokeWidth = 1.5f
                }
                val gx1 = l + (r - l) / 3f
                val gx2 = l + (r - l) * 2f / 3f
                val gy1 = t + (b - t) / 3f
                val gy2 = t + (b - t) * 2f / 3f
                canvas.drawLine(gx1, t, gx1, b, thirds)
                canvas.drawLine(gx2, t, gx2, b, thirds)
                canvas.drawLine(l, gy1, r, gy1, thirds)
                canvas.drawLine(l, gy2, r, gy2, thirds)
            }

            // 法则名标签（框上方）
            val rule = s.ruleName.ifEmpty { s.type }
            if (rule.isNotEmpty()) {
                val tw = rulePaint.measureText(rule)
                val tagX = l
                val tagY = (t - 14f).coerceAtLeast(originY + 34f)
                tagBgPaint.color = if (aligned) Color.parseColor("#CC1B5E20")
                else Color.parseColor("#CC3A2A00")
                val padH = 16f
                canvas.drawRoundRect(
                    RectF(tagX, tagY - 32f, tagX + tw + padH * 2, tagY + 8f),
                    10f, 10f, tagBgPaint
                )
                rulePaint.color = if (aligned) Color.parseColor("#B9F6CA") else Color.WHITE
                canvas.drawText(rule, tagX + padH, tagY, rulePaint)
            }
        }

        // ---------- 2. AR 动态引导线（主体 → 推荐框） ----------
        if (showArGuides && !aligned && s.arLines.isNotEmpty()) {
            // 对齐进度越高线越淡，避免到位后残留
            guidePaint.alpha = (230 * (1f - s.alignProgress.coerceIn(0f, 1f) * 0.7f)).toInt()
            guidePaint.color = AMBER
            val path = Path()
            // 只画主引导线（第一条），辅助线用更淡的短线段
            val main = s.arLines.firstOrNull()
            if (main != null) {
                val ax = originX + main.first.x * contentW
                val ay = originY + main.first.y * contentH
                val bx = originX + main.second.x * contentW
                val by = originY + main.second.y * contentH
                path.reset()
                path.moveTo(ax, ay)
                path.lineTo(bx, by)
                canvas.drawPath(path, guidePaint)

                // 主体当前位置的小圆点
                pointPaint.color = AMBER
                pointPaint.alpha = 220
                canvas.drawCircle(ax, ay, 9f, pointPaint)
                canvas.drawCircle(ax, ay, 18f, guidePaint)

                // 箭头（指向目标框）
                drawArrowHead(canvas, ax, ay, bx, by, guidePaint)
            }
            // 其余辅助线：从目标框四角射出的短虚线，弱化显示
            val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#55FF9F0A")
                style = Paint.Style.STROKE
                strokeWidth = 2f
                pathEffect = DashPathEffect(floatArrayOf(12f, 16f), 0f)
            }
            for (i in 1 until s.arLines.size) {
                val seg = s.arLines[i]
                val ax = originX + seg.first.x * contentW
                val ay = originY + seg.first.y * contentH
                val cx = originX + seg.second.x * contentW
                val cy = originY + seg.second.y * contentH
                // 只保留靠外侧一段，避免框内杂乱
                canvas.drawLine(
                    ax + (cx - ax) * 0.45f, ay + (cy - ay) * 0.45f,
                    ax + (cx - ax) * 0.85f, ay + (cy - ay) * 0.85f,
                    faint
                )
            }
        }

        // ---------- 3. 对齐完成标记 ----------
        if (aligned && box != null) {
            val cx = originX + box.centerX() * contentW
            val cy = originY + box.centerY() * contentH
            canvas.drawCircle(cx, cy, 30f, checkPaint)
            // 对勾
            val p = Path().apply {
                moveTo(cx - 13f, cy + 1f)
                lineTo(cx - 4f, cy + 11f)
                lineTo(cx + 14f, cy - 10f)
            }
            canvas.drawPath(p, checkPaint)
        }

        // ---------- 4. 智能焦段推荐标签（右下角） ----------
        if (s.recommendZoom > 0f) {
            val txt = "建议 ${formatZoom(s.recommendZoom)}×"
            val tw = tagTextPaint.measureText(txt)
            val bx = originX + contentW - tw - 56f
            val by = originY + contentH - 120f
            tagBgPaint.color = Color.parseColor("#CC0A84FF")
            canvas.drawRoundRect(RectF(bx - 16f, by - 30f, bx + tw + 16f, by + 10f), 12f, 12f, tagBgPaint)
            tagTextPaint.color = Color.WHITE
            canvas.drawText(txt, bx, by, tagTextPaint)
        }

        // ---------- 5. 底部提示文字 ----------
        val msg = buildString {
            if (s.message.isNotEmpty()) append(s.message)
            if (s.hint.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(s.hint)
            }
        }
        if (msg.isNotEmpty()) {
            textPaint.color = accent
            canvas.drawText(msg, originX + 40f, originY + contentH - 60f, textPaint)
        }
    }

    /** 画一个 L 形取景框角 */
    private fun drawCorner(
        canvas: Canvas, x: Float, y: Float, arm: Float,
        sx: Int, sy: Int, paint: Paint
    ) {
        canvas.drawLine(x, y, x + arm * sx, y, paint)
        canvas.drawLine(x, y, x, y + arm * sy, paint)
    }

    /** 在 (bx,by) 端画指向 (ax,ay)→(bx,by) 方向的箭头 */
    private fun drawArrowHead(
        canvas: Canvas, ax: Float, ay: Float, bx: Float, by: Float, paint: Paint
    ) {
        val ang = Math.atan2((by - ay).toDouble(), (bx - ax).toDouble())
        val size = 26.0
        val a1 = ang + Math.toRadians(150.0)
        val a2 = ang - Math.toRadians(150.0)
        canvas.drawLine(bx, by, (bx + size * Math.cos(a1)).toFloat(), (by + size * Math.sin(a1)).toFloat(), paint)
        canvas.drawLine(bx, by, (bx + size * Math.cos(a2)).toFloat(), (by + size * Math.sin(a2)).toFloat(), paint)
    }

    private fun formatZoom(z: Float): String =
        if (abs(z - z.toInt()) < 0.05f) z.toInt().toString() else "%.1f".format(z)
}
