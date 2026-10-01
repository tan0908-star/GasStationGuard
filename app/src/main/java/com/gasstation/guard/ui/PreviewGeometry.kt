package com.gasstation.guard.ui

import android.graphics.RectF
import kotlin.math.min

/**
 * 预览画面的几何计算 —— 全项目【唯一】的一份。
 *
 * ============================================================
 *  ⚠️ 为什么必须只有一个地方算这个
 * ============================================================
 *
 *  M2 已经用真机截图验证过：按下面这个公式算出来的白框，
 *  和预览画面的实际四边【误差只有 1 像素】。
 *
 *  现在有三处都要用到同一套映射：
 *    ① 画检测框      （DetectionOverlayView）
 *    ② 画监测区域    （RoiOverlayView）
 *    ③ 用户框选 ROI 时把触摸点换算成图像坐标
 *
 *  如果这三处各算各的，一旦有一个地方写错，就会出现
 *  "框画对了但 ROI 判定错位" 这种极难排查的问题 —— 而且是静默的。
 *  所以统一到这里。
 *
 *  ---------- 规则（fitCenter）----------
 *
 *  预览用的是 fitCenter：等比缩放 + 居中，多余部分留黑边。
 *
 *      scale  = min(视图宽/画面宽, 视图高/画面高)
 *      显示宽 = 画面宽 × scale     显示高 = 画面高 × scale
 *      偏移X  = (视图宽 - 显示宽) / 2    ← 这就是左边黑边的宽度
 *      偏移Y  = (视图高 - 显示高) / 2
 *
 *  如果将来把预览改成 fillCenter（铺满裁切），只需要改这一个函数。
 */
fun computeVideoRect(
    viewWidth: Int,
    viewHeight: Int,
    imageWidth: Int,
    imageHeight: Int
): RectF {
    if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
        return RectF()
    }
    val scale = min(
        viewWidth.toFloat() / imageWidth,
        viewHeight.toFloat() / imageHeight
    )
    val displayWidth = imageWidth * scale
    val displayHeight = imageHeight * scale
    val offsetX = (viewWidth - displayWidth) / 2f
    val offsetY = (viewHeight - displayHeight) / 2f
    return RectF(offsetX, offsetY, offsetX + displayWidth, offsetY + displayHeight)
}
