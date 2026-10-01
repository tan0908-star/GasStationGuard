package com.gasstation.guard.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * ============================================================
 *  YOLO 车辆检测器（TFLite）
 * ============================================================
 *
 *  模型：yolo_int8.tflite（int8 全整数量化的 YOLOv8n，输入 640x640）
 *  类别：只保留 COCO 里的 汽车 / 摩托车 / 客车 / 卡车 这 4 类
 *
 *  ---------- 完整的数据流（每一步都可能出错，按顺序理解） ----------
 *
 *   ① 相机帧（1280x720，可能是横躺的）
 *         ↓ 按 rotationDegrees 摆正
 *   ② 摆正后的图（例如 1280x720 或 720x1280）
 *         ↓ 等比缩放 + 居中补灰边（letterbox），缩到 640x640
 *   ③ 640x640 的方形输入图
 *         ↓ 按模型要求的 dtype / 量化参数填进输入张量
 *   ④ 模型推理
 *         ↓ 输出 [1, 84, N]
 *   ⑤ 解码：84 = 4 个框参数(cx,cy,w,h) + 80 个类别分数
 *         ↓ 只挑 4 个目标类别，丢弃置信度低的
 *   ⑥ 候选框（在 640 空间里）
 *         ↓ 反 letterbox：减掉灰边、除以缩放比、再除以原图尺寸
 *   ⑦ 归一化到 0~1 的候选框
 *         ↓ NMS 去掉重叠的重复框
 *   ⑧ 最终 Detection 列表
 *
 *  ---------- 为什么 letterbox 那一步如此重要 ----------
 *
 *  图片被等比缩小后上下（或左右）补了灰边。模型看到的坐标是含灰边的，
 *  必须先把灰边减掉、再除以缩放比，才能还原到原图。
 *  这一步算错，框会整体偏移 —— 而 M3 要靠框的位置判断"车进没进监测区"，
 *  偏了就是漏报。所以第 ⑥ 步的公式必须和构造输入时的矩阵完全对应。
 */
class YoloDetector(context: Context) {

    companion object {
        private const val TAG = "GasGuard"

        /** 模型文件名（放在 app/src/main/assets/ 下） */
        private const val MODEL_ASSET = "yolo_int8.tflite"

        /**
         * 只关心这 4 类。键是 COCO 的类别索引。
         * 其余 76 类（人、狗、椅子…）即使置信度再高也一律丢弃 ——
         * 夜班值守关心的只有车。
         */
        private val TARGET_CLASSES = linkedMapOf(
            2 to "汽车",
            3 to "摩托车",
            5 to "客车",
            7 to "卡车"
        )

        /** 置信度阈值：低于它直接丢弃 */
        private const val CONF_THRESHOLD = 0.35f

        /** NMS 的 IoU 阈值：两个同类框重叠超过它就认为是同一个目标 */
        private const val IOU_THRESHOLD = 0.45f

        /** 单帧最多输出多少目标（防止极端情况下的性能尖刺） */
        private const val MAX_DETECTIONS = 30

        /** 推理线程数。骁龙 7 Gen 3 是 8 核，给 4 个比较平衡（M6 再按温度调） */
        private const val NUM_THREADS = 4

        /** letterbox 补边用的灰（YOLO 训练时的标准填充色 114） */
        private const val LETTERBOX_GRAY = 114
    }

    /** YOLO 的输入张量。 */
    private val interpreter: Interpreter
    private val inputSize: Int
    private val inputType: DataType
    private val inputScale: Float
    private val inputZeroPoint: Int
    private val inputBuffer: ByteBuffer

    /** YOLO 的输出张量。 */
    private val outputType: DataType
    private val outputScale: Float
    private val outputZeroPoint: Int
    private val outputBuffer: ByteBuffer
    private val outputShape: IntArray

    // ---------- 复用的临时对象：避免每帧都 new，减少 GC 抖动 ----------
    private var uprightBitmap: Bitmap? = null
    private val letterboxBitmap: Bitmap
    private val letterboxCanvas: Canvas
    private val pixels: IntArray
    private val matrix = Matrix()
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        interpreter = Interpreter(
            loadModel(context),
            Interpreter.Options().setNumThreads(NUM_THREADS)
        )

