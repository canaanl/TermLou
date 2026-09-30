package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无头判定的锁（5.9.7）。
 *
 * 这一版的整个前提是"不挂窗口的 WebView 也能用" —— 实机探针（`:probe` 模块 v4）
 * 验证过它与挂 overlay 窗口完全等价，但**那是 WebView 的实现行为，不是官方保证**。
 * 所以服务启动会自检一遍，不过就自动落回。
 *
 * 判据的松紧就是这层的意义：**宁可误判成不可用（退回旧方案），
 * 也不能误判成可用（`text` 静默返回空）**。
 */
class WebHeadlessTest {

    // ---------- 排版一致性：自参照，换设备也对 ----------

    @Test
    fun `排版按视口自参照 不硬编码像素`() {
        // 探测视口 240dp → body margin 8×2 → 内容宽 224 → 一半 112
        assertEquals(112, WebHeadless.layoutConsistent(240, 112).let { 112 })
        assertTrue("视口 240 时 112 是对的", WebHeadless.layoutConsistent(240, 112))
        assertTrue("正式视口 412 时 198 也是对的", WebHeadless.layoutConsistent(412, 198))
    }

    @Test
    fun `排版确实不对时判为不对`() {
        assertFalse(WebHeadless.layoutConsistent(240, 0))
        assertFalse(WebHeadless.layoutConsistent(240, 240))
        assertFalse("没量到宽度不算对", WebHeadless.layoutConsistent(0, 112))
        assertFalse("负数不算对", WebHeadless.layoutConsistent(240, -1))
    }

    @Test
    fun `允许两像素误差 抗字体与舍入`() {
        assertTrue(WebHeadless.layoutConsistent(240, 113))
        assertTrue(WebHeadless.layoutConsistent(240, 111))
        assertFalse("差 3 像素就超了", WebHeadless.layoutConsistent(240, 115))
    }

    // ---------- 视口 ----------

    @Test
    fun `视口必须等于给的 dp 宽度`() {
        assertTrue(WebHeadless.viewportDriven(240, 240))
        assertFalse("尺寸被忽略时报的是别的值", WebHeadless.viewportDriven(980, 240))
    }

    // ---------- 模式判定：从严 ----------

    private fun allPass() = WebHeadless.decide(
        loaded = true, innerTextOk = true, viewportOk = true, layoutOk = true, drawHasInk = true
    )

    @Test
    fun `五项全过才走无头`() {
        assertEquals(WebHeadless.Mode.HEADLESS, allPass())
    }

    @Test
    fun `少任何一项都落回 overlay`() {
        // 逐项抽掉，每一项都必须翻脸
        assertEquals(
            WebHeadless.Mode.OVERLAY,
            WebHeadless.decide(false, true, true, true, true)
        )
        assertEquals(
            WebHeadless.Mode.OVERLAY,
            WebHeadless.decide(true, false, true, true, true)
        )
        assertEquals(
            WebHeadless.Mode.OVERLAY,
            WebHeadless.decide(true, true, false, true, true)
        )
        assertEquals(
            WebHeadless.Mode.OVERLAY,
            WebHeadless.decide(true, true, true, false, true)
        )
        assertEquals(
            "截图出不来图也必须落回 —— 那是 shot 唯一的路",
            WebHeadless.Mode.OVERLAY,
            WebHeadless.decide(true, true, true, true, false)
        )
    }

    @Test
    fun `自检超时等同不可用`() {
        // 超时传的是 loaded=false / 全 false，与判不出等价
        assertEquals(WebHeadless.Mode.OVERLAY, WebHeadless.decide(false, false, false, false, false))
    }

    // ---------- 给用户看的话 ----------

    @Test
    fun `无头可用时那句话要明说不需要权限`() {
        val t = WebHeadless.reasonFor(WebHeadless.Mode.HEADLESS)
        assertTrue(t.contains("无头可用"))
        // 正常路径最要紧的是别让用户以为还要去授权
        assertTrue("应当明说无需权限，实际：$t", t.contains("无需"))
        assertFalse("不该出现退回的字样，实际：$t", t.contains("退回"))
    }

    @Test
    fun `落回时要说清为什么和怎么办`() {
        val t = WebHeadless.reasonFor(WebHeadless.Mode.OVERLAY)
        assertTrue(t.contains("退回"))
        assertTrue(t.contains("悬浮窗"))
    }

    @Test
    fun `只列没过的项`() {
        assertEquals("", WebHeadless.failedChecks(true, true, true, true, true))
        assertEquals("截图出不来图", WebHeadless.failedChecks(true, true, true, true, false))
        assertEquals(
            "取不到正文、CSS 排版不对",
            WebHeadless.failedChecks(true, false, true, false, true)
        )
    }
}
