package com.gasstation.guard.evidence

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * 把一串位图编码成 H.264 的 MP4。
 *
 * ============================================================
 *  为什么用 getInputImage 而不是自己拼 NV12 字节数组
 * ============================================================
 *
 *  用 MediaCodec 编码时，输入缓冲的 YUV 排布【各芯片不一样】：
 *  高通常用 NV12（UV 交错），有些平台是 I420（UV 分开），
 *  rowStride 还可能带对齐填充。自己拼字节数组一旦猜错就是**花屏**，
 *  而且只在某些机型上出现，极难排查。
 *
 *  正确做法：色彩格式声明成 COLOR_FormatYUV420Flexible，
 *  然后用 codec.getInputImage(index) 拿到 Image，
 *  按每个平面【自己声明的】rowStride / pixelStride 去填。
 *  这样无论底层是 NV12 还是 I420 都能正确工作。
 *
 *  （这一点很关键：ColorFormat.COLOR_FormatYUV420SemiPlanar 在部分
 *    设备上根本不被编码器接受，configure() 会直接抛异常。Flexible
 *    是官方推荐的通用写法。）
 */
internal class Mp4Encoder(
    private val outFile: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitRate: Int
) {

    companion object {
        private const val TAG = "GasGuard"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC

        /** 等编码器吐数据的超时 */
        private const val DEQUEUE_TIMEOUT_US = 10_000L

        /** 收尾时最多再等这么多轮，防止极小概率下死循环 */
        private const val DRAIN_ROUNDS = 500
    }

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()

    // ---------- 诊断 ----------
    // 编码失败时必须说清楚"卡在哪一步"，否则只能看到一句"编码失败"，
    // 完全没法定位。这些信息会写进 info.txt。
    var framesQueued = 0
        private set
    var framesSkipped = 0
        private set
    var samplesWritten = 0
        private set
    var lastError: String? = null
        private set

    /** 复用的像素数组，避免每帧分配 230KB */
    private val pixels = IntArray(width * height)

    /** 每帧的 YUV420 数据量：Y 全分辨率 + UV 各 1/4 */
    private val yuvSize = width * height * 3 / 2

    fun start(): Boolean = try {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        true
    } catch (t: Throwable) {
        lastError = "启动编码器失败：${t.javaClass.simpleName}: ${t.message}"
        Log.e(TAG, "启动 MP4 编码器失败", t)
        release()
        false
    }

    /**
     * 送入一帧。
     *
     * @param frameIndex 帧序号，用来推算时间戳
     */
    fun encodeFrame(bitmap: Bitmap, frameIndex: Int) {
        val c = codec ?: return
        try {
            val inputIndex = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex < 0) {
                framesSkipped++
                lastError = "输入缓冲忙，第 $frameIndex 帧被跳过"
                return
            }

            val image = c.getInputImage(inputIndex)
            if (image == null) {
                // 理论上 Flexible 一定给 Image；真给了 null 就丢这帧，别把编码器喂坏
                framesSkipped++
                lastError = "getInputImage 返回 null"
                c.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                return
            }

            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            fillLuma(image.planes[0])
            fillChroma(image.planes[1], isU = true)
            fillChroma(image.planes[2], isU = false)

            c.queueInputBuffer(
                inputIndex,
                0,
                yuvSize,
                frameIndex * 1_000_000L / fps,
                0
            )
            framesQueued++

            drain(endOfStream = false)
        } catch (t: Throwable) {
            lastError = "送入第 $frameIndex 帧失败：${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "送入第 $frameIndex 帧失败", t)
        }
    }

    /**
     * 收尾：通知编码器结束，把剩余数据全部排空。
     *
     * ⚠️ 这里【只】回答"有没有排到流结束"，**不要**去查文件大小。
     *    真实踩过的坑：一开始这里顺手返回 outFile.length() > 0，
     *    但 MediaMuxer 是 stop() 时才刷盘的 —— 调用方此刻查长度永远是 0，
     *    于是明明已经写出 27 个样本、304KB 的完好视频，却被判定为"编码失败"，
     *    用户拿不到录像，而且界面上只会看到一句"编码失败"。
     *
     *    文件是否有效由调用方在 release()（内部会 stop() 刷盘）之后判断。
     */
    fun finish(): Boolean = try {
        val c = codec
        if (c == null) {
            false
        } else {
            // ⚠️ 这里【不能】用 signalEndOfInputStream()。
            //    那个 API 只在 Surface 输入模式（createInputSurface）下有效，
            //    我们是 buffer 输入（getInputImage），调用它会抛：
            //        IllegalStateException: signalEndOfInputStream() called
            //        without an input surface set
            //    正确做法：送一个 size=0、带 END_OF_STREAM 标志的输入缓冲。
            val endIndex = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (endIndex >= 0) {
                c.queueInputBuffer(
                    endIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            } else {
                lastError = "收尾时拿不到输入缓冲"
            }

            var reachedEnd = false
            var rounds = 0
            while (rounds++ < DRAIN_ROUNDS) {
                if (drain(endOfStream = true)) {
                    reachedEnd = true
                    break
                }
            }
            if (!reachedEnd) lastError = "收尾时没有等到流结束标记（$DRAIN_ROUNDS 轮）"
            reachedEnd
        }
    } catch (t: Throwable) {
        lastError = "结束编码失败：${t.javaClass.simpleName}: ${t.message}"
        Log.e(TAG, "结束编码失败", t)
        false
    }

    fun release() {
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
        try { if (muxerStarted) muxer?.stop() } catch (_: Throwable) {}
        try { muxer?.release() } catch (_: Throwable) {}
        muxer = null
        muxerStarted = false
        trackIndex = -1
    }

    // ============================================================
    //  内部
    // ============================================================

    /**
     * 把编码器已产出的数据尽量排空。
     *
     * @param endOfStream 收尾阶段：遇到 TRY_AGAIN 要重试而不是直接返回
     * @return 收尾阶段是否已经遇到 END_OF_STREAM
     */
    private fun drain(endOfStream: Boolean): Boolean {
        val c = codec ?: return true
        val m = muxer ?: return true

        while (true) {
            val outIndex = c.dequeueOutputBuffer(bufferInfo, if (endOfStream) DEQUEUE_TIMEOUT_US else 0L)

            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // 收尾时还要继续等；正常送帧时直接返回，不阻塞
                    if (!endOfStream) return false
                }

                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = m.addTrack(c.outputFormat)
                        m.start()
                        muxerStarted = true
                    }
                }

                outIndex >= 0 -> {
                    val encoded: ByteBuffer? = c.getOutputBuffer(outIndex)
                    if (encoded != null && bufferInfo.size > 0 && muxerStarted) {
                        // 编码器配置数据（SPS/PPS）不写进 muxer，muxer 会自己处理
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            m.writeSampleData(trackIndex, encoded, bufferInfo)
                            samplesWritten++
                        }
                    }
                    c.releaseOutputBuffer(outIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return true
                    }
                }
            }
        }
    }

    /** 亮度平面：每个像素一个 Y */
    private fun fillLuma(plane: Image.Plane) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var pos = 0
        for (row in 0 until height) {
            var offset = row * rowStride
            for (col in 0 until width) {
                val c = pixels[pos++]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // BT.601 定点近似：Y = 0.299R + 0.587G + 0.114B
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                buffer.put(offset, y.coerceIn(0, 255).toByte())
                offset += pixelStride
            }
        }
    }

    /**
     * 色度平面：2x2 个像素共用一个 U 或 V。
     *
     * 不用管底层是 NV12 还是 I420 —— 每个平面自己的 buffer / rowStride /
     * pixelStride 已经把差异吸收掉了。
     */
    private fun fillChroma(plane: Image.Plane, isU: Boolean) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val halfWidth = width / 2
        val halfHeight = height / 2

        for (row in 0 until halfHeight) {
            var offset = row * rowStride
            val srcRow = row * 2
            for (col in 0 until halfWidth) {
                // 取 2x2 块左上角像素代表整块：够用，而且比求平均快很多
                val c = pixels[srcRow * width + col * 2]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val value = if (isU) {
                    // BT.601：U = -0.169R - 0.331G + 0.5B + 128
                    ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                } else {
                    // BT.601：V = 0.5R - 0.419G - 0.081B + 128
                    ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                }
                buffer.put(offset, value.coerceIn(0, 255).toByte())
                offset += pixelStride
            }
        }
    }
}
