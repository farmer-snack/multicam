package com.example.multicam

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size

data class CameraCapabilities(
    val cameraInfos: List<CameraInfo>,
    val orderedBackIds: List<String>,
    val mainCameraId: String?,
    val fullResSupported: Boolean,
    val fullResSize: Size?,
    val fullResByCameraId: Map<String, Size> = emptyMap(),
    val concurrentIds: List<String>,
    val concurrentMaxSize: Size?,
    val previewSize: Size,
    val reason: String = "",
    // ---------- 长曝光能力（供星空 / 时光慢门使用） ----------
    /** SENSOR_INFO_MAX_EXPOSURE_TIME，0 表示未探测到 */
    val maxExposureNs: Long = 0L,
    /** SENSOR_INFO_SENSITIVITY_RANGE，0..0 表示未探测到 */
    val isoRange: IntRange = 0..0,
    /** 设备是否支持 DEPTH_OUTPUT（人像虚化可选增强） */
    val depthSupported: Boolean = false
) {
    /** 是否具备长曝光能力（星空/慢门可用性判定） */
    val longExposureSupported: Boolean get() = maxExposureNs > 0L
}

class CameraCapabilityDetector(context: Context) {

    private val manager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private companion object {
        /**
         * 判定"全分辨率输出"的面积阈值（以 万分数 表示，避免浮点）。
         * 输出 JPEG 面积达到原始像素阵列面积的该比例即认为支持全像素。
         * 取 9000/10000 = 0.9 是为了容忍 binning / 边缘裁切带来的少量损失。
         *
         * 【关键修复】原来写的是 `9L / 10` —— 这是 **Long 整数除法，结果是 0**。
         * 于是判据退化成 `maxArea >= pixelArea * 0`，即 **任何** JPEG stream
         * configuration map（含 640×480、1024×768 这种被严重 binning 的尺寸）
         * 都会被判定成"全像素"。
         * 后果：UI 显示"全像素"、走全像素分支，但实际拍到的是被大幅降采样的图，
         * 用户以为拿到了传感器满分辨率，其实画质远低于预期 —— 且完全无法察觉。
         * （const 表达式不做运行期求值，编译器不报错，静默生效。）
         *
         * 现在用整数比例表达，比较时按整数乘法，避免任何浮点误差：
         *     maxArea * 10000L >= pixelArea * FULL_RES_PERCENT
         */
        const val FULL_RES_PERCENT = 9000L
    }

    fun detect(): CameraCapabilities {
        val allIds: List<String> = try {
            manager.cameraIdList.toList()
        } catch (e: Exception) {
            AppLogger.e("Capability", "cameraIdList 失败: ${e.message}", e)
            emptyList()
        }
        AppLogger.d("Capability", "全部相机 id = $allIds")

        if (allIds.isEmpty()) return emptyCapabilities("无相机")

        val facingMap = mutableMapOf<String, Int?>()
        for (id in allIds) {
            facingMap[id] = try {
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING)
            } catch (e: Exception) {
                AppLogger.w("Capability", "读 $id LENS_FACING 失败: ${e.message}"); null
            }
        }
        AppLogger.d("Capability", "LENS_FACING = $facingMap")

        val backIds = allIds.filter {
            facingMap[it] == CameraCharacteristics.LENS_FACING_BACK
        }.ifEmpty {
            allIds.filter { facingMap[it] == null }
        }.ifEmpty { allIds }
        AppLogger.d("Capability", "候选相机 = $backIds")

        val infos = CameraRoleResolver.resolve(manager, backIds)
        val orderedIds = CameraRoleResolver.orderByRole(infos)

        val mainId = infos.firstOrNull { it.role == CameraRole.MAIN }?.id
            ?: orderedIds.getOrNull(orderedIds.size / 2)
            ?: backIds.firstOrNull()
            ?: allIds.firstOrNull()

        AppLogger.d("Capability", "infos=$infos ordered=$orderedIds mainId=$mainId")

        if (mainId == null) return emptyCapabilities("无可用主摄")

