package com.workspace.proot

import android.app.Activity
import android.widget.EditText
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImeFocusTest {

    @Test
    fun `clearAndHide clears the focused input`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val edit = RecordingEditText(activity)
        activity.setContentView(edit)

        edit.requestFocus()
        assertTrue("test setup: edit must start focused", edit.hasFocus())

        ImeFocus.clearAndHide(activity)

        assertTrue("the focused view must receive clearFocus()", edit.cleared)
    }

    @Test
    fun `clearAndHide is safe without a focused input`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ImeFocus.clearAndHide(activity)
    }

    /** Robolectric 的窗口焦点标志不一定随 clearFocus 同步，用记录调用验证目标视图。 */
    private class RecordingEditText(context: android.content.Context) : EditText(context) {
        var cleared = false
            private set

        override fun clearFocus() {
            cleared = true
            super.clearFocus()
        }
    }
}
