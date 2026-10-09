package com.workspace.proot

/**
 * `shot` 截一屏还是整页 —— 判定与缩放的**纯逻辑**（可单测）。
 *
 * ## 5.9.27：整页改成"滚动分段"
 *
 * 5.9.7–5.9.26 的整页是**把视图量到整页那么高**，靠"高过整页就没有滚动区，
 * 整页就是首屏"拿到图。那是绕过，不是修好：
 *
 * 1. 机制不知道 —— 为什么无头模式下滚出去的内容画不出来。探针（`:probe` v8）
 *    扫了 11 种"踢一帧"的办法、**22 次全灭**（可见性、时间、渲染层三条路全排除）。
 *    一个没有 surface 的视图没有东西可等。
 * 2. 代价明确 —— 长页面会按整页高度**重新排版一次**：`100vh` 变高、
 *    懒加载一次性全触发、吸顶页头贴到整页顶部。画出来的是长页面版式，
 *    不是"滚动后的视口版式"。
 *
 * 现在 WebView 挂在**真的悬浮窗**上，合成器一直在产帧，滚到哪里渲染到哪里。
 * 于是回到正常浏览器的路子：滚一段、拍一张、拼起来（[WebScrollShot]）。
 * 这不是新发明，就是用户在浏览器里做的事。
 *
 * ## 为什么还保留这个类
 *
 * 「页面比视口高多少倍才值得拼」和「拼出来的图超上限要缩多少」这两件事
 * 与用不用滚动无关 —— 分段本身在 [WebScrollShot]。
 */
object WebShotPlan {

    /** 页面比视口高多少倍才值得走整页。 */
    const val FULL_PAGE_RATIO = 1.5f

    /**
     * 位图面积上限（像素数）。
     *
     * 1236 × 7275 ≈ 899 万，正好在下面；再多就按比例缩。
     * **缩了必须说** —— 不然 agent 会拿缩小图当原尺寸看。
     */
    const val MAX_PIXELS = 9_000_000

    /** 问页面要整页高度。用 `String()` 包一层：`evaluateJavascript` 回的是 JSON 字符串。 */
    const val PAGE_HEIGHT_JS =
        "String(Math.round((document.documentElement||document.body||{}).scrollHeight||0))"

    enum class Mode { VIEWPORT, FULL_PAGE }

    /**
     * 截一张图要怎么做。
     *
     * @param viewWidthPx  视口宽（设备像素）
     * @param viewHeightPx **视口**高（设备像素）—— 5.9.27 起这里**永远是视口高**，
     *   不再是整页高：视图不再被撑高，改成滚动分段（[WebScrollShot]）
     * @param pageHeightPx 整页高（设备像素）。`0` = 没量到
     * @param scale      最终长图的缩放系数，`1f` = 原尺寸
     * @param heightUnknown 问不到页面高度 —— 调用方照常截一屏，别假装截了整页
     * @param note       给 agent 看的补充说明，空串 = 没话说
     */
    data class Plan(
        val mode: Mode,
        val viewWidthPx: Int,
        val viewHeightPx: Int,
        val pageHeightPx: Int,
        val scale: Float,
        val heightUnknown: Boolean,
        val note: String
    ) {
        /** 是不是长截图（比视口高）。agent 拿到图后据此决定要不要自己再缩。 */
        val isLong: Boolean get() = mode == Mode.FULL_PAGE

        /** 位图被缩过了 —— 尺寸不等于真实像素，必须告诉 agent。 */
        val downscaled: Boolean get() = scale < 1f

        /** 整页时滚动分段的方案；一屏时为 null。 */
        fun scrollPlan(): WebScrollShot.Plan? =
            if (!isLong) null else WebScrollShot.plan(pageHeightPx, viewWidthPx, viewHeightPx)
    }

    /**
     * 判定。
     *
     * @param pageHeightPx  页面自己报的整页高（设备像素）。`0` 或负数 = 没量到
     * @param viewportHeightPx 视口高（设备像素）
     * @param viewportWidthPx 视口宽（设备像素）
     */
    fun decide(pageHeightPx: Int, viewportHeightPx: Int, viewportWidthPx: Int): Plan {
        val w = viewportWidthPx.coerceAtLeast(1)
        val h = viewportHeightPx.coerceAtLeast(1)
        fun viewport(note: String = "", unknown: Boolean = false) =
            Plan(Mode.VIEWPORT, w, h, 0, 1f, unknown, note)

        // 量不到页面高度：照常截一屏，但**如实说**没量到，不能让 agent 以为这就是整页
        if (pageHeightPx <= 0) return viewport("page height unknown — this is one screen, not the full page", true)
        if (pageHeightPx < (h * FULL_PAGE_RATIO).toInt()) return viewport()

        val s = scaleFor(w, pageHeightPx)
        val note = if (s < 1f) {
            "full page (scrolled ${segmentsNote(pageHeightPx, h)} screens), " +
                "downscaled to ${(s * 100).toInt()}% (${w}x$pageHeightPx device px)"
        } else {
            "full page, captured by scrolling ${segmentsNote(pageHeightPx, h)} screens"
        }
        // **viewHeightPx 是视口高，不是整页高** —— 视图不再被撑高。
        // 撑高会让长页面按整页版式重新排版一次（见类注释）。
        return Plan(Mode.FULL_PAGE, w, h, pageHeightPx, s, false, note)
    }

    private fun segmentsNote(pageHeightPx: Int, viewportHeightPx: Int): Int =
        WebScrollShot.plan(pageHeightPx, 1, viewportHeightPx.coerceAtLeast(1)).shots

    /**
     * 位图面积超上限时的缩放系数，`1f` = 不缩。
     *
     * **按面积等比缩，不裁不补。**
     */
    fun scaleFor(widthPx: Int, heightPx: Int): Float {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val px = w.toLong() * h.toLong()
        if (px <= MAX_PIXELS) return 1f
        return kotlin.math.sqrt(MAX_PIXELS.toFloat() / px.toFloat())
    }
}