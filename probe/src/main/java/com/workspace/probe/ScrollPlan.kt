package com.workspace.probe

/**
 * 一个做法要按哪些步骤走，以及每步等多久。**纯逻辑，可单测。**
 *
 * ## 为什么要把它从 Activity 里抽出来
 *
 * v8 头一次把队列写成「在回调里调下一步」，回调一层层往下传，
 * 其中一环写成了 `{ done }` —— 那是**函数引用**不是调用。
 * Kotlin 会把 lambda 的最后一个表达式强制转成 `Unit`，
 * 于是**编译器一声不吭地收下了**。
 *
 * 结果：每一步都跑完了、PNG 也存到手机上了，但队列永远不往下走，
 * 状态文字冻在 `1/5`，用户只能来问"怎么执行不完"。
 *
 * 抽成计划有两个好处：
 *  1. 步骤顺序变成**能单测的数据**，不再是埋在 Activity 里的接线
 *  2. Activity 压成"照着列表走"的薄壳，形状对不对一眼看得出
 *
 * ## 每步为什么都有预算
 *
 * 这个探针做了 8 版，全程一个超时都没有。回调一丢整条链就永久停住，
 * 而且**不报错、不留线索** —— 卡死本身成了唯一能看到的现象。
 * [budgetMs] 是"这一步回调最迟什么时候该到"；超了就记下卡在哪、继续往下走。
 */
object ScrollPlan {

    /** 回调迟到这么多才判卡。给主线程一点喘息，免得把"有点慢"误判成"卡住"。 */
    const val STALL_SLACK_MS = 4_000L

    enum class Step(val label: String, val budgetMs: Long) {
        /** 加载页面并等首帧。 */
        LOAD("加载页面", 2_500L),

        /** 问页面要整页高度 —— 全高视图那条得靠它撑开视图。 */
        READ_HEIGHT("问页面整页高度", 5_000L),

        /** 把视图量到整页那么高，再等页面按新视口重排。 */
        RESIZE("撑到整页并重排", 2_500L),

        /** 滚到底。 */
        SCROLL("滚到底", 5_000L),

        /** v9：滚到 99%，留 1px 滚动余量，不进静止点。 */
        SCROLL99("滚到99%留1px", 5_000L),

        /** v9：滚到底并改底部标记的字，逼出 DOM 脏区。 */
        DIRTY("滚到底并改字", 5_000L),

        /** v9：起 ±1px 抖动循环（20 次 × 120ms）。量的时候它还在抖。 */
        JITTERLOOP("起±1抖动循环", 5_000L),

        /** v9：等 20 秒，看慢帧来不来。 */
        SOAK("等20秒看帧来不来", 20_000L),

        /** v10：等 visual state 回调。不来就记一笔（那本身也是数据）。 */
        VISUAL("等visual回调再画", 8_000L),

        /** v11：建虚拟屏 + Presentation + 显示上下文 WebView，并等首帧。 */
        VSETUP("建虚拟屏并等首帧", 6_000L),

        /** v11：在虚拟屏那个 WebView 里滚到底。 */
        VSCROLL("虚拟屏里滚到底", 5_000L),

        /** v12：滚到底并插一个 100px 元素，逼整页重排。 */
        LDIRTY("滚到底并插元素", 5_000L),

        /** v11：PixelCopy 从 Surface 取图。 */
        VSHOT("PixelCopy取图", 6_000L),

        /** 抖 `opacity` 逼它重画。 */
        REPAINT("抖 opacity 逼重绘", 5_000L),

        /** 滚到底之后上下抖 6 下。 */
        NUDGE("上下抖 6 下", 6_000L),

        /**
         * 读状态 + 两条截图路各画一次。
         *
         * **收尾步骤：每个计划里恰好一次、且在最后。**
         * 这一步的看门狗是最后一道保险 —— 它不到，队列就永远停在原地。
         */
        MEASURE("读状态并画图", 10_000L)
    }

    /**
     * 每个做法要走哪些步骤。
     *
     * 条目一律写全 `Step.XXX`：Kotlin **不会**把嵌套枚举的成员带进外层对象的作用域，
     * 少写就是「Unresolved reference」。踩过。
     */
    fun stepsFor(how: ScrollFix.How): List<Step> = when (how) {
        ScrollFix.How.SCROLL -> listOf(Step.LOAD, Step.SCROLL, Step.MEASURE)
        ScrollFix.How.REPAINT -> listOf(Step.LOAD, Step.SCROLL, Step.REPAINT, Step.MEASURE)
        ScrollFix.How.NUDGE -> listOf(Step.LOAD, Step.SCROLL, Step.NUDGE, Step.MEASURE)

        // 偏移烤在 HTML 里，首帧就画到底部 —— 不用滚，也不用踢帧
        ScrollFix.How.CSS_SHIFT -> listOf(Step.LOAD, Step.MEASURE)

        // 不滚，但要先知道整页多高才撑得开；撑开之后要等页面按新视口重排
        ScrollFix.How.FULL_PAGE -> listOf(Step.LOAD, Step.READ_HEIGHT, Step.RESIZE, Step.MEASURE)

        // v9：之前 11 种全是"逼它出帧"，这 4 种是"让它觉得有帧可出" ——
        // 测的是不同的东西，所以单独列
        ScrollFix.How.P99 -> listOf(Step.LOAD, Step.SCROLL99, Step.MEASURE)
        ScrollFix.How.DIRTY -> listOf(Step.LOAD, Step.DIRTY, Step.MEASURE)
        ScrollFix.How.JITTER -> listOf(Step.LOAD, Step.SCROLL, Step.JITTERLOOP, Step.MEASURE)
        ScrollFix.How.WAIT20 -> listOf(Step.LOAD, Step.SCROLL, Step.SOAK, Step.MEASURE)

        // v10：preraster 只是个开关，步骤和基线一样 —— 差别在 setup 里，不在步骤里
        ScrollFix.How.PRERASTER -> listOf(Step.LOAD, Step.SCROLL, Step.MEASURE)
        ScrollFix.How.VISUAL -> listOf(Step.LOAD, Step.SCROLL, Step.VISUAL, Step.MEASURE)

        // v11：主 wv 碰都不碰 —— 建自己的虚拟屏 WebView，滚它，PixelCopy 取图。
        // 和其他 11 个比的不是"做法"，是"路"。
        ScrollFix.How.VDISPLAY -> listOf(Step.VSETUP, Step.VSCROLL, Step.VSHOT, Step.MEASURE)

        // v12：如果这张红了，根因收敛到"scroll 不调度 raster 但 layout 可以"；
        // 还是 0，"怎么让它 raster"这个方向封口
        ScrollFix.How.LDIRTY -> listOf(Step.LOAD, Step.LDIRTY, Step.MEASURE)
    }

    /** `CSS_SHIFT` 要的是另一个页面 —— 偏移得在首帧之前就到位。 */
    fun usesShiftedPage(how: ScrollFix.How): Boolean = how == ScrollFix.How.CSS_SHIFT

    /** 一个计划最多能花多久（不含看门狗宽限）。看门狗上界就靠它。 */
    fun worstCaseMs(how: ScrollFix.How): Long =
        stepsFor(how).sumOf { it.budgetMs } + STALL_SLACK_MS
}
