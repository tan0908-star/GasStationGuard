package com.gasstation.guard.ui

import android.content.Context
import android.graphics.Canvas
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
import androidx.core.content.ContextCompat
import com.gasstation.guard.R

/**
 * 「已到岗」长按确认按钮。
 *
 * ============================================================
 *  交互设计（这部分【刻意没有改】）
 * ============================================================
 *
 *  这是整个 App 唯一的解除报警方式，必须同时满足两个矛盾的诉求：
 *
 *    · 报警时屏幕在闪、在震、在响，人又刚被吵醒、半梦半醒，手会乱按
 *      → **必须防误触解除**，一旦误解除就等于漏报
 *    · 真到岗了必须能可靠解除，不能出现"按了没反应"
 *
 *  所以：按住时进度实时填充，满 [holdMillis] 才生效，中途松手立刻回退。
 *  用户能**看得见**自己的按压在生效 —— 既不会误触，也不会怀疑按钮坏了。
 *
 *  按钮形状保持长方形：使用者已经学会这个手势了，
 *  为了"好看"去改成圆形是拿零学习成本去换审美，不划算。
 *  做的是**视觉打磨**，不是交互重设计。
 */
class LongPressConfirmButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 进度刷新间隔。25ms ≈ 40fps，肉眼看是连续的，又不会白耗电。 */
        private const val TICK_MS = 25L
    }

    /** 需要按住多久才算确认 */
    var holdMillis: Long = 2_000L

    /** 按满时长后回调（在主线程） */
    var onConfirmed: (() -> Unit)? = null

    private val density = resources.displayMetrics.density

    private var pressing = false
    private var pressStartAt = 0L

    private val handler = Handler(Looper.getMainLooper())

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
            handler.postDelayed(this, TICK_MS)
        }
    }

    // ---------- 配色取自统一规范 ----------
    private val surfaceColor = ContextCompat.getColor(context, R.color.surface)
    private val onSurface = ContextCompat.getColor(context, R.color.on_surface)
    private val onSurfaceVariant = ContextCompat.getColor(context, R.color.on_surface_variant)

    /** 按钮底：接近纯黑的深色，压在红色报警背景上对比最强 */
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (surfaceColor and 0x00FFFFFF) or 0xE6000000.toInt()
        style = Paint.Style.FILL
    }

    /** 进度填充：近白。
     *  刻意不用彩色 —— 红色背景上再放一个彩色进度会显得脏，
     *  近白是唯一能保持"高级感"又极其清晰的选择。 */
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = onSurface
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (onSurface and 0x00FFFFFF) or 0x66000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.08f
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.03f
    }

    private val rect = RectF()

    /** 当前按压进度 0f~1f */
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
                // 松手就回退：没按满不算数
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
        val radius = 20f * density

        // 底
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        // 进度：从左往右填充
        val p = progress
        val fillRight = rect.left + rect.width() * p
        if (p > 0f) {
            val filled = RectF(rect)
            filled.right = fillRight
            canvas.save()
            canvas.clipRect(filled)
            canvas.drawRoundRect(rect, radius, radius, progressPaint)
            canvas.restore()
        }

        // 细描边：把按钮从红色背景里"托"出来
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        val cx = width / 2f
        val cy = height / 2f
        val title = "已到岗"
        val hint = if (pressing) "松手即取消 · 按满 ${holdLabel()} 秒生效"
        else "长按 ${holdLabel()} 秒 · 我在这里"

        // ────────────────────────────────────────────────────────
        //  文字分两次画，按进度"切"开
        // ────────────────────────────────────────────────────────
        //
        // 真实踩过的坑：一开始用"进度过半就把文字翻成深色"，
        // 结果进度到 48% 时，"已"字正好压在白色填充上，
        // 而文字这时还是浅色 —— 白字压白底，整个字消失。
        //
        // 根因：文字是**居中**的，进度到 35% 左右就已经压到字的左边缘了，
        //       "过半"这个阈值根本没有意义。
        //
        // 正确做法：不去猜什么时候该变色，而是把文字画两遍，
        // 一遍用深色并裁剪到"已填充"区域，一遍用浅色并裁剪到"未填充"区域。
        // 这样无论进度停在哪，压在填充上的那一半永远清晰可读。
        drawTextClipped(canvas, title, hint, cx, cy, 0f, fillRight, filled = true)
        drawTextClipped(canvas, title, hint, cx, cy, fillRight, width.toFloat(), filled = false)
    }

    /** 在 [clipStart, clipEnd] 这段横向区域内画按钮文字 */
    private fun drawTextClipped(
        canvas: Canvas,
        title: String,
        hint: String,
        cx: Float,
        cy: Float,
        clipStart: Float,
        clipEnd: Float,
        filled: Boolean
    ) {
        if (clipEnd <= clipStart) return

        titlePaint.color = if (filled) surfaceColor else onSurface
        hintPaint.color = if (filled) {
            (surfaceColor and 0x00FFFFFF) or 0xCC000000.toInt()
        } else {
            (onSurfaceVariant and 0x00FFFFFF) or 0xE6000000.toInt()
        }

        canvas.save()
        canvas.clipRect(clipStart, 0f, clipEnd, height.toFloat())
        // 标题略上移，给下面的提示留出呼吸
        canvas.drawText(title, cx, cy + 4f * density, titlePaint)
        canvas.drawText(hint, cx, cy + 26f * density, hintPaint)
        canvas.restore()
    }

    /** 长按时长的显示文本：整数秒不带小数点 */
    private fun holdLabel(): String {
        val seconds = holdMillis / 1000f
        return if (seconds == seconds.toInt().toFloat()) {
            seconds.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", seconds)
        }
    }
}
