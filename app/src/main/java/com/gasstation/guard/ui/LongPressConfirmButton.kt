package com.gasstation.guard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * 「已到岗」长按确认按钮。
 *
 * ============================================================
 *  为什么必须是"长按 2 秒"而不是点一下
 * ============================================================
 *
 *  这是整个 App 唯一的解除报警方式，设计上必须同时满足两个矛盾的诉求：
 *
 *    - 报警状态下屏幕在闪、在震、在响，人又是刚被吵醒、半梦半醒的，
 *      手会乱按。**必须防止误触解除** —— 一旦误解除，就等于漏报。
 *
 *    - 但真到岗了，必须能可靠地解除，不能出现"按了没反应"。
 *
 *  所以做成：按住时进度条实时填充，满 2 秒才生效，中途松手立刻回退。
 *  用户能【看得见】自己的按压在生效，既不会误触也不会怀疑按钮坏了。
 *
 *  按压过程中有轻微震动反馈（HapticFeedback），让人确认"按到了"。
 */
class LongPressConfirmButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 需要按住多久才算确认 */
    var holdMillis: Long = 2_000L

    /** 按满时长后回调（在主线程） */
    var onConfirmed: (() -> Unit)? = null

    private val density = resources.displayMetrics.density

    private var pressing = false
    private var pressStartAt = 0L

    private val handler = Handler(Looper.getMainLooper())

    /** 刷新进度的定时器 */
    private val tick = object : Runnable {
        override fun run() {
            if (!pressing) return
            val elapsed = SystemClock.elapsedRealtime() - pressStartAt
            if (elapsed >= holdMillis) {
                pressing = false
                invalidate()
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onConfirmed?.invoke()
                return
            }
            invalidate()
            handler.postDelayed(this, 25L)
        }
    }

    // ---------- 画笔 ----------
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE0102A12.toInt()          // 深绿底
        style = Paint.Style.FILL
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E676.toInt()          // 亮绿进度
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()

    /** 长按时长的显示文本：整数秒不带小数点，小数保留一位 */
    private fun holdSecondsLabel(): String {
        val seconds = holdMillis / 1000f
        return if (seconds == seconds.toInt().toFloat()) {
            seconds.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", seconds)
        }
    }

    /** 当前按压进度 0f~1f，供绘制与外部观察 */
    val progress: Float
        get() = if (!pressing) 0f
        else ((SystemClock.elapsedRealtime() - pressStartAt).toFloat() / holdMillis)
            .coerceIn(0f, 1f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressing = true
                pressStartAt = SystemClock.elapsedRealtime()
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                handler.removeCallbacks(tick)
                handler.post(tick)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 松手就回退：没按满 2 秒不算数
                pressing = false
                handler.removeCallbacks(tick)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(tick)
        pressing = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        rect.set(0f, 0f, width.toFloat(), height.toFloat())

        // 底
        canvas.drawRoundRect(rect, 16f * density, 16f * density, bgPaint)

        // 进度填充（从左往右）
        val p = progress
        if (p > 0f) {
            val filled = RectF(rect)
            filled.right = rect.left + rect.width() * p
            canvas.save()
            canvas.clipRect(filled)
            canvas.drawRoundRect(rect, 16f * density, 16f * density, progressPaint)
            canvas.restore()
        }

        // 边框
        canvas.drawRoundRect(rect, 16f * density, 16f * density, borderPaint)

        // 文字（进度过半时用深色字，保证在亮绿上能看清）
        val onBright = p > 0.5f
        titlePaint.color = if (onBright) 0xFF00330F.toInt() else Color.WHITE
        hintPaint.color = if (onBright) 0xDD00330F.toInt() else 0xCCFFFFFF.toInt()

        val cx = width / 2f
        val cy = height / 2f
        canvas.drawText("已到岗", cx, cy + 2f * density, titlePaint)
        canvas.drawText(
            if (pressing) "松手即取消，按满 ${holdSecondsLabel()} 秒生效"
            else "长按 ${holdSecondsLabel()} 秒 · 我在这里",
            cx, cy + 22f * density, hintPaint
        )
    }
}
