package com.workspace.proot

import org.junit.Assert.assertEquals
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