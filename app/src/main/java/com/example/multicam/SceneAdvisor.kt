package com.example.multicam

import android.graphics.PointF
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 构图分析器（DOKA 风格重写）。
 *
 * 参考 DOKA 相机的 AI 构图交互链路：
 *   1. 用户点击「AI 构图」→ 扫描一帧，生成**推荐取景框**与构图法则
 *   2. 屏幕上出现 AR 引导线，把主体当前位置指到推荐框
 *   3. 移动手机使主体进入框内 → [aligned] 置位，引导元素**变绿**
 *   4. 对齐后按法则（三分法/对称/引导线/水平）给出智能焦段推荐
 *
 * 与原实现的差异：
 *   - 原来只有"单个瞄准点 + 文字提示"，用户需理解"三分点"概念；
 *     现在给出**可视化的推荐框**，把主体搬进框即可，零学习成本。
 *   - 新增**对齐进度**（分轴），引导线随靠近逐渐变绿，而不是等到位才突然变化。
 *   - 新增**智能焦段推荐**：按主体画面占比推荐变焦，避免主体过小。
 *   - 引导线仍用一阶低通滤波平滑跟随，避免抖动闪烁。
 */
object SceneAdvisor {

    private const val TAG = "SceneAdvisor"

    data class Advice(
        val type: String,          // horizon / thirds / leading / symmetry / center
        val message: String,
        val zoomDelta: Float,
        val targetZoom: Float,
        val guidePoints: List<PointF>,
        val horizonAngle: Float,
        val targetYawDelta: Float = 0f,
        val guideAnchor: PointF? = null,
        val score: Int = 0,              // 构图评分 0..100
        val subjectX: Float = 0.5f,      // 主体归一化坐标
        val subjectY: Float = 0.5f,
        val hint: String = "",           // 移动方向建议
        // ---------- 【新增】DOKA 风格 AR 引导字段 ----------
        /** 推荐取景框（归一化 0..1） */
        val targetBox: android.graphics.RectF? = null,
        /** 是否已对齐到位（引导元素变绿） */
        val aligned: Boolean = false,
        /** 总对齐进度 0..1 */
        val alignProgress: Float = 0f,
        /** 分轴对齐进度 0..1 */
        val alignX: Float = 0f,
        val alignY: Float = 0f,
        /** 遵循的构图法则名（三分法/对称/引导线/水平/居中） */
        val ruleName: String = "",
        /** 智能焦段推荐（1.0 = 原生），0f = 不推荐 */
        val recommendZoom: Float = 0f,
        /** AR 引导线（归一化端点对） */
        val arLines: List<Pair<PointF, PointF>> = emptyList()
    )

    // ---------- 锁定 ----------

    @Volatile private var locked = false

    fun setLocked(v: Boolean) {
        locked = v
        if (!v) resetTracking()
    }

    fun isLocked(): Boolean = locked

    /**
     * 设备自身的 roll 倾角（度，来自陀螺仪 / OrientationTracker）。
     *
     * 【新增】与「画面内容里的水平线倾角」（detectHorizon 的结果）严格区分。
     * 只有前者能用来指导用户"转手机" —— 场景里的线歪不代表设备歪。
     * 由 MainActivity 在传感器回调里注入；未注入（null）时不生成水平校正提示。
     */
    @Volatile var deviceRollDeg: Float? = null

    fun setDeviceRoll(deg: Float?) { deviceRollDeg = deg }

    // ---------- 平滑跟随 ----------

    private const val SMOOTH_ALPHA = 0.28f      // EMA 系数，越小越稳越迟钝
    private const val TILT_SMOOTH_ALPHA = 0.35f
    private const val MAX_JUMP_PX = 220f        // 单帧最大跳变，超过视为误检丢弃

    private var smoothX: Float? = null
    private var smoothY: Float? = null
    private var smoothTilt: Float? = null
    private var smoothScore: Float? = null
    private val lock = Any()

