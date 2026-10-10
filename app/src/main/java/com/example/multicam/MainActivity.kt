package com.example.multicam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity() {

    // ---------- 视图 ----------
    private lateinit var preview: GLSurfaceView
    private lateinit var tvStatus: TextView
    private lateinit var tvModeBadge: TextView
    private lateinit var btnShutter: FrameLayout
    private lateinit var shutterCore: View
    private lateinit var thumbContainer: FrameLayout
    private lateinit var ivThumb: ImageView
    private lateinit var colorWheel: ColorWheelView
    private lateinit var compositionOverlayView: CompositionOverlayView
    private lateinit var compositionPanel: CompositionPanelView
    private lateinit var gridOverlay: GridOverlayView
    private lateinit var focusRing: View
    private lateinit var progressPanel: View
    private lateinit var tvProgress: TextView
    private lateinit var zoomRow: LinearLayout
    private lateinit var modeStrip: LinearLayout
    private lateinit var quickRowScroll: android.widget.HorizontalScrollView
    private lateinit var quickRow: LinearLayout
    private lateinit var btnFlash: FrameLayout
    private lateinit var ivFlash: ImageView
    private lateinit var btnComposition: FrameLayout
    private lateinit var btnGrid: FrameLayout
    private lateinit var btnPalette: FrameLayout
    private lateinit var btnSettings: FrameLayout
    private lateinit var btnSwitch: FrameLayout

    // 专业模式
    private lateinit var proPanel: View
    private lateinit var sbEv: SeekBar
    private lateinit var sbIso: SeekBar
    private lateinit var sbShutter: SeekBar
    private lateinit var sbWb: SeekBar
    private lateinit var tvEv: TextView
    private lateinit var tvIso: TextView
    private lateinit var tvShutter: TextView
    private lateinit var tvWb: TextView

    // 调色面板
    private lateinit var colorPanel: View
    private lateinit var filterRow: LinearLayout
    private lateinit var sbSaturation: SeekBar
    private lateinit var sbContrast: SeekBar
    private lateinit var sbTemperature: SeekBar
    private lateinit var tvSaturation: TextView
    private lateinit var tvContrast: TextView
    private lateinit var tvTemperature: TextView

    // 设置面板
    private lateinit var settingsPanel: View
    private lateinit var settingsList: LinearLayout

    private val renderer = GpuPreviewRenderer()
    private var controller: CameraController? = null
    private lateinit var orientationTracker: OrientationTracker
    private var cameraStarted = false
    private var previewSurface: Surface? = null
    private var glSurfaceTexture: SurfaceTexture? = null
    private var previewBufferW = 1920
    private var previewBufferH = 1080

    private var sensorOrientation = 90
    private var lastUri: Uri? = null

    // ---------- 设置 ----------
    private var preferQuality = true
    private var framesPerCamera = 3
    private var enableAI = false
    private var enableAIDenoise = false
    private var mirrorX = false
    private var extraRotation = 0
    private var preferredRole: CameraRole? = null
    private var autoZoomEnabled = false
    private var autoColorEnabled = false
    private var rawEnabled = false
    private var videoMode = false
    private var videoSession: MultiVideoSession? = null
    private var panoramaMode = false
    // 修复（2026-10）：handleResult 由 CameraController 后台线程 / BackgroundProcessor
    // 线程池回调，多摄路径下还会并发进入，原来的 ArrayList 无任何同步 →
    // 并发 add 会触发 ArrayIndexOutOfBounds / 丢帧。改用线程安全列表，并在
    // 快照时复制。
    private val panoramaFrames = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
    private var afAeLocked = false
    /** 全景首帧时间戳：> PANO_MIN_INTERVAL_MS 才允许再拍一张，防止手抖连拍成静止画面 */
    private var panoStartAt = 0L

    private var colorGradeParams = ColorGradeParams()
    private var colorWheelVisible = false
    private var compositionEnabled = false
    private var compositionLocked = false

    /** 【新增】最近一次构图法则名（DOKA 风格），锁定后在面板上展示 */
    @Volatile private var lastCompositionRule = ""
    private var currentFilter = 0

    private var flashMode = CameraControls.FLASH_OFF
    private var gridEnabled = false
    private var evValue = 0f
    private var isoIndex = 0
    private var shutterIndex = 0
    private var wbIndex = 0
    private var currentMode = "拍照"
    private var zoomValue = 1f

    // ---------- 【新增】5 个 vivo 风格模式的状态 ----------
    /** 时光慢门子档：光轨 / 丝绢 */
    private var slowShutterStyle = LongExposureProcessor.Style.LIGHT_TRAIL
    /** 人像虚化强度（0..100，映射到高斯核） */
    private var bokehStrength = 55
    /** 模式处理期间正在运行的标志（防止重入） */
    @Volatile private var modeProcessing = false

    /**
     * 【新增】Activity 已销毁标志。
     *
     * 相机回调与 [BackgroundProcessor] 的线程是进程级的、不随 Activity 结束而停止，
     * 若在 onDestroy 之后仍调用 runOnUiThread 操作已销毁的 View（tvStatus /
     * ivThumb / Toast 等），会抛 IllegalStateException 或操作到 stale 引用。
     * 所有跨线程回 UI 的入口都先检查此标志。
     */
    @Volatile private var thisDestroyed = false

    private companion object {
        const val FRAMES_NIGHT = 6
        const val FRAMES_ASTRO = 4
        const val FRAMES_SLOW_SHUTTER = 8
        /** 全景两帧之间的最小间隔，防止手抖拍出静止画面 */
        const val PANO_MIN_INTERVAL_MS = 450L
    }

    private val requiredPermissions = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startCameraIfReady()
        else {
            Toast.makeText(this, "权限被拒绝", Toast.LENGTH_LONG).show()
            tvStatus.text = "权限被拒绝"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        restoreSettings()
        bindViews()
        buildZoomRow()
        buildModeStrip()
        buildQuickRow()
        buildFilterRow()
        buildSettingsList()

        AIDenoise.init(this)
        AISuperResolution.init(this)
        syncToggleStates()

        orientationTracker = OrientationTracker(this)

        // 修复（2026-10）：BackgroundProcessor 是进程级单例，直接赋 lambda 会持有
        // MainActivity 引用造成泄漏。用 WeakReference 包裹，Activity 回收后自动失效；
        // 同时在 onDestroy 里显式置空。
        val self = WeakReference(this)
        BackgroundProcessor.setOnJobsChanged {
            val act = self.get() ?: return@setOnJobsChanged
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                val n = BackgroundProcessor.activeJobs().size
                act.tvModeBadge.text = act.badgeText(n)
            }
        }

        setupTopBar()
        setupListeners()
        setupProPanel()
        setupColorPanel()
        setupSettingsPanel()
        setupManualFocus()
        setupGlPreview()
    }

    // ================= 视图绑定 =================

    private fun bindViews() {
        preview = findViewById(R.id.preview)
        tvStatus = findViewById(R.id.tvStatus)
        tvModeBadge = findViewById(R.id.tvModeBadge)
        btnShutter = findViewById(R.id.btnShutter)
        shutterCore = findViewById(R.id.shutterCore)
        thumbContainer = findViewById(R.id.thumbContainer)
        ivThumb = findViewById(R.id.ivThumb)
        colorWheel = findViewById(R.id.colorWheel)
        compositionOverlayView = findViewById(R.id.compositionOverlay)
        compositionPanel = findViewById(R.id.compositionPanel)
        gridOverlay = findViewById(R.id.gridOverlay)
        focusRing = findViewById(R.id.focusRing)
        progressPanel = findViewById(R.id.progressPanel)
        tvProgress = findViewById(R.id.tvProgress)
        zoomRow = findViewById(R.id.zoomRow)
        modeStrip = findViewById(R.id.modeStrip)
        quickRowScroll = findViewById(R.id.quickRowScroll)
        quickRow = findViewById(R.id.quickRow)
        btnFlash = findViewById(R.id.btnFlash)
        ivFlash = findViewById(R.id.ivFlash)
        btnComposition = findViewById(R.id.btnComposition)
        btnGrid = findViewById(R.id.btnGrid)
        btnPalette = findViewById(R.id.btnPalette)
        btnSettings = findViewById(R.id.btnSettings)
        btnSwitch = findViewById(R.id.btnSwitch)

        proPanel = findViewById(R.id.proPanel)
        sbEv = proPanel.findViewById(R.id.sbEv)
        sbIso = proPanel.findViewById(R.id.sbIso)
        sbShutter = proPanel.findViewById(R.id.sbShutter)
        sbWb = proPanel.findViewById(R.id.sbWb)
        tvEv = proPanel.findViewById(R.id.tvEv)
        tvIso = proPanel.findViewById(R.id.tvIso)
        tvShutter = proPanel.findViewById(R.id.tvShutter)
        tvWb = proPanel.findViewById(R.id.tvWb)

        colorPanel = findViewById(R.id.colorPanel)
        filterRow = colorPanel.findViewById(R.id.filterRow)
        sbSaturation = colorPanel.findViewById(R.id.sbSaturation)
        sbContrast = colorPanel.findViewById(R.id.sbContrast)
        sbTemperature = colorPanel.findViewById(R.id.sbTemperature)
        tvSaturation = colorPanel.findViewById(R.id.tvSaturation)
        tvContrast = colorPanel.findViewById(R.id.tvContrast)
        tvTemperature = colorPanel.findViewById(R.id.tvTemperature)

        settingsPanel = findViewById(R.id.settingsPanel)
        settingsList = settingsPanel.findViewById(R.id.settingsList)
    }

    private fun restoreSettings() {
        preferQuality = SettingsStore.loadPreferQuality(this)
        framesPerCamera = SettingsStore.loadFrames(this)
        enableAI = SettingsStore.loadAI(this)
        enableAIDenoise = SettingsStore.loadDenoise(this)
        mirrorX = SettingsStore.loadMirror(this)
        extraRotation = SettingsStore.loadExtraRotation(this)
        preferredRole = SettingsStore.loadPreferredRole(this)
        autoZoomEnabled = SettingsStore.loadAutoZoom(this)
        autoColorEnabled = SettingsStore.loadAutoColor(this)
        // 修复（2026-10）：以下三项原先没有恢复，冷启动总是回到默认关闭
        rawEnabled = SettingsStore.loadRaw(this)
        compositionEnabled = SettingsStore.loadComposition(this)
        compositionLocked = SettingsStore.loadCompositionLock(this)
        // 【新增】模式相关偏好
        slowShutterStyle = if (SettingsStore.loadSlowStyle(this) == 1) {
            LongExposureProcessor.Style.SILK
        } else LongExposureProcessor.Style.LIGHT_TRAIL
        bokehStrength = SettingsStore.loadBokeh(this)
    }

    private fun badgeText(jobs: Int = 0): String = buildString {
        append(if (preferQuality) "全像素" else "多摄")
        if (jobs > 0) append(" · 任务").append(jobs)
    }

    // ================= 动态构建：变焦条 =================

    private fun buildZoomRow() {
        zoomRow.removeAllViews()
        val stops = zoomStops()
        stops.forEach { z ->
            val tv = TextView(this).apply {
                text = if (z == z.toInt().toFloat()) "${z.toInt()}×" else "%.1f×".format(z)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = ContextCompat.getDrawable(
                    this@MainActivity,
                    if (z == zoomValue) R.drawable.bg_zoom_pill_sel else R.drawable.bg_zoom_pill
                )
                setOnClickListener {
                    zoomValue = z
                    controller?.setZoom(z)
                    buildZoomRow()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(5); marginEnd = dp(5) }
            zoomRow.addView(tv, lp)
        }
    }

    private fun zoomStops(): List<Float> {
        val maxZ = controller?.maxZoom() ?: 10f
        val list = mutableListOf(1f)
        val roles = controller?.availableFocalStops().orEmpty()
        roles.forEach { s -> if (s > 1.05f) list.add(s) }
        if (!list.contains(2f) && maxZ >= 2f) list.add(2f)
        list.addAll(listOf(5f, 10f))
        return list.distinct().filter { it <= maxZ + 0.01f }.sorted()
    }

    // ================= 动态构建：模式条 =================

    private data class Mode(val name: String, val available: Boolean, val note: String = "")

    private fun modeList(): List<Mode> {
        val longExp = controller?.longExposureSupported() == true
        return listOf(
            Mode("拍照", true),
            Mode("人像", true),                    // 软件虚化（ML Kit 人脸 + 高斯模糊）
            Mode("夜景", true),                    // 多帧时域降噪堆栈
            Mode("录像", controller?.concurrentCameraIds().orEmpty().size >= 2),
            Mode("专业", true),
            Mode("全景", true),
            Mode("超级月亮", false, "依赖厂商月亮识别与长焦算法（本机不可用）"),
            Mode(
                "星空", longExp,
                "本机不支持长曝光（SENSOR_INFO_EXPOSURE_TIME_RANGE 上限过低）"
            ),
            Mode(
                "时光慢门", longExp,
                "本机不支持长曝光（SENSOR_INFO_EXPOSURE_TIME_RANGE 上限过低）"
            ),
            Mode("HDR", true),                     // 曝光包围 + 中间调加权融合
            Mode("高像素", true)
        )
    }

    /** 【新增】模式 → 采集计划（逐帧 EV / ISO / 快门） */
    private fun planForMode(name: String): BracketPlan {
        // maxExp 为 0 表示未探测到上限（能力探测失败），此时不做上限裁剪，
        // 交给 HAL 自行拒绝/收敛，而不是把曝光时间压成 0。
        val maxExp = controller?.maxExposureNs() ?: 0L
        val clampShutter: (Long) -> Long = { ns ->
            val floor = 1_000_000L // 至少 1ms，避免出现 0 曝光的非法请求
            if (maxExp > 0L) ns.coerceIn(floor, maxExp) else ns.coerceAtLeast(floor)
        }
        val iso = isoStepsFor()
        return when (name) {
            "夜景" -> BracketPlan(
                isoSteps = iso,
                // 120ms 起、逐帧递增到约 720ms（共 6 帧），并受设备曝光上限约束
                shutterNsSteps = LongArray(FRAMES_NIGHT) { clampShutter(120_000_000L * (it + 1)) },
                frameCount = FRAMES_NIGHT
            )
            "星空" -> BracketPlan(
                isoSteps = iso.map { (it * 2).coerceAtMost(isoRangeMax()) }.toIntArray(),
                // 1s..4s 逐帧递增，同样受设备上限约束
                shutterNsSteps = LongArray(FRAMES_ASTRO) { clampShutter(1_000_000_000L * (it + 1)) },
                frameCount = FRAMES_ASTRO
            )
            "时光慢门" -> BracketPlan(
                isoSteps = iso,
                shutterNsSteps = LongArray(FRAMES_SLOW_SHUTTER) { clampShutter(500_000_000L * (it + 1)) },
                frameCount = FRAMES_SLOW_SHUTTER
            )
            "HDR" -> BracketPlan(
                evSteps = floatArrayOf(-2f, -1f, 0f, 1f, 2f, 0f, 0.5f),
                frameCount = 7
            )
            else -> BracketPlan.NONE
        }
    }

    /**
     * 取可用的 ISO 区间。
     *
     * 修复（2026-10）：能力探测失败时 [CameraController.isoRange] 返回 `0..0`，
     * 直接 `coerceIn(0, 0)` 会把逐帧 ISO 全部压成 **0**。Camera2 里
     * `SENSOR_SENSITIVITY = 0` 是非法值，请求会被 HAL 拒绝或输出全黑画面
     * （表现为夜景/星空/HDR 拍出纯黑）。这里统一兜底到保守可用区间。
     */
    private fun safeIsoRange(): IntRange {
        val r = controller?.isoRange() ?: (100..3200)
        return if (r.first <= 0 || r.last <= 0 || r.first > r.last) 100..3200 else r
    }

    private fun isoStepsFor(): IntArray {
        val r = safeIsoRange()
        val base = if (r.first <= 400 && r.last >= 400) 400 else r.first.coerceAtLeast(100)
        return IntArray(2) { (base * (it + 1)).coerceIn(r.first, r.last) }
    }

    private fun isoRangeMax(): Int = safeIsoRange().last

    private fun buildModeStrip() {
        modeStrip.removeAllViews()
        modeList().forEach { m ->
            val tv = TextView(this).apply {
                text = m.name
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
                if (!m.available) {
                    setTextColor(0x4DFFFFFF)
                    alpha = 0.55f
                } else if (m.name == currentMode) {
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_mode_sel)
                } else {
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.mode_normal))
                }
                setOnClickListener {
                    if (!m.available) {
                        Toast.makeText(
                            this@MainActivity,
                            if (m.note.isNotEmpty()) m.note else "当前设备不支持",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnClickListener
                    }
                    switchMode(m.name)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4); marginEnd = dp(4) }
            modeStrip.addView(tv, lp)
        }
    }

    // ================= 【新增】底部横向快捷开关行（vivo 风格） =================

    /**
     * 一个快捷开关：名称 + 当前态 + 点击行为。
     * 与设置面板双向同步 —— 快捷行改状态后回写 [SettingsStore]，设置面板同步刷新。
     */
    private data class QuickToggle(
        val id: String,
        val label: String,
        val active: () -> Boolean,
        val onToggle: () -> Unit,
        val available: () -> Boolean = { true }
    )

    private fun quickToggles(): List<QuickToggle> = listOf(
        QuickToggle("ai", "AI 画质", { enableAI }, {
            enableAI = !enableAI; SettingsStore.saveAI(this, enableAI)
        }),
        QuickToggle("denoise", "降噪", { enableAIDenoise }, {
            enableAIDenoise = !enableAIDenoise; SettingsStore.saveDenoise(this, enableAIDenoise)
        }),
        QuickToggle("raw", "RAW", { rawEnabled }, {
            rawEnabled = !rawEnabled; SettingsStore.saveRaw(this, rawEnabled)
            restartCamera()
        }),
        QuickToggle("grid", "网格", { gridEnabled }, {
            gridEnabled = !gridEnabled
            gridOverlay.visibility = if (gridEnabled) View.VISIBLE else View.GONE
            gridOverlay.invalidate()
        }),
        QuickToggle("comp", "构图", { compositionEnabled }, {
            compositionEnabled = !compositionEnabled
            SettingsStore.saveComposition(this, compositionEnabled)
            compositionPanel.visibility = if (compositionEnabled) View.VISIBLE else View.GONE
            if (!compositionEnabled) {
                compositionOverlayView.forceClear()
                lastCompositionRule = ""
            }
            // DOKA 风格：开启即重置跟踪，重新扫描一帧生成推荐框
            SceneAdvisor.resetTracking()
            restartCamera()
        }),
        QuickToggle("autozoom", "自动变焦", { autoZoomEnabled }, {
            autoZoomEnabled = !autoZoomEnabled
            SettingsStore.saveAutoZoom(this, autoZoomEnabled)
            controller?.setAutoZoom(autoZoomEnabled)
        }),
        QuickToggle("autocolor", "自动调色", { autoColorEnabled }, {
            autoColorEnabled = !autoColorEnabled
            SettingsStore.saveAutoColor(this, autoColorEnabled)
        }),
        QuickToggle("hq", "高像素", { preferQuality }, {
            preferQuality = !preferQuality
            SettingsStore.savePreferQuality(this, preferQuality)
            tvModeBadge.text = badgeText()
            restartCamera()
        }),
        QuickToggle("mirror", "镜像", { mirrorX }, {
            mirrorX = !mirrorX; SettingsStore.saveMirror(this, mirrorX)
        }),
        // 时光慢门子档：光轨 / 丝绢（仅慢门模式下可点，其他模式点击会切到慢门）
        QuickToggle("trail", "光轨", {
            slowShutterStyle == LongExposureProcessor.Style.LIGHT_TRAIL
        }, {
            slowShutterStyle = LongExposureProcessor.Style.LIGHT_TRAIL
            SettingsStore.saveSlowStyle(this, 0)
            if (currentMode != "时光慢门") switchMode("时光慢门") else buildQuickRow()
        }, available = { controller?.longExposureSupported() == true }),
        QuickToggle("silk", "丝绢", {
            slowShutterStyle == LongExposureProcessor.Style.SILK
        }, {
            slowShutterStyle = LongExposureProcessor.Style.SILK
            SettingsStore.saveSlowStyle(this, 1)
            if (currentMode != "时光慢门") switchMode("时光慢门") else buildQuickRow()
        }, available = { controller?.longExposureSupported() == true }),
        QuickToggle("bokeh", "虚化强度", { bokehStrength > 0 }, {
            bokehStrength = when {
                bokehStrength >= 90 -> 25
                bokehStrength >= 55 -> 90
                else -> 55
            }
            SettingsStore.saveBokeh(this, bokehStrength)
            Toast.makeText(
                this, "虚化强度 ${bokehStrength}%", Toast.LENGTH_SHORT
            ).show()
            buildQuickRow()
        })
    )

    private fun buildQuickRow() {
        quickRow.removeAllViews()
        quickToggles().forEach { t ->
            val on = t.active()
            val enabled = t.available()
            val label = when (t.id) {
                "bokeh" -> "${t.label} ${bokehStrength}%"
                else -> t.label
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(13), dp(6), dp(13), dp(6))
                if (!enabled) {
                    setTextColor(0x4DFFFFFF)
                    alpha = 0.5f
                } else {
                    setTextColor(
                        ContextCompat.getColor(
                            this@MainActivity,
                            if (on) R.color.accent else R.color.text_primary
                        )
                    )
                    background = ContextCompat.getDrawable(
                        this@MainActivity,
                        if (on) R.drawable.bg_filter_chip_sel else R.drawable.bg_filter_chip
                    )
                }
                setOnClickListener {
                    if (!enabled) {
                        Toast.makeText(this@MainActivity, "当前设备不支持", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    t.onToggle()
                    buildQuickRow()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4); marginEnd = dp(4) }
            quickRow.addView(tv, lp)
        }
    }

    private fun switchMode(name: String) {
        if (currentMode == name) { buildModeStrip(); buildQuickRow(); return }

        // 【关键修复】原来整个 switchMode 从头到尾没有写过 currentMode，
        // 而 currentMode 被下面四处消费：
        //   1. buildModeStrip()  → `m.name == currentMode` 决定哪个模式 chip 高亮
        //   2. planForMode(currentMode) → 夜景/星空/慢门/HDR 的逐帧曝光计划
        //   3. bracketModeSnapshot → handleBracketResult 分派到哪个处理器
        //   4. showProgress 文案 → "夜景采集中…" vs "拍摄中…"
        // 漏掉赋值 = 模式条永远不跟随切换 + 所有特殊模式退化成普通单张。
        currentMode = name

        // 【关键修复】高像素必须跟着模式走。原来只在切到高像素时置 true，
        // 切回多摄时从不置回 false，导致 badge 文案与实际采集策略不一致。
        if (name == "高像素") {
            if (!preferQuality) { preferQuality = true; SettingsStore.savePreferQuality(this, true) }
        } else if (name == "多摄" || name == "拍照") {
            if (preferQuality) { preferQuality = false; SettingsStore.savePreferQuality(this, false) }
        }

        // 全景模式：原来只有置 false 的分支，没有任何地方置 true → 全景 100% 死代码
        if (name == "全景") {
            if (!panoramaMode) {
                synchronized(panoramaFrames) { panoramaFrames.clear() }
                panoStartAt = 0L
            }
            panoramaMode = true
        } else if (panoramaMode) {
            panoramaMode = false
            // 切走时释放已积累的全景帧，否则 ByteArray 永久驻留堆内存
            synchronized(panoramaFrames) { panoramaFrames.clear() }
        }

        proPanel.visibility = if (name == "专业" || name == "高像素") View.VISIBLE else View.GONE

        // 先停掉录像会话再决定是否重建相机（下面 restartCamera 依赖这个状态）
        if (name != "录像" && videoSession != null) {
            runCatching { videoSession?.stopRecording() }
            videoSession = null; videoMode = false
            syncShutterForVideo(false)
        }

        if (name == "录像") {
            // 【关键修复】切录像前必须释放拍照控制器。
            // 原来只是 `if (cameraStarted && name != "录像") restartCamera()` ——
            // 也就是"录像模式不重启"，但旧 controller 仍持有同一 cameraId，
            // MultiVideoSession.openCamera 会拿到 CAMERA_IN_USE，录像永远开不起来，
            // 并且两个会话互相 onDisconnected 抢相机，最后双双黑屏。
            releaseCameraController()
            startVideo()
        } else {
            if (cameraStarted) restartCamera()
        }

        // 模式变化必须刷新所有受影响的 UI：模式条高亮、快捷开关可用态、倍率、角标
        buildModeStrip()
        buildQuickRow()
        buildZoomRow()
        tvModeBadge.text = badgeText(BackgroundProcessor.activeJobs().size)
        syncToggleStates()
        // 【新增】采集计划随模式重建（夜景/星空/慢门/HDR 需要逐帧曝光）
        pushModePlan()
        Toast.makeText(this, "已切换到 $name", Toast.LENGTH_SHORT).show()
    }

    /** 释放拍照用相机控制器（切录像 / onPause / onDestroy 共用） */
    private fun releaseCameraController() {
        runCatching { controller?.release() }
        controller = null
        cameraStarted = false
    }

    // ================= 多摄同步录像（修复：原先从未真正启动） =================

    private fun startVideo() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "录像需要 Android 11+", Toast.LENGTH_SHORT).show()
            return
        }
        val surface = previewSurface
        if (surface == null) {
            Toast.makeText(this, "预览未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        val caps = CameraCapabilityDetector(this).detect()
        val ids = caps.cameraInfos.map { it.id }.take(2)
        if (ids.isEmpty()) {
            Toast.makeText(this, "未找到可用摄像头", Toast.LENGTH_SHORT).show()
            return
        }
        // 录像分辨率：优先 1080p，退化到预览尺寸
        val recSize = pickVideoSize(ids.first()) ?: Size(1920, 1080)
        val outDir = FileSaver.videoDir(this)
        val outFile = File(outDir, "multicam_${System.currentTimeMillis()}.mp4")
        // 修复：重复进入录像模式时旧会话未被释放，会导致摄像头被上一实例占住。
        runCatching { videoSession?.stopRecording() }
        videoSession = null
        videoMode = false
        videoSession = MultiVideoSession(
            context = this,
            cameraIds = ids,
            size = recSize,
            previewSurface = surface,
            outputFile = outFile,
            onError = { msg ->
                if (!thisDone()) runOnUiThread {
                    if (thisDone()) return@runOnUiThread
                    Toast.makeText(this, "录像错误：$msg", Toast.LENGTH_LONG).show()
                    videoSession = null
                    videoMode = false
                }
            },
            onReady = {
                if (!thisDone()) runOnUiThread {
                    if (thisDone()) return@runOnUiThread
                    videoMode = true
                    tvStatus.text = "● 录制中 ${recSize.width}×${recSize.height}"
                    Toast.makeText(this, "开始录制", Toast.LENGTH_SHORT).show()
                }
                videoSession?.startRecording()
            },
            onStopped = { file ->
                if (!thisDone()) runOnUiThread {
                    if (thisDone()) return@runOnUiThread
                    tvStatus.text = "录像已保存"
                    Toast.makeText(
                        this, "录像已保存：${file.name}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        ).also { it.start(Handler(Looper.getMainLooper())) }
    }

    private fun stopVideo() {
        videoSession?.stopRecording()
        videoSession = null
        videoMode = false
    }

    /** 从摄像头支持的高分辨率 JPEG/录制尺寸里挑一个 1080p 附近的 Size */
    private fun pickVideoSize(cameraId: String): Size? {
        return try {
            val mgr = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val map = mgr.getCameraCharacteristics(cameraId)
                .get(android.hardware.camera2.CameraCharacteristics
                    .SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val sizes = map.getOutputSizes(android.media.MediaRecorder::class.java)
                ?.toList() ?: return null
            sizes.filter { it.width <= 1920 && it.height <= 1080 }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: sizes.minByOrNull { it.width.toLong() * it.height }
        } catch (_: Exception) { null }
    }

    // ================= 动态构建：滤镜 =================

    /** 滤镜预设来自独立文件 FilterPresets.kt */
    private val filters: List<FilterPreset> get() = FilterPresets.all

    private fun buildFilterRow() {
        filterRow.removeAllViews()
        filters.forEachIndexed { i, f ->
            val tv = TextView(this).apply {
                text = f.name
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setTextColor(
                    if (i == currentFilter) ContextCompat.getColor(
                        this@MainActivity, R.color.accent
                    ) else ContextCompat.getColor(this@MainActivity, R.color.text_primary)
                )
                background = ContextCompat.getDrawable(
                    this@MainActivity,
                    if (i == currentFilter) R.drawable.bg_filter_chip_sel else R.drawable.bg_filter_chip
                )
                setOnClickListener {
                    currentFilter = i
                    applyFilter(f.params)
                    SettingsStore.savePreset(this@MainActivity, i)
                    SettingsStore.saveGrade(this@MainActivity, f.params)
                    buildFilterRow()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            filterRow.addView(tv, lp)
        }
    }

    private fun applyFilter(p: ColorGradeParams) {
        colorGradeParams = p
        sbSaturation.progress = (p.saturation * 100).toInt().coerceIn(0, 200)
        sbContrast.progress = (p.contrast * 100).toInt().coerceIn(50, 150)
        sbTemperature.progress = ((p.temperature + 1f) * 100).toInt().coerceIn(0, 200)
        colorWheel.setInner(p.hueShiftX, p.toneShiftY)
        syncColorLabels()
        controller?.colorParams = colorGradeParams
        updateLut()
    }

    // ================= 动态构建：设置项 =================

    private data class SettingItem(
        val title: String,
        val valueProvider: () -> String,
        val available: Boolean = true,
        val note: String = "",
        val onClick: (() -> Unit)? = null
    )

    private fun settingItems(): List<SettingItem> = listOf(
        SettingItem("画质模式",
            { if (preferQuality) "全像素" else "多摄并发" },
            onClick = {
                preferQuality = !preferQuality
                SettingsStore.savePreferQuality(this, preferQuality)
                tvModeBadge.text = badgeText()
                if (cameraStarted) restartCamera()
            }),
        SettingItem("连拍帧数", { "$framesPerCamera 帧" }, onClick = {
            framesPerCamera = when (framesPerCamera) {
                1 -> 3; 3 -> 5; 5 -> 7; else -> 1
            }
            SettingsStore.saveFrames(this, framesPerCamera)
            if (cameraStarted) restartCamera()
        }),
        SettingItem("AI 超分",
            { if (!AISuperResolution.isReady()) "不可用" else if (enableAI) "开" else "关" },
            available = AISuperResolution.isReady(),
            note = "AI 模型未加载",
            onClick = { enableAI = !enableAI; SettingsStore.saveAI(this, enableAI) }),
        SettingItem("AI 去噪",
            { if (!AIDenoise.isReady()) "不可用" else if (enableAIDenoise) "开" else "关" },
            available = AIDenoise.isReady(),
            note = "去噪模型未加载",
            onClick = { enableAIDenoise = !enableAIDenoise; SettingsStore.saveDenoise(this, enableAIDenoise) }),
        SettingItem("RAW/DNG", { if (rawEnabled) "开" else "关" }, onClick = {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                Toast.makeText(this, "需要 Android 7.0+", Toast.LENGTH_SHORT).show()
            } else {
                rawEnabled = !rawEnabled
                SettingsStore.saveRaw(this, rawEnabled)
                if (cameraStarted) restartCamera()
            }
        }),
        SettingItem("镜头选择", {
            when (preferredRole) {
                CameraRole.ULTRA_WIDE -> "超广角"
                CameraRole.MAIN -> "主摄"
                CameraRole.TELE -> "长焦"
                else -> "自动"
            }
        }, onClick = {
            preferredRole = when (preferredRole) {
                null -> CameraRole.ULTRA_WIDE
                CameraRole.ULTRA_WIDE -> CameraRole.MAIN
                CameraRole.MAIN -> CameraRole.TELE
                else -> null
            }
            SettingsStore.savePreferredRole(this, preferredRole)
            controller?.applyPreferredRole(preferredRole)
        }),
        SettingItem("画面镜像", { if (mirrorX) "开" else "关" }, onClick = {
            mirrorX = !mirrorX
            SettingsStore.saveMirror(this, mirrorX)
            applyPreviewTransform()
        }),
        SettingItem("输出方向", { "$extraRotation°" }, onClick = {
            extraRotation = (extraRotation + 90) % 360
            SettingsStore.saveExtraRotation(this, extraRotation)
            applyPreviewTransform()
        }),
        SettingItem("自动变焦", { if (autoZoomEnabled) "开" else "关" }, onClick = {
            autoZoomEnabled = !autoZoomEnabled
            SettingsStore.saveAutoZoom(this, autoZoomEnabled)
            controller?.setAutoZoom(autoZoomEnabled)
        }),
        SettingItem("自动调色", { if (autoColorEnabled) "开" else "关" }, onClick = {
            autoColorEnabled = !autoColorEnabled
            SettingsStore.saveAutoColor(this, autoColorEnabled)
        }),
        SettingItem("构图辅助", { if (compositionEnabled) "开" else "关" }, onClick = {
            compositionEnabled = !compositionEnabled
            SettingsStore.saveComposition(this, compositionEnabled)
            compositionPanel.visibility = if (compositionEnabled) View.VISIBLE else View.GONE
            if (!compositionEnabled) {
                compositionOverlayView.forceClear()
                lastCompositionRule = ""
            }
            if (cameraStarted) restartCamera()
        }),
        SettingItem("构图锁定", { if (compositionLocked) "已锁" else "未锁" }, onClick = {
            toggleCompositionLock()
        }),
        SettingItem("导出日志", { ">" }, onClick = { exportLog() }),
        SettingItem("蔡司人像虚化", { "不可用" }, available = false,
            note = "需要 vivo 私有 ISP 与蔡司算法，标准 Camera2 无法实现"),
        SettingItem("超级夜景", { "不可用" }, available = false,
            note = "需要厂商多帧堆栈算法，标准 Camera2 无法实现"),
        SettingItem("超级月亮 / 星空", { "不可用" }, available = false,
            note = "需要厂商长焦与识别算法，标准 Camera2 无法实现")
    )

    private fun buildSettingsList() {
        settingsList.removeAllViews()
        settingItems().forEach { item ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_setting, settingsList, false)
            val title = row.findViewById<TextView>(R.id.rowTitle)
            val value = row.findViewById<TextView>(R.id.rowValue)
            title.text = item.title
            value.text = item.valueProvider()
            if (!item.available) {
                title.alpha = 0.45f
                value.alpha = 0.45f
            }
            row.setOnClickListener {
                if (!item.available) {
                    Toast.makeText(
                        this,
                        item.note.ifEmpty { "当前设备不支持" },
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                item.onClick?.invoke()
                rebuildSettings()
            }
            settingsList.addView(row)
        }
    }

    private fun rebuildSettings() {
        settingsList.removeAllViews()
        buildSettingsList()
    }

    // ================= 顶栏 =================

    private fun setupTopBar() {
        btnFlash.setOnClickListener {
            flashMode = when (flashMode) {
                CameraControls.FLASH_OFF -> CameraControls.FLASH_ON
                CameraControls.FLASH_ON -> CameraControls.FLASH_AUTO
                CameraControls.FLASH_AUTO -> CameraControls.FLASH_TORCH
                else -> CameraControls.FLASH_OFF
            }
            ivFlash.setImageResource(
                when (flashMode) {
                    CameraControls.FLASH_ON -> R.drawable.ic_flash_on
                    CameraControls.FLASH_AUTO -> R.drawable.ic_flash_auto
                    CameraControls.FLASH_TORCH -> R.drawable.ic_flash_on
                    else -> R.drawable.ic_flash_off
                }
            )
            btnFlash.background = ContextCompat.getDrawable(
                this,
                if (flashMode != CameraControls.FLASH_OFF) R.drawable.bg_circle_btn_on
                else R.drawable.bg_circle_btn
            )
            pushControls()
        }

        // ---------- DOKA 风格：AI 构图开关 ----------
        // 点击开启 → 扫描一帧生成推荐取景框与 AR 引导线 → 再点关闭
        btnComposition.setOnClickListener {
            compositionEnabled = !compositionEnabled
            SettingsStore.saveComposition(this, compositionEnabled)
            compositionPanel.visibility = if (compositionEnabled) View.VISIBLE else View.GONE
            btnComposition.background = ContextCompat.getDrawable(
                this,
                if (compositionEnabled) R.drawable.bg_circle_btn_on else R.drawable.bg_circle_btn
            )
            if (!compositionEnabled) {
                compositionOverlayView.forceClear()
                lastCompositionRule = ""
            } else {
                // 重新扫描：清空跟踪状态，让第一帧立即生成引导
                SceneAdvisor.resetTracking()
                Toast.makeText(this, "AI 构图已开启，移动手机对齐取景框", Toast.LENGTH_SHORT).show()
            }
            restartCamera()
        }

        btnGrid.setOnClickListener {
            gridEnabled = !gridEnabled
            gridOverlay.visibility = if (gridEnabled) View.VISIBLE else View.GONE
            btnGrid.background = ContextCompat.getDrawable(
                this,
                if (gridEnabled) R.drawable.bg_circle_btn_on else R.drawable.bg_circle_btn
            )
        }

        btnPalette.setOnClickListener { toggleColorPanel() }

        btnSettings.setOnClickListener {
            settingsPanel.visibility =
                if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (settingsPanel.visibility == View.VISIBLE) rebuildSettings()
        }

        btnSwitch.setOnClickListener {
            preferredRole = when (preferredRole) {
                null -> CameraRole.MAIN
                CameraRole.MAIN -> CameraRole.ULTRA_WIDE
                CameraRole.ULTRA_WIDE -> CameraRole.TELE
                else -> null
            }
            controller?.applyPreferredRole(preferredRole)
            Toast.makeText(
                this,
                when (preferredRole) {
                    CameraRole.ULTRA_WIDE -> "已切到超广角"
                    CameraRole.MAIN -> "已切到主摄"
                    CameraRole.TELE -> "已切到长焦"
                    else -> "已切到自动镜头"
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun pushControls(plan: BracketPlan = planForMode(currentMode)) {
        // 修复：把"本轮采集属于哪个模式"在发起时固化下来。
        // 相机回调是异步的，若回调里直接读可变的 currentMode，用户在采集期间切模式
        // 会导致 HDR 的帧被交给夜景处理器（反之亦然）——画面结果与所选模式不符。
        bracketModeSnapshot = currentMode
        controller?.applyControls(
            CameraControls(
                flashMode = flashMode,
                evCompensation = evValue,
                isoManual = if (isoIndex > 0) ISO_STEPS[isoIndex] else 0,
                shutterNs = if (shutterIndex > 0) SHUTTER_STEPS[shutterIndex] else 0L,
                wbManual = wbIndex > 0,
                wbTemperature = if (wbIndex > 0) WB_STEPS[wbIndex] else 5000f,
                bracket = plan
            )
        )
    }

    /** 与当前 [BracketPlan] 同批固化的模式名，供包围帧回调使用 */
    @Volatile private var bracketModeSnapshot = "拍照"

    // ================= 专业模式 =================

    private val ISO_STEPS = intArrayOf(0, 100, 200, 400, 800, 1600, 3200, 6400)
    private val SHUTTER_STEPS = longArrayOf(
        0L,
        1_000_000_000L / 1000,   // 1/1000s
        1_000_000_000L / 500,
        1_000_000_000L / 250,
        1_000_000_000L / 125,
        1_000_000_000L / 60,
        1_000_000_000L / 30,
        1_000_000_000L / 15,
        1_000_000_000L / 8
    )
    private val SHUTTER_LABELS = arrayOf(
        "自动", "1/1000", "1/500", "1/250", "1/125", "1/60", "1/30", "1/15", "1/8"
    )
    private val WB_STEPS = floatArrayOf(5000f, 2800f, 3800f, 5000f, 6500f)
    private val WB_LABELS = arrayOf("自动", "2800K", "3800K", "5000K", "6500K")

    private fun setupProPanel() {
        sbEv.max = 8
        sbEv.progress = 4
        sbEv.setOnSeekBarChangeListener(simpleSeek { p ->
            evValue = (p - 4) / 2f
            tvEv.text = "%+.1f".format(evValue)
            pushControls()
        })

        sbIso.setOnSeekBarChangeListener(simpleSeek { p ->
            isoIndex = p
            tvIso.text = if (ISO_STEPS[p] == 0) "自动" else "${ISO_STEPS[p]}"
            pushControls()
        })

        sbShutter.setOnSeekBarChangeListener(simpleSeek { p ->
            shutterIndex = p
            tvShutter.text = SHUTTER_LABELS[p]
            pushControls()
        })

        sbWb.setOnSeekBarChangeListener(simpleSeek { p ->
            wbIndex = p
            tvWb.text = WB_LABELS[p]
            pushControls()
        })
    }

    private fun simpleSeek(onChange: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }

    // ================= 调色面板 =================

    private fun setupColorPanel() {
        colorPanel.findViewById<View>(R.id.btnColorClose).setOnClickListener {
            colorPanel.visibility = View.GONE
        }

        colorWheel.onColorChanged = { x, y ->
            colorGradeParams = colorGradeParams.copy(hueShiftX = x, toneShiftY = y)
            controller?.colorParams = colorGradeParams
            persistCustomGrade()
            updateLut()
        }
        colorWheel.onHueRingChanged = { angle ->
            // 色相环映射到 -1..1 的色相偏移（左右各半圈）
            val n = if (angle > 180f) (angle - 360f) / 180f else angle / 180f
            colorGradeParams = colorGradeParams.copy(hueShiftX = n)
            controller?.colorParams = colorGradeParams
            persistCustomGrade()
            updateLut()
        }

        sbSaturation.setOnSeekBarChangeListener(simpleSeek { p ->
            colorGradeParams = colorGradeParams.copy(saturation = p / 100f)
            tvSaturation.text = "%.2f".format(p / 100f)
            controller?.colorParams = colorGradeParams
            persistCustomGrade()
            updateLut()
        })
        sbContrast.setOnSeekBarChangeListener(simpleSeek { p ->
            colorGradeParams = colorGradeParams.copy(contrast = p / 100f)
            tvContrast.text = "%.2f".format(p / 100f)
            controller?.colorParams = colorGradeParams
            persistCustomGrade()
            updateLut()
        })
        sbTemperature.setOnSeekBarChangeListener(simpleSeek { p ->
            val t = (p - 100) / 100f
            colorGradeParams = colorGradeParams.copy(temperature = t)
            tvTemperature.text = "%+.2f".format(t)
            controller?.colorParams = colorGradeParams
            persistCustomGrade()
            updateLut()
        })

        // 启动恢复上次的预设 / 自定义调色
        restoreColorState()
        syncColorLabels()
    }

    /**
     * 启动时恢复调色状态：
     * - 预设下标 > 0 且有存档 → 用预设
     * - 否则用自定义调色存档（若有）
     */
    private fun restoreColorState() {
        val savedPreset = SettingsStore.loadPreset(this, 0)
        val savedGrade = SettingsStore.loadGrade(this)

        if (savedPreset > 0 && savedPreset < filters.size) {
            currentFilter = savedPreset
            colorGradeParams = filters[savedPreset].params
        } else if (savedGrade != null) {
            currentFilter = 0
            colorGradeParams = savedGrade
        } else {
            currentFilter = 0
            colorGradeParams = ColorGradeParams()
        }

        sbSaturation.progress = (colorGradeParams.saturation * 100).toInt().coerceIn(0, 200)
        sbContrast.progress = (colorGradeParams.contrast * 100).toInt().coerceIn(50, 150)
        sbTemperature.progress =
            ((colorGradeParams.temperature + 1f) * 100).toInt().coerceIn(0, 200)
        colorWheel.setInner(colorGradeParams.hueShiftX, colorGradeParams.toneShiftY)
        controller?.colorParams = colorGradeParams
    }

    /** 自定义调色：改动后即持久化，下次启动恢复 */
    private fun persistCustomGrade() {
        currentFilter = 0
        SettingsStore.savePreset(this, 0)
        SettingsStore.saveGrade(this, colorGradeParams)
    }

    private fun syncColorLabels() {
        tvSaturation.text = "%.2f".format(colorGradeParams.saturation)
        tvContrast.text = "%.2f".format(colorGradeParams.contrast)
        tvTemperature.text = "%+.2f".format(colorGradeParams.temperature)
    }

    private fun toggleColorPanel() {
        colorWheelVisible = !colorWheelVisible
        colorPanel.visibility = if (colorWheelVisible) View.VISIBLE else View.GONE
        btnPalette.background = ContextCompat.getDrawable(
            this,
            if (colorWheelVisible) R.drawable.bg_circle_btn_on else R.drawable.bg_circle_btn
        )
        if (colorWheelVisible) {
            colorPanel.post { colorWheel.setInner(colorGradeParams.hueShiftX, colorGradeParams.toneShiftY) }
        } else {
            currentFilter = 0
            applyFilter(filters[0].params)
            buildFilterRow()
        }
    }

    // ================= 设置面板事件 =================

    private fun setupSettingsPanel() {
        settingsPanel.findViewById<View>(R.id.btnSettingsClose).setOnClickListener {
            settingsPanel.visibility = View.GONE
        }
    }

    // ================= 主交互 =================

    private fun setupListeners() {
        btnShutter.setOnClickListener {
            animateShutter()
            when {
                videoMode -> stopVideo()
                panoramaMode -> controller?.capture()
                cameraStarted -> {
                    if (rawEnabled) {
                        controller?.setOrientation(
                            when (extraRotation) {
                                90 -> ExifInterface.ORIENTATION_ROTATE_90
                                180 -> ExifInterface.ORIENTATION_ROTATE_180
                                270 -> ExifInterface.ORIENTATION_ROTATE_270
                                else -> ExifInterface.ORIENTATION_NORMAL
                            }
                        )
                    }
                    val bracketed = planForMode(currentMode).isBracketed
                    showProgress(if (bracketed) "${currentMode}采集中…" else "拍摄中…")
                    pushModePlan()
                    controller?.capture()
                }
                else -> Toast.makeText(this, "相机未就绪", Toast.LENGTH_SHORT).show()
            }
        }

        thumbContainer.setOnClickListener {
            val uri = lastUri ?: return@setOnClickListener
            try {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (_: Exception) {
                Toast.makeText(this, "无法打开图库", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleCompositionLock() {
        compositionLocked = !compositionLocked
        SceneAdvisor.setLocked(compositionLocked)
        compositionOverlayView.setFrozen(compositionLocked)
        controller?.resetCompositionTracking()
        compositionPanel.setData(
            CompositionPanelView.Data(
                locked = compositionLocked,
                active = compositionEnabled,
                score = if (compositionLocked) SceneAdvisor.currentScore() else 0,
                tiltDeg = if (compositionLocked) SceneAdvisor.currentTilt() else 0f,
                ruleName = if (compositionLocked) lastCompositionRule else "",
                aligned = compositionLocked
            )
        )
        if (compositionLocked) {
            Toast.makeText(this, "构图已锁定，指引线固定", Toast.LENGTH_SHORT).show()
        } else {
            compositionOverlayView.update(null)
            lastCompositionRule = ""
        }
        SettingsStore.saveCompositionLock(this, compositionLocked)
    }

    private fun setupManualFocus() {
        val longPressHandler = Handler(Looper.getMainLooper())
        var longPressTriggered = false
        var downX = 0f
        var downY = 0f
        val longPressRunnable = Runnable {
            longPressTriggered = true
            val rect = calculateFocusRect(downX, downY, preview.width, preview.height)
            if (afAeLocked) {
                controller?.unlockAfAe()
                afAeLocked = false
                Toast.makeText(this, "已解锁 AF/AE", Toast.LENGTH_SHORT).show()
            } else {
                controller?.lockAfAe(rect)
                afAeLocked = true
                Toast.makeText(this, "已锁定 AF/AE，再次长按解锁", Toast.LENGTH_SHORT).show()
            }
        }

        preview.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressTriggered = false
                    downX = event.x; downY = event.y
                    longPressHandler.postDelayed(longPressRunnable, 600L)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.hypot(
                            (event.x - downX).toDouble(),
                            (event.y - downY).toDouble()
                        ) > 30
                    ) longPressHandler.removeCallbacks(longPressRunnable)
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (!longPressTriggered) {
                        val rect = calculateFocusRect(
                            event.x, event.y, preview.width, preview.height
                        )
                        showFocusRingAt(event.x, event.y)
                        controller?.triggerFocus(rect, lockAe = true)
                    }
                }
                MotionEvent.ACTION_CANCEL ->
                    longPressHandler.removeCallbacks(longPressRunnable)
            }
            true
        }
    }

    /**
     * 把 PreviewView 上的触摸点换算成 Camera2 的 AF/AE 区域（传感器坐标系，−1000..1000）。
     *
     * 修复（2026-10）：原实现直接 `x/viewW * 2000 - 1000`，忽略了三个关键因素：
     *  1. **预览是裁剪填充的**——GLSurfaceView 与传感器输出的宽高比不一致时会被拉伸/裁剪，
     *     触摸点必须先映射回"传感器坐标系下的归一化位置"；
     *  2. **SENSOR_ORIENTATION**——竖屏时传感器是横着的，x/y 需要互换并翻转
     *     （否则点左上角会对焦到右上角，是典型的"对焦位置反了"）；
     *  3. **镜像**——前摄开了 mirrorX 后 x 轴要再翻一次。
     *
     * @param touchX/touchY 相对预览 View 的触摸坐标（px）
     * @param viewW/viewH   预览 View 的尺寸（px）
     */
    private fun calculateFocusRect(
        touchX: Float, touchY: Float, viewW: Int, viewH: Int
    ): android.graphics.Rect {
        if (viewW <= 0 || viewH <= 0) return android.graphics.Rect(-100, -100, 100, 100)

        // 1. 触摸点 → 归一化 [0,1]（相对预览 View）
        var u = (touchX / viewW).coerceIn(0f, 1f)
        var v = (touchY / viewH).coerceIn(0f, 1f)

        // 2. 处理预览裁剪：预览是按 previewBufferW/H 填满 View 的（center-crop 或 fit），
        //    这里按 center-crop 还原到相机 buffer 的归一化坐标。
        val bufW = previewBufferW.toFloat()
        val bufH = previewBufferH.toFloat()
        if (bufW > 0f && bufH > 0f) {
            val viewAspect = viewW.toFloat() / viewH
            val bufAspect = bufW / bufH
            if (bufAspect > viewAspect) {
                // buffer 更宽 → 左右被裁掉
                val visible = viewAspect / bufAspect            // 可见宽度占比
                u = (1f - visible) / 2f + u * visible
            } else if (bufAspect < viewAspect) {
                // buffer 更高 → 上下被裁掉
                val visible = bufAspect / viewAspect
                v = (1f - visible) / 2f + v * visible
            }
        }

        // 3. 按传感器方向旋转（SENSOR_ORIENTATION 是"传感器相对设备自然方向的顺时针角度"）
        val rot = ((sensorOrientation % 360) + 360) % 360
        val su: Float
        val sv: Float
        when (rot) {
            90 -> { su = v; sv = 1f - u }
            180 -> { su = 1f - u; sv = 1f - v }
            270 -> { su = 1f - v; sv = u }
            else -> { su = u; sv = v }
        }

        // 4. 镜像（前摄）
        val fx = if (mirrorX) 1f - su else su
        val fy = sv

        // 5. 归一化 → 传感器 −1000..1000
        val nx = (fx * 2000f - 1000f)
        val ny = (fy * 2000f - 1000f)
        val half = 100
        return android.graphics.Rect(
            (nx - half).toInt().coerceIn(-1000, 1000),
            (ny - half).toInt().coerceIn(-1000, 1000),
            (nx + half).toInt().coerceIn(-1000, 1000),
            (ny + half).toInt().coerceIn(-1000, 1000)
        )
    }

    private fun showFocusRingAt(x: Float, y: Float) {
        focusRing.x = x - focusRing.width / 2f
        focusRing.y = y - focusRing.height / 2f
        focusRing.alpha = 1f
        focusRing.scaleX = 1.4f
        focusRing.scaleY = 1.4f
        focusRing.animate().scaleX(1f).scaleY(1f).alpha(0f).setDuration(800).start()
    }

    private fun setupGlPreview() {
        preview.setEGLContextClientVersion(2)
        renderer.onSurfaceTextureReady = { st ->
            runOnUiThread {
                glSurfaceTexture = st
                st.setDefaultBufferSize(previewBufferW, previewBufferH)
                previewSurface = Surface(st)
                applyPreviewTransform()
                checkPermissionsAndStart()
            }
        }
        preview.setRenderer(renderer)
        preview.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    private fun applyPreviewTransform() {
        val rotation = (sensorOrientation + extraRotation) % 360
        renderer.setTransform(rotation, mirrorX)
    }

    /**
     * 计算预览画面在 GLSurfaceView 中的实际显示矩形（center-crop 语义），
     * 并同步给 CompositionOverlayView。
     *
     * 修复（2026-10）：构图叠加层原先假设分析帧铺满整个 View，与实际裁剪不一致，
     * 导致瞄准环/主体位置整体偏移。这里按 previewBufferW/H 与 View 宽高比算出
     * 真实的可见区域，让叠加层按同一个几何做映射。
     */
    private fun syncCompositionContentRect() {
        val vw = preview.width
        val vh = preview.height
        if (vw <= 0 || vh <= 0 || previewBufferW <= 0 || previewBufferH <= 0) return

        // 竖屏下预览 buffer 是横的，宽高比需要对调后再比较
        val portrait = (sensorOrientation + extraRotation) % 360 != 0 &&
            (sensorOrientation + extraRotation) % 360 != 180
        val bw = if (portrait) previewBufferH else previewBufferW
        val bh = if (portrait) previewBufferW else previewBufferH
        if (bw <= 0 || bh <= 0) return

        val viewAspect = vw.toFloat() / vh
        val bufAspect = bw.toFloat() / bh
        val rect = android.graphics.RectF(0f, 0f, vw.toFloat(), vh.toFloat())
        if (bufAspect > viewAspect) {
            // buffer 更宽 → 左右被裁
            val visW = vh * bufAspect
            val left = (vw - visW) / 2f
            rect.set(left, 0f, left + visW, vh.toFloat())
        } else if (bufAspect < viewAspect) {
            // buffer 更高 → 上下被裁
            val visH = vw / bufAspect
            val top = (vh - visH) / 2f
            rect.set(0f, top, vw.toFloat(), top + visH)
        }
        compositionOverlayView.contentRect = rect
        compositionOverlayView.analysisW = 640
        compositionOverlayView.analysisH = 480
    }

    private fun updateSensorOrientation() {
        try {
            val mgr = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val backId = mgr.cameraIdList.firstOrNull { id ->
                mgr.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            } ?: return
            sensorOrientation = mgr.getCameraCharacteristics(backId)
                .get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        } catch (_: Exception) {}
    }

    private fun animateShutter() {
        val scaleDown = android.view.animation.ScaleAnimation(
            1f, 0.85f, 1f, 0.85f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply { duration = 90 }
        val scaleUp = android.view.animation.ScaleAnimation(
            0.85f, 1f, 0.85f, 1f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply { duration = 90; startOffset = 90 }
        shutterCore.startAnimation(scaleDown)
        shutterCore.postDelayed({ shutterCore.startAnimation(scaleUp) }, 90)
    }

    private fun showProgress(text: String) {
        progressPanel.visibility = View.VISIBLE
        tvProgress.text = text
    }

    private fun hideProgress() {
        progressPanel.visibility = View.GONE
    }

    private fun checkPermissionsAndStart() {
        updateSensorOrientation()
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startCameraIfReady()
        else permLauncher.launch(missing.toTypedArray())
    }

    private fun startCameraIfReady() {
        if (previewSurface == null) return
        restartCamera()
    }

    private fun restartCamera() {
        controller?.release()
        controller = CameraController(
            context = this,
            previewSurface = previewSurface,
            framesPerCamera = framesPerCamera,
            enableAIDenoise = enableAIDenoise,
            enableAIUpscale = enableAI,
            colorParams = colorGradeParams,
            compositionEnabled = compositionEnabled,
            compositionOverlay = { s ->
                if (!thisDone()) runOnUiThread {
                    if (thisDone()) return@runOnUiThread
                    compositionOverlayView.update(s)
                    if (compositionEnabled && s != null) {
                        lastCompositionRule = s.ruleName
                        compositionPanel.setData(
                            CompositionPanelView.Data(
                                type = s.type,
                                score = s.score,
                                tiltDeg = s.tiltDeg,
                                subjectX = s.subjectX,
                                subjectY = s.subjectY,
                                hint = s.hint,
                                locked = SceneAdvisor.isLocked(),
                                active = true,
                                // ---------- DOKA 风格字段 ----------
                                ruleName = s.ruleName,
                                alignProgress = s.alignProgress,
                                alignX = s.alignX,
                                alignY = s.alignY,
                                aligned = s.aligned,
                                recommendZoom = s.recommendZoom
                            )
                        )
                    }
                }
            },
            enableRaw = rawEnabled,
            onRawReady = { dng ->
                // 修复：DNG 落盘在裸线程里执行，Activity 销毁后不能再碰 FileSaver / Toast
                if (!thisDone()) Thread {
                    if (thisDone()) return@Thread
                    val uri = FileSaver.saveDng(this, dng, "raw")
                    runOnUiThread {
                        if (thisDone()) return@runOnUiThread
                        Toast.makeText(
                            this,
                            if (uri != null) "DNG 已保存" else "DNG 保存失败",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }.start()
            },
            autoColorEnabled = { autoColorEnabled },
            onStatus = { msg ->
                if (!thisDone()) runOnUiThread {
                    if (thisDone()) return@runOnUiThread
                    tvStatus.text = msg
                    cameraStarted = true
                    hideProgress()
                    buildZoomRow()
                    buildModeStrip()
                    buildQuickRow()
                }
            },
            onProgress = { msg ->
                if (!thisDone()) runOnUiThread {
                    if (!thisDone()) showProgress(msg)
                }
            },
            onPreviewSize = { size: Size ->
                previewBufferW = size.width
                previewBufferH = size.height
                renderer.setPreviewSize(size.width, size.height)
                if (!thisDone()) runOnUiThread {
                    if (thisDone()) return@runOnUiThread
                    glSurfaceTexture?.setDefaultBufferSize(previewBufferW, previewBufferH)
                    applyPreviewTransform()
                    // 修复（2026-10）：把预览画面在 View 内的实际显示区域（center-crop）
                    // 同步给构图叠加层，否则瞄准环会整体偏移。
                    syncCompositionContentRect()
                }
            },
            onResult = { tag, jpeg ->
                if (!thisDone()) handleResult(tag, jpeg)
            },
            // 修复：用发起采集时固化的模式快照，而非可变的 currentMode
            onBracketFrames = { frames ->
                if (!thisDone()) handleBracketResult(bracketModeSnapshot, frames)
            },
            onError = { msg ->
                if (!thisDone()) runOnUiThread {
                    if (thisDone()) return@runOnUiThread
                    hideProgress()
                    tvStatus.text = "错误：$msg"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            },
            orientationTracker = orientationTracker,
            onTargetReached = { advice ->
                if (!thisDone()) runOnUiThread {
                    if (thisDone()) return@runOnUiThread
                    Toast.makeText(
                        this,
                        "已到位，自动%s".format(
                            if (advice.targetZoom > 0f)
                                "变焦 %.1f×".format(advice.targetZoom) else "调色"
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
        controller?.init(preferQuality)
        controller?.setAutoZoom(autoZoomEnabled)
        pushControls()
        controller?.setZoom(zoomValue)
    }

    private fun handleResult(tag: String, jpeg: ByteArray) {
        // 全景：单帧累加，攒够帧数后自动拼接。
        // 【关键修复】原来 PanoramaStitcher.stitch() 全项目零调用 —— 帧只进不出，
        // 永远拼不出图，而且 panoramaFrames 只 add 不 clear，扫几十帧 OOM。
        if (panoramaMode) {
            when (tag) {
                "raw_original", "composed", "full", "single" -> {
                    val n = synchronized(panoramaFrames) {
                        panoramaFrames.add(jpeg)
                        panoramaFrames.size
                    }
                    if (panoStartAt == 0L) panoStartAt = System.currentTimeMillis()
                    if (!thisDone()) runOnUiThread {
                        if (thisDone()) return@runOnUiThread
                        if (n == 1) tvStatus.text = "全景 1 张 · 继续平移取景"
                        else tvStatus.text = "全景 $n 张"
                    }
                    // 够 4 帧即可出图；不自动触发，避免用户还没转完就被抢走
                }
                else -> {}
            }
            return
        }
        when (tag) {
            "raw_original" -> Thread {
                val uri = FileSaver.saveJpeg(this, jpeg, "raw")
                runOnUiThread {
                    hideProgress()
                    if (uri != null) {
                        lastUri = loadThumbnail(uri)
                        if (lastUri != null) Toast.makeText(this, "原图已保存", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "原图保存失败（存储空间不足？）", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()

            // 【关键修复】原来这个分支不调 hideProgress()，而 processSingleAsync 在
            // 调色/AI 分支前会 onProgress("调色") 显示全屏遮罩 → 只要选过一个滤镜或
            // 开了 AI，遮罩就永久停留，必须再拍一张才恢复。
            "ai_processed" -> Thread {
                val uri = FileSaver.saveJpeg(this, jpeg, "ai")
                runOnUiThread {
                    hideProgress()
                    if (uri != null) {
                        lastUri = loadThumbnail(uri)
                        Toast.makeText(this, "AI 处理完成", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "AI 图片保存失败", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()

            else -> Thread {
                // 【关键修复】方向处理必须对所有分支一致。
                // 原来 raw_original / ai_processed 直接原样落盘，只有 else 分支做旋转，
                // 于是开了「输出方向」或「镜像」后一次拍摄存出两张朝向不同的图。
                val finalJpeg = applyOrientationToJpeg(jpeg, extraRotation, mirrorX)
                val uri = FileSaver.saveJpeg(this, finalJpeg, tag)
                runOnUiThread {
                    hideProgress()
                    // 【关键修复】原来无条件弹「已保存 XXX KB」，用的是输入字节数，
                    // 完全不看 uri —— 存图失败也告诉用户已保存。
                    if (uri != null) {
                        Toast.makeText(this, "已保存 ${finalJpeg.size / 1024} KB", Toast.LENGTH_SHORT).show()
                        lastUri = loadThumbnail(uri)
                    } else {
                        Toast.makeText(this, "保存失败，请检查存储空间", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    private fun applyOrientationToJpeg(
        jpeg: ByteArray, rotation: Int, mirror: Boolean
    ): ByteArray {
        if (rotation == 0 && !mirror) return jpeg
        return try {
            val bmp = MatUtils.jpegToBitmap(jpeg) ?: return jpeg
            val m = Matrix()
            if (mirror) m.postScale(-1f, 1f)
            if (rotation != 0) m.postRotate(rotation.toFloat())
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out != bmp) bmp.recycle()
            val bytes = MatUtils.bitmapToJpeg(out, 95)
            out.recycle()
            bytes
        } catch (_: Exception) { jpeg }
    }

    // ================= 【新增】5 个 vivo 风格模式的帧处理分派 =================

    /**
     * 包围曝光帧到达后的分派中心。
     * 由 [CameraController] 的 `onBracketFrames` 在相机线程回调，这里只做线程切换。
     */
    private fun handleBracketResult(mode: String, frames: List<ByteArray>) {
        if (frames.isEmpty()) return
        // 修复：相机回调在进程级线程池上执行，Activity 可能已被销毁。
        if (thisDone()) return
        if (modeProcessing) {
            AppLogger.w("MainActivity", "上一轮 $mode 处理未结束，丢弃本轮帧")
            return
        }
        modeProcessing = true
        runOnUiThread { if (!thisDone()) showProgress("${mode}处理中…") }

        val activity = this
        BackgroundProcessor.submit("$mode 处理") { onProgress ->
            if (activity.thisDestroyed) return@submit
            val onTick: (Int, String) -> Unit = { p, msg ->
                if (!activity.thisDestroyed) onProgress(p, msg)
            }
            val out: ByteArray? = try {
                when (mode) {
                    "夜景" -> NightProcessor.merge(frames, onTick)
                    "星空" -> AstroProcessor.stack(frames, stretch = true, onProgress = onTick)
                    "时光慢门" -> LongExposureProcessor.merge(frames, slowShutterStyle, onTick)
                    "HDR" -> HdrProcessor.merge(frames, onTick)
                    "人像" -> {
                        // 人像虚化取中间帧做基准，先检测人脸再按掩膜虚化背景
                        val src = frames[frames.size / 2]
                        val bmp = MatUtils.jpegToBitmap(src)
                        if (bmp == null) {
                            onProgress(100, "人像：解码失败")
                            null
                        } else {
                            onProgress(25, "人脸检测")
                            // 修复：原实现 await 超时后无条件 bmp.recycle()，
                            // 而 ML Kit 的异步回调可能仍在读这张 Bitmap（InputImage
                            // 持有的是同一引用）→ 回收后使用导致原生崩溃。
                            // 改为：无论是否超时都等回调把 latch 计完，回调里也只做
                            // 纯数据搬运；确实要回收时再等一小段宽限期。
                            val boxed = java.util.concurrent.CountDownLatch(1)
                            // Kotlin 的局部变量不能加 @Volatile，用单元素数组承载跨线程结果，
                            // 并由 CountDownLatch 的 happens-before 保证可见性。
                            val boxesRef = arrayOf<List<android.graphics.RectF>>(emptyList())
                            CompositionAnalyzer.detectFaceBoxes(bmp) { list ->
                                boxesRef[0] = list
                                boxed.countDown()
                            }
                            val callbackDone =
                                boxed.await(3, java.util.concurrent.TimeUnit.SECONDS)
                            if (!callbackDone) {
                                AppLogger.w("MainActivity", "人脸检测超时，按无脸处理")
                            } else {
                                // 回调已完成，可安全回收
                                bmp.recycle()
                            }
                            onProgress(55, "背景虚化")
                            PortraitBokehProcessor.render(
                                src, boxesRef[0],
                                blurStrength = (7 + bokehStrength / 100f * 38f).toInt(),
                                onProgress = onTick
                            )
                        }
                    }
                    else -> frames.firstOrNull()
                }
            } catch (e: Exception) {
                AppLogger.e("MainActivity", "$mode 处理失败: ${e.message}", e)
                null
            } finally {
                modeProcessing = false
            }

            if (activity.thisDestroyed) return@submit
            activity.runOnUiThread { if (!activity.thisDestroyed) hideProgress() }
            val result = out ?: frames.first()
            val fixed = applyOrientationToJpeg(result, extraRotation, mirrorX)
            val uri = FileSaver.saveJpeg(activity, fixed, mode)
            activity.runOnUiThread {
                if (activity.thisDestroyed) return@runOnUiThread
                if (uri != null) {
                    lastUri = uri; loadThumbnail(uri)
                    Toast.makeText(
                        activity, "$mode 已保存（${frames.size} 帧合成）", Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(activity, "$mode 保存失败", Toast.LENGTH_SHORT).show()
                }
                tvStatus.text = "$mode 完成"
            }
        }
    }

    /** Activity 是否已不可用（销毁 / finishing） */
    private fun thisDone(): Boolean = thisDestroyed || isFinishing || isDestroyed

    /** 把当前模式的采集计划推给相机控制器（切换模式 / 进入拍摄前调用） */
    private fun pushModePlan() {
        val plan = planForMode(currentMode)
        pushControls(plan)
    }

    private fun updateLut() {
        val lut = LutBuilder.build(colorGradeParams)
        preview.queueEvent {
            renderer.updateLut(lut)
            renderer.setLutIntensity(if (colorGradeParams.isDefault) 0f else 1f)
        }
    }

    /** 加载缩略图；返回传入的 uri 便于调用方链式赋值 */
    private fun loadThumbnail(uri: Uri): Uri? {
        Thread {
            try {
                val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.loadThumbnail(uri, Size(160, 160), null)
                } else {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                    contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    } ?: return@Thread
                }
                runOnUiThread {
                    if (thisDone()) return@runOnUiThread
                    ivThumb.setImageBitmap(bmp)
                    thumbContainer.visibility = View.VISIBLE
                }
            } catch (_: Throwable) {}
        }.start()
        return uri
    }

    /**
     * 录像态快门外观。
     * 原来 bg_shutter_ring_video.xml 做了但全项目零引用 —— 录制中快门不变红，
     * 用户完全没有"正在录"的反馈。
     */
    private fun syncShutterForVideo(recording: Boolean) {
        val target = if (recording) R.drawable.bg_shutter_ring_video else R.drawable.shutter_ring
        runCatching { btnShutter.background = ContextCompat.getDrawable(this, target) }
    }

    private fun syncToggleStates() {
        tvModeBadge.text = badgeText(BackgroundProcessor.activeJobs().size)
        btnFlash.background = ContextCompat.getDrawable(this, R.drawable.bg_circle_btn)
        // 【修复】顶栏与快捷行是同一个开关的两个入口，原来只各自刷新自己，
        // 导致"网格/构图"在两处显示相反的高亮状态。这里统一刷新顶栏，
        // 快捷行由 buildQuickRow() 负责。
        btnGrid.background = ContextCompat.getDrawable(
            this,
            if (gridEnabled) R.drawable.bg_circle_btn_on else R.drawable.bg_circle_btn
        )
        btnComposition.background = ContextCompat.getDrawable(
            this,
            if (compositionEnabled) R.drawable.bg_circle_btn_on else R.drawable.bg_circle_btn
        )
        btnPalette.background = ContextCompat.getDrawable(this, R.drawable.bg_circle_btn)
        gridOverlay.visibility = if (gridEnabled) View.VISIBLE else View.GONE
        compositionPanel.visibility = if (compositionEnabled) View.VISIBLE else View.GONE
        if (compositionLocked) SceneAdvisor.setLocked(true)
    }

    private fun exportLog() {
        Thread {
            val uri = AppLogger.exportToFile(this)
            runOnUiThread {
                if (uri != null) {
                    Toast.makeText(this, "日志已导出到 Downloads/MultiCam", Toast.LENGTH_LONG).show()
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "text/plain")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (_: Exception) {}
                } else {
                    Toast.makeText(this, "日志导出失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onPause() {
        preview.onPause()
        orientationTracker.stop()
        videoMode = false
        videoSession?.stopRecording()
        videoSession = null
        controller?.release()
        controller = null
        cameraStarted = false
        previewSurface = null
        glSurfaceTexture = null
        hideProgress()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        preview.onResume()
        orientationTracker.start()
        // 修复（2026-10）：onPause 会把 previewSurface 清空，而恢复是否重启相机
        // 取决于 GLSurfaceView 是否再次回调 onSurfaceTextureReady。部分机型在该
        // SurfaceTexture 被复用时不会重发回调 → 回前台后预览永久黑屏、快门无效。
        // 这里直接用仍存活的 glSurfaceTexture 重建 Surface 并启动相机。
        val st = glSurfaceTexture
        if (st != null) {
            if (previewSurface == null) {
                previewSurface = Surface(st)
                applyPreviewTransform()
            }
            if (!cameraStarted) checkPermissionsAndStart()
        } else if (previewSurface != null) {
            checkPermissionsAndStart()
        }
    }

    override fun onDestroy() {
        // 修复（2026-10）：解注册进程级单例回调，避免 Activity 被 BackgroundProcessor 持有
        BackgroundProcessor.setOnJobsChanged(null)
        // 修复：把相机线程池上悬挂的回调标记为失效，防止 Activity 销毁后
        // 后台线程仍调用 handleResult/handleBracketResult → 操作已销毁的 View 崩溃。
        thisDestroyed = true
        controller?.release()
        controller = null
        super.onDestroy()
    }
}
