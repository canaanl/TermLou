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

    // ---------- 滚动用的 JS（可单测） ----------

    /**
     * 滚动文档到指定 CSS 像素。
     *
     * ## 为什么必须走 JS，不能用 `View.scrollTo`
     *
     * `wv.scrollTo(0, y)` 改的是**视图自己的滚动偏移**，WebView 的**文档滚动**
     * 它根本不管 —— 5.9.28 整页截图只有第一屏、后面全白，就是栽在这里。
     *
     * 佐证：`:probe` v8 扫过 11 种"踢一帧"的办法，**`View.scrollTo` 就在里面，
     * 22 次全灭** —— 这条路对 WebView 不通，当时已经验过了。
     *
     * 文档滚动只有一条正路：`window.scrollTo`。
     */
    fun scrollToJs(cssY: Int): String =
        "(function(){try{window.scrollTo(0,${cssY.coerceAtLeast(0)});" +
            "return String(Math.round(window.pageYOffset||window.scrollY||0));}" +
            "catch(e){return 'ERR:'+e}})()"

    /** 读回当前文档滚动位置（CSS px）。用于**确认真的滚到位**，不是猜。 */
    const val SCROLL_Y_JS =
        "(function(){try{return String(Math.round(window.pageYOffset||window.scrollY||0));}" +
            "catch(e){return 'ERR:'+e}})()"

    /**
     * 一次拿全页面度量（5.9.32）。
     *
     * ## 为什么要它
     *
     * 整页截图必须**边走边量**：`maxScroll` 每拍完一屏就重读一次。
     * 真机实测两次 `shot` 的 `page_height` 是 **6621 → 7443** —— 页面在拍摄途中
     * 还在长高（懒加载、评论展开）。原先按开拍前那个数算好段数与滚动位置，
     * 那份计划**从一开始就是过期的**。
     *
     * 一次 eval 把四个值都拿回来，省掉三趟往返。
     */
    const val METRICS_JS =
        "(function(){try{var d=document.documentElement||{},b=document.body||{};" +
            "var h=Math.max(d.scrollHeight||0,b.scrollHeight||0);" +
            "return JSON.stringify({h:Math.round(h)," +
            "y:Math.round(window.pageYOffset||window.scrollY||0)," +
            "vh:window.innerHeight||0,ready:String(document.readyState||'')});}" +
            "catch(e){return ''}})()"

    /** `document.readyState`。`'complete'` = 页面连同资源都加载完了。 */
    const val READY_STATE_JS =
        "(function(){try{return String(document.readyState||'')}catch(e){return ''}})()"

    /**
     * 解析 [METRICS_JS] 的返回（纯逻辑，可单测）。
     *
     * 解析不出来返回 null —— 调用方**必须**分得清"没量到"与"量到 0"：
     * 当成 0 会把"到底了"判成真，于是只拍一屏就停。
     */
    fun parseMetrics(raw: String?): Metrics? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val o = runCatching { org.json.JSONObject(s) }.getOrNull() ?: return null
        if (!o.has("h")) return null
        return Metrics(
            pageHeightCss = o.optInt("h", 0).coerceAtLeast(0),
            scrollYCss = o.optInt("y", 0).coerceAtLeast(0),
            viewportHCss = o.optInt("vh", 0).coerceAtLeast(0),
            readyState = o.optString("ready", "")
        )
    }

    /** [METRICS_JS] 的解析结果（CSS px）。 */
    data class Metrics(
        val pageHeightCss: Int,
        val scrollYCss: Int,
        val viewportHCss: Int,
        val readyState: String
    ) {
        /**
         * 能滚到的最远处。页面比视口矮时是 0。
         *
         * ⚠ **每拍完一屏都要重读** —— 页面可能又长高了。
         */
        val maxScrollCss: Int get() = (pageHeightCss - viewportHCss).coerceAtLeast(0)
    }

    /**
     * 下一屏的起点；返回 `null` 表示**已经到底了**。
     *
     * "到底了"是**实测**的（`next <= current`），不是"拍够 N 屏"——
     * 段数是走出来的，不是算出来的。
     */
    fun nextScreenTop(currentPx: Int, stepPx: Int, maxScrollPx: Int): Int? {
        val next = (currentPx.toLong() + stepPx).coerceAtMost(maxScrollPx.toLong()).toInt()
        return if (next <= currentPx) null else next
    }

    /** 切整页时，每一屏要切的那一块（纯算术，可单测）。 */
    data class SrcRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * 第 `scrollDeviceY` 屏在整页里对应的那一块（5.9.33）。
     *
     * ## 为什么是"切"而不是"缩"
     *
     * 旧兜底是 `canvas.scale(min(bw/picW, bh/picH))` 把**整页缩进一张图**——
     * 那正是真机上报的"第 2 屏起变成缩小的长图"。现在只切，**永不缩放**。
     *
     * ## 为什么要夹
     *
     * 整页可能比"滚到最后那一屏"该有的高度更短（页面中途还在长高/变矮）。
     * 切出来的块必须**始终是视口原尺寸**，否则拼不出统一的几何。
     *
     * @param picW/picH 整页尺寸
     * @param vw/vh 视口尺寸（目标尺寸，一个像素都不缩）
     * @param scrollDeviceY 这一屏在文档里的起点
     * @return 切哪一块；画不出东西时返回 null
     */
    fun scrollSrcRect(
        picW: Int, picH: Int, vw: Int, vh: Int, scrollDeviceY: Int
    ): SrcRect? {
        if (picW <= 0 || picH <= 0) return null
        val w = vw.coerceAtLeast(1).coerceAtMost(picW)
        val h = vh.coerceAtLeast(1)
        val top = scrollDeviceY.coerceIn(0, (picH - 1).coerceAtLeast(0))
        // 底部夹到整页末尾，但**至少留 1 行** —— 否则切出 0 高
        val bottom = (top + h).coerceAtMost(picH).coerceAtLeast(top + 1).coerceAtMost(picH)
        if (bottom <= top || w <= 0) return null
        return SrcRect(0, top, w, bottom)
    }

    /** 设备像素 → CSS 像素。**别用 `density.toInt()`** —— 2.75/3.5/2.625 会截错。 */
    fun toCss(px: Int, density: Float): Int =
        if (density <= 0f) px else Math.round(px / density.toDouble()).toInt()

    /**
     * 滚动到位没有。
     *
     * 容差 2 CSS px：浏览器滚动吸附（scroll-snap）、子像素布局都可能差一两像素，
     * 那不是"没滚到"。
     */
    fun scrollLanded(targetCss: Int, actualCss: Int, toleranceCss: Int = 2): Boolean =
        Math.abs(targetCss.toLong() - actualCss.toLong()) <= toleranceCss.toLong()

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