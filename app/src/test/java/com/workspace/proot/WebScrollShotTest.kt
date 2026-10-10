package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动分段方案的锁。
 *
 * ## 这套分段为什么存在
 *
 * 5.9.7–5.9.26 的整页截图靠"把视图量到整页高"（见 [WebShotPlan] 类注释）——
 * 那条路在**无头模式**下才不得不走，因为没有真窗口时滚出去的内容压根不产帧。
 *
 * 挂了真悬浮窗之后合成器一直在产帧，于是回到正常浏览器的做法：
 * 滚一段、拍一张、拼起来。这里锁的就是"拼得对不对"。
 *
 * ## 最容易错的地方：最后一段
 *
 * 视口滚到底只能停在 `pageHeight - viewportHeight`。照"每段往前推一格"算，
 * 最后一段会滚到一个到不了的位置，或者长图底部留下一段空白。
 */
class WebScrollShotTest {

    // 真机那台：density=3.0，视口 412×892dp = 1236×2676 设备像素
    private val w = 1236
    private val h = 2676

    @Test
    fun `整页就是首屏时只截一次`() {
        val p = WebScrollShot.plan(h, w, h)
        assertEquals(1, p.shots)
        assertEquals(0, p.segments[0].scrollY)
    }

    @Test
    fun `正好两屏截两次且首尾相接无缝`() {
        val p = WebScrollShot.plan(h * 2, w, h)
        assertEquals(2, p.shots)
        assertEquals(0, p.segments[0].scrollY)
        assertEquals(h, p.segments[1].scrollY)
        // 无缝：第一段底 = 第二段顶
        assertEquals(p.segments[0].top + p.segments[0].height, p.segments[1].top)
    }

    @Test
    fun `最后一段必须夹到最大滚动位置`() {
        // 5000 / 2000 = 2.5 屏 → 3 段；最大滚动 = 3000
        val p = WebScrollShot.plan(5000, 2000, 2000)
        assertEquals(3, p.shots)
        val maxScroll = 5000 - 2000
        for (s in p.segments) {
            assertTrue(
                "滚到了到不了的位置：scrollY=${s.scrollY} > maxScroll=$maxScroll",
                s.scrollY <= maxScroll
            )
        }
        assertEquals("最后一段要贴在最大滚动位置", maxScroll, p.segments.last().scrollY)
    }

    @Test
    fun `最后一段的sourceY指向新内容而不是整张图`() {
        // 整页 5000、视口 2000：第三段本该从 4000 起，但只能滚到 3000，
        // 于是前 1000 行是第二段拍过的 —— sourceY 必须等于 1000。
        val p = WebScrollShot.plan(5000, 2000, 2000)
        val last = p.segments.last()
        assertEquals("最后一段的起点", 4000, last.top)
        assertEquals("跳过拍过的部分", 1000, last.sourceY)
        assertEquals("只剩最后 1000 行是新的", 1000, last.height)
    }

    @Test
    fun `sourceY加上height不许超出视口高`() {
        for (page in listOf(3000, 5000, 7260, 11440, 26761)) {
            val p = WebScrollShot.plan(page, w, h)
            for (s in p.segments) {
                assertTrue(
                    "贡献区超出了视口：page=$page sourceY=${s.sourceY} height=${s.height} vh=$h",
                    s.sourceY + s.height <= h
                )
                assertTrue("sourceY 不能为负：page=$page s=$s", s.sourceY >= 0)
            }
        }
    }

    @Test
    fun `分段必须盖满整页不漏`() {
        for (page in listOf(3000, 5000, 7260, 7275, 11440, 26761)) {
            val p = WebScrollShot.plan(page, w, h)
            var covered = 0
            for (s in p.segments) covered += s.height
            assertEquals("分段总高必须等于整页高：page=$page covered=$covered", page, covered)
        }
    }

    @Test
    fun `分段严丝合缝既不漏也不重叠`() {
        for (page in listOf(3000, 5000, 7260, 11440)) {
            val p = WebScrollShot.plan(page, w, h)
            for (i in 1 until p.segments.size) {
                val prevEnd = p.segments[i - 1].top + p.segments[i - 1].height
                assertEquals(
                    "第 $i 段必须正好接在上一段末尾（page=$page）",
                    prevEnd, p.segments[i].top
                )
            }
        }
    }

