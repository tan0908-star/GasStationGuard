package com.gasstation.guard.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 外部看门狗 —— 最后一道防线。
 *
 * ============================================================
 *  为什么光有 START_STICKY 不够（真机实测数据）
 * ============================================================
 *
 *  在荣耀 200 / MagicOS 10 上实测 START_STICKY 的重启行为：
 *
 *      第 1 次崩溃 → 未恢复
 *      第 2 次崩溃 → 恢复
 *      第 3 次崩溃 → 未恢复
 *      第 4 次崩溃 → 恢复
 *
 *  **4 次里只成功了 2 次，约 50%。**
 *
 *  系统日志显示原因是荣耀自己的限制：
 *      mAllowStart_noBinding=PROC_STATE_TOP
 *  即"只有进程处于前台状态时才允许后台启动"。这是 MagicOS 的
 *  「应用启动管理」策略，属于系统设置层面的事，代码绕不过去
 *  （必须在系统设置里关掉"自动管理"并手动允许，见 M4 操作手册）。
 *
 *  但即使配置了系统设置，也不该把"恢复值守"全押在系统仁慈上 ——
 *  所以再加一层：
 *
 *  ---------- 这一层为什么可能更可靠 ----------
 *
 *  用 AlarmManager 布一个定时闹钟。闹钟到期时，**系统会主动去启动应用**
 *  来投递这个广播 —— 哪怕应用进程早就死了、被杀了十几次。
 *  这是 Android 投递闹钟的固有机制，不依赖应用自己还活着。
 *
 *  代价：恢复不是瞬时的，最长要等一个闹钟周期。所以它是
 *  START_STICKY（秒级恢复）的【补充】，不是替代。
 */
class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GasGuard"

        /** 外部看门狗的间隔。太长则恢复慢，太短则无谓耗电。 */
        const val INTERVAL_MS = 5 * 60 * 1000L

        /**
         * 解除布防。
         *
         * ⚠️ 用户主动"退出值守"时必须调用，否则 5 分钟后闹钟到期，
         *    系统会把应用重新拉起来 —— 用户以为退出了，其实没有，
         *    而且界面上没有任何提示。
         */
        fun disarm(context: Context) {
            try {
                val intent = Intent(context, WatchdogReceiver::class.java)
                val pending = PendingIntent.getBroadcast(
                    context,
                    3,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val alarmManager =
                    context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.cancel(pending)
                pending.cancel()
                Log.i(TAG, "外部看门狗已解除布防")
            } catch (t: Throwable) {
                Log.e(TAG, "解除外部看门狗失败", t)
            }
        }

        /** 布防（或重新布防）外部看门狗 */
        fun arm(context: Context) {
            try {
                val intent = Intent(context, WatchdogReceiver::class.java)
                val pending = PendingIntent.getBroadcast(
                    context,
                    3,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val alarmManager =
                    context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

                // setAndAllowWhileIdle：不需要精确闹钟权限，且在低电耗模式下也会触发。
                // 我们不需要精确到秒，只需要"一定会来"。
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + INTERVAL_MS,
                    pending
                )
                Log.i(TAG, "外部看门狗已布防，${INTERVAL_MS / 1000 / 60} 分钟后检查一次")
            } catch (t: Throwable) {
                Log.e(TAG, "布防外部看门狗失败", t)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.w(TAG, "外部看门狗触发：检查值守是否还在")

        // 启动服务。如果服务还活着，这次调用是无害的；
        // 如果服务已经死了，系统会因为这次广播而重新把应用进程拉起来。
        try {
            MonitorService.start(context)
        } catch (t: Throwable) {
            Log.e(TAG, "外部看门狗拉起服务失败", t)
        }

        // 广播是一次性的，必须重新布防，否则只会触发一次
        arm(context)
    }
}
