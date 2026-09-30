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

    /** 自检页：纯本地、无网络、无外部资源，几百毫秒就该出结果。 */
    const val PROBE_HTML = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        "<title>termlou-probe</title></head><body>" +
        "<p id=\"t\">ready</p>" +
        "<div style=\"width:50%;height:10px;background:#123\"></div>" +
        "</body></html>"

    /** 自检要取的东西。 */
    const val PROBE_JS = "(function(){var d=document.getElementById('t');" +
        "var h=document.querySelector('div');" +
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
