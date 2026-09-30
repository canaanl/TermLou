package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `shot` 整页判定的锁。
 *
 * ## 这套判定为什么存在
 *
 * 5.9.7 之前 `shot` 只能截一屏。页面滚出去之后 `draw()` 画出来的是**一整片页面底色**
 * —— 那块内容压根没被光栅化。
 *
 * 真机探针（`:probe` v8）扫了 11 种"踢一帧"的办法，**22 次全灭**：
 * `invalidate` / `onResume` / 软件渲染层 / `onVisibilityAggregated` / 渲染优先级 /
 * 重排版 / `View.scrollTo` / 等 3 秒 / 等 8 秒 / `alpha=0` 挂屏上。
 * 可见性、时间、渲染层三条路全部排除。
 *
 * 唯一实测成立的是**把视图量到整页那么高**。原理：视图一旦高过整页，页面就没有
 * 滚动区了，**整页就是首屏**。两组（挂窗口 / 不挂窗口）× 两条截图路四张全绿。
 *
 * 探针里另一条全绿的是"页面整体贴底"，**不作为修法**：
 * 它把底部挪进视口，代价是**顶部被推出视口**（绿像素 0）。换个页面高度就露馅。
 * 视图高度是照着页面算出来的，没有这种情况。
 *
 * ## 这是绕过，不是修好
 *
 * 机制仍然不知道。代价是长页面会按整页高度**重新排版一次**。下面锁不住这件事，
 * 但能锁住"别让 agent 拿到一张自己不知道被缩过的图"。
 */
class WebShotPlanTest {

    // 真机那台：vivo V2505A，density=3.0，视口 412×892dp = 1236×2676 设备像素。
    // 探针的长页面 2420 CSS px = 7260 设备像素
    private val w = 1236
    private val h = 2676
    private val fullPage = 7260

    // ---------- 什么时候走整页 ----------

    @Test
    fun `短页面走原来的路且一行行为都不变`() {
        val p = WebShotPlan.decide(1000, h, w)
        assertEquals(WebShotPlan.Mode.VIEWPORT, p.mode)
        assertEquals("视口宽不能动", w, p.viewWidthPx)
        assertEquals("视口高不能动", h, p.viewHeightPx)
        assertEquals("一屏图不缩放", 1f, p.scale, 0f)
        assertEquals("", p.note)
        assertFalse(p.isLong)
    }

    @Test
    fun `比视口矮的页面也走原来的路`() {
        assertEquals(WebShotPlan.Mode.VIEWPORT, WebShotPlan.decide(2000, h, w).mode)
    }

    @Test
    fun `刚过1_5倍就切整页`() {
        val cut = (h * WebShotPlan.FULL_PAGE_RATIO).toInt()
        assertEquals("不到门槛就该走一屏", WebShotPlan.Mode.VIEWPORT, WebShotPlan.decide(cut - 1, h, w).mode)
        assertEquals("过了门槛就该走整页", WebShotPlan.Mode.FULL_PAGE, WebShotPlan.decide(cut, h, w).mode)
    }

    @Test
    fun `长页面走整页且视图要撑到整页高`() {
        val p = WebShotPlan.decide(fullPage, h, w)
        assertEquals(WebShotPlan.Mode.FULL_PAGE, p.mode)
        assertTrue(p.isLong)
        // **整页模式的全部原理就在这一行**：视图高过整页 → 没有滚动区 → 整页就是首屏
        assertEquals("视图必须高过整页", fullPage, p.viewHeightPx)
        assertTrue("必须真的高过视口", p.viewHeightPx > h)
        assertEquals(w, p.viewWidthPx)
    }

    // ---------- 问不到高度时要说实话 ----------

    @Test
    fun `问不到页面高度就走一屏并明说`() {
        for (bad in listOf(0, -1, -9999)) {
            val p = WebShotPlan.decide(bad, h, w)
            assertEquals("量到 $bad 就该退回一屏", WebShotPlan.Mode.VIEWPORT, p.mode)
            assertTrue("必须承认量不到：$p", p.heightUnknown)
            assertTrue("必须说清这是一屏不是整页：${p.note}", p.note.contains("unknown"))
            assertFalse("不能假装是整页", p.isLong)
        }
    }

    // ---------- 面积上限 ----------

    @Test
    fun `面积在上限之内不缩放`() {
        assertEquals(1f, WebShotPlan.scaleFor(1236, 7260), 0.0001f)
    }

    @Test
    fun `超上限按面积等比缩小`() {
        val s = WebShotPlan.scaleFor(1236, 72600)
        assertTrue("该缩：$s", s < 1f)
        // 缩完必须真的落回上限之内
        val px = (1236 * s).toLong() * (72600 * s).toLong()
        assertTrue("缩完还超上限：$px", px <= WebShotPlan.MAX_PIXELS)
    }

