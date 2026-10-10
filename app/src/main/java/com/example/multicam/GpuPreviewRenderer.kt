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

        program = buildProgram(vs, fs)
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
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glUniform1i(uTexLoc, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
        GLES20.glUniform1i(uLutLoc, 1)
        GLES20.glUniform1f(uIntensityLoc, lutIntensity)

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
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            512, 512, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
            ByteBuffer.wrap(lut))
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

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, v)
            GLES20.glAttachShader(it, f)
            GLES20.glLinkProgram(it)
        }
    }

    private fun compile(type: Int, src: String): Int {
        return GLES20.glCreateShader(type).also { s ->
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val status = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                AppLogger.e("GpuPreview", GLES20.glGetShaderInfoLog(s))
            }
        }
    }
}