    fun resetTracking() {
        synchronized(lock) {
            smoothX = null
            smoothY = null
            smoothTilt = null
            smoothScore = null
            smoothSubX = null
            smoothSubY = null
        }
        // 【关键修复】一并复位滞回状态，否则解锁构图后三分点方向与法则名
        // 仍带着上一次会话的记忆，取景框一上来就跳到另一侧。
        lastSideX = 1
        leadingStreak = 0
        lastRule = ""
    }

    /** 返回平滑后的锚点；若首帧或跳变过大则返回原始值/丢弃 */
    private fun smoothPoint(x: Float, y: Float): PointF? {
        synchronized(lock) {
            val px = smoothX
            val py = smoothY
            if (px == null || py == null) {
                smoothX = x
                smoothY = y
                return PointF(x, y)
            }
            val d = hypot((x - px).toDouble(), (y - py).toDouble()).toFloat()
            if (d > MAX_JUMP_PX) {
                // 跳变过大：不更新，沿用上一帧（避免乱飘）
                return PointF(px, py)
            }
            val nx = px + (x - px) * SMOOTH_ALPHA
            val ny = py + (y - py) * SMOOTH_ALPHA
            smoothX = nx
            smoothY = ny
            return PointF(nx, ny)
        }
    }

    private fun smoothTilt(v: Float): Float = synchronized(lock) {
        val p = smoothTilt ?: v
        val n = p + (v - p) * TILT_SMOOTH_ALPHA
        smoothTilt = n
        n
    }

    private fun smoothScore(v: Float): Float = synchronized(lock) {
        val p = smoothScore ?: v
        val n = p + (v - p) * SMOOTH_ALPHA
        smoothScore = n
        n
    }

    // ---------- 分轴平滑（主体 X / Y 独立滤波，避免一轴抖动污染另一轴） ----------

    private var smoothSubX: Float? = null
    private var smoothSubY: Float? = null

    private fun smoothAxis(v: Float, isX: Boolean): Float = synchronized(lock) {
        val key = if (isX) smoothSubX else smoothSubY
        val p = key ?: v
        val n = p + (v - p) * SMOOTH_ALPHA
        if (isX) smoothSubX = n else smoothSubY = n
        n
    }

    /**
     * 带方向的移动提示：告诉用户"往哪边走、走多少"。
     * 对齐后返回拍摄建议。
     */
    private fun directionHintEx(
        subX: Float,
        subY: Float,
        boxCx: Float,
        boxCy: Float
    ): String {
        val dx = boxCx - subX
        val dy = boxCy - subY
        val thr = 0.03f
        val hPart = when {
            dx > thr -> "右"
            dx < -thr -> "左"
            else -> ""
        }
        val vPart = when {
            dy > thr -> "下"
            dy < -thr -> "上"
            else -> ""
        }
        return when {
            hPart.isEmpty() && vPart.isEmpty() -> "构图合适，可以拍摄"
            hPart.isEmpty() -> "向$vPart 移动主体"
            vPart.isEmpty() -> "向$hPart 移动主体"
            else -> "向$hPart$vPart 移动主体"
        }
    }

    /** 供面板直接读取的实时平滑值（锁定后仍可展示） */
    fun currentAnchor(): PointF? = synchronized(lock) {
        val x = smoothX ?: return null
        val y = smoothY ?: return null
        PointF(x, y)
    }

    fun currentScore(): Int = synchronized(lock) { (smoothScore ?: 0f).toInt() }

    fun currentTilt(): Float = synchronized(lock) { smoothTilt ?: 0f }

    // ---------- 主入口 ----------

    fun analyze(gray: Mat, currentZoom: Float): Advice? {
        if (locked) return null
        return doAnalyze(gray, currentZoom)
    }

