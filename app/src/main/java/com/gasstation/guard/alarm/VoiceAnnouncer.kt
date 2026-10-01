package com.gasstation.guard.alarm

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 一次性语音播报（用于温度警告这类提示）。
 *
 * 和 AlarmController 里的 TTS 分开是有意的：
 *   AlarmController 的 TTS 是报警的一部分（循环播报、随之启动和销毁）；
 *   这个是**偶尔说一句话**，生命周期和使用场景都不一样，
 *   混在一起会让报警那段本来就很关键的代码变复杂。
 *
 * ⚠️ 同样挂到【闹钟】音频通道：
 *    TTS 默认走媒体音量，值班时媒体音量常常很低甚至静音，
 *    那样警告就白播了 —— M3 阶段在报警语音上踩过一模一样的坑。
 */
class VoiceAnnouncer(private val context: Context) {

    companion object {
        private const val TAG = "GasGuard"
    }

    private var tts: TextToSpeech? = null
    private var ready = false

    /** 初始化还没完成时先把要说的话记下来，就绪后补播 */
    private var pendingText: String? = null

    private fun ensureInit() {
        if (tts != null) return
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "语音播报初始化失败（状态 $status）")
                    return@TextToSpeech
                }
                val t = tts ?: return@TextToSpeech
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                val result = t.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.w(TAG, "设备缺少中文语音包，无法播报")
                    return@TextToSpeech
                }
                ready = true
                pendingText?.let {
                    pendingText = null
                    speak(it)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "创建语音播报器失败", t)
        }
    }

    /** 播报一句话。初始化未完成时会等就绪后再播。 */
    fun say(text: String) {
        ensureInit()
        if (ready) {
            speak(text)
        } else {
            pendingText = text
        }
    }

    private fun speak(text: String) {
        try {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "gas_guard_announce")
            Log.i(TAG, "语音播报：$text")
        } catch (t: Throwable) {
            Log.w(TAG, "语音播报失败", t)
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "关闭语音播报失败", t)
        }
        tts = null
        ready = false
        pendingText = null
    }
}
