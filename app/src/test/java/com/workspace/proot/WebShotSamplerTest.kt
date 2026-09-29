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
}