    private fun doAnalyze(gray: Mat, currentZoom: Float): Advice? {
        if (gray.empty()) return null
        val w = gray.cols().toFloat()
        val h = gray.rows().toFloat()

        val horizon = detectHorizon(gray)
        val subject = findSubject(gray)
        val leading = detectLeadingLines(gray)

        val tilt = horizon?.angle ?: 0f
        val sTilt = smoothTilt(tilt)

        // 主体归一化坐标（找不到主体时退回画面中心）
        val rawSubX = ((subject?.x ?: (w / 2f)) / w).coerceIn(0f, 1f)
        val rawSubY = ((subject?.y ?: (h / 2f)) / h).coerceIn(0f, 1f)
        val subX = smoothAxis(rawSubX, true)
        val subY = smoothAxis(rawSubY, false)

        // ---------- 1. 选构图法则 + 生成推荐取景框 ----------
        // DOKA 的做法是先判断主体适合哪种法则，再给对应的框。
        val plan = choosePlan(subX, subY, horizon != null, leading != null)

        val box = plan.box
        // 【关键】主体中心 → 目标框中心 的归一化位移
        val dx = box.centerX() - subX
        val dy = box.centerY() - subY
        // 主体是否需要落在框内（宽松判据：主体接近框中心即算对齐）
        val tolX = box.width() * 0.35f
        val tolY = box.height() * 0.35f
        val inBox = abs(dx) <= tolX && abs(dy) <= tolY

        // ---------- 2. 对齐进度（分轴，用于引导线渐绿） ----------
        val alignX = alignRatio(dx, box.width() * 0.5f)
        val alignY = alignRatio(dy, box.height() * 0.5f)
        val alignProgress = ((alignX + alignY) / 2f).coerceIn(0f, 1f)
        // 对齐阈值 0.8：足够接近就变绿，避免用户反复微调到不可能达成的完美值
        val aligned = inBox && alignProgress >= 0.8f

        // ---------- 3. 构图评分 ----------
        val tiltPenalty = (abs(sTilt) / 10f).coerceAtMost(1f) * 40f
        val boxPenalty = (1f - alignProgress) * 50f
        val rawScore = (100f - tiltPenalty - boxPenalty).coerceIn(0f, 100f)
        val score = smoothScore(rawScore).toInt()

        // ---------- 4. 智能焦段推荐（DOKA 特性） ----------
        // 按主体在画面中的占比推荐：占比过小 → 推近，过大 → 拉远。
        val subjectArea = subject?.let { s ->
            (s.radius * 2f / w) * (s.radius * 2f / h)
        } ?: 0f
        val recommendZoom = recommendZoomFor(subjectArea, currentZoom)

        // ---------- 5. AR 引导线（从主体当前位置指向推荐框） ----------
        val arLines = buildArLines(subX, subY, box, aligned)

        // ---------- 6. 组装建议 ----------
        val dirHint = if (aligned) "构图合适，可以拍摄"
        else directionHintEx(subX, subY, box.centerX(), box.centerY())

        // 【修复】deviceRollDeg 是 @Volatile var（会被传感器线程并发改写），
        // Kotlin 不允许对可变属性做 smart-cast；且 when 的条件位置也不能写语句。
        // 所以先取局部快照，再进 when。
        val deviceRoll = deviceRollDeg
        val type: String
        val message: String
        when {
            // 【关键修复】原来这里的条件是 `horizon != null && abs(sTilt) > 2.5f`，
            // 而 sTilt 来自 detectHorizon() —— 它找的是**画面内容里**最长的
            // 近水平直线段（屋檐、栏杆、桌面边缘、书架、地砖缝），
            // 语义是「场景里的线歪了」，不是「设备本身歪了」。
            // 却拿它生成「向 X 微转」的转手机指令，且这一分支会**覆盖**掉
            // 正常的构图提示。后果双向：
            //   - 手机端得很稳，只是画面里有条 8° 斜屋顶 → 提示「地平线右倾 8.3°」
            //   - 手机歪了 15°，但画面里的线是水平的 → 完全不提示
            //
            // 现在：水平线只用于「构图法则」（choosePlan 里的"水平构图"），
            // **不再生成转手机指令**。设备的 roll 判断交由 OrientationTracker
            // （陀螺仪）在 MainActivity 侧做，语义正确且不误报。
            // 这里改为基于设备自身姿态（由调用方通过 setDeviceRoll 注入）来提示。
            deviceRoll != null && abs(deviceRoll) > 2.5f -> {
                val roll = deviceRoll
                type = "水平校正"
                message = "手机%s倾 %.1f°，请%s转正手机".format(
                    if (roll > 0) "右" else "左",
                    abs(roll),
                    if (roll > 0) "向左" else "向右"
                )
            }
            aligned -> {
                type = plan.ruleName
                message = "构图合适 · ${plan.ruleName}"
            }
            else -> {
                type = plan.ruleName
                message = plan.message
            }
        }

        // 代表点 → 平滑跟随（保持原有点位平滑能力，供瞄准环复用）
        val rep = PointF(box.centerX() * w, box.centerY() * h)
        val anchor = smoothPoint(rep.x, rep.y) ?: return null

        val fovApprox = 65f
        val screenRatio = (anchor.x / w) - 0.5f
        val targetYawDelta = -screenRatio * fovApprox

        AppLogger.d(
            TAG,
            "rule=${plan.ruleName} aligned=$aligned progress=%.2f score=$score " +
                "sub=(%.2f,%.2f) box=[%.2f,%.2f,%.2f,%.2f] zoom=%.1f".format(
                    alignProgress, subX, subY,
                    box.left, box.top, box.right, box.bottom, recommendZoom
                )
        )

        return Advice(
            type = type,
            message = message,
            zoomDelta = if (recommendZoom > 0f) recommendZoom - currentZoom else 0f,
            targetZoom = recommendZoom,
            // 【关键修复】原来 targetYawDelta 在上面算完却**没有传进 Advice**，
            // 于是它永远是 data class 的默认值 0f。
            // 后果（两个症状）：
            //  1. FullRes/MultiConcurrent 的到位判定是
            //     `abs(advice.targetYawDelta - curYaw) < 5f`，
            //     恒等于 `abs(0 - curYaw) < 5` → 手机一动就误判"已到位"，
            //     弹 Toast + 自动变焦；反过来真到位时也判不出来。
            //  2. 主体在画面正中时 targetYawDelta 本应就是 0（正确），
            //     但因为"永远 0"，任何位置都是 0，失去了全部意义。
            // 现在把真实目标角差传下去。
            targetYawDelta = targetYawDelta,
            guidePoints = if (aligned) listOf(rep) else listOf(rep, PointF(subX * w, subY * h)),
            horizonAngle = sTilt,
            guideAnchor = anchor,
            score = score,
            subjectX = subX,
            subjectY = subY,
            hint = dirHint,
            targetBox = box,
            aligned = aligned,
            alignProgress = alignProgress,
            alignX = alignX,
            alignY = alignY,
            ruleName = plan.ruleName,
            recommendZoom = recommendZoom,
            arLines = arLines
        )
    }