    @Test
    fun `缩过了必须在note里说清`() {
        val p = WebShotPlan.decide(72600, h, w)
        assertTrue("该缩", p.downscaled)
        assertTrue("必须说缩到百分之几：${p.note}", p.note.contains("%"))
        assertTrue("必须报出原尺寸：${p.note}", p.note.contains("72600"))
    }

    @Test
    fun `没缩过就不要多嘴`() {
        assertFalse(WebShotPlan.decide(fullPage, h, w).downscaled)
        assertEquals("", WebShotPlan.decide(fullPage, h, w).note)
    }

    @Test
    fun `缩放按面积算不是按边长算`() {
        // 边长各砍一半的话面积是 1/4，会缩过头；按面积等比应该是 1/sqrt(2)
        val over = (WebShotPlan.MAX_PIXELS * 2).toInt()
        val s = WebShotPlan.scaleFor(w, over)
        val expect = Math.sqrt(WebShotPlan.MAX_PIXELS.toDouble() / (w.toDouble() * over)).toFloat()
        assertEquals(expect, s, 0.0005f)
    }

    // ---------- 退化输入不能崩 ----------

    @Test
    fun `视口尺寸为零或负也不崩`() {
        for (vp in listOf(0, -1, 1)) {
            val p = WebShotPlan.decide(fullPage, vp, vp)
            assertTrue("视口尺寸得被兜到至少 1：$p", p.viewWidthPx >= 1 && p.viewHeightPx >= 1)
        }
    }

    @Test
    fun `缩放系数不会算出零或负`() {
        val s = WebShotPlan.scaleFor(0, 0)
        assertTrue("必须是正数：$s", s > 0f)
    }

    // ---------- 那段 JS ----------

    @Test
    fun `问高度的脚本是一行且不裸奔`() {
        val js = WebShotPlan.PAGE_HEIGHT_JS
        assertFalse("不能有换行，否则 evaluateJavascript 拿到的不是想要的东西", js.contains("\n"))
        assertTrue("必须用 String() 包一层：回值是 JSON 字符串，裸数字 JSONObject 会抛", js.startsWith("String("))
        assertTrue(js.contains("scrollHeight"))
        // 5.9.4 踩过：String(null) 会变成字符串 "null"，所以要有兜底
        assertTrue("必须有 ||0 兜底", js.contains("||0"))
    }

    // ---------- 自检：把判据写回旧版会被抓住 ----------

    @Test
    fun `自检——阈值必须是1_5不是1`() {
        assertEquals(1.5f, WebShotPlan.FULL_PAGE_RATIO, 0f)
    }

    @Test
    fun `自检——上限必须是900万`() {
        assertEquals(9_000_000, WebShotPlan.MAX_PIXELS)
    }
}

/**
 * 「取到值」与「没取到」必须分得开 —— 5.9.9 踩过的那个坑。
 *
 * ## 踩的是什么
 *
 * `opShot` 里取页面高度原本写成一条链：
 *
 * ```kotlin
 * val h = evalInPage(...)
 *     .let { outcomeError(it) }
 *     ?.let { return err(it) }
 *     ?.toIntOrNull()
 * ```
 *
 * `outcomeError()` **成功时返回 null**。于是成功那一路上 `?.let` 直接短路，
 * `?.toIntOrNull()` **根本不执行** —— eval 明明回了 `2885`，
 * `pageHcss` 却永远是 null，整页判定每次都收到 0。
 *
 * 真机表现：每张截图都带 `note: page height unknown`，
 * 而同一台机同一时刻 `{"op":"eval","js":"document.documentElement.scrollHeight"}`
 * 回的是 `2885`。**通道是好的，是取值那行把它丢了。**
 *
 * ## 为什么扫源码
 *
 * 这段贴着 WebView，单测跑不到。但形状是可以查的：
 * **`outcomeError(...)` 后面必须紧跟一条独立语句，不许再串 `?.` 链。**
 */
class EvalValueTakenTest {

    private fun serviceSource(): String {
        var d: File? = File("").absoluteFile
        var hops = 0
        while (d != null && hops < 6) {
            val f = File(d, "app/src/main/java/com/workspace/proot/WebAutomationService.kt")
            if (f.isFile) return f.readText()
            d = d.parentFile
            hops++
        }
        throw AssertionError("找不到 WebAutomationService.kt，这条检查等于没跑")
    }

    /**
     * `opShot` 的函数体：**从它自己到下一个顶层 `private fun` 之前**。
     *
     * ⚠ 早先这里找的是 `doScrollStep` —— 那是 **`:probe` 模块**里的函数，
     * 本体没有。`indexOf` 返回 -1 之后我图省事兜成"到文件末尾"，
     * 于是这条检查扫的是整个文件、却以为只扫了 `opShot`。
     * 注入一段坏代码验证它会不会红时，它当然不红 —— **检查是空跑的**。
     * 记下来：扫源码的检查，边界条件必须自己验证过一次。
     */
    private fun opShotBody(): String {
        val src = serviceSource()
        val from = src.indexOf("    private fun opShot(")
        if (from < 0) throw AssertionError("找不到 opShot")
        val next = src.indexOf("\n    private fun ", from + 1)
        val end = if (next < 0) src.length else next
        val body = src.substring(from, end)
        if (body.length > 6000) {
            throw AssertionError("opShot 的片段有 ${body.length} 字符，边界划错了（多半是没找到下一个函数）")
        }
        return body
    }

