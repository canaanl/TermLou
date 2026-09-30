package com.workspace.probe

/**
 * 滚动截图：v8。**不踢帧了，换做法。**
 *
 * ## 前七轮量到了什么
 *
 * v5：overlay 与不挂窗口**两组完全一样**，滚到底后纯红像素 0。`eval` 正常
 * （`scrollY=1338`、尾部色块在视口内、正文读得到）→ **WebView 活着，画不出来**。
 *
 * v6：扫 5 种修法（`invalidate` / 软件渲染层 / 软件层+invalidate / 假可见），
 * A、B 共 10 次，**全灭**。
 *
 * v7：把测法补全（顶部也铺纯绿、每处两条截图路都量），扫 11 种：
 * `invalidate` / `onResume` / 软件渲染层 / `onVisibilityAggregated` / 渲染优先级 /
 * 重排版 / `View.scrollTo` / 等 3 秒 / 等 8 秒 / `alpha = 0` 挂屏上，
 * A、B 共 22 次，**红像素全是 0**。
 *
 * v7 顺手排除掉两件事：
 *  - **不是时间问题** —— 滚完等 8 秒：0。
 *  - **不是可见性问题** —— `alpha = 0` 挂屏上：底部红 0，而且**顶部 `draw` 直接变 0**，
 *    比不挂窗口更糟。所以「挂一个看不见的窗口」不是兜底方案，删掉。
 *  - **`capturePicture()` 也坏** —— 底部红同样 0。不再是推测，是实测。换主次救不了。
 *
 * ## v7 自己写错了一处，差点给出相反的结论
 *
 * 判据写成了「任一色块达标就算画出来」。而 `capturePicture()` 返回的是**整页** Picture，
 * 底部那张图里**顶部那块绿也在**，于是「底部画出来了」被误判成成立，
 * 报告打出「问题这次没复现，先别急着改本体」。
 *
 * **那一行是错的，别照它办事。** 红格子全是 0，bug 一直在。
 * 这一版把判据拆开：顶部只认绿、底部只认红。
 *
 * ## 为什么这一版不踢帧
 *
 * 七轮下来「怎么踢出��帧」这个方向已经量完了 —— 11 种 22 次，全灭。
 * 继续加踢帧动作是浪费。所以改成**让那块内容压根不需要新帧**：
 * 不滚、或者把它挪进首帧就已经在的位置。
 *
 * 每个变体只画**一张图**，从同一张图里同时数绿和红 —— 所以横向可比。
 */
object ScrollFix {

    /** 变体怎么把「页面底部那块」弄到能画的地方。 */
    enum class How {
        /** 滚到底就画 —— 对照组，v5~v7 实测红 0。 */
        SCROLL,

        /** 压根不滚：视图量到整页那么高，整页就是首屏。 */
        FULL_PAGE,

        /** 首帧之前就把整页内容挪到底，让底部落在首屏里。 */
        CSS_SHIFT,

        /** 滚完抖一下 `opacity`，逼 Chromium 重画那一块。 */
        REPAINT,

        /** 滚到底之后上下抖 6 下，逼它更新瓦片。 */
        NUDGE,

        /**
         * v9：滚到 99%，留 1px 的滚动余量。
         *
         * `SCROLL` 滚到的最底部是个整像素对齐的静止点，
         * 页面再也没有"没露出来的内容"，Chromium 可能因此认为"不需要新帧"。
         * 之前 11 种全是"逼它出帧"，没有一种是"让它觉得有帧可出"。
         */
        P99,

        /**
         * v9：±1px 循环 20 次，**画的时候它还在抖**。
         *
         * 和 `NUDGE` 的区别：那个是抖完、停稳、回到静止点**之后**才画；
         * 这个量的时候滚动事件还在进，没有机会进入静止点。
         */
        JITTER,

        /**
         * v9：滚到底，然后改底部标记的字色和字样，逼出一个 DOM 脏区。
         *
         * 如果这张红了 → 帧生产本身没坏，坏的是"滚动不标记脏区"。
         */
        DIRTY,

        /**
         * v9：滚完等 20 秒再画。
         *
         * v7 的 WAIT_8S 只等了 8 秒然后量一次 —— 如果帧在 8 秒之后才来，
         * 我们连"它到底会在多久后来"都不知道。这一档补上。
         */
        WAIT20,

