package com.gasstation.guard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.gasstation.guard.detect.Detection
import com.gasstation.guard.detect.DetectionResult
import kotlin.math.min

/**
 * 检测结果浮层：把识别到的车辆框画在预览画面上。
 *
 * ============================================================
 *  ⚠️ 坐标映射是整个 M2 最容易出错、也最要命的地方
 * ============================================================
 *
 * 这里有【两级】坐标缩放，任何一级算错，框都会画偏 ——
 * 框画偏了看起来只是"没对齐"，但 M3 要靠这个框来判定"车是否进入监测区域"，
 * 一旦偏了就是漏报或误报。所以必须理解清楚：
 *
 *   第一级：模型输出的是【640x640 输入图】里的坐标
 *           → 在 YoloDetector 里被换算成【摆正后原图】的归一化坐标（0~1）
 *
 *   第二级：把【归一化坐标】换算成【本 View 的像素坐标】
 *           → 就是下面 onDraw 里做的事
 *
 * 预览用的是 fitCenter（等比缩放 + 居中，多余部分留黑边，
 * 见 activity_main.xml 里的注释）。所以：
 *
 *   scale  = min(视图宽/画面宽, 视图高/画面高)
 *   显示宽 = 画面宽 × scale，显示高 = 画面高 × scale
 *   偏移X  = (视图宽 - 显示宽) / 2   ← 这就是左边黑边的宽度
 *   偏移Y  = (视图高 - 显示高) / 2
 *
 * 如果哪天预览换成了 fillCenter（铺满裁切），这段必须同步改，
 * 否则框的位置会整体错位。
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 当前这一帧要画的内容。由主线程调用 update() 写入。 */
    @Volatile
    private var result: DetectionResult? = null

    /** 是否画出「程序认为的画面边界」这个调试框，用来核对坐标映射对不对。 */
    var showDebugFrame: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    /** 框线画笔 */
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    /** 标签底色画笔 */
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 标签文字画笔 */
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 14f * density
        typeface = Typeface.MONOSPACE
    }

    /** 调试框画笔（半透明白色细线） */
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x66FFFFFF
    }

    private val labelRect = RectF()

    /** 主线程调用：送入新一帧的检测结果 */
    fun update(newResult: DetectionResult) {
        result = newResult
        invalidate()
    }

    /** 清空（例如相机断开时） */
    fun clear() {
        result = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val res = result ?: return
        if (res.imageWidth <= 0 || res.imageHeight <= 0) return

        // ---------- 第二级坐标映射：归一化坐标 → 本 View 像素坐标 ----------
        // 统一走 computeVideoRect，保证和 ROI 判定、ROI 框选手势是同一套几何。
        // M2 已用真机截图验证过这套映射，误差 1 像素。
        val video = computeVideoRect(width, height, res.imageWidth, res.imageHeight)
        if (video.width() <= 0f || video.height() <= 0f) return

        // 调试框：如果这条白框和预览画面的四边严丝合缝，说明坐标映射是对的
        if (showDebugFrame) {
            canvas.drawRect(video, debugPaint)
        }

        for (d in res.detections) {
            val left = video.left + d.left * video.width()
            val top = video.top + d.top * video.height()
            val right = video.left + d.right * video.width()
            val bottom = video.top + d.bottom * video.height()

            val color = colorFor(d.classId)
            boxPaint.color = color
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // 标签：类别 + 置信度，画在框的上方（贴顶时改画在框内）
            val text = "${d.label} ${(d.confidence * 100).toInt()}%"
            val textWidth = labelPaint.measureText(text)
            val textHeight = labelPaint.textSize
            val pad = 4f * density

            val labelTop = if (top - textHeight - pad * 2 < 0) top else top - textHeight - pad * 2

            labelRect.set(left, labelTop, left + textWidth + pad * 2, labelTop + textHeight + pad * 2)
            labelBgPaint.color = color
            canvas.drawRect(labelRect, labelBgPaint)
            canvas.drawText(text, left + pad, labelRect.bottom - pad - labelPaint.descent(), labelPaint)
        }
    }

    /** 每个类别一个颜色，方便一眼区分车型 */
    private fun colorFor(classId: Int): Int = when (classId) {
        2 -> 0xFF00E676.toInt()   // car        绿
        3 -> 0xFF00E5FF.toInt()   // motorcycle 青
        5 -> 0xFF448AFF.toInt()   // bus        蓝
        7 -> 0xFFFF9100.toInt()   // truck      橙
        else -> 0xFFFFEB3B.toInt() // 其它       黄
    }
}
