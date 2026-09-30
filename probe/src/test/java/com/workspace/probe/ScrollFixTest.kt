package com.workspace.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动截图判据的锁。
 *
 * ## 前七轮量到了什么（这些数字是本轮设计的全部依据）
 *
 * v5：overlay 与不挂窗口**两组完全一样**，滚到底后纯红像素 0。`eval` 正常
 * （`scrollY=1338`、尾部色块在视口内、正文读得到）→ **WebView 活着，画不出来**。
 *
 * v6：扫 5 种修法，A、B 共 10 次，**全灭**。
 *
 * v7：把测法补全（顶部也铺纯绿、每处两条截图路都量），扫 11 种
 * （`invalidate` / `onResume` / 软件渲染层 / `onVisibilityAggregated` / 渲染优先级 /
 * 重排版 / `View.scrollTo` / 等 3 秒 / 等 8 秒 / `alpha = 0` 挂屏上），
 * A、B 共 22 次，**红像素全是 0**。顺手排除掉：不是时间问题（等 8 秒：0）、
 * 不是可见性问题（`alpha=0` 挂屏上底部红 0，而且顶部 draw 直接变 0，比不挂更糟）。
 *
 * ## v7 自己写错了一处，差点给出相反的结论
 *
 * 判据写成了「任一色块达标就算画出来」。而 `capturePicture()` 返回的是**整页** Picture，
 * 底部那张图里**顶部那块绿也在**，于是「底部画出来了」被误判成成立，报告打出
 * 「问题这次没复现，先别急着改本体」。
 *
 * **那一行是错的。** 红格子全是 0，bug 一直在。
 * 这一版把判据拆成 [ScrollFix.Shot.hasGreen] / [ScrollFix.Shot.hasRed]，
 * 两种颜色永远分开判 —— 下面头两条测试就是钉死这个的。
 */
class ScrollFixTest {

    private fun shot(
        green: Int = 0,
        red: Int = 0,
        fail: String? = null
    ) = ScrollFix.Shot(green, red, "E9E9E9", fail)

    private fun spot(
        draw: ScrollFix.Shot = shot(),
        picture: ScrollFix.Shot = shot(),
        evalOk: Boolean = true,
        blockVisible: Boolean = true,
        viewH: Int = 2676,
        innerHPx: Int = 2676
    ) = ScrollFix.Spot(draw, picture, evalOk, 1528, 1236, viewH, innerHPx, blockVisible)

    private fun r(
        v: ScrollFix.Variant = ScrollFix.Variant.BASELINE,
        s: ScrollFix.Spot = spot(),
        stalls: String = ""
    ) = ScrollFix.Result(v, s, stalls)

    /** 「底部被画出来了」—— 这才是每个做法成没成的判据。 */
    private fun drawn(v: ScrollFix.Variant) = r(
        v,
        spot(draw = shot(green = 927_000, red = 927_000), picture = shot(green = 120_000, red = 120_000))
    )

    // ---------- 绿和红必须分开判（v7 那个 bug） ----------

    @Test
    fun `只有绿不算底部画出来了`() {
        // v7 就是在这儿翻的车：判据写成"任一颜色达标"，于是 capturePicture 的整页图里
        // 顶部那块绿把"底部也画出来了"骗了出来
        val s = spot(draw = shot(green = 927_000, red = 0), picture = shot(green = 120_000, red = 0))
        assertFalse("只有绿 → 底部没画出来", s.redDrawn)
        assertTrue("但顶部确实画出来了", s.greenDrawn)
    }

    @Test
    fun `只有红不���顶部画出来了`() {
        val s = spot(draw = shot(green = 0, red = 927_000))
        assertTrue(s.redDrawn)
        assertFalse("只有红 → 顶部没画出来", s.greenDrawn)
    }

    @Test
    fun `色块像素达标才算画出来`() {
        assertTrue(ScrollFix.Shot(ScrollFix.MIN_PIXELS, 0, "-").hasGreen)
        assertTrue(ScrollFix.Shot(0, ScrollFix.MIN_PIXELS, "-").hasRed)
        assertFalse("差一点不算", ScrollFix.Shot(ScrollFix.MIN_PIXELS - 1, 0, "-").hasGreen)
        assertFalse(ScrollFix.Shot(0, ScrollFix.MIN_PIXELS - 1, "-").hasRed)
    }

    @Test
    fun `画图失败不算画出来`() {
        val s = ScrollFix.Shot(999_999, 999_999, "-", "boom")
        assertFalse(s.hasGreen); assertFalse(s.hasRed)
    }

    @Test
    fun `负数是没法量不是零`() {
        // 量不到时记 -1。它绝不能被当成"量到了 0 个像素"，
        // 那样会把"没画出来"和"没量出来"混成一件
        val s = ScrollFix.Shot(-1, -1, "-")
        assertFalse(s.hasGreen); assertFalse(s.hasRed)
    }

    // ---------- v9 三色分解 ----------