        /**
         * v10：预光栅开关打开。
         *
         * 官方文档写明 `setOffscreenPreRaster(true)` 管的正是
         * "attached、但在屏外"这一档 —— B 组（屏外窗口）恰好就是这一档。
         * B 组单测过（不行），但没开过这个开关。A 组（不挂窗口）也跑一遍，
         * 验证文档"detached 不管用"的说法。
         */
        PRERASTER,

        /**
         * v10：滚完等 `postVisualStateCallback` 回调再画。
         *
         * "滚完就画"可能是抢跑 —— visual state 回调是最正的等法。
         * 回调不来会被看门狗记一笔（那本身也是数据：在离屏 WebView 上它不来）。
         */
        VISUAL,

        /**
         * v11：虚拟屏实渲 —— VirtualDisplay + Presentation + PixelCopy。
         *
         * 前面 18 种全是 `View.draw(Canvas)` 这条路。Chromium 官方文档
         * （`software_draw_deprecated.md`，实测存在）明确写：
         * "Do not call webview.onDraw or webview.draw directly"，
         * snapshot 请走 VirtualDisplay + SurfaceTexture + Presentation + readback。
         *
         * 注意它和 overlay 窗口是两回事：VirtualDisplay 是自家私有显示，
         * `DisplayManager.createVirtualDisplay` **不需要悬浮窗权限**，
         * "真无头"保得住。v7 那个 alpha=0 挂屏外窗口是 overlay 路，不相干。
         *
         * 成了 → "路的问题"实锤，本体那套"撑到整页高"可以重写；
         * 不成 → exhaustion 名单再加一行，两种结果都不浪费。
         */
        VDISPLAY,

        /**
         * v12：滚到底并插一个元素，逼整页重排。
         *
         * v9 的 DIRTY 只改字色字样，是装饰性 dirty，测过，不行。
         * 从没试过"布局变了"（scrollHeight 变大、整页重排）能不能逼出新 tile。
         * 红了 = scroll 不调度 raster 但 layout 可以；
         * 还是 0 = "怎么让它 raster"这个方向封口。
         */
        LDIRTY
    }

    /**
     * 候选做法。`BASELINE` 必须是第一个 —— 它是那 22 次量过的已知坏情况，
     * 用来确认这一轮的测法没坏（要是基线这轮冒出红像素，说明测法本身变了，结果不作数）。
     */
    enum class Variant(val label: String, val how: How, val note: String) {
        BASELINE("基线·滚到底", How.SCROLL, "滚到底就画。v5~v7 实测红 0，是对照"),

        /** 不滚。视图量到整页高，整页就是首屏 —— AI agent 要的本来就是整页图。 */
        FULL_PAGE("视图撑到整页", How.FULL_PAGE, "不滚。视图量到整页高，整页就落在首屏里"),

        /**
         * 首帧就画到底部。
         *
         * 用纯 CSS（`body` 贴底 + `html` 裁掉溢出）而不是 JS 挪 —— JS 挪的话
         * 首帧早就画完了，还是要等新帧，那和 `REPAINT` 就没区别了。
         */
        CSS_SHIFT("页面整体贴底", How.CSS_SHIFT, "首帧之前就贴底，不靠新帧。视口不变，版面不变"),

        REPAINT("强制重绘", How.REPAINT, "滚完抖一下 opacity，逼它重画那一块"),

        NUDGE("滚动微颤", How.NUDGE, "滚到底再上下抖 6 下，逼它更新瓦片"),

        P99("滚到99%留1px", How.P99, "不滚到底，留 1px 滚动余量 —— 合成器还有理由出新帧"),

        JITTER("抖着画", How.JITTER, "±1px 循环 20 次，画的时候还在抖，进不了静止点"),

        DIRTY("改字逼重绘", How.DIRTY, "滚到底后改底部标记的字色字样，逼 DOM 脏区"),

        WAIT20("滚完等20秒", How.WAIT20, "帧可能来得比 8 秒还慢。这一档之前没量过"),

        PRERASTER("预光栅开", How.PRERASTER, "setOffscreenPreRaster(true)。B组=官方说的那档，A组=验证文档说法"),

        VISUAL("等visual回调", How.VISUAL, "滚完等postVisualStateCallback再画，不抢跑"),

        VDISPLAY("虚拟屏实渲", How.VDISPLAY, "VirtualDisplay+Presentation+PixelCopy，官方snapshot路径"),

