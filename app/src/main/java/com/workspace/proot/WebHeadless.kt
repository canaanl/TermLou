package com.workspace.proot

/**
 * 无头能力的判定（5.9.7），纯逻辑、可单测。
 *
 * ## 为什么需要自检
 *
 * TermLou 现在**不挂任何窗口**，只手工 `measure()` + `layout()` 给 WebView 一个尺寸，
 * 让 Chromium 自己排版、跑 JS、出图。实机探针（`:probe` 模块）验证过这在
 * vivo V2505A / WebView 151.0.7922.199 上与"挂在屏外 overlay 窗口里"**完全等价**。
 *
 * **但那不是官方 API 保证的行为** —— 官方对 WebView 的定位是"UI 的一部分"，
 * 唯一的离屏 API（`setOffscreenPreRaster`）不但要求挂在窗口上，还早已废弃移除。
 * 也就是说：WebView 是独立更新的组件，哪天改了内部假设，`text` 会**静默**返回空
 * —— 不报错、不崩，只是什么都没有。
 *
 * 所以每次服务启动都跑一次自检：通了走无头，不通**自动落回** overlay 窗口。
 * 用户看到的最多是"退回旧方案"，而不是"内容突然空了"。
 */
object WebHeadless {

    /** WebView 该用哪种承载方式。 */
    enum class Mode {
        /** 不挂窗口：正常路径，不需要任何权限。 */
        HEADLESS,

        /** 挂在屏外 overlay 窗口：自检没通过的兜底，需要悬浮窗权限。 */
        OVERLAY
    }

    /**
     * 自检页必须**有大块颜色**，不然会被判成空图。
     *
     * 判空走 [WebShotSampler.looksBlank]：8×12 网格抽样，**采到 2 种颜色**才算有内容。
     * 自检页若只有一行小字加一根细条，在 240×480 的视口上网格步长约 30×40 CSS px，
     * 那点内容几乎必然被抽空 —— 于是整屏同色判成空图，
     * **而那时 draw() 其实完全正常**。这会让自检给出假失败、把明明可用的设备
     * 逼去要悬浮窗权限（5.9.7 第一版就踩了：真机 diag 报"截图出不来图"，
     * 而同一台手机上探针实测 draw() 出了 115 万非白像素）。
     *
     * 下面的色块盖住大半屏，抽样不可能落空。
     */
    const val PROBE_BAR_CSS_PX = 150

    /** 探测视口高（CSS px）。判空抽样步长 = 它 / [WebShotSampler.ROWS]。 */
    const val PROBE_VIEWPORT_H_CSS = 480

    /**
     * 自检页：纯本地、无网络、无外部资源。
     *
     * 三样各测一件事：
     *  - `#t` 的文字 → JS 能不能读
     *  - `#w`（`width:50%`，在 8px padding 的 `#box` 里）→ CSS 排版有没有真发生
     *  - 底部两块大色块 → `draw()` 出不出像素
     */
    const val PROBE_HTML = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<title>termlou-probe</title>" +
        "<style>body{margin:0;background:#e9e9e9}#box{padding:8px}</style>" +
        "</head><body>" +
        "<div id=\"box\"><p id=\"t\">ready</p>" +
        "<div id=\"w\" style=\"width:50%;height:20px;background:#123456\"></div></div>" +
        // 大色块：保证 8×12 的判空抽样落不到空处
        "<div style=\"height:${PROBE_BAR_CSS_PX}px;background:#334455\"></div>" +
        "<div style=\"height:${PROBE_BAR_CSS_PX}px;background:#99aacc\"></div>" +
        "</body></html>"

    /**
     * 自检要取的东西。
     *
     * ⚠ **必须 `getElementById('w')`，不能用 `querySelector('div')`**（5.9.9 修）：
     * 5.9.7 的探针页里那个 50% 宽的 div 是页面上**唯一**的 div，`querySelector`
     * 碰巧取对了。5.9.8 为了迁就 `body{margin:0}` 给它加了 `#box` 包裹层，
     * 于是 `querySelector('div')` 改成取**满宽的 `#box`** ——
     * `layoutConsistent` 拿满宽去比半宽，**恒为 false**，
     * 真机上 `headless_failed` 一直挂着"CSS 排版不对"。
     * 根因是取错了元素，不是设备问题。
     */
    const val PROBE_JS = "(function(){var d=document.getElementById('t');" +
        "var h=document.getElementById('w');" +
        "return JSON.stringify({t:d?d.textContent:''," +
        "w:h?Math.round(h.getBoundingClientRect().width):-1," +
        "iw:window.innerWidth,ih:window.innerHeight});})()"

    /**
     * 自检页正文里的那半行 div，宽应当正好是视口的一半（减掉 body 默认 margin 8×2）。
     *
     * 这一项防的是"看起来有内容其实是巧合"：没有它，一个 0×0 的视口也可能照样
     * 返回文字，而那意味着 CSS 排版根本没发生。
     */
    fun layoutConsistent(viewportW: Int, halfDivW: Int): Boolean {
        if (viewportW <= 0 || halfDivW < 0) return false
        val expect = (viewportW - 16) / 2
        return kotlin.math.abs(halfDivW - expect) <= 2
    }

    /** 视口是否由我们给的尺寸驱动。 */
    fun viewportDriven(innerW: Int, expectedW: Int): Boolean = innerW == expectedW

    /**
     * 自检结果 → 该用哪种模式。
     *
     * 判据从严：**文字、CSS 排版、视口、截图，四项全过才算无头可用**。
     * 少一项就落回 overlay —— 宁可慢一点、丑一点，也不要"看着在跑其实什么也没渲染"。
     */
    fun decide(
        loaded: Boolean,
        innerTextOk: Boolean,
        viewportOk: Boolean,
        layoutOk: Boolean,
        drawHasInk: Boolean
    ): Mode {
        if (!loaded || !innerTextOk || !viewportOk || !layoutOk || !drawHasInk) return Mode.OVERLAY
        return Mode.HEADLESS
    }

    /** 不可用时给用户看的一句话（落到状态栏与 diag）。 */
    fun reasonFor(m: Mode): String = when (m) {
        Mode.HEADLESS -> "无头可用（不挂窗口，无需悬浮窗权限）"
        Mode.OVERLAY -> "这台设备的 WebView 不能在不挂窗口时正常渲染，已退回屏外窗口模式（需要悬浮窗权限）"
    }

    /** 需要向用户解释哪几项没过 —— 只报没过的，通过的不占地方。 */
    fun failedChecks(
        loaded: Boolean, innerTextOk: Boolean, viewportOk: Boolean, layoutOk: Boolean, drawHasInk: Boolean
    ): String {
        val bad = buildList {
            if (!loaded) add("页面没加载完")
            if (!innerTextOk) add("取不到正文")
            if (!viewportOk) add("视口不跟视图走")
            if (!layoutOk) add("CSS 排版不对")
            if (!drawHasInk) add("截图出不来图")
        }
        return bad.joinToString("、")
    }
}
