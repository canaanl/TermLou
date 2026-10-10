package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 悬浮窗几何的锁。
 *
 * ## 这几个数字是用户定的，不是我们挑的
 *
 * 1. 宽度 = **屏宽 ÷ 3**，长宽比跟**整块屏**一致。
 * 2. 每次重建回**右上角**，不持久化。
 * 3. 拖动不许把窗拖出屏幕找不回来。
 */
class WebFloatWindowTest {

    // 真机那台：iQOO 15 / vivo V2505A，1440×3168 @ density 3.0
    private val screenW = 1440
    private val screenH = 3168

    @Test
    fun `窗口宽度是屏宽的三分之一`() {
        val (w, h) = WebFloatWindow.windowSize(screenW, screenH)
        assertEquals("宽度必须是屏宽整除 3", screenW / 3, w)
        assertEquals(480, w)
    }

    @Test
    fun `长宽比跟屏幕一致`() {
        val (w, h) = WebFloatWindow.windowSize(screenW, screenH)
        val windowRatio = h.toDouble() / w.toDouble()
        val screenRatio = screenH.toDouble() / screenW.toDouble()
        assertTrue(
            "窗口比例 $windowRatio 必须等于屏幕比例 $screenRatio",
            kotlin.math.abs(windowRatio - screenRatio) < 0.01
        )
    }

    @Test
    fun `窗口不会大过屏幕`() {
        for (sw in listOf(720, 1080, 1440, 3120)) {
            for (sh in listOf(1280, 2400, 3168)) {
                val (w, h) = WebFloatWindow.windowSize(sw, sh)
                assertTrue("窗口比屏还大：sw=$sw sh=$sh -> ${w}x$h", w <= sw && h <= sh)
            }
        }
    }

    @Test
    fun `退化输入不崩`() {
        for (sw in listOf(0, -1, 1, 2)) {
            for (sh in listOf(0, -1, 1)) {
                val (w, h) = WebFloatWindow.windowSize(sw, sh)
                assertTrue("尺寸得为正：sw=$sw sh=$sh -> ${w}x$h", w >= 1 && h >= 1)
            }
        }
    }

    // ---------- 视口 412dp，缩放只在显示层（5.9.36 请回来）----------

    @Test
    fun `缩放因子把412dp视口正好铺满窗口`() {
        // 视口 1236×2676 塞进窗口 360×756：两个方向各自铺满，不留边。
        val (sx, sy) = WebFloatWindow.scaleFactors(360, 756, 1236, 2676)
        assertEquals("横向必须正好铺满", 1f, (sx * 1236) / 360f, 0.001f)
        assertEquals("纵向必须正好铺满", 1f, (sy * 2676) / 756f, 0.001f)
        assertTrue("窗口比视口小，缩放必须 < 1：sx=$sx", sx < 1f)
        assertTrue("sy=$sy", sy < 1f)
    }

    @Test
    fun `视口尺寸为零或负时缩放兜到极小正数不崩`() {
        for (vw in listOf(0, -1)) {
            for (vh in listOf(0, -1)) {
                val (sx, sy) = WebFloatWindow.scaleFactors(360, 756, vw, vh)
                assertTrue("必须是正数：vw=$vw vh=$vh -> $sx,$sy", sx > 0f && sy > 0f)
            }
        }
    }

    @Test
    fun `本机口径下窗口是480x1056`() {
        // 屏 1440×3168：宽 = 1440/3 = 480，高按屏幕比例 = 480*3168/1440 = 1056
        val (w, h) = WebFloatWindow.windowSize(1440, 3168)
        assertEquals(480, w)
        assertEquals(1056, h)
    }

    // ---------- 位置 ----------

    @Test
    fun `初始位置在右上角`() {
        val (x, y) = WebFloatWindow.defaultPosition(screenW, 480)
        assertEquals("右边贴齐", screenW - 480, x)
        assertEquals("上边贴齐", 0, y)
    }

    @Test
    fun `拖动不许出屏`() {
        val winW = 480
        val winH = 1056
        // 拖到四个方向的最远处
        assertEquals(0, WebFloatWindow.clampToScreen(-500, -500, winW, winH, screenW, screenH).first)
        assertEquals(0, WebFloatWindow.clampToScreen(-500, -500, winW, winH, screenW, screenH).second)
        assertEquals(
            screenW - winW,
            WebFloatWindow.clampToScreen(99999, 0, winW, winH, screenW, screenH).first
        )
        assertEquals(
            screenH - winH,
            WebFloatWindow.clampToScreen(0, 99999, winW, winH, screenW, screenH).second
        )
    }

    @Test
    fun `屏内拖动位置不变`() {
        val (x, y) = WebFloatWindow.clampToScreen(700, 900, 480, 1056, screenW, screenH)
        assertEquals(700, x)
        assertEquals(900, y)
    }

    @Test
    fun `窗口比屏还大时夹位不崩`() {
        val (x, y) = WebFloatWindow.clampToScreen(50, 50, 2000, 4000, screenW, screenH)
        assertTrue("得是合法坐标：$x,$y", x >= 0 && y >= 0)
    }
}

/** 从测试的工作目录往上找 `app/src/main/...` —— 单测的工作目录不是仓库根。 */
private fun readMain(name: String): String {
    var d: File? = File("").absoluteFile
    var hops = 0
    while (d != null && hops < 6) {
        val f = File(d, "app/src/main/java/com/workspace/proot/$name")
        if (f.isFile) return f.readText()
        d = d.parentFile
        hops++
    }
    throw AssertionError("找不到 $name，这条检查等于没跑")
}
