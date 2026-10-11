package com.example.multicam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * 构图建议面板：展示规则三分评分、水平仪倾斜角、主体位置、移动方向建议。
 * 数据由 MainActivity 通过 [setData] 推送。
 */
class CompositionPanelView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    data class Data(
        val type: String = "",
        val score: Int = 0,            // 0..100 构图评分
        val tiltDeg: Float = 0f,       // 水平倾斜角，正=右倾
        val subjectX: Float = 0.5f,    // 主体归一化横坐标
        val subjectY: Float = 0.5f,
        val hint: String = "",         // 移动方向建议
        val locked: Boolean = false,
        val active: Boolean = false,
        // ---------- 【新增】DOKA 风格字段 ----------
        val ruleName: String = "",             // 当前构图法则
        val alignProgress: Float = 0f,         // 总对齐进度 0..1
        val alignX: Float = 0f,                // 横向对齐进度
        val alignY: Float = 0f,                // 纵向对齐进度
        val aligned: Boolean = false,          // 是否已对齐（变绿）
        val recommendZoom: Float = 0f          // 智能焦段推荐，0=不推荐
    )

    private var data = Data()

    /**
     * 【修复】绘制尺寸单位换算。
     * textSize / strokeWidth 的单位是 **px**，原来全是硬编码像素值：
     * 在 3x 屏上 26px ≈ 8.7sp，"目标/水平/距离"这些数值小到读不出来；
     * 在 1x 屏上又显得巨大。改为按密度换算，各屏幕观感一致。
     */
    private val density: Float get() = resources.displayMetrics.density
    private fun dpF(v: Float): Float = v * density

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpF(26f)
        isFakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3FFFFFF")
        textSize = dpF(21f)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpF(23f)
        isFakeBoldText = true
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9F0A")
        textSize = dpF(22f)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.FILL
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#26FFFFFF")
        strokeWidth = dpF(1f)
    }
    private val subjectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpF(2f)
        color = Color.parseColor("#FF9F0A")
    }
    private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9F0A")
        textSize = dpF(21f)
        isFakeBoldText = true
    }
    private val okPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        textSize = dpF(22f)
        isFakeBoldText = true
    }

    fun setData(d: Data) {
        data = d
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!data.active) return

        val pad = 26f
        var y = pad + 26f

        // 标题 + 锁定状态
        canvas.drawText(if (data.type.isEmpty()) "构图建议" else data.type, pad, y, titlePaint)
        if (data.locked) {
            val tw = titlePaint.measureText(if (data.type.isEmpty()) "构图建议" else data.type)
            canvas.drawText("· 已锁定", pad + tw + 14f, y, lockPaint)
        }
        // 右侧：法则名 / 对齐状态（DOKA 风格即时反馈）
        val rightTag = when {
            data.aligned -> "✓ 构图合适"
            data.ruleName.isNotEmpty() -> data.ruleName
            else -> ""
        }
        if (rightTag.isNotEmpty()) {
            okPaint.color = if (data.aligned) Color.parseColor("#4CAF50")
            else Color.parseColor("#B3FFFFFF")
            canvas.drawText(
                rightTag,
                width - pad - okPaint.measureText(rightTag),
                y,
                okPaint
            )
        }

        y += 14f
        canvas.drawLine(pad, y, width - pad, y, dividerPaint)
        y += 34f

        // 构图评分条
        canvas.drawText("评分", pad, y, labelPaint)
        canvas.drawText("${data.score}", width - pad - valuePaint.measureText("${data.score}"), y, valuePaint)
        y += 16f
        val barRect = RectF(pad, y, width - pad, y + 10f)
        canvas.drawRoundRect(barRect, 5f, 5f, trackPaint)
        val ratio = (data.score / 100f).coerceIn(0f, 1f)
        barPaint.color = when {
            ratio >= 0.8f -> Color.parseColor("#FF9F0A")
            ratio >= 0.55f -> Color.parseColor("#E8C33A")
            else -> Color.parseColor("#8A8A8A")
        }
        if (ratio > 0.02f) {
            canvas.drawRoundRect(
                RectF(pad, y, pad + (width - pad * 2) * ratio, y + 10f), 5f, 5f, barPaint
            )
        }
        y += 44f

        // 对齐进度条（分轴）——DOKA 引导用户把主体移入推荐框
        canvas.drawText("对齐", pad, y, labelPaint)
        val alignText = if (data.aligned) "已对齐"
        else "${(data.alignProgress * 100).toInt()}%"
        valuePaint.color = if (data.aligned) Color.parseColor("#4CAF50") else Color.WHITE
        canvas.drawText(alignText, width - pad - valuePaint.measureText(alignText), y, valuePaint)
        y += 16f
        canvas.drawRoundRect(RectF(pad, y, width - pad, y + 10f), 5f, 5f, trackPaint)
        val aRatio = data.alignProgress.coerceIn(0f, 1f)
        barPaint.color = if (data.aligned) Color.parseColor("#4CAF50") else Color.parseColor("#0A84FF")
        if (aRatio > 0.02f) {
            canvas.drawRoundRect(
                RectF(pad, y, pad + (width - pad * 2) * aRatio, y + 10f), 5f, 5f, barPaint
            )
        }
        y += 18f
        // 分轴刻度提示：横向 / 纵向各一个小进度条
        val halfW = (width - pad * 2 - 20f) / 2f
        canvas.drawRoundRect(RectF(pad, y, pad + halfW, y + 6f), 3f, 3f, trackPaint)
        barPaint.color = Color.parseColor("#59FFFFFF")
        canvas.drawRoundRect(
            RectF(pad, y, pad + halfW * data.alignX.coerceIn(0f, 1f), y + 6f), 3f, 3f, barPaint
        )
        val rx = width - pad - halfW
        canvas.drawRoundRect(RectF(rx, y, rx + halfW, y + 6f), 3f, 3f, trackPaint)
        canvas.drawRoundRect(
            RectF(rx, y, rx + halfW * data.alignY.coerceIn(0f, 1f), y + 6f), 3f, 3f, barPaint
        )
        y += 30f

        // 水平仪
        canvas.drawText("水平", pad, y, labelPaint)
        val tiltText = if (abs(data.tiltDeg) < 1.0f) "已水平"
        else "%s%.1f°".format(if (data.tiltDeg > 0) "右倾 " else "左倾 ", abs(data.tiltDeg))
        canvas.drawText(tiltText, width - pad - valuePaint.measureText(tiltText), y, valuePaint)
        y += 18f
        val cx = width / 2f
        canvas.drawLine(pad, y + 6f, width - pad, y + 6f, dividerPaint)
        // 水平指示滑块：0° 在中间，±10° 满量程
        val tiltRatio = (data.tiltDeg / 10f).coerceIn(-1f, 1f)
        val markerX = cx + tiltRatio * ((width / 2f) - pad - 8f)
        barPaint.color = if (abs(data.tiltDeg) < 1.0f) Color.parseColor("#FF9F0A")
        else Color.parseColor("#59FFFFFF")
        canvas.drawCircle(markerX, y + 6f, 7f, barPaint)
        y += 40f

        // 主体位置示意（缩略九宫格）
        canvas.drawText("主体", pad, y, labelPaint)
        val gridW = 78f
        val gridH = 52f
        val gx = width - pad - gridW
        val gy = y - 34f
        val gridRect = RectF(gx, gy, gx + gridW, gy + gridH)
        canvas.drawRect(gridRect, trackPaint)
        canvas.drawLine(gx + gridW / 3f, gy, gx + gridW / 3f, gy + gridH, dividerPaint)
        canvas.drawLine(gx + gridW * 2f / 3f, gy, gx + gridW * 2f / 3f, gy + gridH, dividerPaint)
        canvas.drawLine(gx, gy + gridH / 3f, gx + gridW, gy + gridH / 3f, dividerPaint)
        canvas.drawLine(gx, gy + gridH * 2f / 3f, gx + gridW, gy + gridH * 2f / 3f, dividerPaint)
        subjectPaint.color = if (data.aligned) Color.parseColor("#4CAF50")
        else Color.parseColor("#FF9F0A")
        canvas.drawCircle(
            gx + gridW * data.subjectX.coerceIn(0f, 1f),
            gy + gridH * data.subjectY.coerceIn(0f, 1f),
            7f, subjectPaint
        )
        y += 30f

        if (data.hint.isNotEmpty()) {
            hintPaint.color = if (data.aligned) Color.parseColor("#4CAF50")
            else Color.parseColor("#FF9F0A")
            canvas.drawText(data.hint, pad, y, hintPaint)
        }
    }

    /**
     * 【关键修复】原来无条件 `setMeasuredDimension(w, 330)` —— 330 是**像素**，
     * 而 activity_main.xml 里写的是 `330dp`。在 xxhdpi(x3) 屏上，
     * 面板被强行压成 110dp 高，而 onDraw 内部所有 y 偏移是按 ~300px 累加的
     * → 底部"主体示意"与 hint 提示行被裁掉，文字也小到几乎读不出。
     * 而且完全忽略 heightMeasureSpec 的 mode：父容器给 UNSPECIFIED 时
     * getSize 返回 0 → 宽度变 0，面板彻底不可见。
     *
     * 现在：尊重 MeasureSpec，只在 EXACTLY/AT_MOST 下按 dp 给默认值。
     */
    private val dpUnit: Float
        get() = resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = (330 * dpUnit).toInt()
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val w = when (wMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> MeasureSpec.getSize(widthMeasureSpec)
            else -> (230 * dpUnit).toInt()   // UNSPECIFIED：用 XML 里的 230dp
        }
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val h = when (hMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(desiredH, MeasureSpec.getSize(heightMeasureSpec))
            else -> desiredH
        }
        setMeasuredDimension(w, h)
    }
}
