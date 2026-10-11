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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

    /** 模型输出值域系数：NafNet 输出 0–1，乘 255 到像素域 */
    private const val OUT_SCALE = 255.0

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
            // 【关键修复】缩小必须用 INTER_AREA。
            // 原来用默认的 INTER_LINEAR（只取 4 个相邻像素做双线性），
            // 对 4032→1600 这种非整数倍缩小是**欠采样**：树叶/砖墙/发丝混叠，
            // 传感器噪声被同样地点采样带进来 —— 去噪的输入本身已带采样误差，
            // 降噪效果被锁死、边缘出白边锯齿。
            Imgproc.resize(
                input, tmp,
                org.opencv.core.Size(input.cols() * k, input.rows() * k),
                0.0, 0.0, Imgproc.INTER_AREA
            )
            src = tmp
            scaledDown = true
        }

        onProgress?.invoke(0)

        // 【新增】任何一块超时 → 整图放弃去噪并原图返回。
        val output = try {
            denoiseLoop(src, interp, onProgress)
        } catch (e: TileTimeoutException) {
            AppLogger.w(TAG, "去噪超时，返回原图（未降噪）")
            runCatching { src.release() }
            onProgress?.invoke(100)
            return input.clone()
        }

        // 2. 降采样过则缩回原尺寸
        if (scaledDown) {
            val resized = Mat()
            // 【修复】原来用默认的 INTER_LINEAR。从最长边 1600 放回 4032 是 2.5x
            // 放大，双线性只取 4 个邻点 → 细节被抹平，AI 成片比原图明显发糊。
            // 改用 INTER_CUBIC（4 邻点双三次），放大后锐度明显更好，
            // 且只在每张图最后执行一次，性能可接受。
            Imgproc.resize(
                output, resized,
                org.opencv.core.Size(input.cols().toDouble(), input.rows().toDouble()),
                0.0, 0.0, Imgproc.INTER_CUBIC
            )
            output.release()
            src.release()
            onProgress?.invoke(100)
            return resized
        }

        onProgress?.invoke(100)
        return output
    }

    /** 分块推理主体；超时抛 [TileTimeoutException] 由 [denoise] 统一回退 */
    private fun denoiseLoop(src: Mat, interp: Interpreter, onProgress: ((Int) -> Unit)?): Mat {
        val w = src.cols()
        val h = src.rows()
        val output = Mat.zeros(h, w, CvType.CV_8UC3)
        val step = TILE - OVERLAP
        // 修复：w <= OVERLAP 时 ((w - OVERLAP) + step - 1) / step 会算出 0 列 → 整图不处理。
        // 用 coerceAtLeast(1) 保证至少有一块。
        val cols = (((w - OVERLAP) + step - 1) / step).coerceAtLeast(1)
        val rows = (((h - OVERLAP) + step - 1) / step).coerceAtLeast(1)
        val total = (cols * rows).coerceAtLeast(1)
        var done = 0

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

        return output
    }

    /**
     * 带超时保护的整块推理；超时或异常返回 null，由调用方回退原图。
     *
     * 【关键修复】原来注释写着"带超时保护"，但 TILE_TIMEOUT_MS 只在推理
     * **返回之后**用来打一行日志，没有任何 Future.get(timeout) / 中断机制 ——
     * NNAPI 在某些机型上单块可卡到几十秒甚至永久挂起，而 BackgroundProcessor
     * 是**单线程 + 每任务后 sleep 3s**，于是整个 AI 队列彻底堵死、
     * UI 永远停在"处理中"。
     *
     * 现在用单次执行器 + Future.get(timeout) 实现真实超时：
     * 超时后 cancel(true) 发出中断信号并放弃该块（保留原图兜底）。
     *
     * 注意：TFLite 的 native 推理不响应线程中断，cancel 只是让等待方返回、
     * 后台线程可能仍在跑完这一块 —— 但至少 UI 与后续流程不会被无限阻塞。
     */
    private fun runTileSafe(interp: Interpreter, tile: Mat): Mat? {
        val start = System.currentTimeMillis()
        val exec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "DenoiseTile").apply { isDaemon = true }
        }
        return try {
            val future = exec.submit<Mat?> { runTile(interp, tile) }
            val r = try {
                future.get(TILE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                AppLogger.w(TAG, "tile 推理超时 ${TILE_TIMEOUT_MS}ms，整图放弃去噪")
                future.cancel(true)
                // 【关键修复】超时**必须让整张图放弃去噪**，而不是跳过这块继续下一块。
                //
                // TFLite 的 native 推理不响应 Java 线程中断，`cancel(true)` 之后
                // 那个 run() 很可能仍在驱动的 NNAPI/GPU 上跑着。如果此时主循环
                // 用**同一个 Interpreter** 处理下一块，就会出现两个线程并发调用
                // Interpreter.run() —— TFLite 对此没有任何同步保护，内部张量缓冲
                // 被同时读写，结果是随机崩溃/花屏，而不只是算错。
                //
                // 同一个 Mat 也仍在被后台线程读（runTile 里的 rgb/resized），
                // 主循环却已经把它 release() → native use-after-free。
                //
                // 所以这里抛出，由 [denoise] 统一回退成"输出原图"，
                // 保证结果确定（宁可不降噪，也不能崩或花屏）。
                throw TileTimeoutException()
            }
            val cost = System.currentTimeMillis() - start
            if (cost > TILE_TIMEOUT_MS) {
                AppLogger.w(TAG, "tile 耗时 ${cost}ms")
            }
            r
        } catch (e: TileTimeoutException) {
            // 超时必须冒泡给 [denoise]（见上面的说明），不能在这里吞掉
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "tile 推理失败: ${e.message}")
            null
        } finally {
            runCatching { exec.shutdownNow() }
        }
    }

    /** 单块推理超时：整图放弃去噪（而不是继续下一块，避免 Interpreter 并发） */
    private class TileTimeoutException : RuntimeException("tile inference timeout")

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
                    // 【关键修复】删除"逐块探测输出值域"的逻辑。
                    // 原来是：
                    //     var peak = 0f; for (v in hwc) if (v > peak) peak = v
                    //     val scale = if (peak <= 1.5f) 255.0 else 1.0
                    // 问题在于这是**逐块**判断，而分块有几十块：
                    // 夜景/室内场景里任意一块暗区（欠曝角落、阴影）peak 可能只有
                    // 0.1~0.3，被误判成 0–1 → 该块 ×255 → saturate 成纯白方块，
                    // 表现为"降噪后图上偶发一块惨白色块"。
                    // 模型契约是明确的（NafNet 输出 0–1，见 assets/README.txt），
                    // 与 AISuperResolution 保持一致固定 ×255，行为确定、无隐式状态。
                    out32.convertTo(outMat, CvType.CV_8UC3, OUT_SCALE, 0.0)
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
        // 【关键修复】原来 openFd 与 FileInputStream 都没关，fd 一直被 APK 的
        // 映射窗口引用；map() 抛异常时更是彻底泄漏。
        // 注意 MappedByteBuffer 在 channel 关闭后仍然有效，这是 FileChannel.map
        // 的既定语义，不是 use-block 的副作用。
        val afd = context.assets.openFd(name)
        FileInputStream(afd.fileDescriptor).use { fis ->
            return fis.channel.map(
                FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength
            )
        }
    }
}
