package com.workspace.proot

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 缝隙活动条的**运动与明暗**（视觉部分）。纯计算，不碰 Android 类。
 *
 * ## 焦点在哪
 *
 * 轨道 = 整个终端宽度（约 73 格）。焦点是一个点，沿轨道从左走到右，**匀速**。
 * 一趟 1.5s 是用户定的（原本 8 格单元内走一趟只要 320ms，但全宽有 73 格，
 * 照搬 320ms 会变成每帧跳 9 格的"快速扫过"，25fps 下看不出移动）。
 * 1.5s ÷ 73 格 ≈ 每帧 0.9 格，基本平滑。
 *
 * ## 为什么不按格号算
 *
 * 亮度按「格子中心到焦点的**连续距离**」算，焦点位置是浮点像素。
 * 这样速度多快都不会跳格 —— 位置可以是小数，格子的明暗由距离连续决定。
 * （opencode 原版是按格号取模的，那是 8 格小单元的做法。）
 *
 * ## 明暗为什么重新算
 *
 * 拖尾从 6 级拉到 18 级（用户要求 2~3 倍，取 3 倍）。opencode 的 6 级用 `0.65^(i-1)`，
 * 照搬到 18 级会衰减到 0.65^17 ≈ 0.0016 —— 后半截全看不见。
 * 所以改成**对数均分**：18 级从 1.0 平滑降到 [TRAIL_FLOOR]，每级差约 0.885。
 */
object ActivityTrail {

    /** 拖尾级数。opencode 是 6，用户要求拉长 2~3 倍 → 取 3 倍。 */
    const val TRAIL_STEPS = 18

    /** 拖尾末级的 alpha 下限（对数均分的终点）。 */
    const val TRAIL_FLOOR = 0.15f

    /** 一趟（一端到另一端）时长。 */
    const val SWEEP_MS = 1500f

    /** 走到右端后的停留。照 opencode：9 帧 × 40ms = 360ms。 */
    const val HOLD_RIGHT_MS = 360f

    /** 回到左端后的停留。照 opencode：30 帧 × 40ms = 1200ms。 */
    const val HOLD_LEFT_MS = 1200f

    /** 亮点的泛光提亮（opencode 的 `GLARE_BOOST`，落在焦点正后方那一格）。 */
    const val GLARE_BOOST = 1.15f

    /** 未激活格的 alpha 系数（opencode 的 `inactiveFactor`）。 */
    const val INACTIVE_FACTOR = 0.6f

    /** 淡入淡出下限（opencode 的 `minAlpha`）。 */
    const val MIN_ALPHA = 0.3f

    /** 一个完整来回的时长。 */
    val CYCLE_MS = SWEEP_MS + HOLD_RIGHT_MS + SWEEP_MS + HOLD_LEFT_MS

    /**
     * 某一格相对焦点的**有向距离**：正数 = 焦点身后（拖尾方向），负数 = 焦点前方。
     * 往复运动时焦点折返，拖尾方向跟着换边 —— 和 opencode 的 `isMovingForward` 一致。
     */
    fun signedDistance(focusCell: Float, cellCenterCell: Float, movingForward: Boolean): Float {
        val raw = cellCenterCell - focusCell
        return if (movingForward) -raw else raw
    }

    /**
     * 焦点在轨道上的位置（格坐标，浮点）。
     * `cells` 是格子总数，`elapsedInCycle` 是周期内的时间。
     */
    fun focusCell(cells: Int, elapsedInCycle: Float): Float {
        if (cells <= 1) return 0f
        val last = (cells - 1).toFloat()
        var t = elapsedInCycle
        // 往复：右扫 → 右停 → 左扫 → 左停
        if (t < SWEEP_MS) return t / SWEEP_MS * last
        t -= SWEEP_MS
        if (t < HOLD_RIGHT_MS) return last
        t -= HOLD_RIGHT_MS
        if (t < SWEEP_MS) return last - t / SWEEP_MS * last
        t -= SWEEP_MS
        if (t < HOLD_LEFT_MS) return 0f
        return 0f
    }

