@file:Suppress("MagicNumber")

package com.workspace.proot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 锁住浮窗动画/销毁边界：
 * 只有 show() 的 animateIn 和最终 dismiss() 允许窗口动画；
 * updateContent/onOutsideTap/标题更新都不许 animate，也不许 removeView。
 */
class DialogWindowScanTest {

    private fun source(name: String): String {
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

    private fun body(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("missing start marker: $start", from >= 0)
        val to = source.indexOf(end, from + start.length)
        assertTrue("missing end marker: $end", to >= 0)
        return source.substring(from, to)
    }

    @Test
    fun `only show and dismiss animate the window`() {
        val overlay = source("ScriptDialogOverlay.kt")
        val update = body(overlay, "fun updateContent(", "private fun onResultFromNode")
        assertFalse("updateContent must not animate the window", update.contains(".animate("))

        val outside = body(overlay, "private fun onOutsideTap(", "private fun showOutsideHint")
        assertFalse("outside tap must not flash the window", outside.contains(".animate("))

        val dismiss = body(overlay, "fun dismiss(immediate", "private fun finishDismiss")
        assertTrue("final dismiss must keep the fade-out animation", dismiss.contains(".animate("))

        val animateIn = body(overlay, "private fun animateIn(", "private fun dp(")
        assertTrue("first show must keep the entrance animation", animateIn.contains(".animate("))
    }

    @Test
    fun `renderer update reuses content hosts and may animate them`() {
        val renderer = source("ScriptDialogRenderer.kt")
        val update = body(renderer, "fun updateCard(", "private fun updateCardFrame")
        val fadeOut = body(renderer, "private fun fadeOutContent(", "private fun fadeInContent")
        val fadeIn = body(renderer, "private fun fadeInContent(", "private fun startSizeAnimation")
        val size = body(renderer, "private fun startSizeAnimation(", "private fun cancelUpdateTransition")
        assertFalse("updateCard must not add content hosts", update.contains("addView("))
        assertFalse("updateCard must not remove content hosts", update.contains("removeView("))
        assertTrue("old content should fade out", fadeOut.contains("ValueAnimator"))
        assertTrue("new content should fade in", fadeIn.contains("ValueAnimator"))
        assertTrue("card size should animate", size.contains("ValueAnimator"))
    }

    @Test
    fun `updateContent never removes the window`() {
        val overlay = source("ScriptDialogOverlay.kt")
        val update = body(overlay, "fun updateContent(", "private fun onResultFromNode")
        assertFalse("updateContent must not call removeView", update.contains("removeView"))
        assertFalse("updateContent must not call dismiss", update.contains("dismiss()"))
    }
}
