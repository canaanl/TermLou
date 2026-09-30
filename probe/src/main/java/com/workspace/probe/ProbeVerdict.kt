package com.workspace.probe

import org.json.JSONObject
import kotlin.math.abs

/**
 * 判定：把 A 组（不挂窗口）和 B 组（挂 overlay）逐项比。
 *
 * **比的对象是 B 组** —— B 组就是 TermLou 现在在用的、已被实机证明可用的方案。
 * 所以问的不是"无头能跑吗"，而是"**和现在这套差在哪**"。
 *
 * ## v2 修的两处（第一版的断言把好结果判成了坏）
 *
 * 1. **视口那一项**改成：加了 `<meta viewport>` 后 1 CSS px = 1 dp，所以
 *    `innerWidth` 应该**等于我给视图的 dp 宽度**。第一版拿 412 去比，而实际报的是
 *    Chromium 的 980 默认值（因为页面没写 meta）—— 于是好结果被判成失败。
 * 2. **排版那一项**改成自参照：`fiftyW ≈ (innerW - 16) / 2`。body 左右 padding 各 8。
 *    第一版硬编码"应≈206"（412 的一半），而实际视口是 980，正确的值就是 482。
 *    硬编码像素数换个手机就错，自参照不会。
 *
 * 纯逻辑、可单测。
 */
object ProbeVerdict {

    data class Row(
        val name: String,
        val a: Boolean,
        val b: Boolean,
        val aNote: String = "",
        val bNote: String = ""
    ) {
        val same: Boolean get() = a == b
    }

    private fun n(r: RunResult, k: String): Int = r.obs?.optInt(k, -1) ?: -1
    private fun s(r: RunResult, k: String): String = r.obs?.optString(k, "") ?: ""

    /** `fiftyW` 该是多少：视口宽减掉 body 左右 padding 各 8，再取一半。 */
    fun expectedFifty(innerW: Int): Int = (innerW - 16) / 2

    /** CSS 排版是否正确：自参照，不硬编码任何像素数。 */
    fun layoutOk(r: RunResult): Boolean {
        val iw = n(r, "innerW")
        val fw = n(r, "fiftyW")
        if (iw <= 0 || fw < 0) return false
        return abs(fw - expectedFifty(iw)) <= 2
    }

    /** 视口是否由我给的视图尺寸驱动：有 meta 时 1 CSS px = 1 dp。 */
    fun viewportOk(r: RunResult, widthDp: Int): Boolean = n(r, "innerW") == widthDp

    fun rows(a: RunResult, b: RunResult, widthDp: Int): List<Row> = listOf(
        Row("1 页面加载完成", a.loaded, b.loaded, a.loadError ?: "", b.loadError ?: ""),
        Row(
            "2 JS 执行",
            a.loaded && s(a, "title") == "probe-ready",
            b.loaded && s(b, "title") == "probe-ready",
            "title=${s(a, "title")}", "title=${s(b, "title")}"
        ),
        Row(
            "3 视口由视图驱动",
            viewportOk(a, widthDp),
            viewportOk(b, widthDp),
            "innerW=${n(a, "innerW")} (给的是 $widthDp dp)", "innerW=${n(b, "innerW")} (给的是 $widthDp dp)"
        ),
        Row(
            "4 CSS 排版正确",
            layoutOk(a),
            layoutOk(b),
            "50%宽=${n(a, "fiftyW")} (应≈${expectedFifty(n(a, "innerW"))})",
            "50%宽=${n(b, "fiftyW")} (应≈${expectedFifty(n(b, "innerW"))})"
        ),
        Row(
            "5 innerText 有正文",
            n(a, "innerTextLen") > 0,
            n(b, "innerTextLen") > 0,
            "${n(a, "innerTextLen")} 字符", "${n(b, "innerTextLen")} 字符"
        ),
        Row(
            "5b 隐藏内容不进 innerText",
            n(a, "innerTextLen") > 0 && s(a, "innerTextHasSecret") == "false",
            n(b, "innerTextLen") > 0 && s(b, "innerTextHasSecret") == "false",
            "见到SECRET=${s(a, "innerTextHasSecret")}", "见到SECRET=${s(b, "innerTextHasSecret")}"
        ),
        Row(
            "6 图片加载完成",
            s(a, "imgComplete") == "true",
            s(b, "imgComplete") == "true",
            "naturalWidth=${n(a, "imgW")}", "naturalWidth=${n(b, "imgW")}"
        ),
        Row(
            "7 canvas 有像素",
            s(a, "canvasPx").startsWith("255,"),
            s(b, "canvasPx").startsWith("255,"),
            "px=${s(a, "canvasPx")}", "px=${s(b, "canvasPx")}"
        ),
        Row(
            "8 draw() 出图",
            a.drawNonWhitePixels > 50,
            b.drawNonWhitePixels > 50,
            "非白像素=${a.drawNonWhitePixels}", "非白像素=${b.drawNonWhitePixels}"
        ),
        Row(
            "9 点/填能用",
            s(a, "afterClick") == "已点击" && s(a, "afterType") == "probe-typed",
            s(b, "afterClick") == "已点击" && s(b, "afterType") == "probe-typed",
            "click=${s(a, "afterClick")} type=${s(a, "afterType")}",
            "click=${s(b, "afterClick")} type=${s(b, "afterType")}"
        ),
        Row(
            "10 select 能选",
            s(a, "afterSelect") == "b",
            s(b, "afterSelect") == "b",
            "value=${s(a, "afterSelect")}", "value=${s(b, "afterSelect")}"
        ),
        Row(
            "11 导航与历史栈通",
            n(a, "histAfter") > n(a, "histBefore") && s(a, "hash") == "#done",
            n(b, "histAfter") > n(b, "histBefore") && s(b, "hash") == "#done",
            "历史 ${n(a, "histBefore")}→${n(a, "histAfter")} hash=${s(a, "hash")}",
            "历史 ${n(b, "histBefore")}→${n(b, "histAfter")} hash=${s(b, "hash")}"
        )
    )

