package com.workspace.proot

/**
 * 显示模式挑选（5.9.58）—— 让「设备支持高刷就一定用上高刷」在 compileSdk 34 上也能落地。
 *
 * ## 为什么不用 preferredRefreshRate
 *
 * `WindowManager.LayoutParams.preferredRefreshRate` 只是给调度器的**弱提示**，不少 ROM
 * 不看它（本项目 5.9.57 就实测无效：vivo 上仍是 60Hz）。
 *
 * `preferredDisplayModeId` 才是真正有力的一层。AOSP 的 multiple-refresh-rate 文档写明：
 * 应用设了它之后，`DisplayManager` 会把默认配置 ID **以及最小和最大刷新率**一起钉到
 * 该模式上。API 23 起都有，是 View 写的 app 能打到的最强一层。
 *
 * ## 为什么走不到 setFrameRate（游戏那条路）
 *
 * 官方文档确实说「能用 `setFrameRate()` 就用它」，但那条路 View 写的 app 够不到：
 * `View.getRootSurfaceControl()` 返回的 `AttachedSurfaceControl` 是个**接口**，
 * 不暴露里面的 `SurfaceControl`；普通窗口也拿不到 `Surface`。
 * （`Window.setFrameRate` / `View.setRequestedFrameRate` 要 API 35，本项目 compileSdk=34。）
 * 游戏能调是因为它们用 SurfaceView / NativeActivity，天生带 Surface。
 *
 * ## 风险是怎么摁住的
 *
 * - **只认分辨率逐像素一致的模式** —— 换分辨率会让整个版式重排，这是最大风险点。
 * - **当前已经最优就不动**（返回 `modeId = 0`）—— 避免无意义的切模式黑屏
 *   （文档：非无缝切换可能黑屏一秒上下，没有收益就别动）。
 * - 调用方只在 `onCreate` 设一次 —— 全程最多切一次。
 * - 本函数是**幂等**的：切完再调，`currentHz` 已是高档，`best.hz <= currentHz + EPS`
 *   成立，于是返回"不动"。
 */
object DisplayRefresh {

    /**
     * 只留挑选需要的四个字段 —— 刻意不直接吃 `Display.Mode`，
     * 那样就没法在 JVM 单测里精确验证了。
     */
    data class Mode(val id: Int, val width: Int, val height: Int, val hz: Float)

    /** 挑出来的目标。`modeId` 为 0 表示「不动，交给系统自己挑」。 */
    data class Target(val modeId: Int, val hz: Float)

    /**
     * 本次实际请求的刷新率，只给终端介绍页的诊断读数用（没请求过就是 null）。
     * 不是决策输入 —— 决策全在 [pickTarget] 里，那是纯函数。
     */
    @Volatile
    var requestedHz: Float? = null

    /** 浮点比较容差：面板报的 Hz 常带小数（59.94 / 120.0），留 0.5Hz 余量免得反复切。 */
    private const val EPS_HZ = 0.5f

    /**
     * 挑目标显示模式。
     *
     * @param currentW/currentH/currentHz 当前正在跑的模式
     * @param modes 设备 `Display.getSupportedModes()` 的全部模式
     * @return 分辨率一致且刷新率最高的那一个；已经最优或没有同分辨率模式时返回「不动」
     */
    fun pickTarget(
        currentW: Int,
        currentH: Int,
        currentHz: Float,
        modes: List<Mode>
    ): Target {
        val keep = Target(0, currentHz)
        if (currentW <= 0 || currentH <= 0 || modes.isEmpty()) return keep
        // 分辨率必须逐像素一致 —— 换分辨率版式就变了，宁可不升刷也绝不动尺寸。
        val sameSize = modes.filter { it.width == currentW && it.height == currentH }
        val best = sameSize.filter { it.hz > 0f }.maxByOrNull { it.hz } ?: return keep
        if (best.hz <= currentHz + EPS_HZ) return keep
        return Target(best.id, best.hz)
    }
}
