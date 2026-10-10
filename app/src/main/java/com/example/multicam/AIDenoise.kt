package com.example.multicam

import android.content.Context
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * AI 去噪（NafNet 类模型）。
 *
 * 关键设计：**按大 tile（384）切分并缩放到模型输入尺寸**，而不是按模型输入尺寸
 * 切原图。早期实现用模型尺寸（如 256）直接切 4000×3000 的图会产生 200+ 块，
 * 每块一次推理，实际表现就是"永远跑不完"。
 *
 * 现在：整图先限制到 MAX_INPUT（1600），再按 TILE 切块、每块缩放到模型输入、
 * 推理后缩放回 tile 尺寸，写回非重叠区。逐块回调进度，单块超时直接跳过。
 */
object AIDenoise {

    private const val TAG = "AIDenoise"
    private const val MODEL = "denoise_model.tflite"
    private const val TILE = 384
    private const val OVERLAP = 12
    private const val MAX_INPUT = 1600
    private const val TILE_TIMEOUT_MS = 6000L

    private var interpreter: Interpreter? = null
    private var useNnApi = false

    fun init(context: Context): Boolean {
        if (interpreter != null) return true
        return try {
            val model = loadModel(context, MODEL)
            var created: Interpreter?
            try {
                created = Interpreter(
                    model,
                    Interpreter.Options().apply {
                        setNumThreads(4)
                        addDelegate(NnApiDelegate())
                    }
                )
                useNnApi = true
            } catch (e: Throwable) {
                created = null
                AppLogger.w(TAG, "NNAPI 不可用，回退 CPU: ${e.message}")
            }
            if (created == null) {
                created = Interpreter(model, Interpreter.Options().apply { setNumThreads(4) })
                useNnApi = false
            }
            interpreter = created
            val inp = created.getInputTensor(0)
            val out = created.getOutputTensor(0)
            AppLogger.d(
                TAG,
                "loaded nnapi=$useNnApi in=${inp.shape().contentToString()} ${inp.dataType()} " +
                    "out=${out.shape().contentToString()} ${out.dataType()}"
            )
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "去噪模型加载失败: ${e.message}", e)
            false
        }
    }

    fun isReady(): Boolean = interpreter != null

    /**
     * @param input BGR 8UC3
     * @param onProgress 0..100 进度回调
     *
     * 修复（2026-10）：
     *  1. 模型未就绪时返回 `input.clone()` 而非 `input`。调用方
     *     （`CameraController.aiDenoise`）拿到返回值后会 `denoised.release()`，
     *     返回入参别名会把调用方自己的 `mat` 释放掉 → use-after-free。
     *  2. output 用 0 初始化（`Mat.zeros`）。原 `Mat(h, w, CV_8UC3)` 未初始化，
     *     所有被 `tw < 4`/`writeW <= 0` 跳过的块会留下未定义的脏像素（花屏噪点）。
     */
    fun denoise(input: Mat, onProgress: ((Int) -> Unit)? = null): Mat {
        val interp = interpreter ?: return input.clone()
        if (input.empty()) return input.clone()

        // 1. 过大先降采样
        var src = input
        var scaledDown = false
        val maxDim = maxOf(input.cols(), input.rows())
        if (maxDim > MAX_INPUT) {
            val k = MAX_INPUT.toDouble() / maxDim
            val tmp = Mat()
            Imgproc.resize(
                input, tmp,
                org.opencv.core.Size(input.cols() * k, input.rows() * k)
            )
            src = tmp
            scaledDown = true
        }

        val h = src.rows()
        val w = src.cols()
        val output = Mat.zeros(h, w, CvType.CV_8UC3)
        val step = TILE - OVERLAP
        // 修复：w <= OVERLAP 时 ((w - OVERLAP) + step - 1) / step 会算出 0 列 → 整图不处理。
        // 用 coerceAtLeast(1) 保证至少有一块。
        val cols = (((w - OVERLAP) + step - 1) / step).coerceAtLeast(1)
        val rows = (((h - OVERLAP) + step - 1) / step).coerceAtLeast(1)
        val total = (cols * rows).coerceAtLeast(1)
        var done = 0

        onProgress?.invoke(0)

        for (ty in 0 until rows) {
            for (tx in 0 until cols) {
                var x0 = tx * step
                var y0 = ty * step
                var x1 = (x0 + TILE).coerceAtMost(w)
                var y1 = (y0 + TILE).coerceAtMost(h)
                var tw = x1 - x0
                var th = y1 - y0
                // 修复：末行/末列不足 4px 时原来直接 continue，留下 output 的黑色未处理边
                //（表现为图片右/下沿一条黑边）。改为把窗口往左上回退，凑满 4px。
                if (tw < 4 || th < 4) {
                    if (tw < 4) {
                        x0 = (w - TILE).coerceAtLeast(0)
                        x1 = w
                        tw = x1 - x0
                    }
                    if (th < 4) {
                        y0 = (h - TILE).coerceAtLeast(0)
                        y1 = h
                        th = y1 - y0
                    }
                    // 整图本身就小于 4px：无从推理，保留原像素而不是留黑
                    if (tw < 4 || th < 4) {
                        val copySrc = Mat(src, Rect(0, 0, w, h))
                        val copyDst = Mat(output, Rect(0, 0, w, h))
                        copySrc.copyTo(copyDst)
                        copySrc.release(); copyDst.release()
                        done++
                        onProgress?.invoke((done * 100) / total)
                        continue
                    }
                }

                val tile = Mat(src, Rect(x0, y0, tw, th))
                val den = runTileSafe(interp, tile) ?: tile.clone()

                // 回退窗口后本身就是紧贴边界取全宽/全高，此时不能再用 OVERLAP 内缩，
                // 否则右侧 / 下侧仍会缺一条。仅在"本方向确实还有下一块"时才内缩。
                val writeStartX = if (tx == 0 || x0 + tw >= w) 0 else OVERLAP / 2
                val writeStartY = if (ty == 0 || y0 + th >= h) 0 else OVERLAP / 2
                val writeW = tw - writeStartX
                val writeH = th - writeStartY
                if (writeW <= 0 || writeH <= 0) {
                    tile.release(); den.release(); done++; continue
                }

                val cut = Mat(den, Rect(writeStartX, writeStartY, writeW, writeH))
                val dst = Mat(output, Rect(x0 + writeStartX, y0 + writeStartY, writeW, writeH))
                cut.copyTo(dst)
                cut.release(); dst.release(); tile.release(); den.release()

                done++
                onProgress?.invoke((done * 100) / total)
            }
        }

        // 2. 降采样过则缩回原尺寸
        if (scaledDown) {
            val resized = Mat()
            Imgproc.resize(
                output, resized,
                org.opencv.core.Size(input.cols().toDouble(), input.rows().toDouble())
            )
            output.release()
            src.release()
            onProgress?.invoke(100)
            return resized
        }

        onProgress?.invoke(100)
        return output
    }

    /** 带超时保护的整块推理；超时或异常返回 null，由调用方回退原图 */
    private fun runTileSafe(interp: Interpreter, tile: Mat): Mat? {
        val start = System.currentTimeMillis()
        return try {
            val r = runTile(interp, tile)
            val cost = System.currentTimeMillis() - start
            if (cost > TILE_TIMEOUT_MS) {
                AppLogger.w(TAG, "tile 推理超时 ${cost}ms，继续下一块")
            }
            r
        } catch (e: Exception) {
            AppLogger.w(TAG, "tile 推理失败: ${e.message}")
            null
        }
    }

    /** 单块推理：把 tile 缩放到模型输入尺寸，推理后缩放回 tile 尺寸 */
    private fun runTile(interp: Interpreter, tile: Mat): Mat {
        val inTensor = interp.getInputTensor(0)
        val outTensor = interp.getOutputTensor(0)
        val inDtype = inTensor.dataType()
        val outDtype = outTensor.dataType()
        val inShape = inTensor.shape()
        if (inShape.size < 4) return tile.clone()

        val isNCHW = inShape[1] == 3
        val modelH = if (isNCHW) inShape[2] else inShape[1]
        val modelW = if (isNCHW) inShape[3] else inShape[2]
        if (modelH <= 0 || modelW <= 0) return tile.clone()

        // 输出布局未必与输入一致：NafNet-SIDD 的输入与输出同为 NCHW [1,3,H,W]，
        // 但保守起见按各自 shape 独立判定，避免"输入 NCHW / 输出 NHWC"的模型读错。
        val outShape = outTensor.shape()
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
        if (outH <= 0 || outW <= 0) return tile.clone()

        // tile → 模型输入尺寸（保持 tile 原始尺寸用于回缩）
        val origW = tile.cols()
        val origH = tile.rows()
        val resized = Mat()
        val rgb = Mat()
        var rgbF: Mat? = null
        var outMat: Mat? = null
        var bgr: Mat? = null
        try {
            Imgproc.resize(
                tile, resized,
                org.opencv.core.Size(modelW.toDouble(), modelH.toDouble())
            )
            Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB)
            resized.release()

            val inBuf: ByteBuffer = when (inDtype) {
                DataType.FLOAT32 -> {
                    rgbF = Mat()
                    rgb.convertTo(rgbF, CvType.CV_32FC3, 1.0 / 255.0)
                    val inArr = FloatArray(3 * modelH * modelW)
                    if (isNCHW) {
                        val ch = Mat()
                        val tmp = FloatArray(modelH * modelW)
                        for (c in 0 until 3) {
                            org.opencv.core.Core.extractChannel(rgbF, ch, c)
                            ch.get(0, 0, tmp)
                            System.arraycopy(tmp, 0, inArr, c * modelH * modelW, modelH * modelW)
                        }
                        ch.release()
                    } else {
                        rgbF.get(0, 0, inArr)
                    }
                    ByteBuffer.allocateDirect(inArr.size * 4)
                        .order(ByteOrder.nativeOrder())
                        .apply { asFloatBuffer().put(inArr); rewind() }
                }
                DataType.UINT8, DataType.INT8 -> {
                    val arr = ByteArray(3 * modelH * modelW)
                    rgb.get(0, 0, arr)
                    ByteBuffer.allocateDirect(arr.size).apply { put(arr); rewind() }
                }
                else -> {
                    AppLogger.w(TAG, "不支持的输入 dtype=$inDtype")
                    throw IllegalStateException("unsupported input dtype $inDtype")
                }
            }
            rgbF?.release(); rgbF = null
            rgb.release()

            val outSize = outH * outW * 3
            val outBuf: ByteBuffer = when (outDtype) {
                DataType.FLOAT32 ->
                    ByteBuffer.allocateDirect(outSize * 4).order(ByteOrder.nativeOrder())
                DataType.UINT8, DataType.INT8 -> ByteBuffer.allocateDirect(outSize)
                else -> {
                    AppLogger.w(TAG, "不支持的输出 dtype=$outDtype")
                    throw IllegalStateException("unsupported output dtype $outDtype")
                }
            }

            interp.run(inBuf, outBuf)

            // 输出 → RGB 交错的三通道 float / 字节
            // 关键修复：模型输出是 NCHW（[R 平面][G 平面][B 平面]）时必须先"反交织"成
            // HWC，否则三通道平面会被当作交错像素读，成品就是通道错位/画面撕裂。
            outMat = Mat(outH, outW, CvType.CV_8UC3)
            when (outDtype) {
                DataType.FLOAT32 -> {
                    outBuf.rewind()
                    val f = FloatArray(outSize)
                    outBuf.asFloatBuffer().get(f)
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
                    val out32 = Mat(outH, outW, CvType.CV_32FC3)
                    out32.put(0, 0, hwc)
                    // 归一化量级自适应：平面归一模型输出 0..1，直出模型输出 0..255
                    var peak = 0f
                    for (v in hwc) if (v > peak) peak = v
                    val scale = if (peak <= 1.5f) 255.0 else 1.0
                    out32.convertTo(outMat, CvType.CV_8UC3, scale, 0.0)
                    out32.release()
                }
                DataType.UINT8, DataType.INT8 -> {
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
                }
                else -> Unit
            }

            bgr = Mat()
            Imgproc.cvtColor(outMat, bgr, Imgproc.COLOR_RGB2BGR)

            // 缩回 tile 原始尺寸，保证拼接对齐
            val back = Mat()
            Imgproc.resize(
                bgr, back,
                org.opencv.core.Size(origW.toDouble(), origH.toDouble())
            )
            return back
        } finally {
            // 修复：原实现在 cvtColor 抛异常时 outMat / resized 等不会被释放，
            // 逐块累积会明显吃内存；统一在 finally 释放。
            rgbF?.release()
            outMat?.release()
            bgr?.release()
            if (!resized.empty()) resized.release()
            if (!rgb.empty()) rgb.release()
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    private fun loadModel(context: Context, name: String): MappedByteBuffer {
        val afd = context.assets.openFd(name)
        val fis = FileInputStream(afd.fileDescriptor)
        val fc = fis.channel
        return fc.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }
}
