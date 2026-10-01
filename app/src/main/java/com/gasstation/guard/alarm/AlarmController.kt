package com.gasstation.guard.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.gasstation.guard.R
import java.util.Locale

/**
 * ============================================================
 *  强制报警控制器 —— M3 的核心，也是整个项目最关键的一段代码
 * ============================================================
 *
 *  这一段的每一个设计取舍都围绕同一件事：**必须把人叫醒**。
 *
 *  ---------- 三条通道同时发力，任何一条失效另外两条还在 ----------
 *
 *    ① 声音：内置 WAV 双音警报，走【闹钟音频通道】并强制拉到最大音量
 *    ② 震动：波形循环震动，同样标记为闹钟用途
 *    ③ 语音：中文 TTS 循环播报"有车辆进入，请到岗查看"
 *
 *  ---------- 关于静音和勿扰 ----------
 *
 *    使用 AudioAttributes.USAGE_ALARM 有两个关键作用：
 *      1. 走的是"闹钟"音量通道，【静音模式下依然会响】
 *      2. 默认可以【穿透勿扰模式】（系统的"允许闹钟"默认是开着的）
 *
 *    ⚠️ 但如果用户把勿扰设成了"无例外/完全静默"，系统层面会拦掉一切声音，
 *       这是应用无法绕过的。所以安装时会带你在系统设置里确认这一项。
 *
 *  ---------- 关于 30 秒没解除就自动拨号 ----------
 *
 *    这是最后一道防线：如果值班的人被叫不醒（睡得太沉、戴着耳塞、
 *    或者人根本不在岗），光有声音没有用，必须让电话响起来。
 *
 *    ⚠️ 号码未设置时【明确提示】，绝不静默跳过 —— 章程红线。
 */
