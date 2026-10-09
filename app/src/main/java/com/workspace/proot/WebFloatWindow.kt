package com.workspace.proot

/**
 * 浏览器悬浮窗的几何计算（5.9.27），纯逻辑、可单测。
 *
 * ## 需求
 *
 * 1. 窗口宽度 ≈ **屏宽的 1/3**，长宽比与**整块屏**一致（用户指定，不可改）。
 * 2. 位置：**每次重建回右上角**，不持久化。
 * 3. 窗口里的 WebView 仍然按 **agent 视口**（412×892dp）排版，
 *    只是缩着塞进小窗 —— 视口一个像素不动，agent 的坐标与页面版式全不变。
 *
 * ## 为什么不能直接把 WebView 量成窗口大小
 *
 * 那样视口会变成 160dp 宽，多数手机站会重排成完全不同的版式，
 * agent 之前学到的选择器与坐标全部失效。用户明确要求"浏览器对 agent 的使用一模一样"。
 * 所以视口必须锁死在 [WebProtocol.VIEWPORT_W_DP]×[WebProtocol.VIEWPORT_H_DP]，
 * 缩放只发生在**显示**这一层。
 *
 * ## 缩放的两个因子不一样
 *
 * 窗口比例跟屏幕走，视口比例是 412:892 ≈ 1:2.165，屏幕比例未必相同
 * （本机 480×1056dp ≈ 1:2.2）。两者不等时**不能等比缩**，否则会留边。
 * 这里取**两个方向的独立因子**，把视口铺满窗口 —— 用户看到的是完整视口，没有黑边。
 * 代价是纵向被拉伸了约 1.6%，肉眼不可见；换来的是"看得全"。
 */
object WebFloatWindow {

    /** 窗口宽度占屏宽的比例。固定，不可改。 */
    const val WIDTH_DIVISOR = 3

    /** 位置：右上角。[Gravity.TOP or Gravity.START] 下 x/y 就是窗口左上角。 */
    const val DEFAULT_X = 0
    const val DEFAULT_Y = 0

    /** 拖动边界留的余量：窗口不许被拖出屏幕外到找不回来的地步。 */
    const val EDGE_MARGIN_PX = 0

    /**
     * 窗口尺寸。
     *
     * @param screenW 屏宽（设备像素）
     * @param screenH 屏高（设备像素）
     * @return `宽 x 高`，两者都至少 1
     */
    fun windowSize(screenW: Int, screenH: Int): Pair<Int, Int> {
        val w = (screenW / WIDTH_DIVISOR).coerceAtLeast(1)
        // 长宽比跟屏幕走：h = w * screenH / screenW
        val h = ((w.toLong() * screenH.coerceAtLeast(1) / screenW.coerceAtLeast(1)).toInt())
            .coerceAtLeast(1)
        return w to h
    }

    /**
     * 把 agent 视口塞进窗口所需的**独立 x/y 缩放因子**。
     *
     * 两个方向分别算，不是 `min` —— 见类注释。
     *
     * @param winW 窗口宽
     * @param winH 窗口高
     * @param viewW 视口宽（设备像素）
     * @param viewH 视口高（设备像素）
     */
    fun scaleFactors(winW: Int, winH: Int, viewW: Int, viewH: Int): Pair<Float, Float> {
        val sx = if (winW > 0 && viewW > 0) winW.toFloat() / viewW.toFloat() else 1f
        val sy = if (winH > 0 && viewH > 0) winH.toFloat() / viewH.toFloat() else 1f
        return sx.coerceAtLeast(1e-4f) to sy.coerceAtLeast(1e-4f)
    }

    /**
     * 拖动后把窗口夹回屏内。
     *
     * 不夹的话用户能把它拖到屏幕外，然后"找不回来了" —— 悬浮窗没有系统级的
     * 窗口列表可以捞回来。这是纯算术，不碰 Android 任何东西，所以可单测。
     *
     * @param x/y 拖动后的左上角
     * @param winW/winH 窗口尺寸
     * @param screenW/screenH 屏幕尺寸
     */
    fun clampToScreen(
        x: Int, y: Int, winW: Int, winH: Int, screenW: Int, screenH: Int
    ): Pair<Int, Int> {
        val maxX = (screenW - winW).coerceAtLeast(0)
        val maxY = (screenH - winH).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    /**
     * 右上角的初始位置（`Gravity.TOP or Gravity.START` 下的左上角坐标）。
     *
     * x = 屏宽 − 窗宽，y = 0。
     */
    fun defaultPosition(screenW: Int, winW: Int): Pair<Int, Int> =
        ((screenW - winW).coerceAtLeast(0)) to DEFAULT_Y
}