    /** 构图方案：法则名 + 推荐框 + 提示语 */
    private data class CompPlan(
        val ruleName: String,
        val box: android.graphics.RectF,
        val message: String
    )

    /**
     * 选择构图法则并给出推荐取景框。
     *
     * 判定优先级参考 DOKA：
     *   1. 主体明显偏离中心且接近三分点 → 三分法
     *   2. 主体居中且近对称 → 对称构图（框居中）
     *   3. 存在明显引导线 → 引导线构图（框放在线条汇聚侧的三分点）
     *   4. 兜底 → 三分法（最通用的法则）
     */
    private fun choosePlan(
        subX: Float,
        subY: Float,
        hasHorizon: Boolean,
        hasLeading: Boolean
    ): CompPlan {
        // 推荐框尺寸：占画面 62%（留出安全边距，DOKA 的框也是留白型）
        val bw = 0.62f
        val bh = (bw / CompositionAnalyzer.TARGET_BOX_RATIO).coerceAtMost(0.72f)

        val centerDist = hypot((subX - 0.5f).toDouble(), (subY - 0.5f).toDouble()).toFloat()

        // 【关键修复】全部判断加滞回（hysteresis）。
        // subX/subY 虽已过 EMA 平滑（α=0.28），但主体在中线附近时仍会在 0.5
        // 上下微颤；原来 `if (subX < 0.5f)` 没有死区 → tx 每帧在 1/3 与 2/3
        // 之间翻转 → **取景框左右横跳、法则名在三种之间闪**（分析节流 400ms，
        // 看起来就是"框每隔 0.4 秒弹一下"）。hasLeading 更是二值抖动源：
        // 只要画面里有任何一条过阈值的线段就翻转。
        // 做法：三分点选择记住上一次结果，只有偏离死区（HYSTERESIS）才换边。
        fun pickThird(v: Float, last: Int): Int {
            val prev = if (last == 1) 1 else 2
            val center = 0.5f
            // 上次选左：只有明显偏右才换右；上次选右：只有明显偏左才换左
            val lo = if (prev == 1) center - HYSTERESIS else center
            val hi = if (prev == 1) center else center + HYSTERESIS
            return when {
                v <= lo -> 1
                v >= hi -> 2
                else -> prev
            }
        }
        val sideX = pickThird(subX, lastSideX)
        lastSideX = sideX

        // 对称构图：主体接近画面中心（用滞回避免在 0.12 边界反复穿越）
        val symmetricNow = lastRule == "对称构图"
        val symmetric = if (symmetricNow) centerDist < 0.16f else centerDist < 0.12f
        if (symmetric) {
            lastRule = "对称构图"
            return CompPlan(
                ruleName = "对称构图",
                box = boxAt(0.5f, 0.5f, bw, bh),
                message = "主体居中，保持对称平衡"
            )
        }

        // 引导线构图：有明确引导线且主体偏离中心
        // 【修复】hasLeading 加最小持续帧数，避免单帧线段导致法则名闪变
        leadingStreak = if (hasLeading) leadingStreak + 1 else 0
        if (leadingStreak >= LEADING_MIN_STREAK && centerDist > 0.15f) {
            // 框放在主体所在侧的三分点，顺引导线方向
            val tx = if (sideX == 1) 1f / 3f else 2f / 3f
            val ty = if (subY < 0.4f) 1f / 3f else if (subY > 0.6f) 2f / 3f else 0.5f
            lastRule = "引导线构图"
            return CompPlan(
                ruleName = "引导线构图",
                box = boxAt(tx, ty, bw * 0.92f, bh * 0.92f),
                message = "沿引导线把主体放到%s三分点".format(if (sideX == 1) "左" else "右")
            )
        }

        // 三分法：最通用，把主体吸附到最近的三分点
        // 【关键修复】原来无论主体竖直位置如何，ty 都被硬拉到 1/3 或 2/3 ——
        // 只要画面里检测到一条近水平线（窗框/桌面边缘/地平线）且主体偏中心，
        // 就完全忽略水平关系，直接建议"把主体移到上/下三分点"。
        // 现在：水平线存在且主体已接近垂直中线时，给出「水平构图」——
        // 保持水平、只微调垂直位置，这才是水平构图法则的意义
        // （同时让 hasHorizon 这个参数真正被使用，不再是死参数）。
        if (hasHorizon && subY in 0.4f..0.6f) {
            lastRule = "水平构图"
            val hx = if (sideX == 1) 1f / 3f else 2f / 3f
            val hSide = if (sideX == 1) "左" else "右"
            return CompPlan(
                ruleName = "水平构图",
                box = boxAt(hx, 0.5f, bw, bh),
                message = "沿水平线构图，主体保持在" + hSide + "三分点并压低地平线"
            )
        }

        val tx = if (sideX == 1) 1f / 3f else 2f / 3f
        val ty = if (subY < 0.5f) 1f / 3f else 2f / 3f
        lastRule = "三分法"
        return CompPlan(
            ruleName = "三分法",
            box = boxAt(tx, ty, bw, bh),
            message = "把主体移入%s三分点取景框".format(
                when {
                    sideX == 1 && subY < 0.5f -> "左上"
                    sideX == 2 && subY < 0.5f -> "右上"
                    sideX == 1 -> "左下"
                    else -> "右下"
                }
            )
        )
    }

