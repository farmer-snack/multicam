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
    /** 到达帧数后自动拼接 */
    private fun stitchPanorama() {
        val frames = synchronized(panoramaFrames) {
            if (panoramaFrames.size < PANO_MIN_FRAMES) return
            val copy = ArrayList(panoramaFrames)
            panoramaFrames.clear()
            copy
        }
        panoStartAt = 0L
        panoLastAt = 0L
        showProgress("全景拼接中…")
        BackgroundProcessor.submit("全景拼接") {
            val out = try {
                PanoramaStitcher.stitch(frames)
            } catch (e: Throwable) {
                AppLogger.e("MainActivity", "全景拼接失败: ${e.message}", e)
                if (!thisDone()) runOnUiThread {
                    if (!thisDone()) hideProgress()
                    Toast.makeText(this, "全景拼接失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
                return@submit
            }
            if (!thisDone()) runOnUiThread {
                if (thisDone()) return@runOnUiThread
                hideProgress()
                if (out == null || out.isEmpty()) {
                    Toast.makeText(
                        this, "全景拼接失败：帧数或重叠不足，请保持平稳平移", Toast.LENGTH_LONG
                    ).show()
                } else {
                    tvStatus.text = "全景已保存"
                    Toast.makeText(this, "全景已保存 ${out.size / 1024} KB", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 全景首帧时间戳：> PANO_MIN_INTERVAL_MS 才允许再拍一张，防止手抖连拍成静止画面 */
    private var panoStartAt = 0L

    /** 上一帧全景拍摄的时间戳，用于防抖 */
    private var panoLastAt = 0L

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

        /** 达到该帧数即自动拼接并落盘 */
        const val PANO_MIN_FRAMES = 4
    }

    private val requiredPermissions = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    // 【修复】删除 hasAskedPermission 与 PERMISSION_REQUEST_CODE：
    // 它们只服务于旧的"在 launcher 回调里用平台 requestPermissions 二次申请"逻辑。
    // 那条路径的结果不会回到 registerForActivityResult（只会走
    // onRequestPermissionsResult，而本类未覆写），导致授权成功后
    // 界面永远卡在"权限被拒绝"。现在统一由弹窗按钮在方法作用域内重试。

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // 注意：result[it] 是 Boolean?，不能用扩展函数（会触发类型推断递归问题）。
        val denied: List<String> = requiredPermissions.filter { result[it] != true }
        if (denied.isEmpty()) {
            startCameraIfReady()
            return@registerForActivityResult
        }

        /**
         * 【关键修复】原来这里是：
         *     if (hasAskedPermission) showPermissionDeniedDialog(denied)
         *     else { hasAskedPermission = true
         *            ActivityCompat.requestPermissions(this, requiredPermissions, CODE) }
         *
         * 平台版 `requestPermissions()` 的结果只会回调
         * `Activity.onRequestPermissionsResult()`，**不会**回到
         * `registerForActivityResult` 的这个 launcher —— 而本类也没有覆写
         * `onRequestPermissionsResult`。所以第二次申请的结果被完全丢弃：
         * 用户点「允许」也永远等不到 startCameraIfReady()，
         * 界面停在"权限被拒绝，无法使用相机"。
         *
         * 更糟的是它把"首次被拒"也当成了需要引导去设置页，
         * 用户明明还能正常授权，却被直接赶去系统设置。
         *
         * 现在：不在回调里重新发起请求（那会自引用 permLauncher，
         * 触发 Kotlin 递归类型推断），改为给用户一个明确的出口 ——
         * 弹窗里同时提供「重试」与「去设置」，重试按钮在**方法作用域**里
         * 调用 permLauncher，不存在自引用问题。
         */
        showPermissionDeniedDialog(denied)
    }

    /**
     * 长按对焦的 Handler / Runnable。
     *
     * 【关键修复】原来这两个是 [setupManualFocus] 里的局部变量，
     * onPause 无法清理。若用户按住预览时直接按 Home / 弹权限框，
     * View 收不到 ACTION_CANCEL → 600ms 后 runnable 照常执行 →
     * 此时 controller 已为 null，lockAfAe 是空操作，
     * 但 `afAeLocked = true` 照样被置上 → 下次长按走解锁分支，
     * 弹「已解锁 AF/AE」而实际从未锁过（标志与真实状态双向失配）。
     * 现在提升为字段，onPause 里统一 removeCallbacksAndMessages。
     */
    private val longPressFocusHandler = Handler(Looper.getMainLooper())

    /**
     * 权限被永久拒绝的引导弹窗。
     * 系统不再提供任何弹窗，只能把用户送到应用详情页手动开启。
     */
    private fun showPermissionDeniedDialog(denied: List<String>) {
        if (thisDone()) return
        tvStatus.text = "权限被拒绝，无法使用相机"
        val names = denied.joinToString("、") {
            when (it) {
                Manifest.permission.CAMERA -> "相机"
                else -> "存储"
            }
        }
        // 【修复】区分两种被拒：还能再弹窗（普通拒绝）vs 已永久拒绝。
        // 直接说"已被永久拒绝"会把第一次误触的用户也推去设置页。
        val canAskAgain = denied.any { shouldShowRequestPermissionRationale(it) }
        AlertDialog.Builder(this)
            // 【修复】"$names权限" 会被 Kotlin 解析成标识符 names权限（Unresolved），
            // 模板变量后必须紧跟中文时要用 ${} 显式界定边界
            .setTitle("需要${names}权限")
            .setMessage(
                if (canAskAgain)
                    "没有${names}权限无法打开相机，可重新授权。"
                else
                    "相机权限已被永久拒绝。请前往系统设置 → 应用 → MultiCam → 权限 中手动开启。"
            )
            .setPositiveButton(
                if (canAskAgain) "重新授权" else "去设置"
            ) { _, _ ->
                if (canAskAgain) {
                    // 【关键修复】从**方法作用域**（不是 launcher 回调里）发起，
                    // 因此引用 permLauncher 不构成自引用，能正常编译与回调。
                    runCatching { permLauncher.launch(denied.toTypedArray()) }
                        .onFailure {
                            // 兜底：某些 ROM 上 launcher 不可用时至少给出设置入口
                            runCatching {
                                startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.fromParts("package", packageName, null)
                                    )
                                )
                            }
                        }
                } else {
                    runCatching {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", packageName, null)
                            )
                        )
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
        syncToggleStates()

        orientationTracker = OrientationTracker(this)
        // 【新增】把陀螺仪的 roll（设备自身左右倾斜）注入 SceneAdvisor。
        // 之前 SceneAdvisor 只能拿"画面内容里的水平线倾角"去提示用户转手机 ——
        // 场景里有一条斜屋顶就提示"地平线右倾 8°"，而手机其实端得很稳。
        // 设备姿态只有陀螺仪能给出（OrientationTracker 已算出 orientation[2]）。
        orientationTracker.onRollChanged = { roll ->
            SceneAdvisor.setDeviceRoll(roll)
        }

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

    /**
     * 顶部模式角标。
     *
     * 【关键修复】原来只读 `preferQuality`（用户的**意图**），
     * 于是 UI 永远显示「全像素」，而实际可能因为设备/镜头不支持全像素
     * 而悄悄跑在 1080p 并发上 —— 用户看到的和拿到的完全不是一回事。
     * 现在如实反映 controller 实际选中的采集模式。
     */
    private fun badgeText(jobs: Int = 0): String = buildString {
        // 【关键修复】原来这里用**中文字符串**匹配 controller 返回的 modeName：
        //     "多摄并发" -> append("多摄")
        //     "单摄"     -> append(...)
        // 但 CameraController.modeName() 实际返回的是
        //     "三摄并发" / "双摄并发" / "兜底单摄"
        // —— 没有任何一个能命中上面两个分支，**永远**掉进 else，
        // 于是 badge 显示的是 `if (preferQuality) "全像素" else "多摄"`，
        // 也就是又回到了「显示用户意图而非实际采集模式」的老 bug：
        // 明明在跑 1080p 并发，badge 却写着"全像素"。
        //
        // 根因：用可翻译/可改动的展示文案当作跨模块的枚举标识。
        // 现在改为传 CameraModeKind 枚举，字符串只用于显示。
        when (controller?.currentModeKind()) {
            CameraModeKind.FULL_RESOLUTION -> append("全像素")
            CameraModeKind.MULTI_CONCURRENT -> append("多摄")
            CameraModeKind.SINGLE_DEFAULT ->
                append(if (preferQuality) "单摄·非全像素" else "单摄")
            else -> append(if (preferQuality) "全像素" else "多摄")
        }
        if (jobs > 0) append(" · 任务").append(jobs)
    }

    // ================= 动态构建：变焦条 =================

    private fun buildZoomRow() {
        zoomRow.removeAllViews()
        val stops = zoomStops()
        stops.forEach { z ->
            val tv = TextView(this).apply {
                text = if (z == z.toInt().toFloat()) "${z.toInt()}×" else "%.1f×".format(z)
                val selected = (z == zoomValue)
                // 【修复】原来无论选中与否都用 text_primary(#FFFFFF)。
                // 选中态背景是**不透明**的纯橙 #FF9F0A，白/橙对比度只有 2.06:1
                // （WCAG AA 要求 4.5:1），户外几乎读不出「当前是几倍」——
                // 而这恰恰是最该看清的信息。选中时改用深墨色 text_on_accent（7.4:1）。
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (selected) R.color.text_on_accent else R.color.text_primary
                    )
                )
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = ContextCompat.getDrawable(
                    this@MainActivity,
                    if (selected) R.drawable.bg_zoom_pill_sel else R.drawable.bg_zoom_pill
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
        QuickToggle("denoise", "降噪", { enableAIDenoise }, {
            enableAIDenoise = !enableAIDenoise; SettingsStore.saveDenoise(this, enableAIDenoise)
            // 【修复】原来这个开关只改内存值，不下发到 controller。
            // enableAIDenoise 是构造参数，只在 restartCamera() 建 controller 时读一次
            // → 用户打开「降噪」后直接按快门，成片完全没变化，
            // 必须再去拨一下 RAW/构图/高像素才生效。
            if (cameraStarted) restartCamera()
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
                    // 【关键修复】原来只重建快捷行，不刷新顶栏 ——
                    // "网格""构图"在顶栏与快捷行是两个入口，改了快捷行后
                    // 顶栏按钮仍是旧高亮状态（反之亦然），用户看到两个相反的状态。
                    // 这里统一刷新顶栏；设置面板侧由 rebuildSettings() 自行负责。
                    syncToggleStates()
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
        if (currentMode == name) {
            // 【修复】早退不能只是 return：那样会跳过 pushModePlan()，
            // 录像模式的失败（startVideo 的 3 个提前 return 分支）就无法原地重试，
            // preferQuality 的归位与倍率条重建也会被跳过。
            buildModeStrip(); buildQuickRow(); buildZoomRow()
            pushModePlan()
            return
        }

        // 【关键修复】原来整个 switchMode 从头到尾没有写过 currentMode，
        // 而 currentMode 被下面四处消费：
        //   1. buildModeStrip()  → `m.name == currentMode` 决定哪个模式 chip 高亮
        //   2. planForMode(currentMode) → 夜景/星空/慢门/HDR 的逐帧曝光计划
        //   3. bracketModeSnapshot → handleBracketResult 分派到哪个处理器
        //   4. showProgress 文案 → "夜景采集中…" vs "拍摄中…"
        // 漏掉赋值 = 模式条永远不跟随切换 + 所有特殊模式退化成普通单张。
        currentMode = name

        // 【修复】原来在这里把「拍照」强制降级为 preferQuality=false 并落盘。
        // 但「拍照」是默认模式、应该是最高画质，CaptureStrategy 反而会因此
        // 跳过 FullResolution 走多摄并发 —— 点一下默认模式画质就永久变差。
        // preferQuality 只应由「高像素」这个显式选项和设置面板控制。
        if (name == "高像素" && !preferQuality) {
            preferQuality = true
            SettingsStore.savePreferQuality(this, true)
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
            // 【关键修复】这里必须无条件重建。
            // 原来写的是 `if (cameraStarted) restartCamera()`，而上面的
            // releaseCameraController() 已经把 cameraStarted 置成 false，
            // 于是「进录像 → 切回拍照」后永远不会重启相机 → 预览永久黑屏、
            // 快门提示"相机未就绪"，只能切后台再回前台才能救回来。
            // cameraStarted 的唯一置 true 点是 onStatus，而 onStatus 属于
            // 已被释放的 controller —— 鸡生蛋死循环。
            restartCamera()
        }

        // 模式变化必须刷新所有受影响的 UI：模式条高亮、快捷开关可用态、倍率、角标
        // 注意：必须在相机重建之后调用，否则 modeList() 里读的是旧 controller
        // 的能力（甚至 controller 已被置 null），会让「录像/星空/慢门」误判为不可用。
        buildModeStrip()
        buildQuickRow()
        buildZoomRow()
        tvModeBadge.text = badgeText(BackgroundProcessor.activeJobs().size)
        syncToggleStates()
        // 采集计划随模式重建（夜景/星空/慢门/HDR 需要逐帧曝光）。
        // restartCamera() 内部已调过一次 pushControls()，参数与这里等价，
        // 但 planForMode 是纯计算，重复下发只会多一次 setRepeatingRequest。
        pushModePlan()
        Toast.makeText(this, "已切换到 $name", Toast.LENGTH_SHORT).show()
    }

    /**
     * 释放预览相关的 Java 侧 native 资源。
     *
     * 【关键修复】原来 onPause 只是 `previewSurface = null; glSurfaceTexture = null`，
     * 把引用丢掉却从不 release()：
     *   - Surface 持有一个 native producer（BufferQueue slot）
     *   - SurfaceTexture 持有 native BufferQueue（1920×1080×4×3 ≈ 24MB）
     * 反复切前后台 10~30 次后 native 内存单调上涨，
 * * 只有 adb 才能 dump，GC 也回收不了（已被丢弃引用）→ 预览变黑 / 被杀。
     * 现在显式 release，并保持幂等（可重复调用）。
     */
    private fun releasePreviewSurfaces() {
        runCatching { previewSurface?.release() }
        previewSurface = null
        // SurfaceTexture 由 GpuPreviewRenderer 持有并在 GL 线程释放，
        // 这里只清引用；真正 release 走 renderer.releaseGlResources()
        glSurfaceTexture = null
    }

    /** 释放拍照用相机控制器（切录像 / onPause / onDestroy 共用） */
    private fun releaseCameraController() {
        runCatching { controller?.release() }
        controller = null
        cameraStarted = false
        // 【关键修复】相机被整体重建，旧的 AF/AE 锁定状态不再成立。
        // 不复位的话新会话下长按会走"解锁"分支，弹「已解锁 AF/AE」而实际从未锁过。
        afAeLocked = false
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
        // 【关键修复】原来用 caps.cameraInfos（= 所有后摄按焦距升序 = 超广角在前）取前 2 个，
        // 而不是权威的「可并发组合」集合。{超广角, 主摄} 这个组合未必在
        // concurrentIds 里 → 请求一个不受支持的并发组合 → 会话永远配不起来，
        // videoMode 一直是 false，点快门变成拍照。
        // 另外分辨率只查了 ids.first()（超广角）的配置表，再把同一个尺寸套到第二路，
        // 第二路 createCaptureSession 必然失败。
        val caps = CameraCapabilityDetector(this).detect()
        val ids = (caps.concurrentIds.ifEmpty {
            caps.cameraInfos.map { it.id }
        }).take(2)
        if (ids.isEmpty()) {
            Toast.makeText(this, "未找到可用摄像头", Toast.LENGTH_SHORT).show()
            return
        }
        // 录像分辨率：取所有路都支持的最小公共尺寸
        val recSize = ids.mapNotNull { pickVideoSize(it) }.minByOrNull { it.width.toLong() * it.height }
            ?: Size(1280, 720)
        val outDir = FileSaver.videoDir(this)
        val outFile = File(outDir, "multicam_${System.currentTimeMillis()}.mp4")
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
                // 【关键修复】原来这里只把字段置 null，不释放已打开的相机/recorder
                // → 它们继续占着 cameraId → 切回拍照时 openCamera 拿到 CAMERA_IN_USE
                // → 拍照模式也黑屏。必须真正 stopRecording()。
                runCatching { videoSession?.stopRecording() }
                videoSession = null
                videoMode = false
                if (!thisDone()) runOnUiThread {
                    if (thisDone()) return@runOnUiThread
                    syncShutterForVideo(false)
                    tvStatus.text = "录像错误：$msg"
                    Toast.makeText(this, "录像错误：$msg", Toast.LENGTH_LONG).show()
                }
            },
            onReady = {
                if (!thisDone()) runOnUiThread {
                    if (thisDone()) return@runOnUiThread
                    videoMode = true
                    // 【关键修复】原来只有状态栏文案变化，快门外观完全不变，
                    // 用户没有任何"正在录"的视觉反馈（bg_shutter_ring_video 零引用）。
                    syncShutterForVideo(true)
                    tvStatus.text = "● 录制中 ${recSize.width}×${recSize.height}"
                    Toast.makeText(this, "开始录制", Toast.LENGTH_SHORT).show()
                }
                videoSession?.startRecording()
            },
            onStopped = { file ->
                if (!thisDone()) runOnUiThread {
                    if (thisDone()) return@runOnUiThread
                    // 【关键修复】同步复位快门外观，否则停止后按钮仍是红色的
                    syncShutterForVideo(false)
                    videoMode = false
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
        syncShutterForVideo(false)
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
        // 【关键修复】控件变更只在**松手时**下发。
        // 原来每次 onProgressChanged 都 pushControls()，而 pushControls 内部会
        // 覆写 bracketModeSnapshot（采集计划快照）并 setRepeatingRequest ——
        // 拖动滑块时每秒数十次重建重复请求，预览可见顿挫；
        // 更糟的是夜景包围采集中途拖一下 ISO/S，快照就被改写成"拍照"模式，
        // 导致 HDR 帧被交给夜景处理器。
        // UI 数值仍然实时刷新（用户需要看到滑动反馈），只是控制下发延到松手。
        sbEv.setOnSeekBarChangeListener(simpleSeek({ p ->
            evValue = (p - 4) / 2f
            tvEv.text = "%+.1f".format(evValue)
        }, { pushControls() }))

        sbIso.setOnSeekBarChangeListener(simpleSeek({ p ->
            isoIndex = p
            tvIso.text = if (ISO_STEPS[p] == 0) "自动" else "${ISO_STEPS[p]}"
        }, { pushControls() }))

        sbShutter.setOnSeekBarChangeListener(simpleSeek({ p ->
            shutterIndex = p
            tvShutter.text = SHUTTER_LABELS[p]
        }, { pushControls() }))

        sbWb.setOnSeekBarChangeListener(simpleSeek({ p ->
            wbIndex = p
            tvWb.text = WB_LABELS[p]
        }, { pushControls() }))
    }

    /**
     * SeekBar 监听包装。
     *
     * 【关键修复】新增 [onRelease] 回调：SharedPreferences 的持久化只在
     * **松手时**执行一次。
     * 原来每次 onProgressChanged 都调 persistCustomGrade()（内部 2 次
     * `edit().apply()`），而 apply 的写盘会全部堆进 QueuedWork，
     * 60~120Hz 拖动 = 每秒 120~240 次持锁写，把 UI 线程的卡顿放大数倍。
     */
    private fun simpleSeek(
        onChange: (Int) -> Unit,
        onRelease: (() -> Unit)? = null
    ) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) { onRelease?.invoke() }
        }

    // ================= 调色面板 =================

    private fun setupColorPanel() {
        colorPanel.findViewById<View>(R.id.btnColorClose).setOnClickListener {
            colorPanel.visibility = View.GONE
        }

        colorWheel.onColorChanged = { x, y ->
            colorGradeParams = colorGradeParams.copy(hueShiftX = x, toneShiftY = y)
            controller?.colorParams = colorGradeParams
            updateLut()
        }
        colorWheel.onHueRingChanged = { angle ->
            // 色相环映射到 -1..1 的色相偏移（左右各半圈）
            val n = if (angle > 180f) (angle - 360f) / 180f else angle / 180f
            colorGradeParams = colorGradeParams.copy(hueShiftX = n)
            controller?.colorParams = colorGradeParams
            updateLut()
        }
        // 【修复】持久化只在松手时做一次（原来每次 ACTION_MOVE 都写两次
        // SharedPreferences，60~120 次/秒的持锁写把拖动卡顿放大数倍）
        colorWheel.onDragFinished = { persistCustomGrade() }

        sbSaturation.setOnSeekBarChangeListener(simpleSeek({ p ->
            colorGradeParams = colorGradeParams.copy(saturation = p / 100f)
            tvSaturation.text = "%.2f".format(p / 100f)
            controller?.colorParams = colorGradeParams
            updateLut()
        }, { persistCustomGrade() }))

        sbContrast.setOnSeekBarChangeListener(simpleSeek({ p ->
            // 【修复】对比度原来没有下限保护：SeekBar max=150、min 默认为 0，
            // p=0 时 contrast=0 → ColorGrader 把整个 3×4 矩阵乘 0、
            // 偏移恒为 127.5 → 拍出一整张纯灰图，而且会被持久化。
            // ColorGradeParams 的语义域是 0.5..1.5，这里做同样的钳制。
            val c = (p / 100f).coerceIn(0.5f, 1.5f)
            colorGradeParams = colorGradeParams.copy(contrast = c)
            tvContrast.text = "%.2f".format(c)
            controller?.colorParams = colorGradeParams
            updateLut()
        }, { persistCustomGrade() }))

        sbTemperature.setOnSeekBarChangeListener(simpleSeek({ p ->
            val t = (p - 100) / 100f
            colorGradeParams = colorGradeParams.copy(temperature = t)
            tvTemperature.text = "%+.2f".format(t)
            controller?.colorParams = colorGradeParams
            updateLut()
        }, { persistCustomGrade() }))

        // 启动恢复上次的预设 / 自定义调色
        restoreColorState()
        syncColorLabels()
        // 【关键修复】原来 updateLut() 从不在启动路径调用，
        // 只在"用户拖动/点滤镜"时才触发 → 重启 App 后滑块数值与色环位置
        // 都恢复了，但 GL 预览仍是**原始画面**（lutIntensity 停在 0），
        // 用户以为"保存的调色丢了"。
        updateLut()
        // 【关键修复】buildFilterRow 在 onCreate 里先于 setupColorPanel 执行，
        // 此刻 currentFilter 还是 0；restoreColorState 之后才设成存档值，
        // 但没人重建 filterRow → 重启后滤镜条永远高亮第 0 个"原图"，
        // 实际生效的却是存档里的第 N 个预设。
        buildFilterRow()
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
        val longPressHandler = longPressFocusHandler
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
                    // 【修复】原来每次相机状态回调都无条件 hideProgress()。
                    // 而 HDR/夜景的后台合成还在 BackgroundProcessor 里跑着时
                    // 也会触发 onStatus → 遮罩被提前撤掉，用户以为处理完了，
                    // 随即再按快门又撞上「上一轮未结束」的静默丢弃。
                    if (!modeProcessing && BackgroundProcessor.activeJobs().isEmpty()) {
                        hideProgress()
                    }
                    buildZoomRow()
                    buildModeStrip()
                    buildQuickRow()
                    // 【关键修复】用户勾了「高像素」但设备/镜头实际不支持时
                    // 必须明说，而不是让 UI 显示「全像素」却交付 1080p。
                    if (controller?.fullResRequestedButUnavailable() == true) {
                        tvModeBadge.text = badgeText(BackgroundProcessor.activeJobs().size)
                        Toast.makeText(
                            this, "该镜头不支持全像素，已使用普通分辨率", Toast.LENGTH_LONG
                        ).show()
                    }
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
                    // 【关键修复】原实现只 add 不 stitch、不落盘、不设上限：
                    //   - PanoramaStitcher.stitch() 全项目**零调用** → 永远拼不出图
                    //   - panoramaFrames 只进不出 → 扫几十帧必 OOM（每帧 3~8MB）
                    //   - 用户除了状态栏数字在涨，屏幕上没有任何变化
                    // 现在：帧数达标即自动拼接并落盘，随后清空进入下一轮。
                    val now = System.currentTimeMillis()
                    if (panoLastAt != 0L && now - panoLastAt < PANO_MIN_INTERVAL_MS) {
                        // 防抖：两次快门间隔过短多半是手抖，帧几乎相同会拖慢 ORB 匹配
                        return
                    }
                    panoLastAt = now
                    if (panoStartAt == 0L) panoStartAt = now
                    val n = synchronized(panoramaFrames) {
                        panoramaFrames.add(jpeg)
                        panoramaFrames.size
                    }
                    if (!thisDone()) runOnUiThread {
                        if (thisDone()) return@runOnUiThread
                        tvStatus.text = if (n == 1)
                            "全景 1 张 · 继续平移取景" else "全景 $n 张 · 拼图中…"
                    }
                    if (n >= PANO_MIN_FRAMES) stitchPanorama()
                }
                else -> {}
            }
            return
        }
        when (tag) {
            // 【关键修复】原来只有 else 分支做方向处理，raw_original / ai_processed
            // 直接把原始 JPEG 字节落盘。于是只要用户开了「输出方向」或「镜像」
            // （而这恰好是 AI 路径的前置条件），一次拍摄就会在相册里存出
            // **两张朝向不一致的图**，用户以为保存坏了。
            // 方向处理必须对所有分支一致。
            "raw_original" -> Thread {
                val fixed = applyOrientationToJpeg(jpeg, extraRotation, mirrorX)
                val uri = FileSaver.saveJpeg(this, fixed, "raw")
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
                val fixed = applyOrientationToJpeg(jpeg, extraRotation, mirrorX)
                val uri = FileSaver.saveJpeg(this, fixed, "ai")
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
            // 【关键修复】原来这里只写日志就 return，用户完全无感；
            // 更糟的是 return 之前没有 hideProgress()，而进度遮罩是在
            // handleResult 里 showProgress 弹出的 —— 于是「拍摄中…」的
            // 全屏遮罩**永久停留**，夜景/星空模式下只能杀进程。
            // 现在明确告知并撤掉遮罩，让用户知道这一轮没成、可以重拍。
            runOnUiThread {
                if (thisDone()) return@runOnUiThread
                hideProgress()
                Toast.makeText(
                    this, "上一轮$mode 还在处理，请稍候再拍", Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        modeProcessing = true
        runOnUiThread { if (!thisDone()) showProgress("${mode}处理中…") }

        val activity = this
        BackgroundProcessor.submit("$mode 处理") { onProgress ->
            // 【关键修复】原来这里是 `if (activity.thisDestroyed) return@submit`，
            // 位于 try/finally **之外** —— 直接跳过 finally，modeProcessing
            // 永久停在 true，之后所有 HDR/夜景拍摄都被静默丢弃、快门永久失效。
            // 现在任何退出路径都会复位标志。
            if (activity.thisDestroyed) {
                modeProcessing = false
                return@submit
            }
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
                                // 【关键修复】blurStrength 现在是 0..100 的强度百分比，
                                // 实际模糊核由 PortraitBokehProcessor 按图像短边归一化换算。
                                // 原来这里传的是固定像素值 7~45，在 4000px 图上等于没虚化。
                                blurStrength = bokehStrength,
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

    /**
     * 重建 LUT 并上传 GPU。
     *
     * 【关键修复】加了节流 + 背压。GLSurfaceView.queueEvent 内部是**无上限队列**
     * （GLThread.mEventQueue 的 ArrayDeque），每个闭包强引用一整张 1MB 的 LUT：
     *   - 拖动调色时每帧 queueEvent 一次，GL 线程每秒只能处理几百个 glTexImage2D
     *     → 队列长度只增不减，1 秒拖动就堆积几十 MB（实测 OOM）
     *   - 表现是"松手后画面还要追赶好几秒才停"
     *   - onPause 期间 queueEvent 仍入队但无人消费 → 回前台瞬间卡住
     *
     * 现在：用标记位合并连续请求，最多每 66ms（≈15fps）真正上传一次；
     * 参数快照随任务走，避免闭包读到已变化的 colorGradeParams。
     */
    private var lutPending = false
    private var lastLutUploadAt = 0L

    private fun updateLut() {
        val now = System.currentTimeMillis()
        if (now - lastLutUploadAt < LUT_UPLOAD_INTERVAL_MS) {
            // 还在节流窗口内：只标记"需要再来一次"，不排队
            if (!lutPending) {
                lutPending = true
                preview.postDelayed({
                    if (lutPending) { lutPending = false; updateLut() }
                }, LUT_UPLOAD_INTERVAL_MS)
            }
            return
        }
        lutPending = false
        lastLutUploadAt = now
        // 快照：闭包里不再读可变字段
        val params = colorGradeParams
        val lut = LutBuilder.build(params)
        val intensity = if (params.isDefault) 0f else 1f
        preview.queueEvent {
            renderer.updateLut(lut)
            renderer.setLutIntensity(intensity)
        }
    }

    /** LUT 上传节流间隔：15fps 足够顺滑，且把 1MB×60/s 压到 1MB×15/s */
    private val LUT_UPLOAD_INTERVAL_MS = 66L

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
        // 【关键修复】原来是无条件画"关"的样式（只改背景、不看真实状态），
        // 把它加进 switchMode 后每次切模式都会执行 → 开了强制闪光/调色面板时
        // 按��底圈变灰但状态没变，出现"图标亮着、按钮却是灰的"。
        btnFlash.background = ContextCompat.getDrawable(
            this,
            if (flashMode == CameraControls.FLASH_OFF) R.drawable.bg_circle_btn
            else R.drawable.bg_circle_btn_on
        )
        // 顶栏与快捷行是同一开关的两个入口，原来只各自刷新自己，
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
        btnPalette.background = ContextCompat.getDrawable(
            this,
            if (colorWheelVisible) R.drawable.bg_circle_btn_on else R.drawable.bg_circle_btn
        )
        gridOverlay.visibility = if (gridEnabled) View.VISIBLE else View.GONE
        compositionPanel.visibility = if (compositionEnabled) View.VISIBLE else View.GONE
        // 构图锁定标志被持久化了，但 SceneAdvisor/叠加层的冻结状态是进程级的，
        // 冷启动后不会自动恢复 → 设置里显示"已锁"但引导线却还在动。
        SceneAdvisor.setLocked(compositionLocked)
        compositionOverlayView.setFrozen(compositionLocked)
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
        // 【修复】先释放 GL/Java 侧资源，再停相机。
        // 原来只在末尾把 previewSurface/glSurfaceTexture 置 null ——
        // Surface 与 SurfaceTexture 持有的 native BufferQueue（1920×1080×4×3
        // ≈ 24MB/轮）都**从不 release**，只是丢掉引用，
        // 反复切前后台十几次后内存持续上涨、预览变黑。
        releasePreviewSurfaces()
        // 长按对焦的延迟任务：若用户按住预览时直接按 Home，
        // View 收不到 ACTION_CANCEL，600ms 后 runnable 仍会执行 →
        // 在 controller 已为 null 的情况下把 afAeLocked 置 true（状态失配）
        longPressFocusHandler.removeCallbacksAndMessages(null)
        releaseCameraController()
        runCatching { videoSession?.stopRecording() }
        videoSession = null
        videoMode = false
        syncShutterForVideo(false)
        hideProgress()
        super.onPause()
    }

override fun onResume() {
        super.onResume()
        preview.onResume()
        orientationTracker.start()
        // 【修复】原来的"防黑屏兜底"是死代码：onPause 把 glSurfaceTexture 与
        // previewSurface 都置了 null，所以这里 `st != null` 恒为假、
        // `previewSurface != null` 恒为假，两个分支永远不执行。
        // 恢复实际依赖 GpuPreviewRenderer.onSurfaceCreated 重建 SurfaceTexture
        // 并回调 onSurfaceTextureReady。
        // 现在改为无条件检查：GL 线程若尚未回调（首帧很快回来），
        // 仍能通过 onSurfaceTextureReady → checkPermissionsAndStart 恢复；
        // 同时这里做一次兜底重建，避免某些 ROM 上 onSurfaceCreated 不重跑时永久黑屏。
        if (previewSurface == null && glSurfaceTexture != null) {
            previewSurface = Surface(glSurfaceTexture!!)
            applyPreviewTransform()
        }
        if (!cameraStarted) checkPermissionsAndStart()
    }

override fun onDestroy() {
        // 修复（2026-10）：解注册进程级单例回调，避免 Activity 被 BackgroundProcessor 持有
        BackgroundProcessor.setOnJobsChanged(null)
        // 【新增】同样要解绑 OrientationTracker 的回调：
        // onRollChanged 的 lambda 捕获了 SceneAdvisor（单例），不置空会一直持有。
        orientationTracker.onRollChanged = null
        orientationTracker.onYawChanged = null
        orientationTracker.stop()
        // SceneAdvisor 是进程级单例：Activity 销毁后必须清掉 roll，
        // 否则新 Activity 未注入前会沿用上一次的设备姿态（甚至是从后台恢复的旧值），
        // 出现"刚进相机就提示把手机转正"且设备其实摆正的误报。
        SceneAdvisor.setDeviceRoll(null)
        // 【新增】清掉自定义 View 上挂的回调（ColorWheelView.onDragFinished 等
        // 捕获了 Activity 的 lambda），否则 View 树与 lambda 一起泄漏。
        runCatching {
            colorWheel.onColorChanged = null
            colorWheel.onHueRingChanged = null
            colorWheel.onDragFinished = null
        }
        // 标记已销毁：后续 handleResult/handleBracketResult 不再碰任何 View
        thisDestroyed = true
        releaseCameraController()
        // 【新增】释放预览 native 资源（Surface）与 GL 对象
        releasePreviewSurfaces()
        runCatching { renderer.releaseGlResources() }
        // 停止仍在跑的长曝光/延时任务
        runCatching { AIDenoise.close() }
        longPressFocusHandler.removeCallbacksAndMessages(null)
        // 全景帧持有的 ByteArray 一并释放
        synchronized(panoramaFrames) { panoramaFrames.clear() }
        super.onDestroy()
    }
}