    @Test
    fun `四个桶加起来不超总数`() {
        // 白 / 底色 / 绿 / 红四个桶互不重叠：白要 ≥245，底色 220..242，
        // 红要 G<90，绿要 R<90 —— 两两无交集
        val s = ScrollFix.Shot(
            green = 927_000, red = 120_000, tint = "-",
            white = 500_000, pageBg = 1_700_000, total = 3_307_536
        )
        assertEquals(3_307_536 - 500_000 - 1_700_000 - 927_000 - 120_000, s.others())
    }

    @Test
    fun `v5那个签名算出来就是底色铺满`() {
        // v5 实测：非白 3,307,536 = 100%，红 0。
        // 按三色分就是"底色占满" —— 内容层单独掉了，连画布底都没露
        val s = ScrollFix.Shot(
            green = 0, red = 0, tint = "E9E9E9",
            white = 2_608, pageBg = 3_304_928, total = 3_307_536
        )
        assertFalse("红像素 0 → 底部没画出来", s.hasRed)
        assertEquals("其他内容应当约等于 0", 0, s.others())
    }

    @Test
    fun `量不到时其他数也是量不到`() {
        assertEquals(-1, ScrollFix.Shot(-1, -1, "-").others())
        assertEquals(-1, ScrollFix.Shot(0, 0, "-", "boom").others())
    }

    @Test
    fun `新做法排在基线之后且基线仍是第一个`() {
        val order = ScrollFix.Variant.entries.toList()
        assertEquals(ScrollFix.Variant.BASELINE, order.first())
        for (v in listOf(
            ScrollFix.Variant.P99, ScrollFix.Variant.JITTER,
            ScrollFix.Variant.DIRTY, ScrollFix.Variant.WAIT20,
            ScrollFix.Variant.PRERASTER, ScrollFix.Variant.VISUAL,
            ScrollFix.Variant.VDISPLAY, ScrollFix.Variant.LDIRTY
        )) {
            assertTrue("$v 必须在计划里", order.contains(v))
        }
    }

    @Test
    fun `取色点默认空串不影响老数据`() {
        // elAt 是 v9 加的；老构造方式（位置参数 9 个）照样能用
        assertEquals("", spot().elAt)
    }

    @Test
    fun `两条路任一条能画就算`() {
        assertTrue("兜底路能画也是能画", spot(picture = shot(red = 927_000)).redDrawn)
        assertTrue("主路能画也是能画", spot(draw = shot(red = 927_000)).redDrawn)
    }

    // ---------- 一个做法算不算成 ----------

    @Test
    fun `按顺序挑第一个有效的`() {
        val results = listOf(r(), r(ScrollFix.Variant.CSS_SHIFT), drawn(ScrollFix.Variant.FULL_PAGE))
        assertEquals(ScrollFix.Variant.FULL_PAGE, ScrollFix.winner(results))
    }

    @Test
    fun `一个都没成时挑不出赢家`() {
        assertNull(ScrollFix.winner(ScrollFix.Variant.entries.map { r(it) }))
    }

    @Test
    fun `基线排第一——它是那 22 次量过的已知坏情况`() {
        assertEquals(ScrollFix.Variant.BASELINE, ScrollFix.Variant.entries.first())
    }

    @Test
    fun `先试不滚的那两个——它们比需要新帧的便宜`() {
        val o = ScrollFix.Variant.entries.toList()
        assertTrue(
            "贴底/撑高不靠新帧，应当排在重绘与微颤前面",
            o.indexOf(ScrollFix.Variant.CSS_SHIFT) < o.indexOf(ScrollFix.Variant.REPAINT) &&
                o.indexOf(ScrollFix.Variant.FULL_PAGE) < o.indexOf(ScrollFix.Variant.NUDGE)
        )
    }

    @Test
    fun `视图够不够高装得下整页要能单独问出来`() {
        // FULL_PAGE 要是视图没撑开，那它压根没生效，不能当成"这个做法失败了"。
        // viewH 与 innerHPx **都必须是设备像素** —— 拿 2676 设备像素去比 2420 CSS 像素
        // 会得出"装得下"的错结论（这正是 v8 头一次跑红的那条）
        assertTrue(spot(viewH = 7260, innerHPx = 7260).viewCoversPage)
        assertFalse(spot(viewH = 2676, innerHPx = 7260).viewCoversPage)
        assertFalse(spot(viewH = 0, innerHPx = 0).viewCoversPage)
        assertFalse("视口 892dp×3 = 2676px 装不下 2420dp×3 = 7260px", spot().viewCoversPage.not())
    }

    // ---------- 跨做法下结论 ----------

    @Test
    fun `基线红0等于那个bug还在`() {
        val results = ScrollFix.Variant.entries.map { r(it) }
        val d = ScrollFix.diagnose(results)
        assertFalse(d.bugReproduces.not())
        assertFalse(d.baselineBroken)
        assertTrue(ScrollFix.summary(results).contains("那个 bug 还在"))
    }

    @Test
    fun `基线冒出红像素说明这轮测法变了不能照着改本体`() {
        // 要是哪天基线自己修好了，说明页面或测法变了，跟前七轮不可比
        val results = listOf(drawn(ScrollFix.Variant.BASELINE))
        val d = ScrollFix.diagnose(results)
        assertTrue(d.baselineBroken)
        val s = ScrollFix.summary(results)
        assertTrue("必须明说测法变了：$s", s.contains("这轮测法变了"))
        assertTrue("要拦住别改本体：$s", s.contains("先别改本体"))
    }

