package com.gasstation.guard.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用参数存储。
 *
 * M3 阶段先用 SharedPreferences 存起来，M6 会做一个「隐藏设置页」来正式编辑这些值。
 * 现在改参数的办法：改这里代码里的默认值，或者用调试入口。
 *
 * ⚠️ 紧急联系电话【不写死在代码里】是有意为之：
 *    这个仓库是公开的，写死号码等于把私人电话公开。所以它只存在手机本地。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ============================================================
    //  紧急联系电话
    // ============================================================

    /**
     * 报警 30 秒无人解除时要自动拨打的号码。
     *
     * 空字符串 = 还没设置。此时自动拨号会被【明确跳过并在屏幕上提示】，
     * 绝不静默跳过 —— 章程红线。
     */
    var emergencyPhone: String
        get() = prefs.getString(KEY_PHONE, DEFAULT_PHONE).orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_PHONE, value.trim()).apply()

    /** 报警后多久没被解除就自动拨号（秒） */
    var autoDialDelaySeconds: Int
        get() = prefs.getInt(KEY_DIAL_DELAY, DEFAULT_DIAL_DELAY_SECONDS)
        set(value) = prefs.edit().putInt(KEY_DIAL_DELAY, value.coerceIn(10, 300)).apply()

    /** 是否启用自动拨号 */
    var autoDialEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DIAL, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DIAL, value).apply()

    // ============================================================
    //  解除报警的长按时长
    // ============================================================

    /**
     * 按住「已到岗」多久才算解除（秒）。
     *
     * ⚠️ 这个值直接影响**会不会漏报**，不是普通的体验参数：
     *
     *   设得太短 → 报警时屏幕在闪、手机在震，人半梦半醒手忙脚乱，
     *              手掌擦过屏幕就解除了 —— 人还没真正醒，报警没了。
     *              **误触解除 = 漏报**，是项目章程里的头号红线。
     *
     *   设得太长 → 真到岗了却按不掉，人会烦躁，甚至直接强制关掉 App，
     *              那样更糟。
     *
     * 所以下限被硬性限制在 [MIN_HOLD_SECONDS]，默认 2.0 秒。
     */
    var dismissHoldSeconds: Float
        get() = prefs.getFloat(KEY_HOLD_SECONDS, DEFAULT_HOLD_SECONDS)
            .coerceIn(MIN_HOLD_SECONDS, MAX_HOLD_SECONDS)
        set(value) = prefs.edit()
            .putFloat(
                KEY_HOLD_SECONDS,
                value.coerceIn(MIN_HOLD_SECONDS, MAX_HOLD_SECONDS)
            )
            .apply()

    // ============================================================
    //  M6：识别与温控参数
    // ============================================================

    /**
     * 识别频率（毫秒/次）。
     *
     * ⚠️ 这一项直接决定"多久看一次画面"：
     *    调得太稀（比如 2000ms）会明显增加漏报风险 ——
     *    一辆快速开过的车可能只出现在一两帧里。
     *    默认 500ms（2fps）是经过权衡的。
     */
    var detectIntervalMs: Long
        get() = prefs.getLong(KEY_DETECT_INTERVAL, DEFAULT_DETECT_INTERVAL_MS)
            .coerceIn(MIN_DETECT_INTERVAL_MS, MAX_DETECT_INTERVAL_MS)
        set(value) = prefs.edit()
            .putLong(KEY_DETECT_INTERVAL, value.coerceIn(MIN_DETECT_INTERVAL_MS, MAX_DETECT_INTERVAL_MS))
            .apply()

    /**
     * 置信度阈值。
     *
     * ⚠️ 调低 = 更容易触发报警，但误报变多；
     *    调高 = 更少误报，但可能漏掉夜间模糊的车辆。
     *    M2 阶段实测 0.35 在白天表现良好。
     */
    var confidenceThreshold: Float
        get() = prefs.getFloat(KEY_CONF_THRESHOLD, DEFAULT_CONF).coerceIn(0.15f, 0.75f)
        set(value) = prefs.edit().putFloat(KEY_CONF_THRESHOLD, value.coerceIn(0.15f, 0.75f)).apply()

    /**
     * 报警音量（占闹钟最大音量的百分比）。
     *
     * 默认 100%。刻意允许调低，但界面上会提示风险 ——
     * 夜班场景下，音量每降一档，叫醒成功率就低一档。
     */
    var alarmVolumePercent: Int
        get() = prefs.getInt(KEY_ALARM_VOLUME, 100).coerceIn(30, 100)
        set(value) = prefs.edit().putInt(KEY_ALARM_VOLUME, value.coerceIn(30, 100)).apply()

    /** 降载温度阈值（°C） */
    var throttleTempC: Float
        get() = prefs.getFloat(KEY_THROTTLE_TEMP, 45f).coerceIn(38f, 60f)
        set(value) = prefs.edit().putFloat(KEY_THROTTLE_TEMP, value.coerceIn(38f, 60f)).apply()

    /** 暂停温度阈值（°C）。强制比降载阈值高，否则两级会重叠。 */
    var pauseTempC: Float
        get() = prefs.getFloat(KEY_PAUSE_TEMP, 50f).coerceIn(40f, 65f)
        set(value) = prefs.edit()
            .putFloat(KEY_PAUSE_TEMP, maxOf(value, throttleTempC + 2f).coerceIn(40f, 65f))
            .apply()

    // ============================================================
    //  监测区域 ROI（归一化坐标 0~1，相对【摆正后】的画面）
    // ============================================================

    /**
     * ROI 是否已经由用户设定过。
     * 没设过时默认是【整个画面】，也就是不限制区域 —— 这样至少不会漏报，
     * 但误报会更多。所以界面上会一直提示"建议框选监测区域"。
     */
    val hasCustomRoi: Boolean
        get() = prefs.getBoolean(KEY_ROI_SET, false)

    var roiLeft: Float
        get() = prefs.getFloat(KEY_ROI_L, 0f)
        private set(value) = prefs.edit().putFloat(KEY_ROI_L, value).apply()

    var roiTop: Float
        get() = prefs.getFloat(KEY_ROI_T, 0f)
        private set(value) = prefs.edit().putFloat(KEY_ROI_T, value).apply()

    var roiRight: Float
        get() = prefs.getFloat(KEY_ROI_R, 1f)
        private set(value) = prefs.edit().putFloat(KEY_ROI_R, value).apply()

    var roiBottom: Float
        get() = prefs.getFloat(KEY_ROI_B, 1f)
        private set(value) = prefs.edit().putFloat(KEY_ROI_B, value).apply()

    /** 保存 ROI。会自动做上下左右排序，防止用户反向拖拽导致区域为空。 */
    fun saveRoi(x1: Float, y1: Float, x2: Float, y2: Float) {
        roiLeft = minOf(x1, x2).coerceIn(0f, 1f)
        roiTop = minOf(y1, y2).coerceIn(0f, 1f)
        roiRight = maxOf(x1, x2).coerceIn(0f, 1f)
        roiBottom = maxOf(y1, y2).coerceIn(0f, 1f)
        prefs.edit().putBoolean(KEY_ROI_SET, true).apply()
    }

    /** 清除 ROI，恢复成整幅画面 */
    fun clearRoi() {
        prefs.edit()
            .putBoolean(KEY_ROI_SET, false)
            .putFloat(KEY_ROI_L, 0f).putFloat(KEY_ROI_T, 0f)
            .putFloat(KEY_ROI_R, 1f).putFloat(KEY_ROI_B, 1f)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "gas_guard_settings"

        private const val KEY_PHONE = "emergency_phone"
        private const val KEY_DIAL_DELAY = "auto_dial_delay_seconds"
        private const val KEY_AUTO_DIAL = "auto_dial_enabled"

        private const val KEY_HOLD_SECONDS = "dismiss_hold_seconds"

        /** 默认长按 2 秒 */
        private const val DEFAULT_HOLD_SECONDS = 2.0f

        /**
         * 长按时长的下限（秒）。
         * 低于 1 秒，报警时手掌/衣袖擦过屏幕就能解除 —— 那是漏报的直接来源，不允许。
         */
        const val MIN_HOLD_SECONDS = 1.0f

        /** 上限：再长就纯粹是折磨人了 */
        const val MAX_HOLD_SECONDS = 5.0f

        // ---------- M6 ----------
        private const val KEY_DETECT_INTERVAL = "detect_interval_ms"
        private const val KEY_CONF_THRESHOLD = "confidence_threshold"
        private const val KEY_ALARM_VOLUME = "alarm_volume_percent"
        private const val KEY_THROTTLE_TEMP = "throttle_temp_c"
        private const val KEY_PAUSE_TEMP = "pause_temp_c"

        /** 默认 500ms（2fps） */
        private const val DEFAULT_DETECT_INTERVAL_MS = 500L

        /** 识别频率的硬性下限：再快就是浪费电，模型也跑不过来 */
        const val MIN_DETECT_INTERVAL_MS = 300L

        /**
         * 识别频率的硬性上限：2000ms（0.5fps）。
         * 再稀就会明显漏车 —— 不提供这个选项。
         */
        const val MAX_DETECT_INTERVAL_MS = 2000L

        private const val DEFAULT_CONF = 0.35f

        private const val KEY_ROI_SET = "roi_set"
        private const val KEY_ROI_L = "roi_left"
        private const val KEY_ROI_T = "roi_top"
        private const val KEY_ROI_R = "roi_right"
        private const val KEY_ROI_B = "roi_bottom"

        /**
         * 默认号码留空，让自动拨号在未设置时【明确报错】而不是打给一个假号码。
         * 正式使用前必须在设置里填入真实号码。
         */
        private const val DEFAULT_PHONE = ""

        /** 章程定的 30 秒 */
        private const val DEFAULT_DIAL_DELAY_SECONDS = 30
    }
}
