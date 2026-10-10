package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [ShotRunner] 的锁（5.9.34 新建，替掉 `WebShotPlanTest` + `WebScrollShotTest`）。
 *
 * 那两个文件测的东西在 5.9.34 全删了：
 *
 * - `WebShotPlan`（`Plan/scaleFor/MAX_PIXELS/FULL_PAGE_RATIO`）—— 没有长图了，不缩放，
 * - `WebScrollShot`（`plan/Segment/sourceY/scrollSrcRect`）—— 不分段也不拼接了，
 * - 5.9.33 的 `scrollSrcRect` 切块 —— 那是把 1 像素高的白条当成第 2 屏的那一刀。
 *
 * 保留下来的只有本文件里的这些：问度量、要不要滚、下一屏在哪、滚到位没有、
 * 探针多大、失败了怎么说 —— 全部纯逻辑，单测不碰安卓。
 */
class ShotRunnerTest {

    // ---------- 度量解析 ----------

    @Test
    fun `度量能解析出来`() {
        val m = ShotRunner.parseMetrics("""{"h":7443,"y":2676,"vh":892,"ready":"complete"}""")!!
        assertEquals(7443, m.pageHeightCss)
        assertEquals(2676, m.scrollYCss)
        assertEquals(892, m.viewportHCss)
        assertEquals("complete", m.readyState)
    }

    @Test
    fun `最大滚动量是整页减视口`() {
        val m = ShotRunner.parseMetrics("""{"h":7443,"y":0,"vh":892,"ready":"complete"}""")!!
        assertEquals(7443 - 892, m.maxScrollCss)
    }

    @Test
    fun `页面比视口矮时最大滚动量是零`() {
        val m = ShotRunner.parseMetrics("""{"h":800,"y":0,"vh":892,"ready":"complete"}""")!!
        assertEquals("不能是负数", 0, m.maxScrollCss)
    }

    @Test
    fun `解析不出来必须返回null而不是全零`() {
        // ⚠ 当成 0 会把"到底了"判成真，于是只拍一屏就停 —— 那是最阴的一种错
        for (bad in listOf(null, "", "   ", "not json", "{}", """{"y":10}""")) {
            assertNull("不该把 [$bad] 解析成度量", ShotRunner.parseMetrics(bad))
        }
    }

    @Test
    fun `负数度量被兜到零`() {
        val m = ShotRunner.parseMetrics("""{"h":-5,"y":-9,"vh":-1,"ready":""}""")!!
        assertEquals(0, m.pageHeightCss)
        assertEquals(0, m.scrollYCss)
        assertEquals(0, m.viewportHCss)
    }

    // ---------- 要不要滚着拍 ----------

    @Test
    fun `比一屏高就分屏没有门槛`() {
        val h = 1056
        assertFalse("一屏高就走一屏", ShotRunner.needsScroll(h, h))
        assertTrue("比一屏高一像素就要分屏", ShotRunner.needsScroll(h + 1, h))
        assertTrue("一屏半也必须分屏", ShotRunner.needsScroll(h * 3 / 2, h))
    }

    @Test
    fun `比视口矮或者量不到高度都不分屏`() {
        assertFalse(ShotRunner.needsScroll(0, 1056))
        assertFalse(ShotRunner.needsScroll(500, 1056))
    }

    @Test
    fun `视口为零时按一屏处理而不是崩`() {
        // 视口 0 会让"每屏高 0"永远走不动。夹成 1，于是 5000 仍然要分屏 ——
        // **不能拿 0 当视口说"不用分屏"**，那会只交一张空图还说自己是整页。
        assertTrue("5000 高的页面在视口为 0 时仍要分屏", ShotRunner.needsScroll(5000, 0))
        assertFalse("页面也为 0 时不分屏", ShotRunner.needsScroll(0, 0))
    }

    // ---------- 下一屏起点 ----------

    @Test
    fun `还有下文就往前推一屏`() {
        assertEquals(1056, ShotRunner.nextScreenTop(0, 1056, 6000))
        assertEquals(2112, ShotRunner.nextScreenTop(1056, 1056, 6000))
    }

    @Test
    fun `到底了返回null`() {
        // 实测判据：next <= current 就是底。不是"拍够 N 屏"。
        assertNull(ShotRunner.nextScreenTop(4000, 2000, 4000))
        assertNull(ShotRunner.nextScreenTop(4000, 2000, 3000))
        assertNull(ShotRunner.nextScreenTop(0, 2000, 0))
    }

    @Test
    fun `最后一步夹到最大滚动位置`() {
        // 整页 5000、视口 2000：第三步不能是 6000（到不了），夹到 3000
        assertEquals(3000, ShotRunner.nextScreenTop(2000, 2000, 3000))
    }

    @Test
    fun `走到最底之后不再前进`() {
        var y = 0
        val maxScroll = 5000
        var steps = 0
        while (true) {
            val next = ShotRunner.nextScreenTop(y, 2000, maxScroll) ?: break
            y = next
            steps++
            if (steps > 50) throw AssertionError("走不到底")
        }
        assertEquals("5000/2000 应走 3 次到顶", 3, steps)
        assertEquals(maxScroll, y)
    }

    @Test
    fun `每屏一屏高直到实测到底`() {
        // 真机口径：视口 1056、整页 12000 → 走 12 屏，第 12 屏停在最底
        val vh = 1056
        val page = 12000
        val maxScroll = page - vh
        var y = 0
        var screens = 1
        while (true) {
            val next = ShotRunner.nextScreenTop(y, vh, maxScroll) ?: break
            y = next
            screens++
        }
        assertEquals("12000/1056 应该拍 12 屏", 12, screens)
        assertEquals(maxScroll, y)
    }

