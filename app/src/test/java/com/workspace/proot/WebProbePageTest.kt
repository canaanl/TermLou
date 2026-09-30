package com.workspace.proot

import android.graphics.Bitmap
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自检页"必须够花"这条约束的锁（5.9.8）。
 *
 * 5.9.7 第一版的自检页是「白底 + 一行小字 + 一根 20px 细条」，在 240×480 的视口上
 * 判空抽样（8×12 网格，步长约 30×40 CSS px）**一根都抽不中**，于是整屏同色判成空图，
 * `headless_failed` 报"截图出不来图"，明明能用的设备被逼去要悬浮窗权限。
 * 同一台手机上探针实测 `draw()` 出了 115 万非白像素 —— 是判据用错了页面，不是渲染坏了。
 *
 * 这条测试把"色块必须盖过抽样步长"变成可执行的数字，防止有人把自检页"简化"回去。
 */
class WebProbePageTest {

    @Test
    fun `色块高度必须盖过判空抽样的步长`() {
        val step = WebHeadless.PROBE_VIEWPORT_H_CSS / WebShotSampler.ROWS
        assertTrue(
            "色块 ${WebHeadless.PROBE_BAR_CSS_PX}px 盖不过抽样步长 $step px —— " +
                "自检页会重新被判成空图（5.9.7 的假失败就是这么来的）",
            WebHeadless.PROBE_BAR_CSS_PX >= step
        )
    }

    @Test
    fun `色块还要盖得住一整行网格`() {
        val step = WebHeadless.PROBE_VIEWPORT_H_CSS / WebShotSampler.ROWS
        assertTrue(
            "色块只刚够一步长的话，抽样行与色块边界错开仍可能落空",
            WebHeadless.PROBE_BAR_CSS_PX >= step * 2
        )
    }

    @Test
    fun `自检页得同时提供文字与排版两样可测的东西`() {
        val h = WebHeadless.PROBE_HTML
        assertTrue("文字检查靠它", h.contains("id=\"t\""))
        assertTrue("排版检查靠它（50% 宽）", h.contains("width:50%"))
        assertTrue("排版判据按 8px padding 折算，容器不能少", h.contains("padding:8px"))
    }

    @Test
    fun `自检页的空白底色本身就不是纯白`() {
        // 全白底 + 少量内容是最容易误判的组合；底色先给个浅灰兜底
        assertTrue(
            "底色应当不是纯白，否则抽样只要没抽到内容就全同色",
            !WebHeadless.PROBE_HTML.contains("background:#ffffff") &&
                !WebHeadless.PROBE_HTML.contains("background:#fff")
        )
    }

    @Test
    fun `自检页不能有外链资源`() {
        val h = WebHeadless.PROBE_HTML.lowercase()
        assertTrue("自检必须纯本地，否则断网就误判成不可用", !h.contains("http://"))
        assertTrue(!h.contains("https://"))
        assertTrue("不该有 src=", !h.contains("src="))
    }

    @Test
    fun `色块数量够两个 不同抽样行能采到不同颜色`() {
        // 一块的话，若它没盖住全部高度，抽样仍可能全落在同一颜色上
        val needle = "height:${WebHeadless.PROBE_BAR_CSS_PX}px"
        val h = WebHeadless.PROBE_HTML
        var bars = 0
        var at = h.indexOf(needle)
        while (at >= 0) { bars++; at = h.indexOf(needle, at + needle.length) }
        assertTrue("至少两块不同颜色的色块，实际 $bars 块", bars >= 2)
    }
}