class AlarmController(
    private val context: Context,
    /** 到了自动拨号的时间点时回调。参数是要拨打的号码。 */
    private val onEscalateDial: (String) -> Unit,
    /** 状态文字变化时回调，用于显示在诊断条上 */
    private val onStatusChanged: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "GasGuard"

        /** 语音播报间隔。警报音本身 2.4 秒，这里留一点间隙免得听不清 */
        private const val SPEAK_INTERVAL_MS = 4_000L

        /** 第一次播报的延迟。短一点，避免报警很快被解除时一句话都没说出口 */
        private const val FIRST_SPEAK_DELAY_MS = 1_200L

        /** 播报时警报音压到的音量，避免两个声音叠在一起听不清 */
        private const val DUCK_VOLUME = 0.25f

        /** 震动波形：等待 0ms → 震 600ms → 停 300ms，然后从头循环 */
        private val VIBRATION_PATTERN = longArrayOf(0L, 600L, 300L)
        private const val VIBRATION_REPEAT_FROM = 0
    }

    private val uiHandler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** 报警前用户的闹钟音量，报警结束后要还原（不能偷偷改用户的设置） */
    private var originalAlarmVolume = -1

    /** 当前正在报警的号码，供升级拨号使用 */
    private var currentPhone: String = ""

    /** 是否正在报警 */
    var isRinging: Boolean = false
        private set

    /** 是否已经触发过自动拨号（避免重复回调） */
    private var escalated = false

    // ---------- 三条通道的实际状态，用于在报警界面上显示 ----------
    // 这个 ROM 会把应用日志冲掉，靠 logcat 排障太不可靠，
    // 所以把状态直接画在屏幕上 —— 截一张图就能看到全部真相。
    private var soundStarted = false
    private var vibrationStarted = false
    private var ttsError: String? = null

    // ---------- 定时任务 ----------

    /** 循环播报语音 */
    private val speakRunnable = object : Runnable {
        override fun run() {
            speak()
            if (isRinging) uiHandler.postDelayed(this, SPEAK_INTERVAL_MS)
        }
    }

    /** 到点自动拨号 */
    private val escalateRunnable = Runnable {
        if (!isRinging || escalated) return@Runnable
        escalated = true
        if (currentPhone.isBlank()) {
            // ⚠️ 章程红线：禁止静默降级。号码没设就明确说出来。
            Log.w(TAG, "到达自动拨号时间，但紧急号码未设置")
            onStatusChanged("⚠️ 无人解除，但紧急号码未设置 —— 请到设置里填写")
        } else {
            Log.i(TAG, "到达自动拨号时间，拨打 $currentPhone")
            onStatusChanged("已自动拨打 $currentPhone")
            onEscalateDial(currentPhone)
        }
    }

    // ============================================================
    //  开始报警
    // ============================================================

    /**
     * 启动强制报警。会一直响到 [stop] 被调用为止。
     *
     * @param phone             紧急联系号码（可为空，为空时到点会明确提示）
     * @param dialDelaySeconds  多久没人解除就自动拨号
     */
    fun start(phone: String, dialDelaySeconds: Int) {
        if (isRinging) return
        isRinging = true
        escalated = false
        currentPhone = phone.trim()

        Log.i(TAG, "🔔 开始报警（${dialDelaySeconds}s 后自动拨号：'$currentPhone'）")
        onStatusChanged("报警中")

        startSound()
        startVibration()
        startSpeech()

        // 升级：到点自动拨号
        uiHandler.removeCallbacks(escalateRunnable)
        uiHandler.postDelayed(escalateRunnable, dialDelaySeconds * 1000L)
    }

    /** ① 声音：内置警报音，走闹钟通道，强制最大音量，循环播放 */
    private fun startSound() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // 记下原音量，结束后还原 —— 不做"偷偷改用户设置"这种事
            if (originalAlarmVolume < 0) {
                originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            }
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

            val attributes = AudioAttributes.Builder()
                // USAGE_ALARM：静音也能响，默认能穿透勿扰。
                // 这是整段报警逻辑里最关键的一行 —— 它决定了"静音模式下会不会响"。
                //
                // 注：这里刻意不设 content type。AudioAttributes.CONTENT_TYPE_SONIC
                // 虽然语义上最贴切，但它在编译用的 SDK 里不可见（隐藏 API），
                // 设了会编译不过。content type 对行为影响很小，USAGE 才是决定性的。
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            context.resources.openRawResourceFd(R.raw.alarm_loop)?.use { afd ->
                player = MediaPlayer().apply {
                    setAudioAttributes(attributes)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    isLooping = true
                    prepare()
                    start()
                }
                soundStarted = true
            }
        } catch (t: Throwable) {
            // 声音起不来也绝不能中断报警：震动和语音还在，而且要让用户知道
            Log.e(TAG, "报警声音启动失败", t)
            onStatusChanged("⚠️ 铃声启动失败：${t.message}")
        }
    }

    /** ② 震动：波形循环。标记成闹钟用途，勿扰下也能震。 */
    private fun startVibration() {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager =
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, VIBRATION_REPEAT_FROM)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13 起推荐用 VibrationAttributes，标记成"闹钟用途"才能穿透勿扰。
                // 仍然用老的 AudioAttributes 重载也能跑，但语义上不准确，可能被勿扰拦掉。
                vibrator?.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(
                    effect,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                )
            }
            vibrationStarted = true
        } catch (t: Throwable) {
            Log.e(TAG, "震动启动失败", t)
        }
    }

    /**
     * ③ 中文语音循环播报。
     *
     * ⚠️⚠️ 这里有一个真机上踩到的关键坑（2026-10，荣耀 200）⚠️⚠️
     *
     *   TTS 默认走【媒体】音频通道，而警报声走【闹钟】通道。
     *   值班时媒体音量常常被调得很低甚至静音，结果就是：
     *   **"警报声震天响，但完全听不到人说话"** —— 语音这一条通道等于白做了。
     *
     *   日志证据：系统的 TextToSpeechManagerPerUserService 明明显示
     *   "Connected successfully to TTS engine"，语音确实播了，就是听不见。
     *
     *   修法：用 setAudioAttributes 把 TTS 也挂到 USAGE_ALARM 上，
     *   这样它和警报声走同一条通道、同一个音量、同样能穿透勿扰。
     *
     *   另外：播报时把警报音压低，否则两个声音叠在一起听不清说的是什么。
     */
    private fun startSpeech() {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    ttsError = "初始化失败$status"
                    Log.w(TAG, "TTS 初始化失败（状态 $status），语音播报不可用")
                    return@TextToSpeech
                }
                applyAlarmAudioAttributes()

                val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    ttsError = "缺中文语音包"
                    Log.w(TAG, "设备缺少中文 TTS 语音包，语音播报不可用")
                    return@TextToSpeech
                }
                ttsReady = true
                Log.i(TAG, "中文语音播报就绪")
                speak()
            }
            applyAlarmAudioAttributes()
            attachSpeechListener()
        } catch (t: Throwable) {
            Log.e(TAG, "TTS 启动失败", t)
        }

        uiHandler.removeCallbacks(speakRunnable)
        // 第一次播报早一点，免得报警被很快解除时一句话都没说出口
        uiHandler.postDelayed(speakRunnable, FIRST_SPEAK_DELAY_MS)
    }

    /** 把 TTS 挂到闹钟音频通道 —— 这是"语音能不能被听见"的决定性一步。 */
    private fun applyAlarmAudioAttributes() {
        try {
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "设置 TTS 音频通道失败，语音可能走媒体音量", t)
        }
    }

    /** 监听播报进度：用来压低警报音，并且在日志里留下可查证的痕迹 */
    private fun attachSpeechListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.i(TAG, "语音播报：开始")
                // 压低警报音，让说话声听得清
                player?.setVolume(DUCK_VOLUME, DUCK_VOLUME)
            }

            override fun onDone(utteranceId: String?) {
                Log.i(TAG, "语音播报：结束")
                player?.setVolume(1f, 1f)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "语音播报出错")
                player?.setVolume(1f, 1f)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "语音播报出错，错误码 $errorCode")
                player?.setVolume(1f, 1f)
            }
        })
    }

    private fun speak() {
        if (!isRinging || !ttsReady) return
        tts?.speak(
            "有车辆进入，请到岗查看",
            TextToSpeech.QUEUE_FLUSH,       // 每次都打断上一条，保证听到的是最新一次
            null,
            "gas_guard_alarm"
        )
    }

    // ============================================================
    //  停止报警
    // ============================================================

    /** 解除报警。由"已到岗"长按按钮或其它流程调用。 */
    fun stop() {
        if (!isRinging) return
        isRinging = false
        Log.i(TAG, "报警已解除")

        uiHandler.removeCallbacks(speakRunnable)
        uiHandler.removeCallbacks(escalateRunnable)

        // 停声音
        try {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "停止铃声时出错", t)
        }
        player = null

        // 还原闹钟音量
        try {
            if (originalAlarmVolume >= 0) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(
                    AudioManager.STREAM_ALARM, originalAlarmVolume, 0
                )
                originalAlarmVolume = -1
            }
        } catch (t: Throwable) {
            Log.w(TAG, "还原闹钟音量时出错", t)
        }

        // 停震动
        try {
            vibrator?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "停止震动时出错", t)
        }
        vibrator = null

        // 停语音
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "关闭 TTS 时出错", t)
        }
        tts = null
        ttsReady = false

        onStatusChanged("待机")
    }

    /** 是否已经触发过自动拨号（界面用来显示"已拨号"） */
    fun hasEscalated(): Boolean = escalated

    /**
     * 三条报警通道的实际状态，显示在报警界面上。
     *
     * 为什么要在屏幕上显示：这台手机的 ROM 会把应用日志冲掉，
     * 排查"语音到底有没有播"时 logcat 完全不可靠。
     * 画在屏幕上，截图就是证据。
     */
    fun diagnosticSummary(): String {
        val sound = if (soundStarted) "铃声✓" else "铃声✗"
        val vibration = if (vibrationStarted) "震动✓" else "震动✗"
        val speech = when {
            ttsError != null -> "语音✗($ttsError)"
            ttsReady -> "语音✓播报中"
            else -> "语音初始化中"
        }
        return "$sound　$vibration　$speech"
    }
}
