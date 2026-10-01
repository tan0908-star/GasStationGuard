package com.gasstation.guard.evidence

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 告警证据的存储管理。
 *
 * ============================================================
 *  目录结构
 * ============================================================
 *
 *   <外部私有目录>/evidence/
 *       └── 20261001_193045/            ← 一次告警一个目录（用时间戳命名）
 *             ├── photo.jpg             ← 告警瞬间的照片（模型看到的那一帧）
 *             ├── clip.mp4              ← 告警前后的画面（2fps）
 *             └── info.txt              ← 这一次告警的详情（时间、检出类别、置信度）
 *
 *  ---------- 为什么放在 getExternalFilesDir ----------
 *
 *  这是应用的私有外部目录，有三个好处：
 *    ① 不需要任何存储权限（Android 10 起访问自己的私有目录无需授权）
 *    ② 用户可以拔线后用文件管理器看到，方便取证
 *    ③ 卸载应用时会被系统一起清掉，不留垃圾
 *
 *  ---------- 关于 7 天自动清理 ----------
 *
 *  章程规定保留 7 天。这个数字是权衡的结果：
 *    太短 → 出了事想回溯却发现录像已经没了
 *    太长 → 占满存储，而且一个加油站门口 7 天的录像也没什么价值
 *
 *  ⚠️ 清理【永远不会】删除"今天"的目录 —— 哪怕用户把手机时间调错了，
 *     也不能把刚录的证据当场删掉。
 */
class EvidenceStore(private val context: Context) {

    companion object {
        private const val TAG = "GasGuard"
        private const val DIR_NAME = "evidence"
        private const val TIMESTAMP_PATTERN = "yyyyMMdd_HHmmss"

        /** 章程规定的保留天数 */
        const val RETENTION_DAYS = 7
    }

    /** 证据根目录 */
    val rootDir: File
        get() = File(context.getExternalFilesDir(null), DIR_NAME).apply {
            if (!exists()) mkdirs()
        }

    /** 为一次新告警创建目录。用时间戳命名，方便人工按时间找。 */
    fun createSessionDir(timestamp: Long = System.currentTimeMillis()): File {
        val name = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).format(Date(timestamp))
        val dir = File(rootDir, name)
        if (!dir.exists()) dir.mkdirs()
        Log.i(TAG, "创建告警证据目录：${dir.absolutePath}")
        return dir
    }

    /**
     * 清理超过保留期的告警目录。
     *
     * @return 删掉的目录个数
     */
    fun cleanupExpired(retentionDays: Int = RETENTION_DAYS): Int {
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
        // 今天的目录无论如何都不删
        val todayStart = todayStartMillis()

        var removed = 0
        rootDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val parsed = parseTimestamp(dir.name)
            if (parsed == null) {
                Log.w(TAG, "目录名不是时间戳，跳过：${dir.name}")
                return@forEach
            }
            if (parsed >= todayStart) return@forEach   // 今天的绝不动
            if (parsed < cutoff) {
                val ok = dir.deleteRecursively()
                if (ok) {
                    removed++
                    Log.i(TAG, "已清理过期证据：${dir.name}")
                } else {
                    Log.w(TAG, "清理失败：${dir.name}")
                }
            }
        }
        if (removed > 0) Log.i(TAG, "本轮共清理 $removed 个过期告警目录")
        return removed
    }

    /** 已占用的空间（字节），显示在设置里让用户心里有数 */
    fun usedBytes(): Long = rootDir.walkBottomUp()
        .filter { it.isFile }
        .sumOf { it.length() }

    /** 已有多少次告警记录 */
    fun sessionCount(): Int = rootDir.listFiles()?.count { it.isDirectory } ?: 0

    /** 是否存在"今天"之外的旧数据（用于提示用户） */
    fun hasExpiredData(retentionDays: Int = RETENTION_DAYS): Boolean {
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
        val todayStart = todayStartMillis()
        return rootDir.listFiles()?.any { dir ->
            if (!dir.isDirectory) return@any false
            val parsed = parseTimestamp(dir.name) ?: return@any false
            parsed < cutoff && parsed < todayStart
        } ?: false
    }

    private fun parseTimestamp(name: String): Long? = try {
        SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).parse(name)?.time
    } catch (t: Throwable) {
        null
    }

    private fun todayStartMillis(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
