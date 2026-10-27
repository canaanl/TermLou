@file:Suppress("MagicNumber")

package com.workspace.proot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** 真机锁：第一次点空白区域必须出现顶部白字提示，且提示有实际宽高、能画出来。 */
@RunWith(AndroidJUnit4::class)
class ScriptDialogOverlayInstrumentedTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    @Test
    fun outsideTapShowsVisibleHint() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val ctx = instr.targetContext
        val request = ScriptDialogSpec.Request(
            id = "hint",
            ui = ScriptDialogSpec.Ui(
                title = "Hint",
                message = "body",
                rows = listOf(
                    ScriptDialogSpec.Row.Buttons(
                        ScriptDialogSpec.ButtonsRow(
                            listOf(ScriptDialogSpec.Button("Close", "close", close = false))
                        )
                    )
                )
            )
        )
        var root: View? = null
        var hint: View? = null
        var overlay: ScriptDialogOverlay? = null
        instr.runOnMainSync {
            val created = ScriptDialogOverlay(ctx, request) {}
            overlay = created
            created.show()
            root = created.windowRoot()
            hint = created.outsideHintForTest()
            assertTrue("overlay root missing", root != null)
            assertTrue("hint missing", hint != null)
            assertEquals("hint should start hidden", View.GONE, hint!!.visibility)
        }
        instr.waitForIdleSync()
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 540f, 400f, 0)
        val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, 540f, 400f, 0)
        instr.sendPointerSync(down)
        instr.sendPointerSync(up)
        down.recycle()
        up.recycle()
        instr.waitForIdleSync()
        instr.runOnMainSync {
            assertEquals("first outside tap must show hint", View.VISIBLE, hint!!.visibility)
        }
        instr.waitForIdleSync()
        instr.runOnMainSync {
            val r = root!!
            val h = hint!!
            assertTrue("hint has no width: ${h.width}", h.width > 0)
            assertTrue("hint has no height: ${h.height}", h.height > 0)
            val bmp = Bitmap.createBitmap(r.width.coerceAtLeast(1), r.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            r.draw(Canvas(bmp))
            val cx = (h.left + h.right) / 2
            val cy = (h.top + h.bottom) / 2
            val pixel = bmp.getPixel(cx.coerceIn(0, bmp.width - 1), cy.coerceIn(0, bmp.height - 1))
            assertTrue("hint did not paint at ($cx,$cy): #${Integer.toHexString(pixel)}", pixel != 0)
            overlay?.dismiss(immediate = true)
        }
    }
}