    /** 三分点左右选择的滞回死区（相对中线 0.5 的偏移） */
    private const val HYSTERESIS = 0.06f

    /** 引导线需连续命中这么多帧才认定，避免法则名闪变 */
    private const val LEADING_MIN_STREAK = 3

    @Volatile private var lastSideX = 1
    @Volatile private var leadingStreak = 0
    @Volatile private var lastRule = ""

    /** 以归一化中心点构造推荐框，自动钳制在画面内 */
    private fun boxAt(cx: Float, cy: Float, bw: Float, bh: Float): android.graphics.RectF {
        // 框中心不贴边，至少留 4% 边距
        val left = (cx - bw / 2f).coerceIn(0.02f, (1f - bw - 0.02f).coerceAtLeast(0.02f))
        val top = (cy - bh / 2f).coerceIn(0.02f, (1f - bh - 0.02f).coerceAtLeast(0.02f))
        return android.graphics.RectF(left, top, left + bw, top + bh)
    }

    /** 分轴对齐进度：位移越小越接近 1 */
    private fun alignRatio(delta: Float, halfSpan: Float): Float {
        if (halfSpan <= 0f) return 0f
        return (1f - abs(delta) / halfSpan).coerceIn(0f, 1f)
    }

    /**
     * 智能焦段推荐。
     *
     * @param subjectArea 主体占画面面积比（0..1）
     * @param currentZoom 当前变焦
     *
     * 主体占画面 6%~18% 视为理想（人像/主体的常见构图占比）；
     * 过小则倍率放大，过大则适当拉远。结果按常见档位吸附，避免连续跳变。
     */
    private fun recommendZoomFor(subjectArea: Float, currentZoom: Float): Float {
        if (subjectArea <= 0.001f) return 0f   // 没识别到主体，不推荐
        // 【关键修复】ideal 由 0.12 改为 0.30。
        // subjectArea = (2r/w)×(2r/h)，而 r 是**整幅画面 Canny 边缘点的空间标准差**，
        // 不是主体大小。边缘近似均匀铺满时 std ≈ 0.29×边长 →
        // subjectArea ≈ (0.58)² ≈ 0.34。
        // 原 ideal=0.12 使得 ratio = sqrt(0.12/0.34) ≈ 0.59 **恒小于 1**，
        // 推荐方向永远是"拉远"，注释里写的"占比过小则倍率放大"永远不会触发，
        // 且用户手动拉到 10× 后会被自动变焦慢慢拽回来。
        // 把 ideal 提到 0.30（贴近边缘分布的实际中位）后，
        // ratio 才能同时覆盖"放大"与"拉远"两个方向。
        val ideal = 0.30f
        val ratio = kotlin.math.sqrt(ideal / subjectArea)
        val target = (currentZoom * ratio).coerceIn(1f, 6f)
        if (abs(target - currentZoom) < 0.15f) return 0f  // 差异太小，不打扰
        // 吸附到常用档位
        val stops = floatArrayOf(1f, 1.5f, 2f, 3f, 4f, 5f, 6f)
        val snapped = stops.minByOrNull { abs(it - target) } ?: target
        // 【修复】吸附后若与当前几乎相同就不要推荐，
        // 否则会在 1.0↔1.5 之间形成极限环振荡（每 400ms 跳一次）
        return if (abs(snapped - currentZoom) < 0.15f) 0f else snapped
    }

