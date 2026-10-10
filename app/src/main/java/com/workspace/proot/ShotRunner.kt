package com.workspace.proot

/**
 * 截图的**纯逻辑**（5.9.34 从 `WebShotPlan` + `WebScrollShot` 里剥出来重写）。
 *
 * ## 这个文件为什么只剩这些
 *
 * 5.9.27–5.9.33 截图这块散着三个文件、一大堆类型：
 * `WebShotPlan.Plan`（`mode/viewWidthPx/viewHeightPx/pageHeightPx/scale/heightUnknown/note`）、
 * `WebScrollShot.Plan` + `Segment`（`scrollY/top/sourceY/height`）、
 * `FULL_PAGE_RATIO`、`MAX_PIXELS`、`scaleFor()`、`downscaled`、`scrollSrcRect()`。
 *
 * 那些全是**拼接时代**的产物 —— 5.9.30 取消拼接后一行都没删。
 * 5.9.34 一屏一张、不拼不缩，于是：
 *
 * - 不再有"长图"，`scaleFor` / `MAX_PIXELS` / `downscaled` 没人用；
 * - 不再有"分段"，`Segment` / `sourceY` / `scrollPlan()` 没人用；
 * - 不再有"整页图"，`capturePicture()` 切块（`scrollSrcRect`）没人用。
 *
 * 真正剩下的只有：**问页面要度量、滚到位、等画完、一屏一张、走到���底**。
 * 全部写在这里，纯算术 + 字符串解析，**单测不用碰安卓**。
 *
 * ## 关键结构：判据与动作分开
 *
 * 5.9.33 的 `awaitScreenStable()` 是这样的：
 *
 * ```
 * 拍一张 → 是不是白的？→ 再拍一张 → 和上一张一样吗？
 * ```
 *
 * **判"画面画完没有"用的就是"截图"这个动作。** 截图这条路一瞎，
 * 这个循环只剩一个结局：一直白、一直等、最后报一句 `nothing rendered (blank)`。
 *
 * 真机连续三个版本都卡在同一句话上（5.9.31 第 3 屏、5.9.32 第 2 屏、5.9.33 第 2 屏），
 * 根因就在这里 —— **判据和被测对象是同一件事，所以失败时说不出到底哪一步坏了。**
 *
 * 5.9.34 分成三步，各有各的判据、失败各有各的说法：
 *
 * | 步 | 问什么 | 怎么问 | 失败说 |
 * |---|---|---|---|
 * | 1 | 滚到位了吗 | 回读 `pageYOffset` | `the page would not scroll to y=…` |
 * | 2 | 画面画完了吗 | 探针连两次指纹相同 | `the window showed nothing new at y=…` |
 * | 3 | 拍到东西了吗 | 全屏画下来 + 判空 | `the capture came back blank at y=…` |
 */
object ShotRunner {

    /** 探针位图的边长（正方形）。48 足够让"整屏同色"和"有内容"分开。 */
    const val PROBE_SIZE = 48

    /** 最多拍多少屏。 */
    const val MAX_SCREENS = 40

    /**
     * 一次 eval 把页面度量全拿回来。
     *
     * ⚠ **每拍完一屏都要重读** —— 页面可能又长高了（懒加载、评论展开）。
     * 真机实测两次 `shot` 的 `page_height` 是 **6621 → 7443**。
     * 缓存它就等于用一份从一开始就是过期的数据。
     */
    const val METRICS_JS =
        "(function(){try{var d=document.documentElement||{},b=document.body||{};" +
            "var h=Math.max(d.scrollHeight||0,b.scrollHeight||0);" +
            "return JSON.stringify({h:Math.round(h)," +
            "y:Math.round(window.pageYOffset||window.scrollY||0)," +
            "vh:window.innerHeight||0,ready:String(document.readyState||'')});}" +
            "catch(e){return ''}})()"

