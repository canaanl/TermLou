package com.workspace.proot

import kotlin.math.max
import kotlin.math.min

/**
 * opencode v2 工作进度条（`work_spinner`）的帧序列，逐帧照搬。
 *
 * 源：`anomalyco/opencode` 的 `packages/tui/src/ui/spinner.ts` 与
 * `packages/opencode/src/cli/cmd/run/footer.view.tsx`。页脚实际调用是
 *
 * ```
 * createFrames({ color: theme().highlight, style: "blocks", inactiveFactor: 0.6, minAlpha: 0.3 })
 * <spinner color={spin().color} frames={spin().frames} interval={40} />
 * ```
 *
 * 对应函数：[getScannerState] → [activePosition]/[isHolding]/[holdProgress]/[holdTotal]/
 * [movementProgress]/[movementTotal]，[calculateColorIndex] → [trailIndex]，
 * [deriveTrailColors] → [trailAlpha]，[createKnightRiderTrail] → [fadeFactor]。
 *
 * 抽成纯函数是为了能在 JVM 单测里把 54 帧一帧一帧锁住 —— 视觉必须和原版完全一致，
 * 一个参数都不许"优化"。
 */
object OpencodeRider {

    /** 单元宽度（字符格）。 */
    const val WIDTH = 8

    /** 拖尾级数。 */
    const val TRAIL_STEPS = 6

    /** 走到右端后的停留帧数。 */
    const val HOLD_END = 9

    /** 回到左端后的停留帧数。 */
    const val HOLD_START = 30

    /** 页脚的 `<spinner interval={40} />`。 */
    const val INTERVAL_MS = 40L

    /** 未激活格的 alpha（页脚传的 `inactiveFactor`）。 */
    const val INACTIVE_FACTOR = 0.6f

    /** 淡入淡出下限（页脚传的 `minAlpha`）。 */
    const val MIN_ALPHA = 0.3f

    /** 第二级拖尾的泛光提亮系数（`deriveTrailColors` 里的 `brightnessFactor`）。 */
    const val GLARE_BOOST = 1.15f

    /** 激活格字符 `■` U+25A0（原版用字符，这里只留档说明视觉来源）。 */
    const val ACTIVE_GLYPH = '■'

    /** 未激活格字符 `⬝` U+2B1D。 */
    const val IDLE_GLYPH = '⬝'

    /** 一个来回的总帧数：右移 + 右停 + 左移 + 左停。 */
    val TOTAL_FRAMES = WIDTH + HOLD_END + (WIDTH - 1) + HOLD_START      // 54

    private const val FORWARD_END = WIDTH                               // 8：右移结束
    private const val HOLD_END_END = FORWARD_END + HOLD_END             // 17：右端停留结束
    private const val BACKWARD_END = HOLD_END_END + (WIDTH - 1)         // 24：左移结束

    /** 该帧扫描头所在的格号。 */
    fun activePosition(frame: Int): Int = when {
        frame < FORWARD_END -> frame
        frame < HOLD_END_END -> WIDTH - 1
        frame < BACKWARD_END -> WIDTH - 2 - (frame - HOLD_END_END)
        else -> 0
    }

    /** 该帧是否处于两端停留（8..16 与 24..53）。⚠ 回头的 7 帧**不算**停留。 */
    fun isHolding(frame: Int): Boolean =
        frame in FORWARD_END until HOLD_END_END || frame >= BACKWARD_END

    /** 移动方向：true = 向右（右端停留沿用最后方向）。 */
    fun isMovingForward(frame: Int): Boolean = frame < HOLD_END_END

    /** 停留段的第几帧（0 基）。移动段为 0。 */
    fun holdProgress(frame: Int): Int = when {
        frame < HOLD_END_END -> (frame - FORWARD_END).coerceAtLeast(0)
        frame >= BACKWARD_END -> frame - BACKWARD_END
        else -> 0
    }

    /** 停留段总帧数：右端 9、左端 30。移动段为 0。 */
    fun holdTotal(frame: Int): Int = when {
        frame < HOLD_END_END -> HOLD_END
        frame >= BACKWARD_END -> HOLD_START
        else -> 0
    }

    /** 移动段的第几帧（0 基）。停留段为 0。 */
    fun movementProgress(frame: Int): Int = when {
        frame < FORWARD_END -> frame
        frame in HOLD_END_END until BACKWARD_END -> frame - HOLD_END_END
        else -> 0
    }

    /** 移动段总帧数：右移 8、左移 7。停留段为 0。 */
    fun movementTotal(frame: Int): Int = when {
        frame < FORWARD_END -> FORWARD_END
        frame in HOLD_END_END until BACKWARD_END -> WIDTH - 1
        else -> 0
    }

    /**
     * 某一格在该帧的拖尾级号；`-1` = 未激活格（画暗色轨道点）。
     *
     * 移动时：头最亮（0 级），身后 1..5 级递减；头前方一律未激活。
     * 停留时：整条拖尾按停留帧数后移，于是越停越暗，最终**全部跌出级数变暗点** ——
     * 这是 opencode 的原味，不是 bug。
     */
    fun trailIndex(frame: Int, charIndex: Int): Int {
        val head = activePosition(frame)
        val distance = if (isMovingForward(frame)) head - charIndex else charIndex - head
        if (isHolding(frame)) {
            val shifted = distance + holdProgress(frame)
            return if (shifted in 0 until TRAIL_STEPS) shifted else -1
        }
        return when {
            distance in 1 until TRAIL_STEPS -> distance
            distance == 0 -> 0
            else -> -1
        }
    }

    /** 第 [step] 级拖尾的 alpha（`deriveTrailColors`：1.0 / 0.9 / 0.65 指数衰减）。 */
    fun trailAlpha(step: Int): Float = when {
        step <= 0 -> 1f
        step == 1 -> 0.9f
        else -> Math.pow(0.65, (step - 1).toDouble()).toFloat()
    }

    /** 未激活格该帧的 alpha = `inactiveFactor × fadeFactor`。 */
    fun inactiveAlpha(frame: Int): Float = INACTIVE_FACTOR * fadeFactor(frame)

    /**
     * 淡入淡出系数：移动时从 [MIN_ALPHA] 升到 1.0，两端停留时从 1.0 落回 [MIN_ALPHA]。
     * 让"停下来"这件事本身也看得出来。
     */
    fun fadeFactor(frame: Int): Float {
        val hold = holdTotal(frame)
        if (isHolding(frame) && hold > 0) {
            val p = min(holdProgress(frame).toFloat() / hold, 1f)
            return max(MIN_ALPHA, 1f - p * (1f - MIN_ALPHA))
        }
        val movement = movementTotal(frame)
        if (!isHolding(frame) && movement > 0) {
            val p = min(movementProgress(frame).toFloat() / max(1, movement - 1), 1f)
            return MIN_ALPHA + p * (1f - MIN_ALPHA)
        }
        return 1f
    }

    /**
     * 该帧**有没有亮块**。
     *
     * opencode 的拖尾只有 [TRAIL_STEPS] 级，而两端停留分别有 9 帧和 30 帧 ——
     * 停留到第 6 帧后整条拖尾跌出级数，只剩极暗的轨道点。
     * 算下来：右端 3 帧 + 左端 24 帧 = **每 54 帧里有 27 帧（一半）没有亮块**。
     *
     * 用户明确选了"一模一样"，所以**保留**这个行为，不去压缩停留帧数。
     */
    fun hasBrightBlock(frame: Int): Boolean =
        (0 until WIDTH).any { trailIndex(frame, it) >= 0 }
}
