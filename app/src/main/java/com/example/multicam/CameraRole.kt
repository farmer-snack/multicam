package com.example.multicam

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.SizeF

enum class CameraRole { ULTRA_WIDE, MAIN, TELE, UNKNOWN }

data class CameraInfo(
    val id: String,
    val role: CameraRole,
    val focalLength: Float,
    val sensorWidthMm: Float
) {
    val equivalentFocal: Float
        get() = if (sensorWidthMm > 0f) focalLength * (36f / sensorWidthMm) else 0f
}

object CameraRoleResolver {

    private const val TAG = "CameraRole"

    fun resolve(manager: CameraManager, cameraIds: List<String>): List<CameraInfo> {
        val list = cameraIds.mapNotNull { id ->
            try {
                val c = manager.getCameraCharacteristics(id)
                val focals = c.get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                ) ?: return@mapNotNull null
                val sensorSize = c.get(
                    CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
                ) ?: SizeF(0f, 0f)

                val focal = focals.minOrNull() ?: return@mapNotNull null
                val sensorW = sensorSize.width

                val temp = CameraInfo(id, CameraRole.UNKNOWN, focal, sensorW)
                temp.copy(role = classify(temp.equivalentFocal))
            } catch (e: Exception) {
                Log.w(TAG, "解析摄像头 $id 失败: ${e.message}"); null
            }
        }.sortedBy { it.equivalentFocal }

        Log.d(TAG, "摄像头角色: " + list.joinToString {
            "${it.id}=${it.role}(${it.equivalentFocal}mm)"
        })
        return list
    }

    private fun classify(eqFocal: Float): CameraRole = when {
        eqFocal <= 0f -> CameraRole.UNKNOWN
        eqFocal < 20f -> CameraRole.ULTRA_WIDE
        eqFocal in 20f..60f -> CameraRole.MAIN
        else -> CameraRole.TELE
    }

    fun orderByRole(infos: List<CameraInfo>): List<String> {
        val uw = infos.firstOrNull { it.role == CameraRole.ULTRA_WIDE }?.id
        val main = infos.firstOrNull { it.role == CameraRole.MAIN }?.id
        val tele = infos.firstOrNull { it.role == CameraRole.TELE }?.id
        return listOfNotNull(uw, main, tele)
    }

    fun orderSubset(infos: List<CameraInfo>, ids: List<String>): List<String> {
        val byId = infos.associateBy { it.id }
        return ids.sortedBy { byId[it]?.equivalentFocal ?: Float.MAX_VALUE }
    }
}