        // ---------- 读输入张量信息 ----------
        val inTensor = interpreter.getInputTensor(0)
        val inShape = inTensor.shape()                       // 期望 [1, S, S, 3]
        require(inShape.size == 4 && inShape[3] == 3) {
            "模型输入形状不是 [1,S,S,3]，实际是 ${inShape.contentToString()}。请确认导出的是 YOLO 检测模型"
        }
        inputSize = inShape[1]
        inputType = inTensor.dataType()
        inTensor.quantizationParams().let {
            // 没量化时 scale 会是 0，兜底成 1/255 避免除零
            inputScale = if (it.scale > 0f) it.scale else 1f / 255f
            inputZeroPoint = it.zeroPoint
        }
        inputBuffer = ByteBuffer
            .allocateDirect(inputSize * inputSize * 3 * bytesPer(inputType))
            .order(ByteOrder.nativeOrder())

        // ---------- 读输出张量信息 ----------
        val outTensor = interpreter.getOutputTensor(0)
        outputShape = outTensor.shape()                      // 期望 [1, 84, N]
        outputType = outTensor.dataType()
        outTensor.quantizationParams().let {
            outputScale = if (it.scale > 0f) it.scale else 1f
            outputZeroPoint = it.zeroPoint
        }
        outputBuffer = ByteBuffer
            .allocateDirect(outputShape.fold(1) { a, b -> a * b } * bytesPer(outputType))
            .order(ByteOrder.nativeOrder())

        // ---------- 复用的位图 ----------
        letterboxBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        letterboxCanvas = Canvas(letterboxBitmap)
        pixels = IntArray(inputSize * inputSize)

