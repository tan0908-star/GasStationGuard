package com.gasstation.guard.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * ============================================================
 *  值守就绪检查 —— M7
 * ============================================================
 *
 *  ---------- 为什么必须有这个 ----------
 *
 *  这个应用的"配置"不只在它自己里，还散落在**系统设置**里：
 *  权限、悬浮窗、电池白名单、后台启动管理、勿扰例外…
 *
 *  这些**每一项都可能被外部清空**：
 *    · 重装应用（Android Studio 点一次"运行"就可能做卸载重装）
 *    · 系统更新
 *    · 用户清理后台时误操作
 *    · 手机管家"一键优化"
 *
 *  而清空之后，应用**照常运行、界面照常显示"值守中"**，
 *  只是关键时刻不会报警、不会拨号、不会被自动拉起 ——
 *  **这是最危险的一种失效：看起来一切正常。**
 *
 *  所以启动时必须主动检查并**把缺的东西直接摆在用户面前**，
 *  而不是等他某天发现"车来了怎么没响"。
 */
class ReadinessChecker(
    private val context: Context,
    private val settings: SettingsStore
) {

    /** 缺项的处理方式。具体动作由 MainActivity 执行（它持有权限请求器和各种 Intent）。 */
    enum class Fix {
        CAMERA_PERM,
        CALL_PERM,
        NOTIFICATION_PERM,
        OVERLAY_PERM,
        BATTERY_OPT,
        PHONE_NUMBER,
        ROI,
        NONE
    }

    data class Item(
        val title: String,
        /** 为什么需要它 / 缺了会怎样 —— 必须写清楚后果，不能只说"请授权" */
        val why: String,
        val ok: Boolean,
        val fix: Fix
    )

    /**
     * 检查全部就绪项。
     *
     * 顺序是按"缺了后果有多严重"排的，不是按实现顺序。
     */
    fun check(): List<Item> = listOf(
        Item(
            title = "相机权限",
            why = "没有它就没有画面，等于完全没在值守",
            ok = hasPermission(Manifest.permission.CAMERA),
            fix = Fix.CAMERA_PERM
        ),
        Item(
            title = "紧急联系电话",
            why = "报警 30 秒无人解除时自动拨打。没填就不会拨号 —— " +
                "如果值班的人没被叫醒，你不会接到任何电话",
            ok = settings.emergencyPhone.isNotBlank(),
            fix = Fix.PHONE_NUMBER
        ),
        Item(
            title = "电话权限",
            why = "自动拨号需要它，缺了这条最后防线是空的",
            ok = hasPermission(Manifest.permission.CALL_PHONE),
            fix = Fix.CALL_PERM
        ),
        Item(
            title = "显示在其他应用上层",
            why = "Android 上【后台启动界面】的唯一可靠豁免。" +
                "缺了它，界面意外退出后看门狗拉不回来，重启也不会自动恢复值守",
            ok = Settings.canDrawOverlays(context),
            fix = Fix.OVERLAY_PERM
        ),
        Item(
            title = "通知权限",
            why = "前台服务的常驻通知靠它，部分手机会因为\"服务不可见\"而直接杀掉应用",
            ok = hasNotificationPermission(),
            fix = Fix.NOTIFICATION_PERM
        ),
        Item(
            title = "忽略电池优化",
            why = "否则省电时会被系统冻结，表现为\"好好地突然不工作了\"",
            ok = isIgnoringBatteryOptimizations(),
            fix = Fix.BATTERY_OPT
        ),
        Item(
            title = "监测区域",
            why = "没框选时整幅画面都算监测范围，马路上的过路车、灯光、树影都会触发误报",
            ok = settings.hasCustomRoi,
            fix = Fix.ROI
        )
    )

    /** 是否全部就绪 */
    fun isReady(items: List<Item> = check()): Boolean = items.all { it.ok }

    /** 缺失项数量 */
    fun missingCount(items: List<Item> = check()): Int = items.count { !it.ok }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true   // Android 13 以下没有这个运行时权限
        }

    private fun isIgnoringBatteryOptimizations(): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (t: Throwable) {
        // 查不到就当它是好的 —— 不能因为"测不出来"就报缺失，那会造成无谓的困扰
        true
    }
}
