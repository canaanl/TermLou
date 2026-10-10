package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截图空图判定锁定测试（5.9.0）。
 *
 * 无头截图最容易出的错是"假成功"：文件写了、路径也返回了，里头却是一张白图，
 * agent 拿去做视觉判断就全错。所以判空规则必须锁死。
 */
class WebShotSamplerTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun `整屏同色判为空图`() {
        assertTrue(WebShotSampler.looksBlank(1080, 1920) { _, _ -> white })
    }

    @Test
    fun `只有一个像素不同也算有内容`() {
        // 左上角一个黑点，其余全白：不能判成空图（哪怕那是加载指示器也比白图有用）
        assertFalse(WebShotSampler.looksBlank(1080, 1920) { x, y ->
            if (x == 0 && y == 0) black else white
        })
    }

    @Test
    fun `正常页面判为有内容`() {
        assertFalse(WebShotSampler.looksBlank(1080, 1920) { x, _ -> if (x < 540) white else black })
    }

    @Test
    fun `零尺寸判为空图`() {
        assertTrue(WebShotSampler.looksBlank(0, 100) { _, _ -> black })
        assertTrue(WebShotSampler.looksBlank(100, 0) { _, _ -> black })
        assertTrue(WebShotSampler.looksBlank(-1, -1) { _, _ -> black })
    }

    @Test
    fun `极小尺寸也不会除零崩溃`() {
        assertTrue(WebShotSampler.looksBlank(1, 1) { _, _ -> white })
        assertFalse(WebShotSampler.looksBlank(3, 3) { x, _ -> if (x == 0) black else white })
    }

    @Test
    fun `抽样网格固定为 8 乘 12`() {
        assertEquals(8, WebShotSampler.COLS)
        assertEquals(12, WebShotSampler.ROWS)
    }

    @Test
    fun `颜色阈值就是两种`() {
        assertEquals(2, WebShotSampler.COLOR_THRESHOLD)
    }

    @Test
    fun `不会采样到最后一个像素之外的坐标`() {
        var maxX = 0
        var maxY = 0
        WebShotSampler.looksBlank(1080, 1920) { x, y ->
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            white
        }
        assertTrue("采样 x 越界: $maxX", maxX < 1080)
        assertTrue("采样 y 越界: $maxY", maxY < 1920)
    }

    // ---------- 下三分之一验墨（5.9.9 整页截图） ----------

    private val pageBg = 0xFFE9E9E9.toInt()
    private val red = 0xFFFF0000.toInt()

    @Test
    fun `下三分之一纯白画布判成没渲染`() {
        // 真机 qq 长页：撑高之后紧接着画，下半截就是白的
        assertFalse(WebShotSampler.lowerThirdRendered(1236, 8673) { _, _ -> white })
    }

    @Test
    fun `下三分之一纯底色铺满也判成没渲染`() {
        // 真机 v5：整屏 #e9e9e9，一个内容像素都没有。
        // 数"非白"会把它当成有内容 —— 这正是这个函数存在的原因
        assertFalse(WebShotSampler.lowerThirdRendered(1236, 2676) { _, _ -> pageBg })
        // `looksBlank` 对纯底色说"空"是对的 —— 那是它的职责（整屏同色）。
        // 但它分不清"底色铺满"和"白画布"，也验不了"下三分之一"这个位置。
        // 两句话：looksBlank 管整张有没有墨，lowerThirdRendered 管下面那截有没有内容
        assertTrue(WebShotSampler.looksBlank(1236, 2676) { _, _ -> pageBg })
    }

    @Test
    fun `下三分之一有文字就判成渲染出来了`() {
        // 底色打底 + 黑字 + 红块 + 抗锯齿杂色：颜色数轻松过线
        var n = 0
        assertTrue(WebShotSampler.lowerThirdRendered(1236, 3000) { x, y ->
            n++
            when {
                y % 37 == 0 && x % 41 == 0 -> black
                y % 53 == 0 -> red
                (x + y) % 97 == 0 -> 0xFF123456.toInt()
                (x * 31 + y) % 256 < 4 -> white
                else -> pageBg
            }
        })
        assertTrue("抽样点太少，测试本身没意义", n > 1000)
    }

    @Test
    fun `三种颜色正好卡在门槛上判成没渲染`() {
        // 底色 + 黑 + 红 = 3 种，不超过 FULL_COLOR_MIN。
        // 真实文字带抗锯齿，颜色数是几十种，不会卡在这里
        assertFalse(WebShotSampler.lowerThirdRendered(1236, 3000) { x, y ->
            when {
                x < 100 -> black
                x > 1100 -> red
                else -> pageBg
            }
        })
    }

    @Test
    fun `第四种颜色一出现就判成渲染出来了`() {
        var toggle = false
        assertTrue(WebShotSampler.lowerThirdRendered(1236, 3000) { x, y ->
            toggle = !toggle
            when {
                x < 100 -> black
                x > 1100 -> red
                toggle -> white
                else -> pageBg
            }
        })
    }

    @Test
    fun `alpha位不同但RGB相同不算两种颜色`() {
        // 半透明叠加层很常见，不能靠 alpha 凑颜色数
        assertFalse(WebShotSampler.lowerThirdRendered(100, 100) { _, _ -> 0x80E9E9E9.toInt() })
    }

    @Test
    fun `零尺寸判成没渲染`() {
        assertFalse(WebShotSampler.lowerThirdRendered(0, 100) { _, _ -> black })
        assertFalse(WebShotSampler.lowerThirdRendered(100, 0) { _, _ -> black })
    }

    @Test
    fun `只看下三分之一不管上面`() {
        // 上面两屏全是字也没用 —— 要验的就是下面那截
        assertFalse(WebShotSampler.lowerThirdRendered(100, 90) { x, y ->
            if (y < 60) black else white
        })
    }

    // ---------- 5.9.30：内容指纹（抓"这屏和上一屏一样"） ----------

    @Test
    fun `同样内容必须同一个指纹`() {
        val a = WebShotSampler.fingerprint(100, 200) { x, y -> (x + y) }
        val b = WebShotSampler.fingerprint(100, 200) { x, y -> (x + y) }
        assertEquals("同样的像素必须同一个指纹", a, b)
        assertTrue("指纹不能是 0（0 是空图标记）", a != 0L)
    }

    @Test
    fun `内容不同指纹就不同`() {
        val a = WebShotSampler.fingerprint(100, 200) { x, y -> x + y }
        val b = WebShotSampler.fingerprint(100, 200) { x, y -> (x + y) * 7 }
        assertTrue("内容不同必须能分辨", a != b)
    }

    @Test
    fun `内容整体挪动指纹就变`() {
        // 这正是"滚了一屏之后内容变了"的样子
        val a = WebShotSampler.fingerprint(100, 200) { _, y -> if (y < 100) 0xFF0000 else 0x00FF00 }
        val b = WebShotSampler.fingerprint(100, 200) { _, y -> if (y < 60) 0xFF0000 else 0x00FF00 }
        assertTrue("挪动之后指纹必须变", a != b)
    }

    @Test
    fun `sameContent只对两个非零指纹判相同`() {
        assertTrue(WebShotSampler.sameContent(12345L, 12345L))
        assertTrue(WebShotSampler.sameContent(1L, 1L))
        assertTrue("0 是空图标记，不算'相同'", !WebShotSampler.sameContent(0L, 0L))
        assertFalse(WebShotSampler.sameContent(0L, 999L))
        assertFalse(WebShotSampler.sameContent(999L, 0L))
    }

    @Test
    fun `第一屏不会因为上一屏还没记就被误判成没滚`() {
        // prevPrint 初始就是 0。第一屏必须**不被**当成"和上一屏一样"，
        // 否则每次整页截图都在第 1 屏就报错。
        val first = WebShotSampler.fingerprint(100, 100) { x, y -> x + y }
        assertTrue("0 与非 0 不算相同", !WebShotSampler.sameContent(0L, first))
    }

    @Test
    fun `退化尺寸的指纹是零`() {
        assertEquals(0L, WebShotSampler.fingerprint(0, 100) { _, _ -> 1 })
        assertEquals(0L, WebShotSampler.fingerprint(100, 0) { _, _ -> 1 })
        assertEquals(0L, WebShotSampler.fingerprint(-1, -1) { _, _ -> 1 })
    }
}

