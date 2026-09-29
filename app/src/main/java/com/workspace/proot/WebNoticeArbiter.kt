package com.workspace.proot

/**
 * status 栏「无头浏览器开着但还没用过」闪烁提示的**仲裁规则**（纯逻辑、无安卓依赖，故可单测）。
 *
 * 为什么要仲裁而不是直接闪：status 栏是共享总线，终端 Tab 上还有别的信息
 * （2 秒临时提示、按 Ctrl 后的按键信息）。闪烁提示必须让路，否则会把关键信息闪没。
 *
 * 规则（优先级从高到低）：
 *  1. 有 2 秒临时提示（[Inputs.tempStatusActive]）→ 不闪，临时提示说话；
 *  2. 按 Ctrl 之后显示按键信息（[Inputs.ctrlInfoVisible]）→ 不闪，按键信息常驻到被替换；
 *  3. 服务没开（[Inputs.serviceOn]）或已经被 agent 用过（[Inputs.noticePending]）→ 不闪；
 *  4. 以上都不满足 → 亮 [ON_MS] / 暗 [OFF_MS] 循环闪烁。
 */
object WebNoticeArbiter {

    /** 亮着的时长。 */
    const val ON_MS = 900L

    /** 暗下去的时长（短一点，整体节奏才不显得粘）。 */
    const val OFF_MS = 250L

    /** 一个闪烁周期的总长。 */
    const val CYCLE_MS = ON_MS + OFF_MS

    /**
     * status 栏此刻的真实状况。
     *
     * @param serviceOn 无头浏览器服务是否开着
     * @param noticePending 开着但还没被 agent 用过（用过就不再提示）
     * @param tempStatusActive 2 秒临时提示是否正在显示
     * @param ctrlInfoVisible 按 Ctrl 后那行按键信息是否可见
     */
    data class Inputs(
        val serviceOn: Boolean,
        val noticePending: Boolean,
        val tempStatusActive: Boolean = false,
        val ctrlInfoVisible: Boolean = false
    )

    /** 该不该闪（与时间无关，只看谁要说话）。 */
    fun shouldBlink(inputs: Inputs): Boolean =
        inputs.serviceOn &&
            inputs.noticePending &&
            !inputs.tempStatusActive &&
            !inputs.ctrlInfoVisible

    /**
     * 节奏：循环里前 [onMs] 亮、后面 [offMs] 暗。
     * [elapsedMs] 是本次提示开始后经过的毫秒数（`SystemClock.elapsedRealtime()` 差值即可）。
     */
    fun phaseOn(elapsedMs: Long, onMs: Long = ON_MS, offMs: Long = OFF_MS): Boolean {
        val on = onMs.coerceAtLeast(0L)
        val cycle = (on + offMs.coerceAtLeast(0L)).coerceAtLeast(1L)
        return elapsedMs.mod(cycle) < on
    }

    /** 组合判断：此刻 status 栏是否该显示闪烁提示（而不是常规文本）。 */
    fun noticeVisible(inputs: Inputs, elapsedMs: Long): Boolean =
        shouldBlink(inputs) && phaseOn(elapsedMs)

    /**
     * 轮询间隔：闪烁只需 ~100ms 精度就够了（暗段 250ms，抖动 ±50ms 看不出来），
     * 且空闲时不烧 CPU——只在真的要闪的时候才排这个心跳。
     */
    const val TICK_MS = 100L
}
