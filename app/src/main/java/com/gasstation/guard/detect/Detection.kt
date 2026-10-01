package com.gasstation.guard.detect

/**
 * 一次检测结果。
 *
 * 坐标全部是「相对摆正后画面的归一化坐标」（0.0 ~ 1.0）。
 * 用归一化坐标的好处：和分辨率、和屏幕缩放、和预览黑边都无关，
 * 画框时再按当前 View 的实际尺寸换算即可。
 */
data class Detection(
    /** 左边界，0.0 = 画面最左，1.0 = 画面最右 */
    val left: Float,
    /** 上边界 */
    val top: Float,
    /** 右边界 */
    val right: Float,
    /** 下边界 */
    val bottom: Float,
    /** COCO 类别索引 */
    val classId: Int,
    /** 中文标签，例如「汽车」 */
    val label: String,
    /** 置信度 0.0 ~ 1.0 */
    val confidence: Float
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val boxWidth: Float get() = right - left
    val boxHeight: Float get() = bottom - top
    val area: Float get() = boxWidth * boxHeight
}

/**
 * 一帧的完整检测输出。
 *
 * 带上画面尺寸是必须的：预览用 fitCenter 显示时会留黑边，
 * 浮层必须知道「画面本身」的宽高比才能算准每一帧的显示区域。
 * 注意这里给的是【摆正后】的尺寸 —— 相机传感器出来的图可能是转过 90° 的。
 */
data class DetectionResult(
    /** 摆正后的画面宽度（像素） */
    val imageWidth: Int,
    /** 摆正后的画面高度（像素） */
    val imageHeight: Int,
    /** 本帧检出的目标 */
    val detections: List<Detection>,
    /** 本帧推理耗时（毫秒），用于性能观察 */
    val inferenceMs: Long
) {
    val isEmpty: Boolean get() = detections.isEmpty()
}
