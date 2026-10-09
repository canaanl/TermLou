package com.workspace.proot

/**
 * 整页截图的**分段方案**（5.9.27），纯逻辑、可单测。
 *
 * ## 为什么改成"滚动分段"
 *
 * 5.9.7–5.9.26 走的是"把视图量到整页那么高，整页就是首屏"。那条路能出图，
 * 但代价写在 [WebShotPlan] 的类注释里：长页面会按整页高度**重新排版一次**
 * （`100vh` 变高、懒加载一次性全触发、吸顶页头贴到整页顶部），
 * 画出来的图是长页面版式，不是"滚动后的视口版式"。
 *
 * 根子在于**无头模式下 WebView 没有真窗口**：探针（`:probe` v8）扫了 11 种
 * "踢一帧"的办法、22 次全灭 —— 滚出视口的内容压根不会被光栅化，
 * 所以只能改成"让整页都进首屏"。
 *
 * 挂了真窗口之后这件事从根上不存在：合成器一直在产帧，滚到哪里渲染到哪里。
 * 于是回到**正常浏览器**的路子 —— 滚一段、拍一张、拼起来。
 *
 * ## 与正常浏览器一致
 *
 * 这正是用户在浏览器里做的事：页面比窗口长就往下翻，翻过的地方都能渲染。
 * 悬浮窗在截整页时会跟着一起滚，肉眼能看见它走完全程。
 */
object WebScrollShot {

    /**
     * 方案里的一段。
     *
     * @param scrollY 截图前把 WebView 滚到哪（文档坐标，设备像素）
     * @param top     这一段**开始贡献**的文档坐标
     * @param sourceY 贡献区在拍下来的那张位图里从第几行开始
     * @param height  这一段贡献多少设备像素高
     *
     * ## `sourceY` 为什么不是 0
     *
     * 视口滚到底只能停在 `pageHeight - viewportHeight`。最后一段往往**滚不到**
     * 它该在的那一格（例：整页 5000、视口 2000，最后一段本该从 4000 开始，
     * 但最大滚动只有 3000）。于是那张位图的上半截是**上一段已经拍过的内容**。
     *
     * 与其让它重叠着画两遍，不如**只取新出现的那几条**：`sourceY` 指出从哪行起算。
     * 好处是分段**严丝合缝地铺满整页**（各段高之和 == 整页高），
     * 这个不变量可以锁死，拼接代码就不可能漏一段或者重复一段。
     */
    data class Segment(val scrollY: Int, val top: Int, val sourceY: Int, val height: Int)

    /**
     * 完整方案。
     *
     * @param pageHeightPx 整页高（设备像素）
     * @param viewportHeightPx 视口高（设备像素）—— 一段的高度
     * @param viewportWidthPx 视口宽（设备像素）
     * @param segments 分段，从上到下无缝无重叠
     * @param scale 最终长图的缩放（超 [WebShotPlan.MAX_PIXELS] 才 < 1）
     */
    data class Plan(
        val pageHeightPx: Int,
        val viewportWidthPx: Int,
        val viewportHeightPx: Int,
        val segments: List<Segment>,
        val scale: Float
    ) {
        /** 拼接后位图的像素尺寸（已含 [scale]）。 */
        val bitmapWidth: Int get() = (viewportWidthPx * scale).toInt().coerceAtLeast(1)
        val bitmapHeight: Int get() = (pageHeightPx * scale).toInt().coerceAtLeast(1)

        /** 截几次。1 = 整页就是首屏，不用滚。 */
        val shots: Int get() = segments.size
    }

    /**
     * 分段方案。
     *
     * ## 最后一段为什么要特殊处理
     *
     * 视口滚到底也只能停在 `pageHeight - viewportHeight`（再多滚就是空白）。
     * 所以每段的 `scrollY` 必须**夹到最大滚动位置**；而它在长图里的**起点**
     * 仍是"第 i 格"（`i * viewportHeight`），两者会分开 —— 分开的那一段
     * 就是 [Segment.sourceY]。
     *
     * 举例：整页 5000、视口 2000 → `maxScroll = 3000`，三段：
     *
     * | 段 | scrollY | top | sourceY | height |
     * |---|---|---|---|---|
     * | 0 | 0 | 0 | 0 | 2000 |
     * | 1 | 2000 | 2000 | 0 | 2000 |
     * | 2 | 3000 | 4000 | 1000 | 1000 |
     *
     * 第三段滚到 3000（到不了 4000），拍到的位图覆盖文档 `[3000,5000)`，
     * 其中 `[3000,4000)` 是第二段拍过的 —— 从第 1000 行起才是新内容。
     *
     * @param pageHeightPx 整页高
     * @param viewportWidthPx 视口宽
     * @param viewportHeightPx 视口高
     */
    fun plan(
        pageHeightPx: Int, viewportWidthPx: Int, viewportHeightPx: Int
    ): Plan {
        val w = viewportWidthPx.coerceAtLeast(1)
        val vh = viewportHeightPx.coerceAtLeast(1)
        val page = pageHeightPx.coerceAtLeast(1)
        val maxScroll = (page - vh).coerceAtLeast(0)
        // 向上取整：最后不足一屏的那点也得截到
        val n = (page + vh - 1) / vh
        val segs = buildList {
            for (i in 0 until n) {
                val top = (i * vh).coerceAtMost(page)
                val scrollY = top.coerceAtMost(maxScroll)
                val height = (page - top).coerceAtMost(vh).coerceAtLeast(1)
                add(Segment(scrollY, top, top - scrollY, height))
            }
        }
        return Plan(page, w, vh, segs, WebShotPlan.scaleFor(w, page))
    }
}