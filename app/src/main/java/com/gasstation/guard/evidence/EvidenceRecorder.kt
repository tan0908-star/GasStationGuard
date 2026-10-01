package com.gasstation.guard.evidence

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.gasstation.guard.detect.Detection
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * ============================================================
 *  告警录像器 —— M5 的核心
 * ============================================================
 *
 *  ---------- 它录什么 ----------
 *
 *    环形缓冲里最近的 6 秒  +  告警后继续录的 8 秒  =  约 14 秒
 *
 *    为什么要"告警前"那一段：报警是在车已经进入监测区域并连续命中好几帧
 *    之后才触发的。等报警响了再开始录，**车早就进来了** ——
 *    最关键的那几秒恰恰在报警之前。所以必须一直在缓冲，报警时才倒出来。
 *
 *  ---------- ⚠️ 为什么不用 CameraX 的 VideoCapture ----------
 *
 *    给相机会话加 VideoCapture 用例会【改变会话配置】，
 *    而 M1–M3 验证过的相机链路（夜视参数、1280x720、稳定性）是全项目
 *    最不能冒险的地方。会话配置一旦失败，直接后果是**相机起不来 = 漏报**。
 *
 *    所以走零风险的路：**用已有的分析帧自己编码**（见 Mp4Encoder）。
 *    代价是帧率只有 2fps，看起来像快放幻灯片；
 *    好处是**每一帧都是模型真正看到的那一帧**，
 *    排查"为什么漏报"时比高帧率录像更有价值。
 *
 *  ---------- 线程约定 ----------
 *
 *    offerFrame / beginAlarm / finishAlarm 在【analyze 线程】调用。
 *    压缩 JPEG 很快（缩到 640x360 再压），可以接受。
 *
 *    **MP4 编码全部丢到独立的 encoder 线程** —— 编码要几百毫秒到几秒，
 *    放在 analyze 线程会把识别和报警一起卡住，那是不可接受的。
 */
