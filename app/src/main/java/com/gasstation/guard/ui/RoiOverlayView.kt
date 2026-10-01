package com.gasstation.guard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.gasstation.guard.detect.RoiRect

/**
 * 监测区域（ROI）浮层。
 *
 * ============================================================
 *  为什么夜班值守必须有 ROI
 * ============================================================
 *
 *  摄像头对着加油站入口，画面里必然包含：
 *    - 马路上的过路车（**不应该报警**）
 *    - 对面楼里的灯光、树叶晃动、雨水反光（**不应该报警**）
 *    - 偶尔路过的行人（**不应该报警**）
 *
 *  没有 ROI，上面这些全都会触发报警，误报率会直接爆表，
 *  这个工具就会因为"太吵"被关掉 —— 那才是最严重的失败。
 *
 *  有了 ROI，只统计"车辆进入我关心的这块地面"才报警。
 *
 * ============================================================
 *  操作方式（固定支架上设一次就行）
 * ============================================================
 *
 *    **长按画面 2 秒 → 不松手直接拖动 → 松手保存**
 *
 *  故意用长按而不是点一下，是为了防止值班时手碰到屏幕误改设置。
 *  解除报警用的是同一个手势习惯（长按 2 秒），保持一致。
 */
class RoiOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 进入框选模式需要的长按时长，和解除报警保持一致 */
        private const val LONG_PRESS_MS = 2_000L

        /** 手指移动超过这个距离就认为不是长按而是滑动 */
        private const val MOVE_SLOP_DP = 24f
    }

    /** ROI 变化回调。参数是归一化坐标（相对摆正后的画面）。 */
    var onRoiChanged: ((left: Float, top: Float, right: Float, bottom: Float) -> Unit)? = null

    /** 进入/退出框选模式时回调，用于隐藏其它界面元素、显示提示 */
    var onCalibrationChanged: ((Boolean) -> Unit)? = null

    private var roi: RoiRect = RoiRect.FULL

    /** 当前画面的尺寸（由主线程每帧更新），用于坐标映射 */
    private var imageWidth = 1280
    private var imageHeight = 720

    /** 是否正在框选 */
    var calibrating: Boolean = false
        private set

    // ---------- 手势状态 ----------
    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var dragX = 0f
    private var dragY = 0f

    private val longPressRunnable = Runnable {
        calibrating = true
        dragX = downX
        dragY = downY
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onCalibrationChanged?.invoke(true)
        invalidate()
    }

    private val density = resources.displayMetrics.density

    // ---------- 画笔 ----------
    /** ROI 之外的遮罩：压暗，让监测区域一眼可见 */
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
        style = Paint.Style.FILL
    }
    private val roiBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E676.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val dragBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFEB3B.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(14f * density, 10f * density), 0f
        )
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD9000000.toInt()
        style = Paint.Style.FILL
    }

    private val maskPath = Path()
    private val viewRect = RectF()
    private val roiViewRect = RectF()

    // ============================================================
    //  外部调用
    // ============================================================

    fun updateRoi(newRoi: RoiRect) {
        roi = newRoi
        invalidate()
    }

    /** 主线程每帧把当前画面尺寸送进来，用于坐标换算 */
    fun updateImageSize(width: Int, height: Int) {
        if (width == imageWidth && height == imageHeight) return
        imageWidth = width
        imageHeight = height
        invalidate()
    }

    /**
     * 从设置页手动进入框选模式。
     *
     * 除了"长按画面 2 秒"之外再提供一个显式入口：
     * 长按是对的方向（防误触），但第一次配置的人不一定想得到，
     * 设置里给个按钮能省掉一轮"我该怎么弄"的困惑。
     */
    fun startCalibration() {
        if (calibrating) return
        calibrating = true
        // 起点先放在画面中心，用户一按一拖就成型
        downX = width / 2f
        downY = height / 2f
        dragX = downX
        dragY = downY
        onCalibrationChanged?.invoke(true)
        invalidate()
    }

    fun cancelCalibration() {
        if (!calibrating) return
        calibrating = false
        onCalibrationChanged?.invoke(false)
        invalidate()
    }

    // ============================================================
    //  坐标换算（统一走 computeVideoRect，和检测框用同一套）
    // ============================================================

    private fun currentVideoRect(): RectF =
        computeVideoRect(width, height, imageWidth, imageHeight)

    /** 屏幕坐标 → 图像归一化坐标 */
    private fun toNormalized(x: Float, y: Float): Pair<Float, Float> {
        val vr = currentVideoRect()
        if (vr.width() <= 0f || vr.height() <= 0f) return 1f to 1f
        val nx = ((x - vr.left) / vr.width()).coerceIn(0f, 1f)
        val ny = ((y - vr.top) / vr.height()).coerceIn(0f, 1f)
        return nx to ny
    }

    /** 图像归一化坐标 → 屏幕坐标 */
    private fun toViewRect(r: RoiRect, out: RectF) {
        val vr = currentVideoRect()
        out.set(
            vr.left + r.left * vr.width(),
            vr.top + r.top * vr.height(),
            vr.left + r.right * vr.width(),
            vr.top + r.bottom * vr.height()
        )
    }

    // ============================================================
    //  触摸
    // ============================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragX = downX
                dragY = downY
                handler.removeCallbacks(longPressRunnable)
                if (!calibrating) {
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!calibrating) {
                    // 没进入框选模式前，移动超过阈值就取消长按（说明是在滑，不是在按）
                    val slop = MOVE_SLOP_DP * density
                    if (Math.abs(event.x - downX) > slop || Math.abs(event.y - downY) > slop) {
                        handler.removeCallbacks(longPressRunnable)
                    }
                } else {
                    dragX = event.x
                    dragY = event.y
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (calibrating) {
                    val (nx1, ny1) = toNormalized(downX, downY)
                    val (nx2, ny2) = toNormalized(event.x, event.y)
                    val newRoi = RoiRect(
                        minOf(nx1, nx2), minOf(ny1, ny2),
                        maxOf(nx1, nx2), maxOf(ny1, ny2)
                    )
                    if (newRoi.isValid) {
                        roi = newRoi
                        onRoiChanged?.invoke(newRoi.left, newRoi.top, newRoi.right, newRoi.bottom)
                    }
                    calibrating = false
                    onCalibrationChanged?.invoke(false)
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(longPressRunnable)
    }

    // ============================================================
    //  绘制
    // ============================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 正在拖动时，用「手指按下点 → 当前点」这个临时矩形，画成黄虚线
        val drawRect = if (calibrating) {
            val (nx1, ny1) = toNormalized(downX, downY)
            val (nx2, ny2) = toNormalized(dragX, dragY)
            toViewRect(
                RoiRect(
                    minOf(nx1, nx2), minOf(ny1, ny2),
                    maxOf(nx1, nx2), maxOf(ny1, ny2)
                ),
                roiViewRect
            )
            roiViewRect
        } else {
            toViewRect(roi, roiViewRect)
            roiViewRect
        }

        viewRect.set(0f, 0f, width.toFloat(), height.toFloat())

        // 遮罩：ROI 之外压暗（用奇偶填充规则挖洞）
        if (drawRect.width() > 0f && drawRect.height() > 0f) {
            maskPath.reset()
            maskPath.fillType = Path.FillType.EVEN_ODD
            maskPath.addRect(viewRect, Path.Direction.CW)
            maskPath.addRect(drawRect, Path.Direction.CCW)
            canvas.drawPath(maskPath, dimPaint)
        } else {
            canvas.drawRect(viewRect, dimPaint)
        }

        // ROI 边框
        canvas.drawRect(
            drawRect,
            if (calibrating) dragBorderPaint else roiBorderPaint
        )

        // 提示文字
        val hint = if (calibrating) {
            "拖出一个矩形，松手即保存　（覆盖车辆会经过的地面）"
        } else {
            null
        }
        if (hint != null) {
            val textY = height * 0.12f
            val textWidth = textPaint.measureText(hint)
            canvas.drawRect(
                width / 2f - textWidth / 2f - 12f * density,
                textY - 26f * density,
                width / 2f + textWidth / 2f + 12f * density,
                textY + 8f * density,
                textBgPaint
            )
            canvas.drawText(hint, width / 2f, textY, textPaint)
        }
    }
}
