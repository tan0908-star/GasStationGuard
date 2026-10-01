package com.gasstation.guard.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gasstation.guard.MainActivity
import com.gasstation.guard.R

/**
 * ============================================================
 *  值守前台服务 —— M4 的核心
 * ============================================================
 *
 *  ---------- 它负责什么 ----------
 *
 *    ① 常驻通知：让系统知道"这个应用正在为用户做一件用户可见的事"，
 *       从而把进程优先级提到前台级别，大幅降低被低内存杀手干掉的概率
 *    ② 看门狗：值守界面每隔几秒发一次心跳；心跳断了就把它重新拉起来
 *    ③ 被划掉后自恢复：用户从"最近任务"里划掉时，安排一次自动重启
 *    ④ 开机自启的落点：BootReceiver 收到开机广播后启动本服务
 *
 *  ---------- ⚠️ 为什么相机不放在服务里 ----------
 *
 *    Android 15 起，**禁止从 BOOT_COMPLETED 启动 camera 类型的前台服务**。
 *    本项目 targetSdk = 36，这条限制直接生效 ——
 *    "开机 → 起一个带相机的前台服务"这条最直觉的路是走不通的。
 *
 *    所以拆成两层：
 *      服务用 specialUse 类型（不在限制名单里，开机可以正常启动）
 *      相机留在值守界面里（界面可见时访问相机天然合法）
 *      服务负责把界面一直拉回前台
 *
 *  ---------- ⚠️ 这需要「显示在其他应用上层」权限 ----------
 *
 *    Android 从 10 起限制后台启动界面。持有 SYSTEM_ALERT_WINDOW
 *    （设置里的"显示在其他应用上层"）是【唯一可靠】的豁免，
 *    否则服务拉起值守界面时会被系统静默拦下。
 *
 *    这个权限同时还让报警界面能盖在其它应用之上，一举两得。
 *    安装引导里必须让用户手动授予，代码里无法代劳。
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "GasGuard"

        private const val CHANNEL_ID = "gas_guard_monitor"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_HEARTBEAT = "com.gasstation.guard.HEARTBEAT"
        const val ACTION_START = "com.gasstation.guard.START"

        /** 看门狗的检查间隔 */
        private const val WATCHDOG_INTERVAL_MS = 15_000L

        /**
         * 超过这么久没收到界面心跳，就认为界面已经不在了。
         *
         * 设为心跳间隔的 3 倍左右：太短会因为一次卡顿就误判重启（画面闪一下），
         * 太长则界面挂了之后要等很久才恢复 —— 那段时间是**没有值守的**。
         */
        private const val ACTIVITY_TIMEOUT_MS = 45_000L

        /** 从外部启动本服务（Activity / BootReceiver 都用这个） */
        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 值守界面发心跳，告诉服务"我还活着" */
        fun heartbeat(context: Context) {
            try {
                val intent = Intent(context, MonitorService::class.java)
                    .setAction(ACTION_HEARTBEAT)
                context.startService(intent)
            } catch (t: Throwable) {
                // 心跳失败不能让界面崩掉
                Log.w(TAG, "发送心跳失败", t)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 最近一次收到界面心跳的时刻 */
    private var lastHeartbeatAt = 0L

    /** 是否已经进入前台状态 */
    private var foregroundStarted = false

    /** 看门狗：定期检查界面是否还在 */
    private val watchdog = object : Runnable {
        override fun run() {
            val silenceMs = SystemClock.elapsedRealtime() - lastHeartbeatAt
            if (silenceMs > ACTIVITY_TIMEOUT_MS) {
                Log.w(TAG, "看门狗：值守界面已 ${silenceMs / 1000} 秒没有心跳，尝试拉起")
                wakeUpActivity()
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MonitorService 创建")
        lastHeartbeatAt = SystemClock.elapsedRealtime()
        ensureForeground()

        // 外部看门狗：START_STICKY 在部分国产 ROM 上只有约 50% 的成功率
        // （真机实测数据，见 WatchdogReceiver 的注释），所以再加一层闹钟兜底。
        WatchdogReceiver.arm(this)

        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HEARTBEAT -> lastHeartbeatAt = SystemClock.elapsedRealtime()
            else -> Log.i(TAG, "MonitorService 收到启动请求：${intent?.action}")
        }
        ensureForeground()

        // START_STICKY：被系统杀掉后自动重建（不带原来的 Intent）
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户从"最近任务"里划掉了 App。
     *
     * 对一个无人值守的工具来说，这几乎一定是误操作（比如想划掉别的应用划错了），
     * 而后果是**整晚没有值守**。所以这里安排一次自动重启。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "App 被从最近任务划掉，安排 3 秒后自动重启值守")
        try {
            val restartIntent = Intent(applicationContext, MonitorService::class.java)
            val pending = PendingIntent.getService(
                this,
                1,
                restartIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(
                AlarmManager.RTC,
                System.currentTimeMillis() + 3_000L,
                pending
            )
        } catch (t: Throwable) {
            Log.e(TAG, "安排自动重启失败", t)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "MonitorService 被销毁")
        handler.removeCallbacksAndMessages(null)
    }

    // ============================================================
    //  前台通知
    // ============================================================

    private fun ensureForeground() {
        if (foregroundStarted) return
        createChannel()
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "值守状态",
            NotificationManager.IMPORTANCE_LOW   // 不发声：出声的只有报警
        ).apply {
            description = "显示车辆报警是否正在值守"
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // 点通知回到值守界面
        val contentIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("值守中")
            .setContentText("正在监视加油站入口，检测到车辆会强制报警")
            .setContentIntent(contentIntent)
            .setOngoing(true)              // 不可划掉：这是值守状态的凭证
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // ============================================================
    //  看门狗
    // ============================================================

    /**
     * 把值守界面拉回前台。
     *
     * ⚠️ 如果没授予「显示在其他应用上层」权限，这里会被系统静默拦下 ——
     *    日志里会明确写出来，不会假装成功。
     */
    private fun wakeUpActivity() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            startActivity(intent)
            Log.i(TAG, "已请求拉起值守界面")
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "拉起值守界面失败。多半是因为没有授予「显示在其他应用上层」权限 —— " +
                    "该权限是 Android 上后台启动界面的唯一可靠豁免。",
                t
            )
        }
    }
}