    @Test
    fun `有赢家时点名是哪一个并说明代价`() {
        val results = listOf(r(ScrollFix.Variant.BASELINE), drawn(ScrollFix.Variant.FULL_PAGE))
        val s = ScrollFix.summary(results)
        assertTrue(s.contains("有效做法：${ScrollFix.Variant.FULL_PAGE.label}"))
        assertTrue("要把做法怎么做的说清：$s", s.contains(ScrollFix.Variant.FULL_PAGE.note.take(8)))
    }

    @Test
    fun `全灭时要说清只能改报错文案`() {
        val results = ScrollFix.Variant.entries.map { r(it) }
        val s = ScrollFix.summary(results)
        assertTrue("必须明说没一个行：$s", s.contains("一个都没把底部那块画出来"))
        assertTrue("要给下一步：$s", s.contains("shot"))
    }

    @Test
    fun `画图失败与eval没回值的做法要单独点名`() {
        val results = listOf(
            r(ScrollFix.Variant.BASELINE, spot(evalOk = false, blockVisible = false)),
            r(ScrollFix.Variant.CSS_SHIFT, spot(blockVisible = false))
        )
        val d = ScrollFix.diagnose(results)
        assertEquals(2, d.voided.size)
        val s = ScrollFix.summary(results)
        assertTrue("必须警告：$s", s.contains("不作数"))
        assertTrue("要点名是哪几个：$s", s.contains(ScrollFix.Variant.CSS_SHIFT.label))
    }

    @Test
    fun `全都正常时不加那行警告`() {
        val results = ScrollFix.Variant.entries.map { r(it) }
        assertTrue(ScrollFix.diagnose(results).voided.isEmpty())
        assertFalse(ScrollFix.summary(results).contains("不作数"))
    }

    @Test
    fun `页面能不能画要单独说清`() {
        // 全灭但顶部绿有 → 页面本身能画，只是滚出去那块画不出来。
        // 这两件事必须分开报，否则会误以为"整个页面都渲染不了"
        val results = listOf(r(ScrollFix.Variant.BASELINE, spot(draw = shot(green = 927_000))))
        val s = ScrollFix.summary(results)
        assertTrue(ScrollFix.diagnose(results).everGreen)
        assertTrue("要说清顶部画出来了：$s", s.contains("顶部绿像素：画出来过"))
    }

    @Test
    fun `没跑出结果时不编结论`() {
        assertEquals("没跑成", ScrollFix.summary(emptyList()))
    }

    @Test
    fun `有步骤没跑完要单独点名`() {
        // 半截的数字不能当结论。v8 头一次整轮都没跑完，报告里一个字都没提
        val results = listOf(
            r(ScrollFix.Variant.BASELINE),
            r(ScrollFix.Variant.FULL_PAGE, spot(draw = shot(red = 927_000)), "　· 第3/4 步「读状态并画图」等了 10s 回调没来，跳过\n")
        )
        val d = ScrollFix.diagnose(results)
        assertEquals(listOf(ScrollFix.Variant.FULL_PAGE), d.stalled)
        val s = ScrollFix.summary(results)
        assertTrue("必须说有步骤没跑完：$s", s.contains("有步骤没跑完"))
        assertTrue("要点名是哪个：$s", s.contains(ScrollFix.Variant.FULL_PAGE.label))
    }

    @Test
    fun `全跑完时不加那行警告`() {
        val results = ScrollFix.Variant.entries.map { r(it) }
        assertTrue(ScrollFix.diagnose(results).stalled.isEmpty())
        assertFalse(ScrollFix.summary(results).contains("有步骤没跑完"))
    }

    @Test
    fun `结论分支互斥——每组事实只该命中一个说法`() {
        val cases = listOf(
            "基线坏了" to listOf(drawn(ScrollFix.Variant.BASELINE)),
            "有赢家" to listOf(r(ScrollFix.Variant.BASELINE), drawn(ScrollFix.Variant.FULL_PAGE)),
            "全灭" to ScrollFix.Variant.entries.map { r(it) }
        )
        val keys = listOf("这轮测法变了", "有效做法：", "一个都没把底部那块画出来")
        for ((name, results) in cases) {
            val s = ScrollFix.summary(results)
            val hit = keys.filter { s.contains(it) }
            assertEquals("$name 应恰好命中一个，实际 $hit：\n$s", 1, hit.size)
        }
    }

    @Test
    fun `位图面积上限之内就不该缩放`() {
        // 整页视图 1236 × 7260 ≈ 898 万，刚好压在上限下面 —— 缩了就量不到原尺寸的数
        val pixels = 1236L * 7260L
        assertTrue("整页不该被缩：$pixels > ${ScrollFix.MAX_PIXELS}", pixels <= ScrollFix.MAX_PIXELS)
        assertNotNull(ScrollFix.Variant.FULL_PAGE.note)
    }
}