        /**
         * v12：滚到底并插一个元素，逼整页重排。
         *
         * v9 的 DIRTY 只改字色（装饰性），不行。从没试过布局变化。
         * 红了 = scroll 不调度 raster 但 layout 可以；还是 0 = "怎么让它 raster"
         * 这个方向封口，以后指 exhaustion 名单。
         */
        LDIRTY("插元素逼重排", How.LDIRTY, "滚到底后插100px元素，scrollHeight变大整页重排")
    }

    /** 一块色块画出来这么多像素就算数。绿色带 250 CSS px × 1236 设备像素的整条。 */
    const val MIN_PIXELS = 2000

    /**
     * 位图面积上限（像素数）。整页视图 1236 × 7260 ≈ 898 万，正好在下面；
     * 再高就按比例缩小，缩放系数会报出来 —— 免得以为量的是原尺寸。
     */
    const val MAX_PIXELS = 9_000_000

    /**
     * 一条截图路的观测。**只记，不判断。**
     *
     * @param green 纯绿像素数（页面顶部那块）
     * @param red   纯红像素数（页面底部那块）
     * @param tint  三点取色。绿红都是 0 时它告诉我们画出来的是什么颜色 ——
     *              v5 的 `E9E9E9`（页面底色）就是「底色铺满、内容没画」的签名。
     * @param white 纯白像素数（画布底）。v9 新增：和底色分开，
     *              白多 = 连底色都没回放，底色多 = 内容层单独掉了。
     * @param pageBg 页面底色 `#e9e9e9` 附近的像素数。
     * @param total 位图总像素数。四个桶（白/底色/绿/红）互不重叠，
     *              `total - 白 - 底色 - 绿 - 红` 就是"其他未知内容"。
     */
    data class Shot(
        val green: Int,
        val red: Int,
        val tint: String,
        val fail: String? = null,
        val white: Int = -1,
        val pageBg: Int = -1,
        val total: Int = -1
    ) {
        /**
         * 绿色块画出来没有。
         *
         * **只看绿。** 顶层绿色和底层红色必须分开判 ——
         * `capturePicture()` 给的是整页，两种颜色会在同一张图里同时出现，
         * 「任一颜色达标就算」会把「只画出了顶部」误判成「底部也画出来了」。
         * v7 就是这么写错的，差点报出「问题没复现」。
         */
        val hasGreen: Boolean get() = fail == null && green >= MIN_PIXELS

        /** 红色块画出来没有。**只看红**，理由同 [hasGreen]。 */
        val hasRed: Boolean get() = fail == null && red >= MIN_PIXELS

        /**
         * 四个桶都装不下的像素 —— "其他未知内容"。
         *
         * 量不到（任一桶为负）就返回 -1，**不许当成 0**：
         * "没画出来"和"没量出来"是两回事（v8 头一次那个坑的同类）。
         */
        fun others(): Int {
            if (total < 0 || white < 0 || pageBg < 0 || green < 0 || red < 0) return -1
            return (total - white - pageBg - green - red).coerceAtLeast(0)
        }
    }

    /**
     * 一个变体的一张图。
     *
     * @param viewW/viewH  视图实际被量成多大（**设备像素**）—— 全高视图那条要靠它说清生效没
     * @param innerHPx     页面报的 `window.innerHeight`，**调用方已经乘过 density**，
     *                     所以和 `viewH` 是同一把尺子。全高视图的**代价**就在这儿
     * @param blockVisible DOM 说底部那块在不在视口内。不在的话像素数不作数
     */
    data class Spot(
        val draw: Shot,
        val picture: Shot,
        val evalOk: Boolean,
        val scrollY: Int,
        val viewW: Int,
        val viewH: Int,
        val innerHPx: Int,
        val blockVisible: Boolean,
        /** 这一轮的例外说明：位图缩放过、视图没撑开之类。空串表示没例外。 */
        val note: String = "",
        /**
         * v9：视口底部中心那个点，DOM 认为是什么元素（`elementFromPoint`）。
         * 是 `#tail` 说明 DOM 层正常、掉的是后面的管线 —— 不是"滚错了地方"。
         */
        val elAt: String = ""
    ) {
        /** 底部那块被画出来了没有（两条路任一条）。这就是「这个做法成没成」。 */
        val redDrawn: Boolean get() = draw.hasRed || picture.hasRed

        /** 顶部那块被画出来了没有 —— 证明页面本身能画，区分「渲染不了」和「只渲染了首屏」。 */
        val greenDrawn: Boolean get() = draw.hasGreen || picture.hasGreen

        /**
         * 视图够不够高装下整页。`FULL_PAGE` 要是这里为 false，那它压根没生效，
         * 不能当成「这个做法失败了」。**两边都是设备像素**，别拿 CSS 像素来比。
         */
        val viewCoversPage: Boolean get() = viewH > 0 && innerHPx > 0 && viewH >= innerHPx
    }

