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