    /**
     * 构造 AR 引导线（归一化端点对）。
     *
     * DOKA 的引导线是从"主体现在的位置"延伸向"推荐框"，
     * 对齐后线条收起、只留一个绿色完成标记。
     */
    private fun buildArLines(
        subX: Float,
        subY: Float,
        box: android.graphics.RectF,
        aligned: Boolean
    ): List<Pair<PointF, PointF>> {
        if (aligned) return emptyList()
        val target = PointF(box.centerX(), box.centerY())
        val cur = PointF(subX, subY)
        // 主引导线：当前位置 → 目标中心
        val main = cur to target
        // 辅助线：连接框的四角与中心，形成"取景框"视觉暗示
        val corners = listOf(
            PointF(box.left, box.top),
            PointF(box.right, box.top),
            PointF(box.right, box.bottom),
            PointF(box.left, box.bottom)
        )
        val helpers = corners.map { it to target }
        return listOf(main) + helpers
    }

    /** 主体检测：返回重心与等效半径 */
    private data class Subject(val x: Float, val y: Float, val radius: Float)

    /**
     * 主体定位。
     *
     * 用「边缘密度 + 中心加权」估计主体：远离中心的边缘权重衰减，
     * 避免画面边缘的杂乱纹理（树叶、栏杆）把重心带偏。
     * 同时估计主体等效半径，用于焦段推荐。
     */
    private fun findSubject(gray: Mat): Subject? {
        val edges = Mat()
        Imgproc.Canny(gray, edges, 60.0, 180.0)
        try {
            val w = gray.cols()
            val h = gray.rows()
            val m = Imgproc.moments(edges)
            if (m.m00 < 1.0) return null

            var cx = (m.m10 / m.m00)
            var cy = (m.m01 / m.m00)

            // 中心加权：若重心贴边，收缩到画面中心与重心的中点（更符合"主体在画面中"的先验）
            val nx = cx / w
            val ny = cy / h
            if (nx < 0.15f || nx > 0.85f) cx = (cx + w / 2.0) / 2.0
            if (ny < 0.15f || ny > 0.85f) cy = (cy + h / 2.0) / 2.0

            // 等效半径：用边缘点分布的标准差近似（moments 的 m20/m02）
            val varX = (m.m20 / m.m00) - (m.m10 / m.m00) * (m.m10 / m.m00)
            val varY = (m.m02 / m.m00) - (m.m01 / m.m00) * (m.m01 / m.m00)
            val radius = kotlin.math.sqrt(
                (kotlin.math.abs(varX) + kotlin.math.abs(varY)) / 2.0
            ).toFloat().coerceAtLeast(4f)

            return Subject(cx.toFloat(), cy.toFloat(), radius)
        } finally {
            edges.release()
        }
    }

