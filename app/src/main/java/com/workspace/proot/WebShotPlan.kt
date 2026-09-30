package com.workspace.proot

/**
 * `shot` 截一屏还是整页 —— 判定与缩放的**纯逻辑**（可单测）。
 *
 * ## 为什么要整页
 *
 * 5.9.7 之前的 `shot` 只能截到一屏。页面一旦滚出去，`View.draw()` 画出来的是
 * **一整片页面底色**，红像素 0 —— 同一张图上"内容缺失"的信号。
 *
 * 真机探针（`:probe`，v8）扫了 11 种"踢一帧"的办法，**22 次全灭**：
 * `invalidate` / `onResume` / 软件渲染层 / `onVisibilityAggregated` / 渲染优先级 /
 * 重排版 / `View.scrollTo` / 等 3 秒 / 等 8 秒 / `alpha=0` 挂屏上。
 * 可见性、时间、渲染层三条路都排除了。
 *
 * 同一轮里有一条**全绿**：把视图量到整页那么高。
 *
 * ## 它为什么成立
 *
 * 视图一旦高过整页，页面就没有滚动区了 —— **整页就是首屏**。
 * 那块"画不出来"的内容，从头到尾都在首帧里。
 * 两组（挂窗口 / 不挂窗口）× 两条截图路（`draw` / `capturePicture`）四张全绿，
 * 绿块红块同框，证明整页不缺中间。
 *
 * 探针里另一条全绿的是"页面整体贴底"，**但它不作为修法**：
 * 它靠 `body { position:absolute; bottom:0 }` 把底部挪进视口，代价是
 * **顶部被推出视口**（绿像素 0）。换个页面高度就露馅。
 * 视图高度是照着页面算出来的，没有这种情况。
 *
 * ## 这是绕过，不是修好
 *
 * 机制仍然不知道：为什么首屏之外的区域画不出来。绕开的代价是
 * **长页面会按整页高度重新排版一次** —— `100vh` 变高、`100vw` 类元素变宽、
 * 懒加载一次性全触发、吸顶页头贴到整页顶部。
 * 画出来的图是长页面版式，不是"滚动后的视口版式"。
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
     * @param heightPx   位图高（设备像素）
     * @param viewHeightPx 视图要量到多高（设备像素）。整页模式下它就是整页高
     * @param scale      缩放系数，`1f` = 原尺寸
     * @param heightUnknown 问不到页面高度 —— 调用方照常截一屏，别假装截了整页
     * @param note       给 agent 看的补充说明，空串 = 没话说
     */
    data class Plan(
        val mode: Mode,
        val viewWidthPx: Int,
        val viewHeightPx: Int,
        val heightPx: Int,
        val scale: Float,
        val heightUnknown: Boolean,
        val note: String
    ) {
        /** 是不是长截图（比视口高）。agent 拿到图后据此决定要不要自己再缩。 */
        val isLong: Boolean get() = mode == Mode.FULL_PAGE

        /** 位图被缩过了 —— 尺寸不等于真实像素，必须告诉 agent。 */
        val downscaled: Boolean get() = scale < 1f
    }

    /**
     * 判定。
     *
     * @param pageHeightPx  页面自己报的整页高（设备像素）。`0` 或负数 = 没量到
     * @param viewportHeightPx 视口高（设备像素）
     * @param viewportWidthPx  视口宽（设备像素）
     */
    fun decide(pageHeightPx: Int, viewportHeightPx: Int, viewportWidthPx: Int): Plan {
        val w = viewportWidthPx.coerceAtLeast(1)
        val h = viewportHeightPx.coerceAtLeast(1)
        fun viewport(note: String = "", unknown: Boolean = false) =
            Plan(Mode.VIEWPORT, w, h, h, 1f, unknown, note)

        // 量不到页面高度：照常截一屏，但**如实说**没量到，不能让 agent 以为这就是整页
        if (pageHeightPx <= 0) return viewport("page height unknown — this is one screen, not the full page", true)
        if (pageHeightPx < (h * FULL_PAGE_RATIO).toInt()) return viewport()

        val s = scaleFor(w, pageHeightPx)
        val note = if (s < 1f) {
            "full page, downscaled to ${(s * 100).toInt()}% (${w}x$pageHeightPx device px)"
        } else {
            ""
        }
        return Plan(Mode.FULL_PAGE, w, pageHeightPx, pageHeightPx, s, false, note)
    }

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
