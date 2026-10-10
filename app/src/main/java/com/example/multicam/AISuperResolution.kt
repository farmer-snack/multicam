package com.example.multicam

import android.content.Context
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * AI 4× 超分（Real-ESRGAN-General-x4v3，Qualcomm AI Hub float 导出版）。
 *
 * 模型契约（见 assets/README.txt）：
 *   输入 [1,128,128,3] NHWC float32，值域 0–1
 *   输出 [1,512,512,3] NHWC float32
 *
 * ============================ 两个已修的严重缺陷 ============================
 *
 * 【缺陷 1】严重偏色 —— 输出值域被硬编码当成 0–1
 *   原实现第 315 行无条件 `out32.convertTo(outMat, CV_8UC3, 255.0)`，
 *   也就是「假设模型输出 0–1」。但 Qualcomm AI Hub 导出的 Real-ESRGAN
 *   与原始 PyTorch 语义一致，输出就是 **0–255** 的 float。
 *   → 乘 255 后凡是 ≥1 的像素全部 saturate 到 255，暗部全部压到 0，
 *     整张图被拉成高对比的假色（惨白/惨黄/惨紫），这就是实测的「非常严重偏色」。
 *   修法：不猜，运行时探测一次（扫一遍输出的最大绝对值）并缓存。
 *        max ≤ 1.5 → 按 0–1 处理乘 255；否则按 0–255 直接钳制。
 *
 * 【缺陷 2】画质不理想 —— 分块尺寸远大于模型输入，逐块降采样 4 倍
 *   原实现 TILE_SIZE = 512，而模型输入固定 128×128，
 *   于是每个 ~544×544 的读块被 resize 到 128×128 —— **降采样 4.25 倍**，
 *   细节在进模型前就丢光了；模型再 4× 放大只是把平滑后的像素拉大，
 *   得到的必然是「糊 + 过锐化边缘」的塑料感。
 *   修法：分块尺寸改成模型真实输入尺寸（本模型 128），让每块以 ~1:1
 *        （仅 overlap 上下文被轻微压缩）送进模型，细节完整保留。
 *        为控制耗时，用块数预算 BUDGET 反推工作分辨率，
 *        保证「块内不降采样」与「总耗时可控」同时成立。
 *
 * ================================ 拼接策略 ================================
 *  1. 输出先铺一层双三次放大的底图，保证每个像素都有初值
 *  2. 按块读「读块」（= 块尺寸 + 两侧 overlap），推理得放大结果
 *  3. 只把该块的**核心区**（去掉 overlap）覆盖到输出对应位置
 *  4. 未被任何核心区覆盖的 overlap 边缘，保留 base 的双三次结果兜底
 *  这样任何一块的写入范围都严格落在输出边界内，不会出现错位条纹。
 */
object AISuperResolution {

    private const val TAG = "AISR"
    private const val MODEL = "sr_model.tflite"

    /**
     * 分块推理的总块数预算。
     * 块数 = ceil(W/128) × ceil(H/128)，1920×1080 直接切会有 126 块（太慢）。
     * 预算 64 块 → 工作分辨率约 1365×768 → 输出 5460×3072。
     * 注意这是「按真实细节量算输出」，不是「把 512 块压成 128 喂模型」，
     * 后者块数少但每块细节被降采样 4 倍，画质反而更差。
     */
    private const val TILE_BUDGET = 64

    /** 边缘重叠像素（输入侧），给模型提供上下文，避免切块接缝 */
    private const val TILE_OVERLAP = 8

    private var interpreter: Interpreter? = null

    /** 模型固定输入尺寸，取自 input tensor */
    @Volatile private var modelInW = 0
    @Volatile private var modelInH = 0

    /** 模型真实倍率（由输出尺寸 / 输入尺寸推出），不信任调用方传入的 scale */
    @Volatile private var modelScale = 4