        Log.i(
            TAG,
            "模型加载完成 | 输入 ${inputShapeToString()} ${inputType} " +
                "scale=$inputScale zp=$inputZeroPoint | " +
                "输出 ${outputShape.contentToString()} ${outputType} " +
                "scale=$outputScale zp=$outputZeroPoint"
        )
    }

    private fun inputShapeToString() = "[1, $inputSize, $inputSize, 3]"

    /**
     * 每种数据类型占几个字节。
     *
     * 注意：LiteRT 1.4.2 的 DataType 枚举里【没有】FLOAT16（那是 2.x 才加的），
     * 所以这里不能列它 —— 列了会编译不过。
     * 我们实际只用到 FLOAT32 和 UINT8/INT8，其余按 1 字节兜底即可，
     * 真有异常类型会在上面的 require() 阶段就明确报错，不会悄悄算错。
     */
    private fun bytesPer(type: DataType): Int = when (type) {
        DataType.FLOAT32 -> 4
        DataType.INT32 -> 4
        else -> 1                                            // UINT8 / INT8
    }

    /**
     * 对一帧图像做检测。
     *
     * @param source          相机原始帧（Bitmap）
     * @param rotationDegrees 需要顺时针旋转多少度才能摆正
     * @return 检测结果（坐标已归一化到摆正后的画面）
     */
    fun detect(source: Bitmap, rotationDegrees: Int): DetectionResult {
        val startMs = SystemClock.elapsedRealtime()

        // ---------- ① / ② 摆正 ----------
        val swap = rotationDegrees == 90 || rotationDegrees == 270
        val uprightW = if (swap) source.height else source.width
        val uprightH = if (swap) source.width else source.height
        val upright = rotateToUpright(source, rotationDegrees, uprightW, uprightH)

        // ---------- ③ letterbox：等比缩放 + 居中补灰边 ----------
        val scale = min(inputSize.toFloat() / uprightW, inputSize.toFloat() / uprightH)
        val drawW = uprightW * scale
        val drawH = uprightH * scale
        val padX = (inputSize - drawW) / 2f
        val padY = (inputSize - drawH) / 2f

        letterboxCanvas.drawColor(Color.rgb(LETTERBOX_GRAY, LETTERBOX_GRAY, LETTERBOX_GRAY))
        matrix.reset()
        matrix.setScale(scale, scale)                        // 先缩放
        matrix.postTranslate(padX, padY)                     // 再平移到居中位置
        letterboxCanvas.drawBitmap(upright, matrix, drawPaint)

        // ---------- ④ 填输入张量 ----------
        fillInputBuffer()

        // ---------- ⑤ 推理 ----------
        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)

        // ---------- ⑥⑦⑧ 解码 + 反 letterbox + NMS ----------
        val detections = decode(padX, padY, scale, uprightW, uprightH)

        return DetectionResult(
            imageWidth = uprightW,
            imageHeight = uprightH,
            detections = detections,
            inferenceMs = SystemClock.elapsedRealtime() - startMs
        )
    }

    /** 按 rotationDegrees 把图摆正。已经是正的就直接返回原图，不做无谓拷贝。 */
    private fun rotateToUpright(
        source: Bitmap,
        degrees: Int,
        width: Int,
        height: Int
    ): Bitmap {
        if (degrees == 0) return source

        var target = uprightBitmap
        if (target == null || target.width != width || target.height != height) {
            target?.recycle()
            target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            uprightBitmap = target
        }

        val canvas = Canvas(target)
        canvas.drawColor(Color.BLACK)

        matrix.reset()
        // 绕自身中心旋转
        matrix.postRotate(degrees.toFloat(), source.width / 2f, source.height / 2f)
        // 旋转后图会跑到负坐标区，算一下旋转后的包围盒并平移回原点
        val bounds = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        matrix.mapRect(bounds)
        matrix.postTranslate(-bounds.left, -bounds.top)

        canvas.drawBitmap(source, matrix, drawPaint)
        return target
    }

    /** 把 640x640 的 letterbox 图按模型要求的 dtype 和量化参数写进输入张量。 */
    private fun fillInputBuffer() {
        letterboxBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        inputBuffer.rewind()
        val count = inputSize * inputSize

        when (inputType) {
            DataType.FLOAT32 -> {
                val fb = inputBuffer.asFloatBuffer()
                for (i in 0 until count) {
                    val p = pixels[i]
                    fb.put(i * 3, ((p shr 16) and 0xFF) / 255f)
                    fb.put(i * 3 + 1, ((p shr 8) and 0xFF) / 255f)
                    fb.put(i * 3 + 2, (p and 0xFF) / 255f)
                }
            }

            DataType.UINT8, DataType.INT8 -> {
                for (i in 0 until count) {
                    val p = pixels[i]
                    inputBuffer.put(i * 3, quantize((p shr 16) and 0xFF))
                    inputBuffer.put(i * 3 + 1, quantize((p shr 8) and 0xFF))
                    inputBuffer.put(i * 3 + 2, quantize(p and 0xFF))
                }
            }

            else -> error("不支持的输入类型：$inputType")
        }
    }

    /**
     * 把一个 0~255 的像素值按量化参数压成 1 字节整数。
     *
     * 量化公式（TFLite 标准）：q = round(real / scale) + zeroPoint
     * 其中 real 是归一化到 0~1 的真实值。
     *
     * 举例：int8 模型常见 scale=1/255、zeroPoint=-128，
     *       此时 q = pixel - 128，正好落在 int8 的 -128~127 区间。
     */
    private fun quantize(pixel: Int): Byte {
        val real = pixel / 255f
        val q = Math.round(real / inputScale) + inputZeroPoint
        return q.coerceIn(-128, 255).toByte()
    }

    /** 把输出张量里第 idx 个元素还原成真实浮点数。 */
    private fun dequantize(idx: Int): Float = when (outputType) {
        DataType.FLOAT32 -> outputBuffer.getFloat(idx * 4)
        DataType.UINT8 ->
            ((outputBuffer.get(idx).toInt() and 0xFF) - outputZeroPoint) * outputScale
        DataType.INT8 ->
            (outputBuffer.get(idx).toInt() - outputZeroPoint) * outputScale
        else -> 0f
    }

    /**
     * 解码 YOLOv8 输出并做 NMS。
     *
     * 输出布局有两种可能，运行时自动判断：
     *   [1, 84, N] —— 通道在前（Ultralytics 的标准导出）
     *   [1, N, 84] —— 通道在后
     */
    private fun decode(
        padX: Float,
        padY: Float,
        scale: Float,
        imageWidth: Int,
        imageHeight: Int
    ): List<Detection> {
        val channelsFirst = outputShape[1] < outputShape[2]
        val numChannels = if (channelsFirst) outputShape[1] else outputShape[2]
        val numAnchors = if (channelsFirst) outputShape[2] else outputShape[1]

        if (numChannels < 5) {
            Log.w(TAG, "输出通道数异常：$numChannels，跳过本帧")
            return emptyList()
        }

        fun indexOf(anchor: Int, channel: Int): Int =
            if (channelsFirst) channel * numAnchors + anchor else anchor * numChannels + channel

        val candidates = ArrayList<Detection>()

        for (a in 0 until numAnchors) {
            // 先在这 4 个目标类别里挑最高分
            var bestScore = 0f
            var bestClass = -1
            for (classId in TARGET_CLASSES.keys) {
                val score = dequantize(indexOf(a, 4 + classId))
                if (score > bestScore) {
                    bestScore = score
                    bestClass = classId
                }
            }
            if (bestClass < 0 || bestScore < CONF_THRESHOLD) continue

            // 框参数：cx, cy, w, h
            //
            // ⚠️⚠️ 这里是 M2 最容易踩坑的一处，务必看清 ⚠️⚠️
            // 导出脚本（tools/export_yolo_tflite.py）在 ONNX 图里给框分支插了一个
            // Div(640)，所以模型吐出来的框是【归一化的 0~1】，不是 640 空间的像素值。
            //
            // 为什么要这么改：YOLO 的最终输出是 cat([框(0~640), 分数(0~1)])，
            // int8 量化给整个张量只算一个缩放系数，0~656 的值域会把 0~1 的
            // 类别分数全部压成 0（实测就是这么坏的，模型一个车都检不出来）。
            // 把框也压到 0~1，两者共用一个 scale 才都不失真。
            //
            // 所以这里必须【先乘回 inputSize】才能和 padX / scale 对齐。
            val cx = dequantize(indexOf(a, 0)) * inputSize
            val cy = dequantize(indexOf(a, 1)) * inputSize
            val bw = dequantize(indexOf(a, 2)) * inputSize
            val bh = dequantize(indexOf(a, 3)) * inputSize

            // ---------- 反 letterbox：减灰边 → 除缩放 → 除原图尺寸 ----------
            val left = ((cx - bw / 2f) - padX) / scale / imageWidth
            val top = ((cy - bh / 2f) - padY) / scale / imageHeight
            val right = ((cx + bw / 2f) - padX) / scale / imageWidth
            val bottom = ((cy + bh / 2f) - padY) / scale / imageHeight

            candidates += Detection(
                left = left.coerceIn(0f, 1f),
                top = top.coerceIn(0f, 1f),
                right = right.coerceIn(0f, 1f),
                bottom = bottom.coerceIn(0f, 1f),
                classId = bestClass,
                label = TARGET_CLASSES[bestClass] ?: "?",
                confidence = bestScore
            )
        }

        return nonMaxSuppression(candidates)
    }

    /** 非极大值抑制：同类框重叠太多时只保留置信度最高的那个。 */
    private fun nonMaxSuppression(input: List<Detection>): List<Detection> {
        if (input.size <= 1) return input

        val sorted = input.sortedByDescending { it.confidence }
        val kept = ArrayList<Detection>(min(sorted.size, MAX_DETECTIONS))

        for (candidate in sorted) {
            var suppressed = false
            for (existing in kept) {
                if (existing.classId == candidate.classId &&
                    intersectionOverUnion(existing, candidate) > IOU_THRESHOLD
                ) {
                    suppressed = true
                    break
                }
            }
            if (!suppressed) {
                kept += candidate
                if (kept.size >= MAX_DETECTIONS) break
            }
        }
        return kept
    }

    private fun intersectionOverUnion(a: Detection, b: Detection): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)

        val interW = right - left
        val interH = bottom - top
        if (interW <= 0f || interH <= 0f) return 0f

        val intersection = interW * interH
        val union = a.area + b.area - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    /** 把模型文件映射进内存（比读成字节数组省内存、启动也快）。 */
    private fun loadModel(context: Context): MappedByteBuffer {
        context.assets.openFd(MODEL_ASSET).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                return stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength
                )
            }
        }
    }

    /** 释放资源。App 退出或相机长时间不可用时调用。 */
    fun close() {
        try {
            interpreter.close()
        } catch (t: Throwable) {
            Log.w(TAG, "释放模型失败", t)
        }
        uprightBitmap?.recycle()
        uprightBitmap = null
        letterboxBitmap.recycle()
    }
}
