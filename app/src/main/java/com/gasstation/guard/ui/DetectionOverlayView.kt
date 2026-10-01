package com.gasstation.guard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.gasstation.guard.R
import com.gasstation.guard.detect.Detection
import com.gasstation.guard.detect.DetectionResult

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

    /** 框线画笔。细线 + 圆角，比粗直角线"贵"得多，也更不挡画面。 */
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    /** 标签底色画笔 */
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 标签文字画笔。用无衬线而不是等宽 —— 等宽在这里显得"工程感"太重。 */
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.02f
    }

    /** 调试框画笔（画面边界参考线，极细极淡） */
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x4DFFFFFF
    }

    private val labelRect = RectF()
    private val boxRect = RectF()

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
            boxRect.set(left, top, right, bottom)
            val r = 6f * density
            canvas.drawRoundRect(boxRect, r, r, boxPaint)

            // 标签：类别 + 置信度，做成圆角小胶囊贴在框的左上角
            val text = "${d.label} ${(d.confidence * 100).toInt()}%"
            val textWidth = labelPaint.measureText(text)
            val textHeight = labelPaint.textSize
            val padH = 8f * density
            val padV = 4f * density
            val chipHeight = textHeight + padV * 2

            // 贴顶时把标签压在框内侧，避免被屏幕边缘切掉
            val labelTop = if (top - chipHeight - 4f * density < 0f) {
                top + 4f * density
            } else {
                top - chipHeight - 4f * density
            }

            labelRect.set(left, labelTop, left + textWidth + padH * 2, labelTop + chipHeight)
            labelBgPaint.color = color
            canvas.drawRoundRect(labelRect, chipHeight / 2f, chipHeight / 2f, labelBgPaint)
            canvas.drawText(
                text,
                labelRect.left + padH,
                labelRect.bottom - padV - labelPaint.descent(),
                labelPaint
            )
        }
    }

    /**
     * 每个类别一个颜色，方便一眼区分车型。
     *
     * 色值统一取自 res/values/colors.xml —— 和状态胶囊、监测区域用的是同一套规范，
     * 避免界面出现"两种不搭的绿色"这种廉价感。
     */
    private fun colorFor(classId: Int): Int = when (classId) {
        2 -> ContextCompat.getColor(context, R.color.box_car)          // 汽车
        3 -> ContextCompat.getColor(context, R.color.box_motorcycle)   // 摩托车
        5 -> ContextCompat.getColor(context, R.color.box_bus)          // 客车
        7 -> ContextCompat.getColor(context, R.color.box_truck)        // 卡车
        else -> ContextCompat.getColor(context, R.color.box_other)
    }
}