    /** 一个做法跑完的结果。`stalls` 记这一轮有哪些步骤没跑完 —— 半截的数字不能当结论。 */
    data class Result(val variant: Variant, val spot: Spot, val stalls: String = "")

    /** 挑出有效的做法，按 [Variant] 顺序返回**第一个**有效的。 */
    fun winner(results: List<Result>): Variant? = results.firstOrNull { it.spot.redDrawn }?.variant

    /**
     * 跨变体下结论。
     *
     * @param baselineRed 基线有没有画出红。`false` = 那个 bug 还在（对照组存在的意义）。
     *                    `null` = 基线没跑成，这轮的数一概不信。
     * @param winner      把底部那块画出来了的做法
     * @param voided      判据本身不可信的变体：`eval` 没回值，或者 DOM 说那块根本不在视口里
     */
    data class Diagnosis(
        val baselineRed: Boolean?,
        val everGreen: Boolean,
        val drawRed: Boolean,
        val picRed: Boolean,
        val winner: Variant?,
        val voided: List<Variant>,
        /** 有步骤没跑完的变体 —— 它们的结论只覆盖跑完的那部分。 */
        val stalled: List<Variant> = emptyList()
    ) {
        /** 基线没画出红 = v5~v7 那个 bug 还在。 */
        val bugReproduces: Boolean get() = baselineRed == false

        /** 这轮的测法坏了 —— 基线都冒出红像素了，前面七轮的数不能直接比。 */
        val baselineBroken: Boolean get() = baselineRed == true
    }

    fun diagnose(results: List<Result>): Diagnosis = Diagnosis(
        baselineRed = results.firstOrNull { it.variant == Variant.BASELINE }?.spot?.redDrawn,
        everGreen = results.any { it.spot.greenDrawn },
        drawRed = results.any { it.spot.draw.hasRed },
        picRed = results.any { it.spot.picture.hasRed },
        winner = winner(results),
        voided = results.filter { !it.spot.evalOk || !it.spot.blockVisible }.map { it.variant },
        stalled = results.filter { it.stalls.isNotEmpty() }.map { it.variant }
    )

    /**
     * 给用户看的一段话。**只说测出来的**：
     * 一个做法都没效时要说清这条路走不通，不许把任何东西说成修好了。
     */
    fun summary(results: List<Result>): String {
        if (results.isEmpty()) return "没跑成"
        val d = diagnose(results)
        val L = StringBuilder()
        if (d.voided.isNotEmpty()) {
            L.append("⚠ 这几个的数不作数（eval 没回值，或 DOM 说红块根本不在视口里）：")
                .append(d.voided.joinToString("、") { it.label }).append('\n')
        }
        if (d.stalled.isNotEmpty()) {
            L.append("⚠ 有步骤没跑完（回调没来，被看门狗跳过），这些只覆盖跑完的那部分：")
                .append(d.stalled.joinToString("、") { it.label }).append('\n')
        }
        L.append("基线（滚到底）红像素：").append(
            when (d.baselineRed) {
                true -> "有 —— **这轮测法变了，跟前七轮不可比，先别改本体**"
                false -> "0 → **那个 bug 还在**"
                null -> "没跑成"
            }
        ).append('\n')
        L.append("顶部绿像素：").append(if (d.everGreen) "画出来过 ✓" else "从没画出来 ✗")
            .append("（页面本身能不能画）\n")
        L.append("底部红像素：draw 那条路 ").append(yn(d.drawRed))
            .append("　capturePicture 那条路 ").append(yn(d.picRed)).append('\n')
        L.append(
            when {
                d.baselineBroken -> "**基线都冒出红像素了** —— 说明这一轮的测法或页面变了，" +
                    "v5~v7 的结论不能拿来比。下一步先弄清哪里变了。"
                d.winner != null -> "**有效做法：${d.winner.label}** —— ${d.winner.note}。红色块像素达到 $MIN_PIXELS。"
                else -> "**${results.size} 种做法一个都没把底部那块画出来** —— " +
                    "首屏之外的区域在这台机器上就是画不出来，没有绕过去的办法。" +
                    "只能发版并把 `shot` 的报错改成人话。"
            }
        )
        return L.toString()
    }

    private fun yn(b: Boolean) = if (b) "有 ✓" else "0 ✗"
}
