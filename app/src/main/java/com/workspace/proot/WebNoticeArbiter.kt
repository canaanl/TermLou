package com.workspace.proot

/**
 * status 栏「无头浏览器空闲了，该关了」闪烁提示的**仲裁规则**
 * （5.9.1，纯逻辑、无安卓依赖，故可单测）。
 *
 * ## 什么时候才提示
 *
 * 之前是"服务开着但还没被用过就闪"，用户用着开着它也会一直闪——不合理。
 * 5.9.1 改成**闲置满 10 分钟**才提示（[IDLE_MS]），中间每发一条真指令就重置计时
 * （`ping` 只是探活，不算使用）。
 *
 * ## 为什么要仲裁而不是直接闪
 *
 * status 栏是共享总线，终端 Tab 上还有别的信息（2 秒临时提示、按 Ctrl 后的按键信息）。
 * 闪烁提示必须让路，否则会把关键信息闪没。优先级：
 *  1. 有 2 秒临时提示（[Inputs.tempStatusActive]）→ 不闪，临时提示说话；
 *  2. 按 Ctrl 之后显示按键信息（[Inputs.ctrlInfoVisible]）→ 不闪，按键信息常驻；
 *  3. 服务没开，或还没闲置够 [IDLE_MS] → 不闪；
 *  4. 以上都不满足 → 亮 [ON_MS] / 暗 [OFF_MS] 循环闪烁。
 */
object WebNoticeArbiter {

    /** 闲置多久才提示：10 分钟。 */
    const val IDLE_MS = 10 * 60 * 1000L

    /** 亮着的时长。 */
    const val ON_MS = 900L

    /** 暗下去的时长（短一点，整体节奏才不显得粘）。 */
    const val OFF_MS = 250L

    /** 一个闪烁周期的总长。 */
    const val CYCLE_MS = ON_MS + OFF_MS

    /**
     * 心跳间隔：闪烁只需 ~100ms 精度（暗段 250ms，抖动 ±50ms 看不出来）。
     * **只有真的要闪的时候才排这个心跳**，空闲等待期一律不排。
     */
    const val TICK_MS = 100L

    /**
     * status 栏此刻的真实状况。
     *
     * @param serviceOn 无头浏览器服务是否开着
     * @param idleMs 已经闲置多久（毫秒）
     * @param tempStatusActive 2 秒临时提示是否正在显示
     * @param ctrlInfoVisible 按 Ctrl 后那行按键信息是否可见
     */
    data class Inputs(
        val serviceOn: Boolean,
        val idleMs: Long,
        val tempStatusActive: Boolean = false,
        val ctrlInfoVisible: Boolean = false
    )

    /** 该不该闪（与时间无关，只看谁要说话）。 */
    fun shouldBlink(inputs: Inputs): Boolean =
        inputs.serviceOn &&
            inputs.idleMs >= IDLE_MS &&
            !inputs.tempStatusActive &&
            !inputs.ctrlInfoVisible

    /**
     * 距离开始闪烁还有多久（毫秒）：没到 [IDLE_MS] 给一个延时值（用来排一次性检查），
     * 已经到点则 0。
     */
    fun msUntilBlink(idleMs: Long): Long = (IDLE_MS - idleMs).coerceAtLeast(0L)

    /**
     * 节奏：循环里前 [onMs] 亮、后面 [offMs] 暗。
     * [elapsedMs] 是本次开始闪烁后经过的毫秒数。
     */
    fun phaseOn(elapsedMs: Long, onMs: Long = ON_MS, offMs: Long = OFF_MS): Boolean {
        val on = onMs.coerceAtLeast(0L)
        val cycle = (on + offMs.coerceAtLeast(0L)).coerceAtLeast(1L)
        return elapsedMs.mod(cycle) < on
    }

    /** 组合判断：此刻 status 栏是否该显示闪烁提示（而不是常规文本）。 */
    fun noticeVisible(inputs: Inputs, elapsedMs: Long): Boolean =
        shouldBlink(inputs) && phaseOn(elapsedMs)
}