class EvidenceRecorder(
    private val context: Context,
    private val store: EvidenceStore,
    /** 录像保存完成后回调（第二个参数：视频是否编码成功） */
    private val onSessionSaved: (File, Boolean) -> Unit = { _, _ -> }
) {

    companion object {
        private const val TAG = "GasGuard"

        /** 视频尺寸。比 1280x720 小：证据够看即可，编码快、文件小 */
        const val CLIP_WIDTH = 640
        const val CLIP_HEIGHT = 360

        /** 视频帧率 —— 和分析帧率一致（每 500ms 一帧） */
        const val CLIP_FPS = 2

        /** 环形缓冲保留多少帧（6 秒 @2fps） */
        private const val RING_FRAMES = 12

        /** 报警后继续录多久 */
        private const val POST_ALARM_MS = 8_000L

        /**
         * 单次录制的最长时间上限。
         *
         * ⚠️ 没有这个上限就会出问题：报警最长可能一直响到自动拨号后都没人解除
         *    （值班的人不在岗的情况）。那样 sessionFrames 会无限增长，
         *    既吃内存，而且因为 finalizeSession 只在"解除报警"时触发，
         *    **视频永远落不了盘** —— 最需要证据的那种情况反而没有证据。
         */
        private const val MAX_SESSION_MS = 60_000L

        private const val JPEG_QUALITY = 75
        private const val VIDEO_BIT_RATE = 1_500_000
    }

    /** 后台编码线程。**绝不能**用相机 analyze 线程，否则会卡住识别。 */
    private val encoderExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "evidence-encoder").apply { isDaemon = true }
    }

    /** 环形缓冲：最近的若干帧（已压缩成 JPEG 小图，省内存） */
    private val ring = ArrayDeque<ByteArray>(RING_FRAMES)

    private var sessionFrames: MutableList<ByteArray>? = null

    /**
     * 告警瞬间的一张【全分辨率】照片。
     *
     * 视频帧是缩到 640x360 的（为了编码速度和文件大小），
     * 但作为取证照片太糊了 —— 车牌、车型这些细节看不清。
     * 所以额外单独存一张原始分辨率的。
     */
    private var sessionPhoto: ByteArray? = null
    private var needsFullResPhoto = false

    private var sessionDir: File? = null
    private var sessionStartedAt = 0L
    private var sessionDetections: List<Detection> = emptyList()
    private var finishRequested = false
    private val lock = Any()

    // ---------- 复用的临时对象，避免每帧分配 ----------
    private val scaledBitmap: Bitmap =
        Bitmap.createBitmap(CLIP_WIDTH, CLIP_HEIGHT, Bitmap.Config.ARGB_8888)
    private val scaleCanvas = Canvas(scaledBitmap)
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstRect = Rect(0, 0, CLIP_WIDTH, CLIP_HEIGHT)
    private val jpegBuffer = ByteArrayOutputStream(64 * 1024)

    /** 全分辨率照片的压缩缓冲，容量开大一点免得反复扩容 */
    private val fullJpegBuffer = ByteArrayOutputStream(512 * 1024)

    /** 是否正在记录一次告警 */
    val isRecording: Boolean
        get() = synchronized(lock) { sessionFrames != null }

    // ---------- 诊断计数 ----------
    // 这台手机的 ROM 会把应用日志冲掉，排查只能靠数据本身。
    // 区分"根本没被调用"和"调用了但压缩失败"是定位问题的关键。
    @Volatile private var offerCount = 0
    @Volatile private var compressFailCount = 0

    /** 一句话说明录制器的内部状态，显示在报警界面上 */
    fun debugSummary(): String = synchronized(lock) {
        "调用$offerCount 压失败$compressFailCount 环形${ring.size} 本次${sessionFrames?.size ?: 0}帧"
    }

    /**
     * 每帧调用（analyze 线程）。
     *
     * 传入原尺寸 Bitmap，内部缩到 640x360 再压缩 ——
     * 不这样做的話，几十帧 1280x720 的原始数据就是几十 MB 内存。
     */
    fun offerFrame(source: Bitmap) {
        offerCount++
        val jpeg = compressFrame(source)
        if (jpeg == null) {
            compressFailCount++
            return
        }

        var shouldFinalize = false
        var takeFullResPhoto = false
        synchronized(lock) {
            ring.addLast(jpeg)
            while (ring.size > RING_FRAMES) ring.removeFirst()

            sessionFrames?.add(jpeg)

            if (needsFullResPhoto) {
                needsFullResPhoto = false
                takeFullResPhoto = true
            }

            val elapsed = System.currentTimeMillis() - sessionStartedAt
            // 录满上限就强制收尾，避免无人解除报警时无限堆积
            if (elapsed >= MAX_SESSION_MS) finishRequested = true
            shouldFinalize = finishRequested && elapsed >= POST_ALARM_MS
        }

        // 全分辨率压缩要几十毫秒，放到锁外面做，别占着锁
        if (takeFullResPhoto) {
            val full = compressFullRes(source)
            synchronized(lock) { sessionPhoto = full }
        }

        if (shouldFinalize) finalizeSession()
    }

    /**
     * 报警触发时调用。
     *
     * 把环形缓冲里"报警前"的帧全部倒进本次会话 —— 那几秒才是车开进来的过程。
     */
    fun beginAlarm(detections: List<Detection>) {
        val beforeCount: Int
        synchronized(lock) {
            if (sessionFrames != null) return          // 已经在录了
            val dir = store.createSessionDir()
            sessionDir = dir
            sessionStartedAt = System.currentTimeMillis()
            sessionDetections = detections
            finishRequested = false
            // 等下一帧来时压一张全分辨率照片 —— 那一帧最接近"车刚进来"的瞬间
            needsFullResPhoto = true
            sessionPhoto = null

            val frames = ArrayList<ByteArray>(RING_FRAMES + 64)
            frames.addAll(ring)                        // ← 报警【前】的画面
            sessionFrames = frames
            beforeCount = ring.size
        }
        Log.i(TAG, "开始记录告警证据，已倒入报警前 $beforeCount 帧")
    }

    /** 报警解除时调用。还会再录满 POST_ALARM_MS 才真正落盘。 */
    fun finishAlarm() {
        val elapsed: Long
        val pending: Boolean
        synchronized(lock) {
            if (sessionFrames == null) return
            finishRequested = true
            elapsed = System.currentTimeMillis() - sessionStartedAt
            pending = elapsed < POST_ALARM_MS
        }
        if (!pending) finalizeSession()
    }

    /** 相机断开等异常：放弃本次录制，不留半截文件 */
    fun abortSession() {
        synchronized(lock) {
            sessionFrames = null
            sessionDir = null
            finishRequested = false
        }
    }

    fun shutdown() {
        abortSession()
        encoderExecutor.shutdown()
    }

    // ============================================================
    //  落盘
    // ============================================================

    private fun finalizeSession() {
        val frames: List<ByteArray>
        val dir: File
        val detections: List<Detection>
        val startedAt: Long
        val photo: ByteArray?
        synchronized(lock) {
            val f = sessionFrames ?: return
            val d = sessionDir ?: return
            frames = f
            dir = d
            detections = sessionDetections
            startedAt = sessionStartedAt
            // 优先用全分辨率那张；万一没拍到就退回最后一帧（至少有个东西）
            photo = sessionPhoto ?: frames.lastOrNull()
            sessionFrames = null
            sessionDir = null
            sessionPhoto = null
            finishRequested = false
        }

        encoderExecutor.execute {
            var clip = ClipResult(false, "未执行")
            try {
                Log.i(TAG, "编码告警录像：${frames.size} 帧 → ${dir.name}/clip.mp4")

                // 照片：报警瞬间的全分辨率快照（取证要看得清车型/车牌）
                writeBytes(File(dir, "photo.jpg"), photo)

                // 视频
                clip = encodeClip(frames, File(dir, "clip.mp4"))
            } catch (t: Throwable) {
                clip = ClipResult(false, "保存过程异常：${t.javaClass.simpleName}: ${t.message}")
                Log.e(TAG, "保存告警证据失败", t)
            }
            // 无论成功失败都把详情写下来（含失败原因），
            // 这样"为什么没有视频"是有据可查的，而不是一句空话
            writeInfo(File(dir, "info.txt"), startedAt, frames.size, detections, clip)

            // 即使视频编码失败也保留目录 —— 里面至少还有照片和 info.txt
            onSessionSaved(dir, clip.ok)
        }
    }

    /** 视频编码结果。detail 会写进 info.txt —— 失败原因必须可查。 */
    private data class ClipResult(val ok: Boolean, val detail: String)

    private fun encodeClip(frames: List<ByteArray>, outFile: File): ClipResult {
        if (frames.isEmpty()) return ClipResult(false, "没有可用的画面帧")

        val encoder = Mp4Encoder(
            outFile = outFile,
            width = CLIP_WIDTH,
            height = CLIP_HEIGHT,
            fps = CLIP_FPS,
            bitRate = VIDEO_BIT_RATE
        )
        if (!encoder.start()) {
            return ClipResult(false, encoder.lastError ?: "编码器启动失败")
        }

        var drained = false
        var thrown: String? = null
        try {
            frames.forEachIndexed { index, jpeg ->
                val bitmap = decodeJpeg(jpeg) ?: return@forEachIndexed
                try {
                    encoder.encodeFrame(bitmap, index)
                } finally {
                    bitmap.recycle()
                }
            }
            drained = encoder.finish()
        } catch (t: Throwable) {
            thrown = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "编码告警视频失败", t)
        } finally {
            // release() 里会 muxer.stop()，而 MediaMuxer 是 stop() 时才真正刷盘
            encoder.release()
        }

        // ⚠️⚠️ 文件大小一定要在 release() 之后才检查 ⚠️⚠️
        // 真实踩过的坑：一开始在 release 之前查，长度永远是 0，
        // 于是明明生成了一个 572KB 的完好视频，却报告"编码失败"，
        // 用户拿不到录像 —— 又是一个不报错的静默失效。
        val size = if (outFile.exists()) outFile.length() else 0L
        // 判定标准是"确实写出过样本、且文件非空"。
        // drained（是否排到流结束）只作为附加信息 —— 有些编码器不发流结束标记，
        // 但文件其实完全正常，不能因此把好好的录像删掉。
        val valid = size > 0L && encoder.samplesWritten > 0
        if (!valid) outFile.delete()

        val detail = buildString {
            append("送入 ${encoder.framesQueued} 帧，跳过 ${encoder.framesSkipped} 帧，")
            append("写出 ${encoder.samplesWritten} 个样本，文件 ${size / 1024} KB")
            append(if (drained) "，已正常收尾" else "，⚠ 未收到流结束标记")
            thrown?.let { append("；异常：$it") }
            encoder.lastError?.let { append("；最后错误：$it") }
        }
        return ClipResult(valid, detail)
    }

    private fun decodeJpeg(jpeg: ByteArray): Bitmap? = try {
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    } catch (t: Throwable) {
        Log.w(TAG, "解码证据帧失败", t)
        null
    }

    /** 全分辨率照片：不做缩放，直接压。只在报警后压一次，开销可接受。 */
    private fun compressFullRes(source: Bitmap): ByteArray? = try {
        fullJpegBuffer.reset()
        source.compress(Bitmap.CompressFormat.JPEG, 85, fullJpegBuffer)
        fullJpegBuffer.toByteArray()
    } catch (t: Throwable) {
        Log.w(TAG, "压缩全分辨率照片失败", t)
        null
    }

    private fun compressFrame(source: Bitmap): ByteArray? = try {
        scaleCanvas.drawBitmap(source, null, dstRect, scalePaint)
        jpegBuffer.reset()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpegBuffer)
        jpegBuffer.toByteArray()
    } catch (t: Throwable) {
        Log.w(TAG, "压缩证据帧失败", t)
        null
    }

    private fun writeBytes(out: File, bytes: ByteArray?) {
        if (bytes == null) return
        try {
            FileOutputStream(out).use { it.write(bytes) }
        } catch (t: Throwable) {
            Log.w(TAG, "写入文件失败：${out.name}", t)
        }
    }

    private fun writeInfo(
        out: File,
        startedAt: Long,
        frameCount: Int,
        detections: List<Detection>,
        clip: ClipResult
    ) {
        try {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            val seconds = frameCount.toFloat() / CLIP_FPS
            out.writeText(
                buildString {
                    appendLine("告警时间：${fmt.format(Date(startedAt))}")
                    appendLine("画面：$frameCount 帧 / 约 ${"%.1f".format(seconds)} 秒 / ${CLIP_FPS}fps")
                    appendLine("视频：${if (clip.ok) "已生成 clip.mp4" else "⚠ 编码失败，本次只有照片"}")
                    appendLine("      编码详情：${clip.detail}")
                    appendLine()
                    appendLine("报警时的检出：")
                    if (detections.isEmpty()) {
                        appendLine("  （无）")
                    } else {
                        detections.forEach {
                            appendLine("  ${it.label}　置信度 ${"%.0f".format(it.confidence * 100)}%")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "写入告警详情失败", t)
        }
    }
}
