package com.example.multicam

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

object CompositionAnalyzer {

    private const val TAG = "CompositionAnalyzer"

    data class Suggestion(
        val type: String,
        val confidence: Float,
        val guidePoints: List<PointF>,
        val message: String,
        // 构图面板所需扩展字段（由 SceneAdvisor 填充）
        val score: Int = 0,
        val tiltDeg: Float = 0f,
        val subjectX: Float = 0.5f,
        val subjectY: Float = 0.5f,
        val hint: String = "",
        // ---------- 【新增】DOKA 风格 AR 引导所需字段 ----------
        /**
         * 推荐取景框（归一化 0..1，相对分析帧）。
         * DOKA 的做法是给用户一个「框」，把主体移动进框内即可，
         * 比单个瞄准点更直观——用户不需要理解"三分点"是什么。
         */
        val targetBox: android.graphics.RectF? = null,
        /**
         * 主体当前所在位置（归一化 0..1），用于画「当前位置 → 目标框」的 AR 引导线箭头。
         */
        val hasSubject: Boolean = false,
        /**
         * 是否已对齐到位。DOKA 用「引导线变绿」表示构图合适，
         * 这里即 [aligned] = true 时所有引导元素切换为绿色并通过。
         */
        val aligned: Boolean = false,
        /**
         * 对齐进度 0..1（用于引导线渐变与进度反馈）。
         */
        val alignProgress: Float = 0f,
        /**
         * 分轴对齐进度（0..1），用于分别提示"横向还差多少 / 纵向还差多少"。
         */
        val alignX: Float = 0f,
        val alignY: Float = 0f,
        /**
         * 当前建议的构图法则名称（三分法 / 对称 / 引导线 / 水平），
         * 展示在取景框上方，像 DOKA 一样告诉用户"按哪种法则构图"。
         */
        val ruleName: String = "",
        /**
         * 智能焦段推荐（等效焦距档位，1.0 = 主摄原生）。
         * DOKA 会按主体占比推荐焦段，0f 表示不推荐。
         */
        val recommendZoom: Float = 0f,
        /**
         * 引导线（AR 动态线）：一条或多条从画面元素延伸、指目标框的线。
         * 每项是一对归一化端点。
         */
        val arLines: List<Pair<PointF, PointF>> = emptyList()
    )

    /** 推荐取景框的宽高比（3:4 竖构图常用比例），供 AR 框绘制对齐使用 */
    const val TARGET_BOX_RATIO = 3f / 4f

    /**
     * 分析单帧 BGR Mat，返回构图建议（无建议时返回 null）
     */
    fun analyze(frame: Mat): Suggestion? {
        if (frame.empty()) return null
        val gray = Mat()
        try {
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY)
            return analyzeGray(gray)
        } finally {
            gray.release()
        }
    }

    /**
     * 分析单帧灰度 Mat（预览 YUV 直接转灰度，避免二次转换）。
     * 统一在 640×480 空间计算，guidePoints 与 SceneAdvisor 同坐标系。
     */
    fun analyzeGray(grayInput: Mat): Suggestion? {
        if (grayInput.empty()) return null
        val gray = Mat()
        val edges = Mat()
        try {
            Imgproc.resize(grayInput, gray, org.opencv.core.Size(640.0, 480.0))
            Imgproc.Canny(gray, edges, 50.0, 150.0)

            val m = Imgproc.moments(edges)
            if (m.m00 < 1.0) return null

            val cx = (m.m10 / m.m00).toFloat()
            val cy = (m.m01 / m.m00).toFloat()
            val w = 640f; val h = 480f

            // 三分线交点
            val thirdsX = listOf(w / 3f, w * 2 / 3f)
            val thirdsY = listOf(h / 3f, h * 2 / 3f)

            var bestDist = Float.MAX_VALUE
            var bestPt: PointF? = null
            for (tx in thirdsX) for (ty in thirdsY) {
                val d = Math.hypot((cx - tx).toDouble(), (cy - ty).toDouble()).toFloat()
                if (d < bestDist) { bestDist = d; bestPt = PointF(tx, ty) }
            }

            val maxDist = Math.hypot(w.toDouble(), h.toDouble()).toFloat() * 0.5f
            if (bestDist > maxDist * 0.35f && bestPt != null) {
                return Suggestion(
                    type = "rule_of_thirds",
                    confidence = (1f - bestDist / maxDist).coerceIn(0f, 1f),
                    guidePoints = listOf(bestPt),
                    message = "主体偏向一侧，试试三分构图"
                )
            }
            return null
        } finally {
            gray.release(); edges.release()
        }
    }

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.1f)
            .build()
    )

    /**
     * 人像虚化专用：同步语义地拿到人脸框（像素坐标，相对传入 bitmap）。
     *
     * 与 [analyzeFace] 的区别：后者只返回构图建议（含相对坐标的引导点），
     * 拿不到原始 `boundingBox`。软件虚化需要精确的人脸矩形来画保护椭圆。
     *
     * 注意 ML Kit 是异步的，回调可能在任意线程；调用方自行切线程。
     */
    fun detectFaceBoxes(bitmap: Bitmap, onBoxes: (List<android.graphics.RectF>) -> Unit) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            faceDetector.process(image)
                .addOnSuccessListener { faces ->
                    onBoxes(faces.map { android.graphics.RectF(it.boundingBox) })
                }
                .addOnFailureListener { e ->
                    AppLogger.w(TAG, "人脸检测失败: ${e.message}")
                    onBoxes(emptyList())
                }
        } catch (e: Exception) {
            AppLogger.w(TAG, "人脸检测异常: ${e.message}")
            onBoxes(emptyList())
        }
    }

    /**
     * 用 MLKit 检测人脸，判断构图
     */
    fun analyzeFace(bitmap: Bitmap, onResult: (Suggestion?) -> Unit) {        val image = InputImage.fromBitmap(bitmap, 0)
        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) { onResult(null); return@addOnSuccessListener }

                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    ?: run { onResult(null); return@addOnSuccessListener }

                val box = face.boundingBox
                val w = bitmap.width.toFloat()
                val h = bitmap.height.toFloat()

                val cx = box.centerX() / w
                val cy = box.centerY() / h

                val idealXs = listOf(1f / 3, 2f / 3)
                val idealYs = listOf(1f / 3, 2f / 3)

                var bestDist = Float.MAX_VALUE
                var bestX = 0f; var bestY = 0f
                for (ix in idealXs) for (iy in idealYs) {
                    val d = Math.hypot((cx - ix).toDouble(), (cy - iy).toDouble()).toFloat()
                    if (d < bestDist) { bestDist = d; bestX = ix; bestY = iy }
                }

                if (bestDist > 0.15f) {
                    onResult(Suggestion(
                        type = "face_thirds",
                        confidence = 1f - bestDist,
                        guidePoints = listOf(PointF(bestX * 640f, bestY * 480f)),
                        message = "人像偏离三分线，尝试移动构图"
                    ))
                } else {
                    onResult(null)
                }
            }
            .addOnFailureListener { onResult(null) }
    }
}