    /**
     * 视口是不是真的跟着视图尺寸走 —— **v2 新增，也是最关键的一项**。
     *
     * 把不挂窗口的 WebView 分别量成 [smallDp] 与 [largeDp]，看 `innerWidth`
     * 是不是分别等于这两个值。
     *
     * 之前看不出这一点，是因为视图宽 412dp × 密度 2.379 ≈ 980 px，
     * 而 Chromium 无 meta 时的默认视口**也正好是 980** —— 两个数撞在一起，
     * 分不清是"我们给的"还是"白送的"。换两个差异明显的尺寸就无从掩盖了。
     */
    fun viewportFollowsView(small: RunResult, large: RunResult, smallDp: Int, largeDp: Int): Boolean =
        viewportOk(small, smallDp) && viewportOk(large, largeDp)

    /** 真实站点：两组的观测是否一致（标题非空 + 正文有内容）。 */
    fun realSiteComparable(a: RunResult, b: RunResult): Boolean {
        fun ok(r: RunResult) = r.loaded && s(r, "title").isNotEmpty() && n(r, "len") > 20
        return ok(a) && ok(b) && a.drawNonWhitePixels > 50 && b.drawNonWhitePixels > 50
    }

    sealed class Verdict {
        abstract val text: String
        data class DROP_PERMISSION(override val text: String) : Verdict()
        data class FALLBACK(override val text: String) : Verdict()
        data class GIVE_UP(override val text: String) : Verdict()
    }

    fun conclusion(
        rows: List<Row>,
        viewportFollows: Boolean?,
        realOk: Boolean?
    ): Verdict {
        fun ok(nm: String) = rows.first { it.name.startsWith(nm) }
        val equal = rows.count { it.same }

        if (viewportFollows == false) {
            return Verdict.GIVE_UP(
                "**视口不跟着视图尺寸走** —— 把视图量成两个差异明显的宽度，innerWidth 却不跟着变。" +
                    "意味着不挂窗口时视图尺寸被忽略，响应式页面会按错误宽度渲染。" +
                    "**无头不可行，保持现有方案。**"
            )
        }
        if (!ok("3").a || !ok("4").a) {
            return Verdict.GIVE_UP(
                "视口或 CSS 排版不对（3=${ok("3").a} 4=${ok("4").a}），无头不可行，保持现有方案。"
            )
        }
        if (!ok("5 ").a || !ok("5b").a) {
            return Verdict.FALLBACK(
                "排版建立了但 innerText 的可见文字语义不成立（5b=${ok("5b").a}）—— " +
                    "text op 与 text= 选择器会失真，只能落回 overlay。"
            )
        }
        if (!ok("11").a) {
            return Verdict.FALLBACK(
                "导航或历史栈不通（11=${ok("11").a}）—— back / reload 指令会失灵，" +
                    "这类站点只能落回 overlay。"
            )
        }
        if (ok("8").a) {
            val tail = if (realOk == true) "真实站点也一致。" else if (realOk == null) "真实站点未验证。" else "真实站点两组有差异，需查。"
            return Verdict.DROP_PERMISSION(
                "十项里 $equal 项与现有方案等价，**draw() 也能出图**。$tail" +
                    "→ SYSTEM_ALERT_WINDOW 可以彻底去掉。"
            )
        }
        return Verdict.FALLBACK(
            "排版与文字都等价，但 draw() 出不来图 → 权限降级成**只在截图时询问**。"
        )
    }
}
