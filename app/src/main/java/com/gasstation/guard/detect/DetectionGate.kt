package com.gasstation.guard.detect

/**
 * ============================================================
 *  多帧确认闸门 —— M3 控制误报的核心
 * ============================================================
 *
 *  为什么需要它：
 *    单帧检测一定会抖 —— 同样一辆车，这一帧检出、下一帧漏检是常态
 *    （夜间、遮挡、角度都会影响）。如果看到一帧就报警，误报会多到无法使用。
 *
 *  做法：
 *    维护一个长度为 [windowSize] 的滑动窗口，记录最近每帧"监测区域内有没有车"。
 *    窗口里命中帧数 ≥ [requiredHits] 才判定为"确认有车"。
 *    一辆车开进加油站会停留好几秒，按 2fps 算就是十几帧，
 *    只要其中几帧检出就能确认 —— 单帧漏检被时间维度的冗余吸收掉了。
 *
 *  默认参数：窗口 8 帧（≈4 秒）、需命中 3 帧。M6 会做成可调。
 *
 *  ⚠️ 关于"报警解除后何时重新武装"（re-arm）—— 这是一个刻意做的取舍：
 *
 *    如果解除后立刻重新武装，那么"车还停在原地"会让报警每 4 秒重复一次，
 *    误报率会直接爆表，必然通不过「误报 ≤ 1 次/小时」的验收。
 *
 *    所以这里要求：必须先出现 [clearFramesToRearm] 帧"区域内没车"，才重新武装。
 *    副作用：如果一辆车还停着、第二辆车又开进来，这期间不会再次报警。
 *
 *    我选择接受这个副作用，理由是：**报警的目的是把人叫醒**。
 *    值班人员按下"已到岗"说明人已经醒了、已经在处理，
 *    此时第二辆车带来的风险远低于"因为误报刷屏导致这个工具被弃用"的风险。
 *
 *    ⚠️ 这是一个已知缺口，已记入已知问题清单，M6 应引入目标跟踪（给每辆车一个 ID）
 *       来彻底解决，而不是靠这种时序技巧。
 */
class DetectionGate(
    private val windowSize: Int = DEFAULT_WINDOW,
    private val requiredHits: Int = DEFAULT_REQUIRED_HITS,
    private val clearFramesToRearm: Int = DEFAULT_CLEAR_FRAMES
) {

    /** 最近若干帧的命中情况，true = 该帧在监测区域内检出了车 */
    private val history = ArrayDeque<Boolean>(windowSize)

    /** 连续多少帧"区域内无车" */
    private var clearStreak = 0

    /** 是否处于"可触发"状态。触发一次后变 false，直到重新武装。 */
    private var armed = true

    /**
     * 送入一帧的判定结果。
     *
     * @param hit 这一帧在监测区域内是否检出了车
     * @return true 表示"确认有车"，调用方应当立即启动报警
     */
    fun offer(hit: Boolean): Boolean {
        if (hit) {
            clearStreak = 0
        } else {
            clearStreak++
            // 画面已经干净足够久，重新武装
            if (!armed && clearStreak >= clearFramesToRearm) {
                armed = true
                history.clear()
            }
        }

        history.addLast(hit)
        while (history.size > windowSize) history.removeFirst()

        if (armed && history.count { it } >= requiredHits) {
            // 触发后立刻解除武装，避免同一辆车连续触发多次
            armed = false
            return true
        }
        return false
    }

    /** 报警被解除时调用：清空历史，等画面干净后再重新武装 */
    fun onAlarmDismissed() {
        history.clear()
        clearStreak = 0
        armed = false
    }

    /** 完全重置（例如相机重连后） */
    fun reset() {
        history.clear()
        clearStreak = 0
        armed = true
    }

    /** 给诊断条用的一句话状态 */
    fun describe(): String {
        val hits = history.count { it }
        return "$hits/${history.size} 帧命中" + if (armed) "" else "（已解除，待画面清空）"
    }

    companion object {
        /** 窗口 8 帧。按 2fps 约 4 秒。 */
        const val DEFAULT_WINDOW = 8

        /** 窗口内需命中 3 帧 */
        const val DEFAULT_REQUIRED_HITS = 3

        /** 连续 3 帧无车才重新武装（约 1.5 秒） */
        const val DEFAULT_CLEAR_FRAMES = 3
    }
}

/**
 * 判断一个检测框是否落在监测区域内。
 *
 * 判定标准：**检测框的中心点**落在 ROI 矩形内。
 *
 * 为什么用中心点而不是"有没有重叠"：
 *   - 重叠判定会让"半个车身在区域边缘"也算命中，误报明显更多
 *   - 中心点判定更好解释，出问题时也更容易和你一起复现
 *
 * ⚠️ 坐标必须是【同一个参照系】：
 *   ROI 存的是归一化坐标（相对摆正后的画面），Detection 也是归一化坐标，
 *   两边都是 0~1，所以可以直接比较，不需要知道分辨率。
 */
fun Detection.isInsideRoi(roi: RoiRect): Boolean =
    centerX >= roi.left && centerX <= roi.right &&
        centerY >= roi.top && centerY <= roi.bottom

/** 监测区域，归一化坐标 0~1 */
data class RoiRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    /** 是否是一个有效（面积不为零）的区域 */
    val isValid: Boolean get() = width > 0.01f && height > 0.01f

    companion object {
        /** 整幅画面 */
        val FULL = RoiRect(0f, 0f, 1f, 1f)
    }
}