    /**
     * 输出值域：0 = 未探测，1 = [0,1]，2 = [0,255]。
     * 跨线程只写一次、之后只读，普通字段的可见性由 TFLite 推理的同步点保证；
     * 最坏情况是重复探测一次，结果一致，无副作用。
     */
    @Volatile private var outputRange = 0

    fun init(context: Context): Boolean {
        if (interpreter != null) return true
        return try {
            val model = loadModel(context, MODEL)
            val opts = Interpreter.Options().apply {
                setNumThreads(4)
                try { addDelegate(NnApiDelegate()) } catch (_: Throwable) {}
            }
            val it = Interpreter(model, opts)
            val inp = it.getInputTensor(0)
            val outT = it.getOutputTensor(0)
            // 注意：变量名不能用 `is`（Kotlin 关键字）
            val inShape = inp.shape()
            val outShapeArr = outT.shape()
            AppLogger.d(TAG, "input  shape=${inShape.contentToString()} dtype=${inp.dataType()}")
            AppLogger.d(TAG, "output shape=${outShapeArr.contentToString()} dtype=${outT.dataType()}")

            // 入参布局：[1,H,W,3] → NHWC；[1,3,H,W] → NCHW
            val inNCHW = inShape.size == 4 && inShape[1] == 3
            val inW = if (inNCHW) inShape[3] else inShape[2]
            val inH = if (inNCHW) inShape[2] else inShape[1]
            // 出参布局必须独立判定，不能沿用入参
            val outNCHW = outShapeArr.size == 4 && outShapeArr[1] == 3 && outShapeArr[3] != 3
            val outW = when {
                outShapeArr.size < 4 -> inW
                outNCHW -> outShapeArr[3]
                else -> outShapeArr[2]
            }
            val outH = when {
                outShapeArr.size < 4 -> inH
                outNCHW -> outShapeArr[2]
                else -> outShapeArr[1]
            }

            if (inW <= 0 || inH <= 0 || outW <= 0 || outH <= 0) {
                AppLogger.e(TAG, "模型 shape 异常，无法确定分块尺寸")
                runCatching { it.close() }
                return false
            }
            modelInW = inW
            modelInH = inH
            modelScale = max(1, outW / inW)
            AppLogger.d(TAG, "分块尺寸=${inW}x$inH 倍率=${modelScale}x 输出=${outW}x$outH")
            interpreter = it
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "模型加载失败: ${e.message}", e)
            false
        }
    }

    fun isReady(): Boolean = interpreter != null

    /**
     * 分块 N× 超分（N 由模型决定）。
     *
     * @param onProgress 进度回调 0..100
     *
     * 注意：模型未就绪时返回 `input.clone()`。调用方（`CameraController.aiUpscale`）
     * 拿到返回值后会 `upscaled.release()`，如果返回入参别名会把调用方的 `mat`
     * 一并释放 → native use-after-free。
     */
    fun upscale(input: Mat, scale: Int = 4, onProgress: ((Int) -> Unit)? = null): Mat {
        if (interpreter == null) return input.clone()
        if (input.empty()) return input.clone()
        if (modelInW <= 0 || modelInH <= 0) return input.clone()

        val s = modelScale
        if (scale != s) {
            AppLogger.w(TAG, "调用方传入 scale=$scale，按模型实际倍率 ${s}x 处理")
        }

        // ---- 按块数预算反推工作分辨率：保证「每块 1:1 送模型」且总块数可控 ----
        var src = input
        val w0 = input.cols()
        val h0 = input.rows()
        val tilePixels = modelInW.toLong() * modelInH.toLong()
        val totalPixels = w0.toLong() * h0.toLong()
        if (tilePixels > 0 && totalPixels / tilePixels > TILE_BUDGET) {
            val k = sqrt(TILE_BUDGET.toDouble() * tilePixels.toDouble() / totalPixels.toDouble())
            val nw = max(modelInW, (w0 * k).roundToInt())
            val nh = max(modelInH, (h0 * k).roundToInt())
            val tmp = Mat()
            Imgproc.resize(input, tmp, Size(nw.toDouble(), nh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            src = tmp
            AppLogger.d(TAG, "工作分辨率 ${w0}x$h0 → ${nw}x$nh（保块内细节，输出 ${nw * s}x${nh * s}）")
        }

        val h = src.rows()
        val w = src.cols()
        val outH = h * s
        val outW = w * s

        // 输出先铺一层双三次放大的底图，保证每个像素都有合理初值
        val output = Mat()
        Imgproc.resize(src, output, Size(outW.toDouble(), outH.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)

        val tw = modelInW
        val th = modelInH
        val tilesX = (w + tw - 1) / tw
        val tilesY = (h + th - 1) / th
        val totalTiles = (tilesX * tilesY).coerceAtLeast(1)
        var doneTiles = 0

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                // ---- 核心区（本块负责写入输出的区域），严格裁剪到图内 ----
                val coreX0 = tx * tw
                val coreY0 = ty * th
                val coreX1 = (coreX0 + tw).coerceAtMost(w)
                val coreY1 = (coreY0 + th).coerceAtMost(h)
                val coreW = coreX1 - coreX0
                val coreH = coreY1 - coreY0
                if (coreW <= 0 || coreH <= 0) {
                    doneTiles++
                    continue
                }

                // ---- 读块（核心区外扩 overlap），用于给模型提供上下文 ----
                val rx0 = (coreX0 - TILE_OVERLAP).coerceAtLeast(0)
                val ry0 = (coreY0 - TILE_OVERLAP).coerceAtLeast(0)
                val rx1 = (coreX1 + TILE_OVERLAP).coerceAtMost(w)
                val ry1 = (coreY1 + TILE_OVERLAP).coerceAtMost(h)

                val tile = Mat(src, Rect(rx0, ry0, rx1 - rx0, ry1 - ry0))

                val upscaled = runTile(tile, s)
                tile.release()

                // ---- 写回：核心区在读块内的偏移 × 倍率 ----
                val offX = (coreX0 - rx0) * s
                val offY = (coreY0 - ry0) * s
                val cw = coreW * s
                val ch = coreH * s

                // 防御：推理结果尺寸异常时跳过该块，保留 base 兜底
                if (upscaled.cols() < offX + cw || upscaled.rows() < offY + ch) {
                    AppLogger.w(
                        TAG,
                        "tile(${tx},${ty}) 结果尺寸异常 ${upscaled.cols()}x${upscaled.rows()}，" +
                            "需要 ${offX + cw}x${offY + ch}，跳过"
                    )
                    upscaled.release()
                    doneTiles++
                    onProgress?.invoke((doneTiles * 100) / totalTiles)
                    continue
                }

                val cut = Mat(upscaled, Rect(offX, offY, cw, ch))
                val dst = Mat(output, Rect(coreX0 * s, coreY0 * s, cw, ch))
                cut.copyTo(dst)

                cut.release()
                dst.release()
                upscaled.release()

                doneTiles++
                onProgress?.invoke((doneTiles * 100) / totalTiles)
            }
        }

        if (src !== input) src.release()
        return output
    }

    /**
     * 单块推理。
     *
     * 读块（可能是 144×144 等）→ 缩放到模型输入尺寸（128×128）→ 推理
     * → 缩回到 读块 × 倍率。
     */
    private fun runTile(tile: Mat, scale: Int): Mat {
        val interp = interpreter ?: return bicubicFallback(tile, scale)
        val h = tile.rows()
        val w = tile.cols()
        if (h <= 0 || w <= 0) return bicubicFallback(tile, scale)

        val inp = interp.getInputTensor(0)
        val dtype = inp.dataType()
        val modelH = modelInH
        val modelW = modelInW

        // 1. BGR → RGB，缩放到模型输入尺寸
        val rgbFull = Mat()
        Imgproc.cvtColor(tile, rgbFull, Imgproc.COLOR_BGR2RGB)

        val rgb = Mat()
        Imgproc.resize(rgbFull, rgb, Size(modelW.toDouble(), modelH.toDouble()))
        rgbFull.release()

        val bufSize = modelW * modelH * 3
        val inBuf: ByteBuffer = when (dtype) {
            DataType.FLOAT32 -> {
                val f32 = Mat()
                // 模型输入约定为 0–1
                rgb.convertTo(f32, CvType.CV_32FC3, 1.0 / 255.0)
                val arr = FloatArray(bufSize)
                f32.get(0, 0, arr)
                f32.release()
                ByteBuffer.allocateDirect(bufSize * 4)
                    .order(ByteOrder.nativeOrder())
                    .also { bb ->
                        bb.asFloatBuffer().put(arr)
                        bb.rewind()
                    }
            }
            DataType.UINT8 -> {
                val arr = ByteArray(bufSize)
                rgb.get(0, 0, arr)
                ByteBuffer.allocateDirect(bufSize).also { bb ->
                    bb.put(arr)
                    bb.rewind()
                }
            }
            else -> {
                AppLogger.w(TAG, "不支持的输入 dtype=$dtype")
                rgb.release()
                return bicubicFallback(tile, scale)
            }
        }
        // rgb 是所有分支共用的中间 Mat，必须在填完输入缓冲后统一释放，
        // 否则每块泄漏一张 modelW×modelH×3 的 Mat。
        rgb.release()

        val outTensor = interp.getOutputTensor(0)
        val outShape = outTensor.shape()
        val outDtype = outTensor.dataType()
        // 输出布局独立判定（不能沿用输入的 NCHW 标志）
        val outNCHW = outShape.size == 4 && outShape[1] == 3 && outShape[3] != 3
        val outH = when {
            outShape.size < 4 -> modelH
            outNCHW -> outShape[2]
            else -> outShape[1]
        }
        val outW = when {
            outShape.size < 4 -> modelW
            outNCHW -> outShape[3]
            else -> outShape[2]
        }
        if (outH <= 0 || outW <= 0) {
            AppLogger.w(TAG, "模型输出尺寸异常 shape=${outShape.contentToString()}")
            return bicubicFallback(tile, scale)
        }
        val outSize = outH * outW * 3

        val outBuf: ByteBuffer = when (outDtype) {
            DataType.FLOAT32 -> ByteBuffer.allocateDirect(outSize * 4)
                .order(ByteOrder.nativeOrder())
            DataType.UINT8 -> ByteBuffer.allocateDirect(outSize)
            else -> {
                AppLogger.w(TAG, "不支持的输出 dtype=$outDtype")
                return bicubicFallback(tile, scale)
            }
        }

        try {
            interp.run(inBuf, outBuf)
        } catch (e: Exception) {
            AppLogger.e(TAG, "推理失败: ${e.message}", e)
            return bicubicFallback(tile, scale)
        }

        val outMat = Mat(outH, outW, CvType.CV_8UC3)
        when (outDtype) {
            DataType.FLOAT32 -> {
                outBuf.rewind()
                val f = FloatArray(outSize)
                outBuf.asFloatBuffer().get(f)
                // 输出 NCHW 时把 [R 平面][G 平面][B 平面] 反交织为 HWC，
                // 否则通道会被当作交错像素读 → 画面错乱。
                val hwc = FloatArray(outSize)
                if (outNCHW) {
                    val plane = outH * outW
                    for (idx in 0 until plane) {
                        val dst = idx * 3
                        hwc[dst] = f[idx]
                        hwc[dst + 1] = f[plane + idx]
                        hwc[dst + 2] = f[2 * plane + idx]
                    }
                } else {
                    System.arraycopy(f, 0, hwc, 0, outSize)
                }
                // 关键：先探测输出值域再决定缩放系数。
                // 若模型输出本来就是 0–255，×255 会把画面拉成假色。
                val mul = outputScaleFor(hwc)
                val out32 = Mat(outH, outW, CvType.CV_32FC3)
                out32.put(0, 0, hwc)
                out32.convertTo(outMat, CvType.CV_8UC3, mul)
                out32.release()
            }
            DataType.UINT8 -> {
                outBuf.rewind()
                val b = ByteArray(outSize)
                outBuf.get(b)
                if (outNCHW) {
                    val plane = outH * outW
                    val hwc = ByteArray(outSize)
                    for (idx in 0 until plane) {
                        val dst = idx * 3
                        hwc[dst] = b[idx]
                        hwc[dst + 1] = b[plane + idx]
                        hwc[dst + 2] = b[2 * plane + idx]
                    }
                    outMat.put(0, 0, hwc)
                } else {
                    outMat.put(0, 0, b)
                }
                // uint8 输出本身就是 0–255，不需要缩放
                outputRange = 2
            }
            else -> {
                AppLogger.w(TAG, "不支持的输出 dtype=$outDtype")
                outMat.release()
                return bicubicFallback(tile, scale)
            }
        }

        val bgr = Mat()
        Imgproc.cvtColor(outMat, bgr, Imgproc.COLOR_RGB2BGR)
        outMat.release()

        // 缩回到 读块 × 倍率 的目标尺寸
        val out = Mat()
        Imgproc.resize(
            bgr, out,
            Size((w * scale).toDouble(), (h * scale).toDouble()),
            0.0, 0.0, Imgproc.INTER_LINEAR
        )
        bgr.release()
        return out
    }

    /**
     * 探测并缓存模型输出值域，返回 convertTo 应使用的乘数。
     *
     * max ≤ 1.5 → 模型输出是 0–1，需 ×255
     * 否则      → 模型输出已是 0–255（如 Qualcomm AI Hub 的 Real-ESRGAN），直接钳制（乘 1）
     *
     * 只探测一次，之后固定复用，避免每块重扫。
     */
    private fun outputScaleFor(f: FloatArray): Double {
        outputRange.let { if (it != 0) return if (it == 1) 255.0 else 1.0 }
        var maxAbs = 0f
        // 抽样扫描即可判定量级，不必遍历全部像素
        val step = if (f.size > 8192) f.size / 8192 else 1
        var i = 0
        while (i < f.size) {
            val v = abs(f[i])
            if (v > maxAbs) maxAbs = v
            i += step
        }
        outputRange = if (maxAbs <= 1.5f) 1 else 2
        val desc = if (outputRange == 1) "0-1（×255）" else "0-255（直接钳制）"
        AppLogger.d(TAG, "探测输出值域 max=$maxAbs → $desc")
        return if (outputRange == 1) 255.0 else 1.0
    }

    /** 推理不可用时的兜底：直接双三次放大，保证尺寸正确、画面完整 */
    private fun bicubicFallback(tile: Mat, scale: Int): Mat {
        val out = Mat()
        Imgproc.resize(
            tile, out,
            Size((tile.cols() * scale).toDouble(), (tile.rows() * scale).toDouble()),
            0.0, 0.0, Imgproc.INTER_CUBIC
        )
        return out
    }

    fun close() {
        runCatching { interpreter?.close() }
        interpreter = null
        outputRange = 0
        modelInW = 0
        modelInH = 0
        modelScale = 4
    }

    private fun loadModel(context: Context, name: String): MappedByteBuffer {
        // 【修复】原来 openFd 与 FileInputStream 都没关，fd 一直被 APK 的映射窗口引用；
        // map() 抛异常时更是彻底泄漏。用 use{} 保证关闭。
        // 注意：fc.map 返回的 MappedByteBuffer 在 channel 关闭后仍然有效，
        // 这是 FileChannel.map 的既定语义，不是 use-block 的副作用。
        val afd = context.assets.openFd(name)
        FileInputStream(afd.fileDescriptor).use { fis ->
            return fis.channel.map(
                FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength
            )
        }
    }
}