    @Test
    fun `opShot的片段边界是对的`() {
        // 上一条注解里那个坑的自检：片段必须是"一个函数"，不是半个文件
        val body = opShotBody()
        assertTrue("片段太短，没抓到 opShot", body.length > 500)
        assertFalse("片段里不该出现第二个 public 顶层函数", body.contains("fun opDiag("))
    }

    @Test
    fun `查错与取值不许串成一条链`() {
        val body = opShotBody()
        // 出现"取到错误就 return"的地方，都必须**换行**再取值 ——
        // 串成 `?.let{return}.?.xxx` 就会在成功时把值丢掉
        for (m in Regex("""outcomeError\([^)]*\)\?\.let \{ return[^\n]*""").findAll(body)) {
            val lineEnd = body.indexOf('\n', m.range.last)
            val after = body.substring(m.range.last + 1, (if (lineEnd < 0) body.length else lineEnd))
            assertFalse(
                "`outcomeError` 之后同一行不许再串 ?. 链 —— 成功时它返回 null，会短路掉取值：\n" +
                    after.trim(),
                after.contains("?.")
            )
        }
    }

    @Test
    fun `整页判定之前必须真的把高度取出来`() {
        val body = opShotBody()
        assertTrue(
            "必须先 outcomeError 再取 pageHcss",
            body.contains("val heightOutcome = evalInPage(")
        )
        assertTrue(
            "必须从 heightOutcome 取值（不是从一条丢掉结果的链里取）",
            body.contains("heightOutcome.valueOrNull()?.toIntOrNull()")
        )
    }
}

/**
 * 「量完必须还原视口」这条的锁。
 *
 * ## 为什么用扫源码来锁
 *
 * 整页模式会把视图量到整页高再量回视口。**万一没还原**，页面就停在整页版式上，
 * 后面 `click` / `type` 按视口坐标算出来的位置全错 —— 那比"截不到图"糟得多，
 * 而且**在单测里测不到**：这段代码贴着 WebView，没有 Robolectric 跑不了。
 *
 * 所以直接查源码里那个 `finally`。这是"测不出接线"时唯一能用的办法
 * （探针那边因为同类问题卡死过一次，见 `CallbackWiringTest`）。
 */
class ShotViewportRestoreTest {

    private fun serviceSource(): String {
        var d: File? = File("").absoluteFile
        var hops = 0
        while (d != null && hops < 6) {
            val f = File(d, "app/src/main/java/com/workspace/proot/WebAutomationService.kt")
            if (f.isFile) return f.readText()
            d = d.parentFile
            hops++
        }
        throw AssertionError("找不到 WebAutomationService.kt，这条检查等于没跑")
    }

    @Test
    fun `截图那段必须在finally里量回视口`() {
        val src = serviceSource()
        val body = src.substringAfter("private fun opShot(").substringBefore("SHOT_NOTHING =")
        assertTrue("没找到 opShot 的函数体", body.isNotEmpty())

        val finallyAt = body.indexOf("finally {")
        assertTrue("opShot 里没有 finally —— 截图一出问题视口就回不去了", finallyAt >= 0)

        val restoreAt = body.indexOf("layoutView(wv, vw, vh)", finallyAt)
        assertTrue(
            "finally 里没有把视图量回视口高 vh —— 不还原的话后面 click/type 的坐标全错",
            restoreAt > finallyAt
        )
    }

    @Test
    fun `量回视口的高度必须来自视口而不是计划`() {
        val src = serviceSource()
        val body = src.substringAfter("private fun opShot(").substringBefore("SHOT_NOTHING =")
        // `plan.viewHeightPx` 是整页高；还原必须用 `vh`
        assertTrue(
            "还原时不能拿 plan.viewHeightPx 顶上，那等于没还原",
            body.contains("layoutView(wv, vw, vh)")
        )
    }

    @Test
    fun `那句误导人的旧报错不许回来`() {
        // 只看代码行。KDoc 里正引着那两句 —— 那是在讲它们为什么不对，不算
        val code = serviceSource().lines()
            .map { it.trimStart().let { t -> if (t.startsWith("*") || t.startsWith("/*")) "" else it } }
            .joinToString("\n")
        assertFalse(
            "「retry after wait」在滚出去那块画不出来时是骗人的 —— agent 会无限重试",
            code.contains("page not rendered yet")
        )
        assertFalse("「view not laid out」也不对：视图一直是量好的", code.contains("view not laid out"))
    }
}