    @Test
    fun `每段贡献的高度都不许超过一屏`() {
        for (page in listOf(3000, 5000, 7260, 11440)) {
            val p = WebScrollShot.plan(page, w, h)
            for (s in p.segments) {
                assertTrue("一段高过视口了：page=$page h=${s.height} vh=$h", s.height <= h)
            }
        }
    }

    @Test
    fun `最后一段的顶必须等于整页减它自己的高`() {
        for (page in listOf(5000, 7260, 11440)) {
            val p = WebScrollShot.plan(page, w, h)
            val last = p.segments.last()
            assertEquals("长图底部必须正好到整页末尾：page=$page", page, last.top + last.height)
        }
    }

    @Test
    fun `段数向上取整`() {
        // 视口 2000：5000→3, 4000→2, 4001→3
        assertEquals(3, WebScrollShot.plan(5000, 1, 2000).shots)
        assertEquals(2, WebScrollShot.plan(4000, 1, 2000).shots)
        assertEquals(3, WebScrollShot.plan(4001, 1, 2000).shots)
    }

    @Test
    fun `视口比整页还高时只截一次`() {
        val p = WebScrollShot.plan(1000, w, h)
        assertEquals(1, p.shots)
        assertEquals(0, p.segments[0].scrollY)
    }

    @Test
    fun `退化输入不崩`() {
        for (page in listOf(0, -1, -9999)) {
            for (vp in listOf(0, -1, 1)) {
                val p = WebScrollShot.plan(page, vp, vp)
                assertTrue("段数得为正：page=$page vp=$vp shots=${p.shots}", p.shots >= 1)
                assertTrue("尺寸得为正", p.bitmapWidth >= 1 && p.bitmapHeight >= 1)
            }
        }
    }

    // ---------- 滚动：必须走 JS，不能走 View.scrollTo ----------

    @Test
    fun `滚动脚本必须滚的是文档`() {
        val js = WebScrollShot.scrollToJs(1200)
        assertTrue("必须用 window.scrollTo（文档滚动）：$js", js.contains("window.scrollTo(0,1200)"))
        assertFalse(
            "不许出现 View 层的东西：$js",
            js.contains("scrollTo(0,") && js.contains("document.documentElement.scrollTop")
        )
    }

    @Test
    fun `滚动脚本自己会回读一次位置`() {
        // 第一次回读能省掉大部分轮询 —— 同步滚动的页面 scrollTo 之后立刻就位。
        val js = WebScrollShot.scrollToJs(800)
        assertTrue("滚动脚本自己也要回读 pageYOffset：$js", js.contains("pageYOffset"))
    }

    @Test
    fun `滚动脚本不许有换行`() {
        // `evaluateJavascript` 回的是 JSON 字符串，脚本里带真实换行会被当字符串内容。
        val js = WebScrollShot.scrollToJs(500)
        assertFalse("不能有换行：$js", js.contains("\n"))
        assertFalse(WebScrollShot.SCROLL_Y_JS.contains("\n"))
    }

    @Test
    fun `负数滚动位置被兜到零`() {
        assertTrue(WebScrollShot.scrollToJs(-100).contains("window.scrollTo(0,0)"))
    }

    @Test
    fun `设备像素转CSS像素用浮点密度`() {
        // 真机 density=3.0。`density.toInt()` 在 2.75/3.5/2.625 上会截错。
        assertEquals(400, WebScrollShot.toCss(1200, 3.0f))
        assertEquals(343, WebScrollShot.toCss(1200, 3.5f))
        assertEquals(436, WebScrollShot.toCss(1200, 2.75f))
    }

    @Test
    fun `密度为零或负时按1比1处理不崩`() {
        for (d in listOf(0f, -1f, -3.5f)) {
            assertEquals("密度 $d 得原样返回", 1200, WebScrollShot.toCss(1200, d))
        }
    }

    @Test
    fun `滚动到位的判定留了两像素容差`() {
        // scroll-snap 与子像素布局都会差一两像素，那不算"没滚到"。
        assertTrue("正好到位", WebScrollShot.scrollLanded(1000, 1000))
        assertTrue("差 1 像素算到位", WebScrollShot.scrollLanded(1000, 1001))
        assertTrue("差 2 像素算到位", WebScrollShot.scrollLanded(1000, 998))
        assertFalse("差 3 像素就不算", WebScrollShot.scrollLanded(1000, 1003))
        assertFalse("差很多更不算", WebScrollShot.scrollLanded(1000, 0))
    }

