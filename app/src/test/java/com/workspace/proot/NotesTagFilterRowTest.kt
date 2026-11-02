@file:Suppress("MagicNumber")

package com.workspace.proot

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotesTagFilterRowTest {

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun row(activity: Activity): NotesTagFilterRow =
        NotesTagFilterRow(activity, ThemeColors.default(true)).also { activity.setContentView(it) }

    private fun chips(row: NotesTagFilterRow): LinearLayout =
        row.getChildAt(0) as LinearLayout

    @Test
    fun `all tags stay in one horizontally scrollable row`() {
        val activity = activity()
        val row = row(activity)
        val tags = (1..30).map { "标签$it" }

        row.bind(tags, emptySet())
        row.showAnimated()

        assertEquals(30, chips(row).childCount)
        assertEquals(LinearLayout.HORIZONTAL, chips(row).orientation)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, chips(row).layoutParams.width)
        assertFalse(row.isHorizontalScrollBarEnabled)
        assertEquals(View.VISIBLE, row.visibility)
    }

    @Test
    fun `tapping a tag reports the tag and keeps it non focusable`() {
        val activity = activity()
        val row = row(activity)
        var clicked: String? = null
        row.onTagClick = { clicked = it }
        row.bind(listOf("工作", "重要"), emptySet())
        row.showAnimated()

        val chip = chips(row).getChildAt(0) as TextView
        assertFalse(chip.isFocusable)
        assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, row.descendantFocusability)
        chip.performClick()

        assertEquals("工作", clicked)
    }

    @Test
    fun `selected tags get the selected description and full opacity`() {
        val activity = activity()
        val row = row(activity)
        row.bind(listOf("工作", "重要"), setOf("重要"))
        row.showAnimated()

        val work = chips(row).getChildAt(0) as TextView
        val important = chips(row).getChildAt(1) as TextView
        assertEquals(
            activity.getString(R.string.notes_tag_filter_select_fmt, "工作"),
            work.contentDescription,
        )
        assertEquals(
            activity.getString(R.string.notes_tag_filter_selected_fmt, "重要"),
            important.contentDescription,
        )
        assertTrue(work.alpha < important.alpha)
    }

    @Test
    fun `rebinding the same tag set does not rebuild chips`() {
        val activity = activity()
        val row = row(activity)
        val tags = listOf("工作", "重要")
        row.bind(tags, emptySet())
        val first = chips(row).getChildAt(0)

        row.bind(tags, setOf("工作"))

        assertSame(first, chips(row).getChildAt(0))
    }

    @Test
    fun `row background is translucent and elevated`() {
        val activity = activity()
        val row = row(activity)
        val background = row.background as GradientDrawable
        val color = background.color?.defaultColor ?: 0
        assertTrue("row background must be translucent", (color ushr 24) < 0xFF)
        assertTrue(row.elevation > 0f)
    }

    @Test
    fun `hide animates out and empty tags stay hidden`() {
        val activity = activity()
        val row = row(activity)
        row.bind(emptyList(), emptySet())
        assertEquals(View.GONE, row.visibility)

        row.bind(listOf("工作"), emptySet())
        row.showAnimated()
        assertEquals(View.VISIBLE, row.visibility)
        row.hideAnimated()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals(View.GONE, row.visibility)
        assertFalse(row.isShowing)
    }
}
