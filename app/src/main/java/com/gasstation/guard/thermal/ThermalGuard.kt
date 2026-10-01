package com.gasstation.guard.thermal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * ============================================================
 *  电池温度保护 —— M6
 * ============================================================
 *
 *  ---------- 为什么需要它 ----------
 *
 *  这台手机要**插着电、屏幕常亮、相机常开、每秒跑两次模型**，一跑就是两三个小时。
 *  这是持续高负载，电池发热是必然的。
 *
 *  锂电长期高温会不可逆地鼓包、掉容量，严重时**有安全风险**。
 *  这是一台放在值班室、没人在旁边看着的设备 —— 不能让它自己烧起来。
 *
 *  所以策略是分两级降载：
 *
 *      45°C  →  降载（把识别频率从 2fps 降到约 0.7fps，发热明显下降）
 *      50°C  →  暂停识别 + 语音警告（保住报警能力以外的都让路）
 *
 *  ---------- ⚠️ 关于"暂停"这个决定的取舍 ----------
 *
 *  温度到 50°C 时暂停识别，意味着**这段时间没有车辆检测，会漏报**。
 *  这是章程里"任何可能导致漏报的设计必须显式标红"要管的事，所以写清楚：
 *
 *    我选择暂停，理由是：
 *      ① 50°C 的电池温度已经进入危险区，继续全速跑是在拿设备安全赌
 *      ② 暂停时会**用语音明确播报**，值班的人知道"现在没在监视"
 *      ③ 暂停是**自动恢复**的 —— 降到 47°C 以下就自己继续
 *      ④ 真正不可接受的是"悄悄不工作"；这里是"大声告诉你我不工作了"
 *
 *    如果实测发现夏天经常触发 50°C，正确的做法是改善散热
 *    （别放太阳直射处、垫高留出风道），而不是把阈值调高。
 */
class ThermalGuard(private val context: Context) {

    companion object {
        private const val TAG = "GasGuard"

        /** 降载阈值（摄氏度）—— 章程规定 45 */
        const val DEFAULT_THROTTLE_C = 45f

        /** 暂停阈值（摄氏度）—— 章程规定 50 */
        const val DEFAULT_PAUSE_C = 50f

        /**
         * 回差。
         *
         * 没有回差的话，温度在阈值上下抖动会导致状态反复横跳：
         * 降载→恢复→降载→恢复…… 每次恢复都会拉满 CPU，反而更热，形成振荡。
         * 有了回差，必须明显降下来才恢复。
         */
        private const val HYSTERESIS_C = 3f
    }

    enum class Level {
        /** 正常 */
        NORMAL,

        /** 降载：降低识别频率 */
        THROTTLED,

        /** 暂停：停止识别，语音警告 */
        PAUSED
    }

    /** 降载阈值，可在设置里调 */
    var throttleC: Float = DEFAULT_THROTTLE_C

    /** 暂停阈值，可在设置里调 */
    var pauseC: Float = DEFAULT_PAUSE_C

    private var lastLevel = Level.NORMAL

    /** 最近一次读到的温度 */
    var lastTemperatureC: Float = Float.NaN
        private set

    /**
     * 读当前电池温度。
     *
     * 通过注册一个 null receiver 拿 ACTION_BATTERY_CHANGED 的粘性广播 ——
     * 这是读取电池温度最省事的官方途径，不需要常驻广播接收器。
     *
     * @return 摄氏度；读不到返回 null
     */
    fun readTemperatureC(): Float? = try {
        val intent: Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val tenths = intent?.getIntExtra(
            BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE
        ) ?: Int.MIN_VALUE
        if (tenths == Int.MIN_VALUE) null else tenths / 10f
    } catch (t: Throwable) {
        Log.w(TAG, "读取电池温度失败", t)
        null
    }

    /**
     * 用最新温度更新状态。
     *
     * @return 当前应该工作的档位。**只有状态发生变化时**调用方才会收到通知，
     *         所以这里返回 Level，由调用方负责比对上一次的值。
     */
    fun update(): Level {
        val temp = readTemperatureC()
        if (temp == null) {
            // 读不到温度就不要自作主张降载 —— 那是"因为测不到所以不工作"，
            // 反而成了漏报的理由。保持原状并记录。
            lastTemperatureC = Float.NaN
            return lastLevel
        }
        lastTemperatureC = temp

        val previous = lastLevel
        lastLevel = when (previous) {
            Level.NORMAL ->
                if (temp >= pauseC) Level.PAUSED
                else if (temp >= throttleC) Level.THROTTLED
                else Level.NORMAL

            Level.THROTTLED ->
                if (temp >= pauseC) Level.PAUSED
                else if (temp <= throttleC - HYSTERESIS_C) Level.NORMAL
                else Level.THROTTLED

            Level.PAUSED ->
                if (temp <= pauseC - HYSTERESIS_C) Level.THROTTLED
                else Level.PAUSED
        }

        if (lastLevel != previous) {
            Log.w(TAG, "温度状态变化：$previous → $lastLevel（当前 ${temp}°C）")
        }
        return lastLevel
    }

    /** 强制重置为正常（例如用户手动清除了警告） */
    fun reset() {
        lastLevel = Level.NORMAL
    }

    /**
     * 当前档位下应该多久跑一次识别。
     *
     * 返回 Long.MAX_VALUE 表示"不跑"。
     */
    fun detectIntervalMs(level: Level, normalIntervalMs: Long): Long = when (level) {
        Level.NORMAL -> normalIntervalMs
        // 降载：频率降到约 1/3。发热主要来自 CPU 持续跑模型，这一步效果立竿见影。
        Level.THROTTLED -> normalIntervalMs * 3
        Level.PAUSED -> Long.MAX_VALUE
    }

    /** 给诊断条用的一句话 */
    fun describe(level: Level): String {
        val tempText = if (lastTemperatureC.isNaN()) "测不到" else "${lastTemperatureC}°C"
        val levelText = when (level) {
            Level.NORMAL -> "正常"
            Level.THROTTLED -> "已降载"
            Level.PAUSED -> "已暂停识别"
        }
        return "$tempText $levelText"
    }
}
