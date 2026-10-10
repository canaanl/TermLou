package com.workspace.proot

/**
 * 浏览器悬浮窗的几何计算（5.9.27 建立，5.9.34 误删缩放，5.9.36 请回来）。
 * 纯逻辑、可单测。
 *
 * ## 需求（用户定死，不可改）
 *
 * 1. 窗口宽度 = **屏宽 ÷ 3**，长宽比跟**整块屏**一致。
 * 2. 位置：**每次重建回右上角**，不存。
 * 3. 按窗体**任意处**拖，不用把手。
 *
 * ## 视口与窗口是**两个数**，靠缩放联系（5.9.38 更正）
 *
 * ```
 * 视口 412×892dp × density（给网页排版用，跟正常手机一致）
 *   ↓ scaleFactors()
 * 窗口 屏宽÷3（显示用）
 * ```
 *
 * ⚠ **别把视口改成窗口尺寸。** 5.9.34 那么做过，理由是"缩放导致第 2 屏空白"，
 * 而**那个理由是错的** —— 5.9.34/5.9.35 都没有缩放，第 2 屏照样失败。
 * 那次改动只有一个后果：网页跟着变窄到 160dp，字从 48px 掉到 19px，发虚。
 *
 * 5.9.36 请回 412dp + 缩放（用户要的是**清楚**）：缩放只发生在**显示**这一层，
 * 挂在 [WebFloatWindowHost.ScaleFrameLayout] 上，WebView 自己的 scale 恒为 1。
 *
 * （5.9.37 之后"取像素"那条路整个删了 —— 所以这里不存在"缩放让取像素出错"的问题。）
 */
object WebFloatWindow {

    /** 窗口宽度占屏宽的比例。固定，不可改。 */
    const val WIDTH_DIVISOR = 3

    /** 位置：右上角。[Gravity.TOP or Gravity.START] 下 x/y 就是窗口左上角。 */
    const val DEFAULT_X = 0
    const val DEFAULT_Y = 0

    /**
     * 窗口尺寸（设备像素）。
     *
     * ⚠ **它不是视口尺寸**（5.9.34 曾把它当成视口，5.9.36 改回）。
     * 视口是 [WebProtocol.VIEWPORT_W_DP]×[WebProtocol.VIEWPORT_H_DP] dp × density，
     * 两者之间由 [scaleFactors] 联系。
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
    fun scaleFactors(winW: Int, winH: Int, viewW: Int, viewH: Int): Pair<Float, Float> {        val sx = if (winW > 0 && viewW > 0) winW.toFloat() / viewW.toFloat() else 1f
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