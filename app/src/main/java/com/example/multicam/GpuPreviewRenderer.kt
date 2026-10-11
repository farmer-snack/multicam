package com.example.multicam

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GL 预览渲染器：
 * - 相机流（SurfaceTexture / OES 外部纹理）绘制到全屏四边形
 * - 叠加 512×512 3D LUT（8×8 网格）实现调色实时预览
 * - 支持旋转（SENSOR_ORIENTATION + 手动方向）与水平镜像，等比缩放裁切填满
 */
class GpuPreviewRenderer : GLSurfaceView.Renderer {

    /** SurfaceTexture 就绪回调（GL 线程） */
    @Volatile var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null

    @Volatile private var lutIntensity = 0f
    @Volatile private var rotationDeg = 0
    @Volatile private var mirrorX = false
    @Volatile private var previewW = 1920f
    @Volatile private var previewH = 1080f
    private var viewW = 1f
    private var viewH = 1f

    private var program = 0
    private var oesTexId = 0
    private var lutTexId = 0
    private var surfaceTexture: SurfaceTexture? = null

    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTexLoc = 0
    private var uLutLoc = 0
    private var uIntensityLoc = 0

    private val quad = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f
    )
    private val positionBuf: FloatBuffer = ByteBuffer
        .allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().put(quad).apply { position(0) }
    private val uvBuf: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun setPreviewSize(w: Int, h: Int) {
        if (w > 0 && h > 0) { previewW = w.toFloat(); previewH = h.toFloat() }
    }

    fun setTransform(rotation: Int, mirror: Boolean) {
        rotationDeg = ((rotation % 360) + 360) % 360
        mirrorX = mirror
    }

    fun setLutIntensity(v: Float) { lutIntensity = v.coerceIn(0f, 1f) }

    fun getSurfaceTexture(): SurfaceTexture? = surfaceTexture

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val vs = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord.xy;
            }
        """.trimIndent()

        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform sampler2D uLut;
            uniform float uIntensity;
            void main() {
                vec4 c = texture2D(uTexture, vTexCoord);
                // 512×512 2D 纹理承载 8×8×64 的 3D LUT（蓝色切片选块，RG 块内索引）
                vec3 lutCoord = clamp(c.rgb, 0.0, 1.0) * 63.0;
                float b0 = floor(lutCoord.b);
                float b1 = min(b0 + 1.0, 63.0);
                float fb = lutCoord.b - b0;
                vec2 g0 = vec2(mod(b0, 8.0), floor(b0 / 8.0));
                vec2 g1 = vec2(mod(b1, 8.0), floor(b1 / 8.0));
                vec2 uv0 = (vec2(lutCoord.r, lutCoord.g) + g0 * 64.0 + 0.5) / 512.0;
                vec2 uv1 = (vec2(lutCoord.r, lutCoord.g) + g1 * 64.0 + 0.5) / 512.0;
                vec3 c0 = texture2D(uLut, uv0).rgb;
                vec3 c1 = texture2D(uLut, uv1).rgb;
                vec3 lut = mix(c0, c1, fb);
                gl_FragColor = vec4(mix(c.rgb, lut, uIntensity), 1.0);
            }
        """.trimIndent()

        // 【关键修复】EGL 上下文重建时释放上一轮的 GL 对象与 SurfaceTexture。
        // GLSurfaceView 默认 preserveEGLContextOnPause=false，每次切后台
        // 上下文都会销毁、回前台重跑 onSurfaceCreated；而 SurfaceTexture
        // 持有的 native BufferQueue **不属于 EGL 上下文**，不会随上下文销毁
        // 自动回收 —— 不 release() 就是每轮泄漏一份（10~30 次前后台切换后
        // 预览变黑 / 被 lowmemory 杀掉）。
        // 注意：上下文已丢失时调用 glDelete* 是无害的 no-op。
        releaseGlResources()

        program = buildProgram(vs, fs)
        if (program == 0) {
            // 【关键修复】原来不检查链接状态：失败时 program=0 或未链接成功，
            // glGetAttribLocation 全返回 -1 → glUseProgram(0) 与
            // glEnableVertexAttribArray(-1) 全是 GL_INVALID_VALUE，
            // glDrawArrays 什么也不画 → **纯黑预览且不崩溃不报错**，
            // 用户侧表现为"相机坏了"。
            shaderError = "预览着色器编译失败，预览不可用"
            // 局部变量：shaderError 是 @Volatile 可变属性，不能直接传给非空形参
            AppLogger.e("GpuPreview", "预览着色器编译失败，预览不可用")
            return
        }
        shaderError = null
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexLoc = GLES20.glGetUniformLocation(program, "uTexture")
        uLutLoc = GLES20.glGetUniformLocation(program, "uLut")
        uIntensityLoc = GLES20.glGetUniformLocation(program, "uIntensity")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTexId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(oesTexId)

        // 默认 LUT：全白，配合 intensity=0 时预览不受影响
        val lut = IntArray(1)
        GLES20.glGenTextures(1, lut, 0)
        lutTexId = lut[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
        val defaultLut = ByteArray(512 * 512 * 4) { 255.toByte() }
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            512, 512, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
            ByteBuffer.wrap(defaultLut))
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        onSurfaceTextureReady?.invoke(surfaceTexture!!)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width.toFloat().coerceAtLeast(1f)
        viewH = height.toFloat().coerceAtLeast(1f)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        surfaceTexture?.updateTexImage()
        // 【修复】program / oesTexId 为 0 时（shader 编译失败）直接画醒目底色。
        // 原来会继续 glUseProgram(0) + glEnableVertexAttribArray(-1)，
        // 全是 GL_INVALID_VALUE 且不报错 → 屏幕纯黑，用户以为相机坏了。
        if (program == 0 || oesTexId == 0 || surfaceTexture == null) {
            GLES20.glClearColor(0.35f, 0.08f, 0.08f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        // 【修复】uniform location 可能是 -1（驱动把未使用的 uniform 优化掉了），
        // 此时必须跳过提交，否则 GL_INVALID_OPERATION
        if (uTexLoc >= 0) GLES20.glUniform1i(uTexLoc, 0)
        if (uLutLoc >= 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
            GLES20.glUniform1i(uLutLoc, 1)
        }
        if (uIntensityLoc >= 0) GLES20.glUniform1f(uIntensityLoc, lutIntensity)

        computeUvs(uvBuf)

        positionBuf.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT,
            false, 8, positionBuf)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        uvBuf.position(0)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT,
            false, 8, uvBuf)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** 上传新的 LUT 纹理 */
    fun updateLut(lut: ByteArray) {
        if (lutTexId == 0 || lut.size < 512 * 512 * 4) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
        // 【关键修复】纹理已在 onSurfaceCreated 用 glTexImage2D 定义过（512×512 RGBA），
        // 尺寸与格式都没变，用 glTexSubImage2D 只更新像素即可。
        // 原来每次都 glTexImage2D 重新定义整张纹理 —— 部分驱动会走
        // "孤儿化 + 重新分配"路径，额外触发驱动内存操作。
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, 512, 512,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(lut)
        )
    }

    /**
     * 计算四个顶点的 UV：
     * - 先按缓冲/视图宽高比裁切（等比填满）
     * - 再绕中心顺时针旋转 rotationDeg（等价原 TextureView 的 postRotate）
     * - 最后水平镜像（作用于源空间，与原 postScale(-1,1)+postRotate 一致）
     */
    private fun computeUvs(buf: FloatBuffer) {
        val rot = rotationDeg
        val rotated = rot % 180 != 0
        val srcW = if (rotated) previewH else previewW
        val srcH = if (rotated) previewW else previewH

        val scale = maxOf(viewW / srcW, viewH / srcH)
        val cropW = viewW / scale
        val cropH = viewH / scale
        val fracU = cropW / srcW
        val fracV = cropH / srcH
        val u0 = (1f - fracU) / 2f
        val u1 = 1f - u0
        val v0 = (1f - fracV) / 2f
        val v1 = 1f - v0

        val theta = Math.toRadians(rot.toDouble())
        val cosT = Math.cos(theta).toFloat()
        val sinT = Math.sin(theta).toFloat()

        // 顶点顺序：BL, BR, TL, TR（NDC），对应输出坐标 (u, v)（v 向下）
        val corners = arrayOf(
            floatArrayOf(u0, v1),
            floatArrayOf(u1, v1),
            floatArrayOf(u0, v0),
            floatArrayOf(u1, v0)
        )
        val out = FloatArray(8)
        for (i in 0..3) {
            val au = corners[i][0]
            val av = corners[i][1]
            // 顺时针旋转 θ（源空间）：su/sv 为源图 android 坐标（v=0 为顶行）
            var su = 0.5f + (au - 0.5f) * cosT + (av - 0.5f) * sinT
            val sv = 0.5f - (au - 0.5f) * sinT + (av - 0.5f) * cosT
            if (mirrorX) su = 1f - su
            out[i * 2] = su
            out[i * 2 + 1] = sv
        }
        buf.clear()
        buf.put(out)
        buf.position(0)
    }

    /**
     * 【关键修复】原来完全不检查编译/链接状态：
     * compile 失败只打一行日志就返回那个无效的 shader id；
     * buildProgram 也不查 GL_LINK_STATUS 就把 program 交出去。
     * 于是 shader 编译失败（片元着色器首行的
     * `#extension GL_OES_EGL_image_external : require` 在部分 GLES2 上下文不可用）
     * 时 → attrib location 全为 -1 → glDrawArrays 什么都不画 →
     * **纯黑预览、不崩溃、不降级、UI 无任何提示**，用户以为相机坏了。
     * 现在逐级检查 + 删除无效对象 + 返回 0 让上层走错误路径。
     */
    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        if (v == 0) return 0
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        if (f == 0) {
            GLES20.glDeleteShader(v)
            return 0
        }
        val p = GLES20.glCreateProgram()
        if (p == 0) {
            GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
            return 0
        }
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            AppLogger.e("GpuPreview", "link failed: ${GLES20.glGetProgramInfoLog(p)}")
            GLES20.glDeleteProgram(p)
            GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
            return 0
        }
        // program 已接管 shader，链接后可立即删除
        GLES20.glDetachShader(p, v); GLES20.glDeleteShader(v)
        GLES20.glDetachShader(p, f); GLES20.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        if (s == 0) {
            AppLogger.e("GpuPreview", "glCreateShader 返回 0（type=$type）")
            return 0
        }
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            AppLogger.e("GpuPreview", "compile failed type=$type: ${GLES20.glGetShaderInfoLog(s)}")
            // 【修复】原来失败后不删除，每次上下文重建都泄漏一个 shader
            GLES20.glDeleteShader(s)
            return 0
        }
        return s
    }

    /**
     * 释放 GL 资源与 SurfaceTexture。
     * 供 onSurfaceCreated 开头与 Activity 的 onPause/onDestroy 调用。
     */
    fun releaseGlResources() {
        // SurfaceTexture 的 native BufferQueue 不随 EGL 上下文销毁，必须显式释放
        surfaceTexture?.let { st ->
            runCatching { st.setOnFrameAvailableListener(null) }
            runCatching { st.release() }
        }
        surfaceTexture = null
        if (program != 0) { GLES20.glDeleteProgram(program); program = 0 }
        if (oesTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTexId), 0); oesTexId = 0
        }
        if (lutTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(lutTexId), 0); lutTexId = 0
        }
        aPositionLoc = -1; aTexCoordLoc = -1
        uTexLoc = -1; uLutLoc = -1; uIntensityLoc = -1
        onSurfaceTextureReady = null
    }

    /** shader 编译失败时的错误描述（供 UI 提示），正常为 null */
    @Volatile var shaderError: String? = null
        private set
}
