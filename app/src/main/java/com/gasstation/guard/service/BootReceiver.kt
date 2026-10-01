package com.gasstation.guard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启接收器。
 *
 * ============================================================
 *  ⚠️ 为什么这里只启动【服务】，不直接启动【界面】
 * ============================================================
 *
 *  Android 15 起，禁止从 BOOT_COMPLETED 启动 camera 类型的前台服务。
 *  本项目 targetSdk = 36，这条限制直接生效。
 *
 *  所以这里启动的是 specialUse 类型的 MonitorService（不在限制名单里），
 *  再由服务去看门狗式地把值守界面拉起来。
 *  多绕一层，但这是目前唯一能同时满足"开机自启"和"相机可用"的路径。
 *
 *  ⚠️ 另外：很多国产 ROM（包括荣耀 MagicOS）默认【禁止】应用开机自启，
 *     需要在系统设置里手动允许。见 docs/M4-操作手册.md 里的系统设置引导。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GasGuard"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "收到广播：$action")

        // 开机完成、以及部分 ROM 的"快速启动"广播，都作为启动信号
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            try {
                MonitorService.start(context)
                Log.i(TAG, "已请求启动值守服务")
            } catch (t: Throwable) {
                Log.e(TAG, "开机启动值守服务失败", t)
            }
        }
    }
}
