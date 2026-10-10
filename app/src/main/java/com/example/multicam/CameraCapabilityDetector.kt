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
        val fullResByCameraId = mutableMapOf<String, Size>()
        var fullResSupported = false
        var fullResSize: Size? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (cid in backIds) {
                val c = runCatching { manager.getCameraCharacteristics(cid) }.getOrNull() ?: continue
                val pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val maxMap = c.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
                )
                val maxJpeg = maxMap?.getOutputSizes(ImageFormat.JPEG)
                    ?.maxByOrNull { it.width * it.height } ?: continue
                if (pixelArray != null &&
                    maxJpeg.width >= pixelArray.width && maxJpeg.height >= pixelArray.height
                ) {
                    fullResByCameraId[cid] = maxJpeg
                    if (cid == mainId) {
                        fullResSupported = true
                        fullResSize = maxJpeg
                    }
                }
            }
        }

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
                concurrentMax = Size(1920, 1080)
                AppLogger.d("Capability", "并发组合: $best → $concurrentIds")
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