    /**
     * 运动方向：true = 向右。
     *
     * ⚠ 边界：右扫**和右端停留**都是向右（停留沿用最后方向），从左扫那一刻才换成向左。
     * 所以分界点是「右停结束」，不是 SWEEP_MS —— 早一帧换向会在右端停顿里让拖尾突然跳边。
     */
    fun isMovingForward(elapsedInCycle: Float): Boolean =
        elapsedInCycle < SWEEP_MS + HOLD_RIGHT_MS

    /** 是否处于两端停留（焦点不动）。 */
    fun isHolding(elapsedInCycle: Float): Boolean {
        val t = elapsedInCycle
        val afterRightHold = SWEEP_MS + HOLD_RIGHT_MS
        val afterLeftSweep = afterRightHold + SWEEP_MS
        return (t >= SWEEP_MS && t < afterRightHold) || t >= afterLeftSweep
    }

    /** 移动段的第几毫秒（用于淡入）。 */
    fun movementElapsed(elapsedInCycle: Float): Float {
        val t = elapsedInCycle
        val afterRightHold = SWEEP_MS + HOLD_RIGHT_MS
        val afterLeftSweep = afterRightHold + SWEEP_MS
        return when {
            t < SWEEP_MS -> t
            t < afterRightHold -> 0f                      // 右端停留
            t < afterLeftSweep -> t - afterRightHold       // 左扫
            else -> 0f                                    // 左端停留
        }
    }

    fun movementDuration(): Float = SWEEP_MS

    /** 停留段已过多少毫秒（用于淡出）。 */
    fun holdElapsed(elapsedInCycle: Float): Float {
        val t = elapsedInCycle
        val afterRightHold = SWEEP_MS + HOLD_RIGHT_MS
        val afterLeftSweep = afterRightHold + SWEEP_MS
        return when {
            t < afterRightHold -> t - SWEEP_MS
            t >= afterLeftSweep -> t - afterLeftSweep
            else -> 0f
        }
    }

    fun holdDuration(elapsedInCycle: Float): Float =
        if (elapsedInCycle < SWEEP_MS + HOLD_RIGHT_MS) HOLD_RIGHT_MS else HOLD_LEFT_MS

    /**
     * 第 [step] 级拖尾的 alpha。
     *
     * **对数均分**，不是 opencode 那个 `0.65^(i-1)`：18 级照搬会衰减到 0.0016，
     * 后半截拖尾全黑。改成 1.0 → [TRAIL_FLOOR] 等比，逐级差约 0.885。
     * 第 0 级最亮，第 1 级带泛光提亮（opencode 的 `GLARE_BOOST`）。
     */
    fun trailAlpha(step: Int): Float {
        if (step <= 0) return 1f
        if (step >= TRAIL_STEPS) return 0f
        val ratio = TRAIL_FLOOR.pow(1f / (TRAIL_STEPS - 1).toFloat())
        return ratio.pow(step.toFloat())
    }

    /** 某一格该帧的 alpha；`distance` 见 [signedDistance]，负数（焦点前方）返回 0。 */
    fun alphaAt(distance: Float, elapsedInCycle: Float): Float {
        if (distance < 0f) return 0f
        val steps = floor(distance).toInt()
        if (steps >= TRAIL_STEPS) return 0f
        val frac = distance - steps
        val a = trailAlpha(steps)
        val b = trailAlpha(steps + 1)
        // 同一格内按小数位置线性插值 → 焦点移动时没有档位感
        return a + (b - a) * frac
    }

    /**
     * 未激活格（拖尾之外的整条轨道）的 alpha。
     * 淡入淡出照 opencode：移动时 0.3→1.0，停留时 1.0→0.3。
     */
    fun trackAlpha(elapsedInCycle: Float): Float = INACTIVE_FACTOR * fadeFactor(elapsedInCycle)

    fun fadeFactor(elapsedInCycle: Float): Float {
        if (isHolding(elapsedInCycle)) {
            val total = holdDuration(elapsedInCycle)
            if (total > 0f) {
                val p = min(holdElapsed(elapsedInCycle) / total, 1f)
                return max(MIN_ALPHA, 1f - p * (1f - MIN_ALPHA))
            }
        }
        val total = movementDuration()
        val p = min(movementElapsed(elapsedInCycle) / max(1f, total - 1f), 1f)
        return MIN_ALPHA + p * (1f - MIN_ALPHA)
    }
}
