package com.example.multicam

import android.content.Context

object SettingsStore {

    private const val PREF = "multicam_settings"

    private const val KEY_PREFER_QUALITY = "prefer_quality"
    private const val KEY_FRAMES = "frames_per_camera"
    private const val KEY_AI = "enable_ai_upscale"
    private const val KEY_DENOISE = "enable_ai_denoise"
    private const val KEY_MIRROR = "mirror_x"
    private const val KEY_EXTRA_ROT = "extra_rotation"
    private const val KEY_PREFERRED_ROLE = "preferred_role"
    private const val KEY_AUTO_ZOOM = "auto_zoom"
    private const val KEY_AUTO_COLOR = "auto_color"
    // 修复（2026-10）：以下四项在设置面板里可切换，但原先完全没有持久化 key，
    // 每次冷启动都会重置为默认值（用户改完退出再进来发现白改了）。
    private const val KEY_RAW = "raw_enabled"
    private const val KEY_COMPOSITION = "composition_enabled"
    private const val KEY_COMPOSITION_LOCK = "composition_lock"

    private fun sp(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun loadPreferQuality(context: Context, def: Boolean = true): Boolean =
        sp(context).getBoolean(KEY_PREFER_QUALITY, def)

    fun savePreferQuality(context: Context, v: Boolean) {
        sp(context).edit().putBoolean(KEY_PREFER_QUALITY, v).apply()
    }

    fun loadFrames(context: Context, def: Int = 3): Int =
        sp(context).getInt(KEY_FRAMES, def)

    fun saveFrames(context: Context, v: Int) {
        sp(context).edit().putInt(KEY_FRAMES, v).apply()
    }

    fun loadAI(context: Context, def: Boolean = false): Boolean =
        sp(context).getBoolean(KEY_AI, def)

    fun saveAI(context: Context, v: Boolean) {
        sp(context).edit().putBoolean(KEY_AI, v).apply()
    }

    fun loadDenoise(context: Context, def: Boolean = false): Boolean =
        sp(context).getBoolean(KEY_DENOISE, def)

    fun saveDenoise(context: Context, v: Boolean) {
        sp(context).edit().putBoolean(KEY_DENOISE, v).apply()
    }

    fun loadMirror(context: Context, def: Boolean = false): Boolean =
        sp(context).getBoolean(KEY_MIRROR, def)

    fun saveMirror(context: Context, v: Boolean) {
        sp(context).edit().putBoolean(KEY_MIRROR, v).apply()
    }

    fun loadExtraRotation(context: Context, def: Int = 0): Int =
        sp(context).getInt(KEY_EXTRA_ROT, def)

    fun saveExtraRotation(context: Context, v: Int) {
        sp(context).edit().putInt(KEY_EXTRA_ROT, v).apply()
    }

    fun loadPreferredRole(context: Context): CameraRole? {
        val name = sp(context).getString(KEY_PREFERRED_ROLE, null) ?: return null
        return try { CameraRole.valueOf(name) } catch (_: Exception) { null }
    }

    fun savePreferredRole(context: Context, role: CameraRole?) {
        sp(context).edit().apply {
            if (role == null) remove(KEY_PREFERRED_ROLE)
            else putString(KEY_PREFERRED_ROLE, role.name)
        }.apply()
    }

    fun loadAutoZoom(context: Context, def: Boolean = false): Boolean =
        sp(context).getBoolean(KEY_AUTO_ZOOM, def)

    fun saveAutoZoom(context: Context, v: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_ZOOM, v).apply()
    }

    fun loadAutoColor(context: Context, def: Boolean = false): Boolean =
        sp(context).getBoolean(KEY_AUTO_COLOR, def)

    fun saveAutoColor(context: Context, v: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_COLOR, v).apply()
    }

    // ---------- RAW / 构图辅助 / 构图锁定（修复：补上持久化） ----------

    fun loadRaw(context: Context, def: Boolean = false): Boolean =
        sp(context).getBoolean(KEY_RAW, def)

    fun saveRaw(context: Context, v: Boolean) {
        sp(context).edit().putBoolean(KEY_RAW, v).apply()
    }

    fun loadComposition(context: Context, def: Boolean = false): Boolean =
        sp(context).getBoolean(KEY_COMPOSITION, def)

    fun saveComposition(context: Context, v: Boolean) {
        sp(context).edit().putBoolean(KEY_COMPOSITION, v).apply()
    }

    fun loadCompositionLock(context: Context, def: Boolean = false): Boolean =
        sp(context).getBoolean(KEY_COMPOSITION_LOCK, def)

    fun saveCompositionLock(context: Context, v: Boolean) {
        sp(context).edit().putBoolean(KEY_COMPOSITION_LOCK, v).apply()
    }

    // ---------- 【新增】时光慢门子档 / 人像虚化强度 ----------

    private const val KEY_SLOW_STYLE = "slow_shutter_style"
    private const val KEY_BOKEH = "bokeh_strength"

    /** 时光慢门子档：0=光轨、1=丝绢 */
    fun loadSlowStyle(context: Context, def: Int = 0): Int =
        sp(context).getInt(KEY_SLOW_STYLE, def)

    fun saveSlowStyle(context: Context, v: Int) {
        sp(context).edit().putInt(KEY_SLOW_STYLE, v).apply()
    }

    fun loadBokeh(context: Context, def: Int = 55): Int =
        sp(context).getInt(KEY_BOKEH, def)

    fun saveBokeh(context: Context, v: Int) {
        sp(context).edit().putInt(KEY_BOKEH, v).apply()
    }

    // ---------- 调色预设（修改：预设持久化） ----------

    private const val KEY_PRESET = "filter_preset"
    private const val KEY_GRADE = "grade_params"

    fun loadPreset(context: Context, def: Int = 0): Int =
        sp(context).getInt(KEY_PRESET, def)

    fun savePreset(context: Context, v: Int) {
        sp(context).edit().putInt(KEY_PRESET, v).apply()
    }

    /** 持久化自定义调色参数（hueShiftX/toneShiftY/saturation/contrast/temperature） */
    fun loadGrade(context: Context): ColorGradeParams? {
        val s = sp(context).getString(KEY_GRADE, null) ?: return null
        return try {
            val a = s.split(",").map { it.toFloat() }
            if (a.size < 5) null
            else ColorGradeParams(a[0], a[1], a[2], a[3], a[4])
        } catch (_: Exception) {
            null
        }
    }

    fun saveGrade(context: Context, p: ColorGradeParams) {
        sp(context).edit()
            .putString(
                KEY_GRADE,
                "${p.hueShiftX},${p.toneShiftY},${p.saturation},${p.contrast},${p.temperature}"
            )
            .apply()
    }
}