    /**
     * 把文档滚到指定 CSS 像素，并回读实际位置。
     *
     * ## 为什么必须走 JS，不能用 `View.scrollTo`
     *
     * `wv.scrollTo(0, y)` 改的是**视图自己的滚动偏移**，WebView 的**文档滚动**
     * 它根本不管 —— 5.9.28 整页截图只有第一屏、后面全白，就是栽在这里。
     *
     * 文档滚动只有一条正路：`window.scrollTo`。**回读不能省** ——
     * 页面可以用 scroll-snap 改掉、可以用 JS 拦掉、可以在平滑滚动动画里还没到位。
     */
    fun scrollToJs(cssY: Int): String =
        "(function(){try{window.scrollTo(0,${cssY.coerceAtLeast(0)});" +
            "return String(Math.round(window.pageYOffset||window.scrollY||0));}" +
            "catch(e){return 'ERR:'+e}})()"

    /** 读回当前文档滚动位置（CSS px）。用于**确认真的滚到位**，不是猜。 */
    const val SCROLL_Y_JS =
        "(function(){try{return String(Math.round(window.pageYOffset||window.scrollY||0));}" +
            "catch(e){return 'ERR:'+e}})()"

    /** `document.readyState`。`'complete'` = 页面连同资源都加载完了。 */
    const val READY_STATE_JS =
        "(function(){try{return String(document.readyState||'')}catch(e){return ''}})()"

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
     * 解析 [METRICS_JS] 的返回（纯逻辑）。
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

    /**
     * 这一页要不要滚着拍。
     *
     * 5.9.33 之前这里还有个 1.5 倍门槛。那是给"把视图撑到整页高"那道**破坏性做法**
     * 设的保护栏（那会重新排版页面）；5.9.29 起改成滚动分段后就没有理由了，
     * 而且**有害**：一屏半高的页面下半页永远拍不到。
     *
     * 现在：**比一屏高就拍**，没有门槛。
     */
    fun needsScroll(pageHeightPx: Int, viewportHeightPx: Int): Boolean =
        pageHeightPx > viewportHeightPx.coerceAtLeast(1)

    /**
     * 下一屏的起点；返回 `null` 表示**已经到底了**。
     *
     * "到底了"是**实测**的（`next <= current`），不是"拍够 N 屏"——
     * 屏数是走出来的，不是算出来的。
     */
    fun nextScreenTop(currentPx: Int, stepPx: Int, maxScrollPx: Int): Int? {
        val next = (currentPx.toLong() + stepPx).coerceAtMost(maxScrollPx.toLong()).toInt()
        return if (next <= currentPx) null else next
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
     * 探针画布的缩放因子：把 `viewW × viewH` 压进 [PROBE_SIZE] 见方。
     *
     * 探针只用来回答"画面变了没有"，不拿去交差 —— 所以压得很小很便宜。
     * **最后交出去的那一屏不走这里**，那是原尺寸画的。
     */
    fun probeScale(viewW: Int, viewH: Int): Float {
        if (viewW <= 0 || viewH <= 0) return 1f
        return minOf(
            PROBE_SIZE.toFloat() / viewW.toFloat(),
            PROBE_SIZE.toFloat() / viewH.toFloat()
        ).coerceAtLeast(1e-4f)
    }

    /**
     * 失败时说人话 —— **说清楚卡在第几步**（5.9.34）。
     *
     * 5.9.33 只有一句 `screen N: nothing rendered (blank)`，三步全归它，
     * 于是我只能靠反推才猜出"整页图其实只有一屏高"。现在三步分开报。
     *
     * @param step 1/2/3/4，见类注释的表
     * @param screenNo 第几屏（1 起）
     * @param yDevice 这一屏在文档里的起点（设备像素）
     */
    fun failure(step: Int, screenNo: Int, yDevice: Int, detail: String = ""): String {
        val tail = if (detail.isEmpty()) "" else " ($detail)"
        return when (step) {
            1 -> "screen $screenNo: the page would not scroll to y=$yDevice$tail"
            2 -> "screen $screenNo: the window showed nothing new at y=$yDevice$tail"
            // 第 4 步是 5.9.35 加的：画面卡住不更新时会拍出**和上一屏一模一样**的图。
            // 那种图"有内容"、判空查不出来，不挡它就会当成正常的一屏交出去。
            4 -> "screen $screenNo: the capture is identical to the previous screen at y=$yDevice$tail"
            else -> "screen $screenNo: the capture came back blank at y=$yDevice$tail"
        }
    }
}