    // ---------- 地平线 ----------

    private data class Horizon(val angle: Float, val linePoints: List<PointF>)

    private fun detectHorizon(gray: Mat): Horizon? {
        val edges = Mat()
        Imgproc.Canny(gray, edges, 60.0, 180.0)
        val lines = Mat()
        Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180, 80, 120.0, 20.0)
        edges.release()

        var best: Horizon? = null
        var bestLen = 0f
        for (i in 0 until lines.rows()) {
            val l = lines.get(i, 0) ?: continue
            val x1 = l[0]; val y1 = l[1]; val x2 = l[2]; val y2 = l[3]
            val dx = (x2 - x1).toFloat()
            val dy = (y2 - y1).toFloat()
            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            if (abs(angle) > 25f) continue
            val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (len > bestLen) {
                bestLen = len
                best = Horizon(
                    angle = angle,
                    linePoints = listOf(
                        PointF(x1.toFloat(), y1.toFloat()),
                        PointF(x2.toFloat(), y2.toFloat())
                    )
                )
            }
        }
        lines.release()
        return best
    }

    // ---------- 引导线 ----------

    private fun detectLeadingLines(gray: Mat): List<PointF>? {
        val edges = Mat()
        Imgproc.Canny(gray, edges, 80.0, 200.0)
        val lines = Mat()
        Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180, 60, 100.0, 30.0)
        edges.release()

        var bestLen = 0f
        var bestPoints: List<PointF>? = null
        for (i in 0 until lines.rows()) {
            val l = lines.get(i, 0) ?: continue
            val x1 = l[0]; val y1 = l[1]; val x2 = l[2]; val y2 = l[3]
            val dx = x2 - x1; val dy = y2 - y1
            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            if (abs(angle) < 15f || abs(angle) > 75f) continue
            val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (len > bestLen) {
                bestLen = len
                bestPoints = listOf(
                    PointF(x1.toFloat(), y1.toFloat()),
                    PointF(x2.toFloat(), y2.toFloat())
                )
            }
        }
        lines.release()
        return bestPoints
    }
}
