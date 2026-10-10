package com.workspace.proot

/**
 * 浏览器悬浮窗的几何计算（5.9.27 建立，5.9.34 重写），纯逻辑、可单测。
 *
 * ## 需求（用户定死，不可改）
 *
 * 1. 窗口宽度 = **屏宽 ÷ 3**，长宽比跟**整块屏**一致。
 * 2. 位置：**每次重建回右上角**，不存。
 * 3. 按窗体**任意处**拖，不用把手。
 *
 * ## 5.9.34：视口就是窗口，没有缩放（用户定的）
 *
 * 此前是"视口锁死 412×892dp，缩到 0.39 塞进小窗"。那条做法**在真机上出不来图**：
 * 缩放挂在容器上，而安卓问网页"你要画多大"时量的是**它在屏幕上实际占多大** ——
 * 缩放一压，它就只画 480×1056，剩下 87% **从来没被画过**（不是画了看不见）。
 *
 * 整页截图于是永远停在第 2 屏：
 *
 * ```
 * 真机 5.9.33：screen 2: nothing rendered (blank)
 * 真机 5.9.32：第 2 屏大块空白 / 错位
 * 真机 5.9.31：第 3 屏起画面不跟随滚动
 * ```
 *
 * **缩放这件事现在整个没有了。** 网页多大，窗口就多大，一个像素对一个像素。
 * 于是安卓量到的、你眼睛看到的、我们要拍的，是同一块东西。
 *
 * ## 代价（用户明确接受）
 *
 * 网页跟着变成 **160dp 宽**（480px ÷ density 3.0），比任何手机都窄，
 * 网站会切到最窄那套排版；小窗里的字比原来大约 2.6 倍（老人机观感，用户要的）。
 * 之前存过的选择器可能失效 —— 坐标是每次现场查的，不会错。
 *
 * ⚠ **不要再把视口改回 412×892 并加缩放。** 那条路走不通，理由见上。
 */
object WebFloatWindow {

    /** 窗口宽度占屏宽的比例。固定，不可改。 */
    const val WIDTH_DIVISOR = 3

    /** 位置：右上角。[Gravity.TOP or Gravity.START] 下 x/y 就是窗口左上角。 */
    const val DEFAULT_X = 0
    const val DEFAULT_Y = 0

    /**
     * 窗口尺寸 —— **同时就是视口尺寸**，全工程只有这一个数（5.9.34）。
     *
     * 以前有两个：`WebProtocol.VIEWPORT_W_DP`（412）与这里的窗口尺寸，
     * 中间靠一个缩放因子联系。两个数就意味着两者可能对不上 ——
     * 而"对不上"正是整页截图拍不出来的那类问题的土壤。
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
     * 把 agent 视口（412×892dp）缩进窗口所需的 **x/y 独立因子**。
     *
     * 5.9.36 请回来（5.9.34 误删）。**缩放不是第 2 屏失败的原因** ——
     * 没有缩放时第 2 屏照样失败 —— 但删掉它的代价是画质：视口跟着窗口变小，
     * 字从 48px 掉到 19px，发虚。用户要的是清楚。
     *
     * 缩放只发生在**显示**这一层：WebView 的 `scaleX/scaleY` 恒为 1，
     * 缩放加在 `ScaleFrameLayout` 容器上（见 [WebFloatWindowHost]）。
     *
     * 两个方向**独立**算（不是 `min`）：窗口比例跟屏幕走（1:2.1），
     * 视口比例是 412:892（1:2.165），两者不等时等比缩会留边。
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