    @Test
    fun `回读脚本要能同时兼容pageYOffset和scrollY`() {
        val js = WebScrollShot.SCROLL_Y_JS
        assertTrue("老浏览器只有 scrollY：$js", js.contains("scrollY"))
        assertTrue("标准写法是 pageYOffset：$js", js.contains("pageYOffset"))
        assertTrue("必须包 try：滚动可能被页面拦掉", js.contains("try"))
    }

    @Test
    fun `位图尺寸含缩放`() {
        val p = WebScrollShot.plan(7260, w, h)
        // 1236×7260 ≈ 897 万 < 900 万，不缩
        assertEquals(1f, p.scale, 0.0001f)
        assertEquals(1236, p.bitmapWidth)
        assertEquals(7260, p.bitmapHeight)
    }

    @Test
    fun `超上限自动缩且缩完落回上限内`() {
        val p = WebScrollShot.plan(72600, w, h)
        assertTrue("该缩：${p.scale}", p.scale < 1f)
        val px = p.bitmapWidth.toLong() * p.bitmapHeight.toLong()
        assertTrue("缩完还超上限：$px", px <= WebShotPlan.MAX_PIXELS)
    }
}

/**
 * 切整页：**只切，不缩**（5.9.33）。
 *
 * 旧兜底是 `canvas.scale(min(bw/picW, bh/picH))` 把**整页缩进一张图** ——
 * 那正是真机上报的"第 2 屏起变成缩小的长图"。现在每屏只切出视口原尺寸，**永不缩放**。
 */
class WebScrollSliceTest {

    private val vw = 1236
    private val vh = 2676

    @Test
    fun `第一屏切出视口原尺寸`() {
        val r = WebScrollShot.scrollSrcRect(1236, 7443, vw, vh, 0)!!
        assertEquals(0, r.top)
        assertEquals(vw, r.width)
        assertEquals("切出来必须是视口高，一个像素都不缩", vh, r.height)
    }

    @Test
    fun `第二屏切的是它自己那一段`() {
        val r = WebScrollShot.scrollSrcRect(1236, 7443, vw, vh, vh)!!
        assertEquals(vh, r.top)
        assertEquals(vh, r.height)
        assertEquals(0, r.left)
    }

    @Test
    fun `每屏切出来都一样宽高`() {
        // 尺寸一致是"统一几何"的前提：任何一屏被缩过，整套图就对不齐
        val maxScroll = 7443 - vh
        var y = 0
        var screens = 1
        while (true) {
            val r = WebScrollShot.scrollSrcRect(1236, 7443, vw, vh, y)
                ?: throw AssertionError("第 $screens 屏切不出东西")
            assertEquals("第 $screens 屏宽", vw, r.width)
            assertTrue("第 $screens 屏高不该超过视口高", r.height <= vh)
            val next = WebScrollShot.nextScreenTop(y, vh, maxScroll) ?: break
            y = next
            screens++
        }
        // 屏数 = 走过的步数 + 1（y=0 那一屏也算）
        assertEquals("7443 高、视口 2676，应该切出 3 屏", 3, screens)
    }

    @Test
    fun `越界的滚动位置被夹住不会崩`() {
        for (y in listOf(-100, 0, 999999)) {
            val r = WebScrollShot.scrollSrcRect(1236, 7443, vw, vh, y)
            assertTrue("滚动 $y 必须切出合法块", r != null && r.height >= 1 && r.width >= 1)
        }
    }

    @Test
    fun `整页尺寸为零时返回null`() {
        assertEquals(null, WebScrollShot.scrollSrcRect(0, 100, vw, vh, 0))
        assertEquals(null, WebScrollShot.scrollSrcRect(1236, 0, vw, vh, 0))
    }

    @Test
    fun `视口比整页还大时切出来的就是整页`() {
        val r = WebScrollShot.scrollSrcRect(800, 500, vw, vh, 0)!!
        assertEquals("不许比整页还宽", 800, r.width)
        assertEquals("不许比整页还高", 500, r.height)
    }
}