        // 全像素探测：遍历所有候选摄像头，记录各自可用的全像素 JPEG 尺寸
        //
        // 【关键修复】原判断是
        //     maxJpeg.width >= pixelArray.width && maxJpeg.height >= pixelArray.height
        // 用「宽和」两个方向都比对。但 SENSOR_INFO_PIXEL_ARRAY_SIZE 是传感器的
        // **原始像素阵列**（常见是 4:3 之外的其它比例，例如 8064×6048 即 4:3，
        // 而 binning 后的输出可能是 4000×3000），宽高比不一致时两个条件无法
        // 同时成立 → 明明支持全像素却被判为不支持 → CaptureStrategy 走不到
        // FullResolution 分支，UI 却仍显示「全像素」。
        //
        // 正确判据：只要「面积」达到像素阵列的 90% 以上就算全分辨率输出，
        // 这也是 Android 官方对 maximum resolution 的定义口径。
        val fullResByCameraId = mutableMapOf<String, Size>()
        var fullResSupported = false
        var fullResSize: Size? = null
        // 仅适配 Android 12+（API 31+）：MAXIMUM_RESOLUTION 配置图是 S 引入的，
        // 且 SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION 也是 S 才有的。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (cid in backIds) {
                val c = runCatching { manager.getCameraCharacteristics(cid) }.getOrNull() ?: continue
                val pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val maxMap = c.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
                )
                val maxJpeg = maxMap?.getOutputSizes(ImageFormat.JPEG)
                    ?.maxByOrNull { it.width.toLong() * it.height } ?: continue
                // 交叉相乘比较，避免除法/浮点：
                //   maxArea >= pixelArea * 0.9
                //   <=>  maxArea * 10000 >= pixelArea * 9000
                val maxArea = maxJpeg.width.toLong() * maxJpeg.height
                val pixelArea =
                    pixelArray?.let { it.width.toLong() * it.height } ?: 0L
                val isFullRes = pixelArray == null ||
                    maxArea * 10000L >= pixelArea * FULL_RES_PERCENT
                if (isFullRes) {
                    fullResByCameraId[cid] = maxJpeg
                    if (cid == mainId) {
                        fullResSupported = true
                        fullResSize = maxJpeg
                    }
                }
                AppLogger.d(
                    "Capability",
                    "$cid 全像素=${if (isFullRes) "是" else "否"} " +
                        "maxJpeg=${maxJpeg.width}x${maxJpeg.height} " +
                        "pixelArray=${pixelArray?.width}x${pixelArray?.height}"
                )
            }
        }
        AppLogger.d(
            "Capability",
            "全像素支持=$fullResSupported size=${fullResSize?.width}x${fullResSize?.height} " +
                "逐路=$fullResByCameraId"
        )

        var concurrentIds: List<String> = emptyList()
        var concurrentMax: Size? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val combos = runCatching { manager.concurrentCameraIds }
                .getOrDefault(emptySet())
            val backSet = backIds.toSet()
            val validCombos = combos
                .map { it.intersect(backSet) }
                .filter { it.size >= 2 }
                .sortedByDescending { it.size }
            val best = validCombos.firstOrNull()
            if (best != null) {
                concurrentIds = CameraRoleResolver.orderSubset(infos, best.toList())
                // 【关键修复】原来硬编码 Size(1920,1080)。
                // getConcurrentCameraIds() 只告诉你「哪些组合能并发」，不告诉你
                // 并发时支持多大分辨率 —— 大量机型（三摄+双摄并发）只支持到
                // 720p 或 1440×1080。凭空假定 1080p 会让 createCaptureSession
                // 直接抛异常 → 会话配不起来 → 重试 3 次 → 放弃 → 永久黑屏。
                // 这里改成取所有路都支持的最高公共尺寸。
                concurrentMax = commonConcurrentSize(concurrentIds)
                AppLogger.d(
                    "Capability",
                    "并发组合: $best → $concurrentIds @ ${concurrentMax?.width}x${concurrentMax?.height}"
                )
            }
        }

        var previewSize = Size(1280, 720)
        runCatching {
            val c = manager.getCameraCharacteristics(mainId)
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
            if (!sizes.isNullOrEmpty()) {
                previewSize = sizes
                    .filter { it.width <= 1920 && it.height <= 1080 }
                    .maxByOrNull { it.width * it.height }
                    ?: sizes.minByOrNull { it.width * it.height }!!
            }
        }
        AppLogger.d("Capability", "previewSize=$previewSize")

        // ---------- 长曝光 / 感光度 / 深度能力（供星空、时光慢门、人像虚化） ----------
        var maxExposureNs = 0L
        var isoRange = 0..0
        var depthSupported = false
        runCatching {
            val c = manager.getCameraCharacteristics(mainId)
            // 注意：Camera2 没有 SENSOR_INFO_MAX_EXPOSURE_TIME 这个常量，
            // 曝光时间上限来自 SENSOR_INFO_EXPOSURE_TIME_RANGE 的 upper（单位 ns）。
            c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                maxExposureNs = it.upper
            }
            c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                isoRange = it.lower..it.upper
            }
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            depthSupported = caps?.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT
            ) == true
        }.onFailure {
            AppLogger.w("Capability", "长曝光/ISO/深度探测失败: ${it.message}")
        }
        AppLogger.d(
            "Capability",
            "maxExposure=${maxExposureNs / 1_000_000}ms isoRange=$isoRange depth=$depthSupported"
        )

        val reason = buildString {
            append("back=${backIds.size} ")
            append("roles=")
            append(infos.joinToString(",") { "${it.role}(${it.equivalentFocal.toInt()}mm)" })
            append(" fullRes=${fullResSize?.toString() ?: "N/A"}")
            append(" concurrent=${if (concurrentIds.isNotEmpty()) concurrentIds.size else "N/A"}")
            if (maxExposureNs > 0L) append(" maxExp=${maxExposureNs / 1_000_000}ms")
        }

        return CameraCapabilities(
            cameraInfos = infos,
            orderedBackIds = orderedIds,
            mainCameraId = mainId,
            fullResSupported = fullResSupported,
            fullResSize = fullResSize,
            fullResByCameraId = fullResByCameraId,
            concurrentIds = concurrentIds,
            concurrentMaxSize = concurrentMax,
            previewSize = previewSize,
            reason = reason,
            maxExposureNs = maxExposureNs,
            isoRange = isoRange,
            depthSupported = depthSupported
        )
    }

    /**
     * 取一组相机**都支持**的最高 JPEG 输出尺寸（并发流的公共上限）。
     *
     * 并发时每颗相机仍要各自 createCaptureSession，输出尺寸必须是所有路的
     * 公共解。取交集后按面积选最大；交集为空时返回 null（调用方会放弃并发模式，
     * 而不是拿一个必然失败的组合去黑屏）。
     */
    private fun commonConcurrentSize(ids: List<String>): Size? {
        if (ids.isEmpty()) return null
        var common: Set<Size>? = null
        for (id in ids) {
            val map = runCatching {
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            }.getOrNull() ?: return null
            val jpeg = runCatching { map.getOutputSizes(ImageFormat.JPEG) }
                .getOrNull()?.toSet() ?: return null
            common = if (common == null) jpeg else (common intersect jpeg)
            if (common.isEmpty()) return null
        }
        val sizes = common ?: return null
        // 并发流是预览级用途，限制在 1080p 以内，避免无谓的大缓冲
        val bounded = sizes.filter { it.width <= 1920 && it.height <= 1920 }
        return bounded.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
    }

    private fun emptyCapabilities(reason: String) = CameraCapabilities(
        cameraInfos = emptyList(),
        orderedBackIds = emptyList(),
        mainCameraId = null,
        fullResSupported = false,
        fullResSize = null,
        fullResByCameraId = emptyMap(),
        concurrentIds = emptyList(),
        concurrentMaxSize = null,
        previewSize = Size(1280, 720),
        reason = reason
    )
}
