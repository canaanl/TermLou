@file:Suppress("MagicNumber")

package com.workspace.proot

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * 浮窗窗口生命周期：show 只 addView 一次，update 复用同一个 root，
 * 第一次点外部出白字提示，第二次才进入 DISMISSING。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScriptDialogOverlayLifecycleTest {

    private fun request(title: String): ScriptDialogSpec.Request = ScriptDialogSpec.Request(
        id = "life",
        ui = ScriptDialogSpec.Ui(
            title = title,
            rows = listOf(
                ScriptDialogSpec.Row.Text(ScriptDialogSpec.TextRow(title)),
                ScriptDialogSpec.Row.Buttons(
                    ScriptDialogSpec.ButtonsRow(
                        listOf(ScriptDialogSpec.Button("OK", "ok", ScriptDialogSpec.BTN_PRIMARY))
                    )
                )
            )
        )
    )

    @Test
    fun `update reuses the same window root and outside tap shows the white hint`() {
        val ctx = RuntimeEnvironment.getApplication()
        val overlay = ScriptDialogOverlay(ctx, request("A")) {}
        overlay.show()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("window not created", overlay.isAlive())
        val root = overlay.windowRoot()!!
        val dialog = findDialog(root)!!

        overlay.updateContent(request("B"))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        assertSame("updateContent must not replace the root", root, overlay.windowRoot())
        assertSame("updateContent must not replace the dialog", dialog, findDialog(root))
        val title = (root as android.widget.FrameLayout).getChildAt(0)
            .let { it as android.widget.LinearLayout }.getChildAt(0) as TextView
        assertTrue("title not updated: ${title.text}", title.text.contains("B"))

        val hint = overlay.outsideHintForTest()!!
        assertEquals(View.GONE, hint.visibility)
        val now = SystemClock.uptimeMillis()
        val outside = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 4f, 4f, 0)
        root.dispatchTouchEvent(outside)
        outside.recycle()
        assertEquals("first outside tap must show hint", View.VISIBLE, hint.visibility)
        assertFalse("first outside tap must not dismiss", overlay.isDismissing())

        val second = MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, 4f, 4f, 0)
        root.dispatchTouchEvent(second)
        second.recycle()
        assertEquals(View.GONE, hint.visibility)
        assertTrue("second outside tap must dismiss", overlay.isDismissing())
    }

    @Test
    fun `new request during the dismiss fade cancels the disappearance`() {
        val ctx = RuntimeEnvironment.getApplication()
        val overlay = ScriptDialogOverlay(ctx, request("A")) {}
        overlay.show()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val root = overlay.windowRoot()!!
        val dialog = findDialog(root)!!

        overlay.dismiss()
        assertTrue(overlay.isDismissing())

        overlay.updateContent(request("C"))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse("update must cancel the fade", overlay.isDismissing())
        assertSame(root, overlay.windowRoot())
        assertSame(dialog, findDialog(root))
    }

    @Test
    fun `final dismiss removes the window once`() {
        val ctx = RuntimeEnvironment.getApplication()
        var dismissed = 0
        val overlay = ScriptDialogOverlay(ctx, request("A")) {}
        overlay.onDismissed = { dismissed++ }
        overlay.show()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(overlay.isAlive())

        overlay.dismiss(immediate = true)
        assertFalse(overlay.isAlive())
        assertEquals(1, dismissed)
        overlay.dismiss(immediate = true)
        assertEquals(1, dismissed)
    }

    @Suppress("ReturnCount")
    private fun findDialog(view: View): CanvasDialogView? {
        if (view is CanvasDialogView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findDialog(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
