package com.workspace.proot

import kotlin.math.max

/**
 * 设计器播放时间轴的纯计算部分。
 *
 * 运行时用单调时钟驱动相位；这里只负责把「有限动画真正什么时候结束」算对，
 * 避免 UI 再按最小 3 秒或无限增长的当前时间去画进度。
 */
internal object CanvasPlayback {

    fun hasInfinite(animations: List<CanvasAnimation>): Boolean = animations.any { it.repeat <= 0 }

    /** 延迟 + 多目标错峰 + 时长 × 次数；无限循环动画不参与结束时间。 */
    fun finiteEndMs(animations: List<CanvasAnimation>): Long {
        var end = 0L
        for (a in animations) {
            if (a.repeat <= 0) continue
            val cycles = a.repeat.coerceAtLeast(1)
            val targets = if (a.targets.isNotEmpty()) a.targets.size else 1
            val stagger = a.staggerMs.toLong() * (targets - 1).coerceAtLeast(0)
            end = max(end, a.delayMs.toLong() + stagger + a.durationMs.toLong() * cycles)
        }
        return end
    }
}