    // ---------- 滚动到位 ----------

    @Test
    fun `回读到位才算滚到了`() {
        assertTrue(ShotRunner.scrollLanded(1000, 1000))
        // 容差 2：scroll-snap 与子像素布局差一两像素不算没滚到
        assertTrue(ShotRunner.scrollLanded(1000, 1002))
        assertFalse(ShotRunner.scrollLanded(1000, 1003))
        assertFalse(ShotRunner.scrollLanded(1000, 0))
    }

    @Test
    fun `密度是小数时不能先截断`() {
        // density 2.75 / 3.5 都很常见：`density.toInt()` 会把高度算错一截
        assertEquals(400, ShotRunner.toCss(1100, 2.75f))
        assertEquals(200, ShotRunner.toCss(700, 3.5f))
        assertEquals("密度为 0 时不许除出无穷", 700, ShotRunner.toCss(700, 0f))
    }

    // ---------- 探针 ----------

    @Test
    fun `探针是正方形且不超上限`() {
        val s = ShotRunner.probeScale(480, 1056)
        val w = (480 * s).toInt()
        val h = (1056 * s).toInt()
        assertTrue("宽不该超 $w", w <= ShotRunner.PROBE_SIZE)
        assertTrue("高不该超 $h", h <= ShotRunner.PROBE_SIZE)
        assertTrue("不许是 0：$w x $h", w >= 1 && h >= 1)
    }

    @Test
    fun `视口为零时探针因子是1而不是除零`() {
        assertEquals(1f, ShotRunner.probeScale(0, 0), 0f)
    }

    // ---------- 失败时说哪一步 ----------

    @Test
    fun `三步失败说得不一样`() {
        val a = ShotRunner.failure(1, 2, 2676)
        val b = ShotRunner.failure(2, 2, 2676)
        val c = ShotRunner.failure(3, 2, 2676)
        assertEquals("都带屏号与位置", 3, setOf(a, b, c).size)
        assertTrue(a, a.contains("would not scroll"))
        assertTrue(b, b.contains("showed nothing new"))
        assertTrue(c, c.contains("came back blank"))
    }

    @Test
    fun `三步失败都不许再报那句含糊的blank`() {
        // 真机 5.9.31 / 5.9.32 / 5.9.33 连续三版都卡在同一句上，而它什么都没说清
        for (step in 1..3) {
            val msg = ShotRunner.failure(step, 2, 2676)
            assertFalse("第 $step 步不该含糊：$msg", msg.contains("nothing rendered"))
            assertTrue("第 $step 步要带位置：$msg", msg.contains("y=2676"))
        }
    }

    @Test
    fun `细节跟在括号里不影响判读`() {
        val m = ShotRunner.failure(2, 3, 5352, "within 4000ms")
        assertTrue(m, m.contains("screen 3"))
        assertTrue(m, m.contains("within 4000ms"))
    }
}

/**
 * 「查错与取值不许串成一条链」这条锁 —— 从 `WebShotPlanTest.EvalValueTakenTest` 搬过来。
 *
 * ## 踩的是什么
 *
 * `opShot` 里取页面度量原本写成一条链：
 *
 * ```kotlin
 * val h = evalInPage(...)
 *     .let { outcomeError(it) }
 *     ?.let { return err(it) }
 *     ?.toIntOrNull()
 * ```
 *
 * `outcomeError()` **成功时返回 null**。于是成功那一路上 `?.let` 直接短路，
 * `?.toIntOrNull()` **根本不执行** —— eval 明明回了 `2885`，页面高度却永远是 0。
 *
 * 真机表现：每张截图都带 `note: page height unknown`。
 *
 * ## 为什么扫源码
 *
 * 这段贴着 WebView，单测跑不到。但形状可以查：
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
     * ⚠ 边界必须自己验一次：早先这里找的是一个**别的模块**里的函数，
     * `indexOf` 返回 -1 之后兜成"到文件末尾"，于是检查扫了整个文件却以为只扫了 `opShot` ——
     * 注入坏代码验证它会不会红时它当然不红，**检查是空跑的**。
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
        assertFalse("片段里不该出现第二个顶层函数", body.contains("fun opBack("))
    }

    @Test
    fun `查错与取值不许串成一条链`() {
        val body = opShotBody()
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
    fun `判定前必须真的把页面度量取出来`() {
        val body = opShotBody()
        assertTrue(
            "必须先 outcomeError 再取度量",
            body.contains("val metricsOutcome = evalInPage(")
        )
        assertTrue(
            "必须从 metricsOutcome 取值（不是从一条丢掉结果的链里取）",
            body.contains("ShotRunner.parseMetrics(metricsOutcome.valueOrNull())")
        )
    }
}

/**
 * 5.9.33 / 5.9.34：整页截图**拍完不滚回原位**。
 *
 * 原来的 `ShotViewportRestoreTest`（要求"必须在 finally 里滚回原来的位置"）已退役 ——
 * 那条行为是我自己加的，用户流程里没有这一步，
 * 而且它有害：往回滚会重新触发懒加载，页面在拍完之后又变一次。
 */
class ShotNoRestoreTest {

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
    fun `opShot里不许再记原位`() {
        val body = serviceSource().substringAfter("private fun opShot(").substringBefore("SHOT_NOTHING =")
        assertFalse(
            "「记住原位并还原」是我自己加的，用户流程里没有这一步 —— 不许回来：\n$body",
            body.contains("originCss")
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