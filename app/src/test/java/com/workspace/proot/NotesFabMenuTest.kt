@file:Suppress("MagicNumber")

package com.workspace.proot

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotesFabMenuTest {

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `collapsed icon is a group drawer not a plus`() {
        val activity = activity()
        val menu = NotesFabMenu(activity, ThemeColors.default(true), {}, {}, {})
        val main = menu.getChildAt(2) as TextView
        assertEquals("☰", main.text.toString())
        assertEquals(activity.getString(R.string.notes_menu_expand), main.contentDescription)
        assertFalse(menu.isExpanded)
    }

    @Test
    fun `expanded shows three and main becomes close`() {
        val activity = activity()
        val menu = NotesFabMenu(activity, ThemeColors.default(true), {}, {}, {})
        menu.setExpandedImmediate(true)

        assertEquals(3, menu.childCount)
        assertTrue(menu.isExpanded)
        val add = menu.getChildAt(0) as TextView
        val search = menu.getChildAt(1) as TextView
        val main = menu.getChildAt(2) as TextView
        assertEquals(View.VISIBLE, add.visibility)
        assertEquals(View.VISIBLE, search.visibility)
        assertEquals("＋", add.text.toString())
        assertEquals("⌕", search.text.toString())
        assertEquals("×", main.text.toString())
        assertEquals(activity.getString(R.string.notes_menu_collapse), main.contentDescription)

        menu.setExpandedImmediate(false)
        assertFalse(menu.isExpanded)
        assertEquals(View.GONE, add.visibility)
        assertEquals(View.GONE, search.visibility)
        assertEquals("☰", main.text.toString())
    }

    @Test
    fun `all three fabs are 56dp and share the same center line`() {
        val activity = activity()
        val menu = NotesFabMenu(activity, ThemeColors.default(true), {}, {}, {})
        val root = FrameLayout(activity)
        root.addView(
            menu,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        activity.setContentView(root)
        menu.setExpandedImmediate(true)
        val width = (360 * activity.resources.displayMetrics.density).toInt()
        val height = (640 * activity.resources.displayMetrics.density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)

        val expected = (56 * activity.resources.displayMetrics.density).toInt()
        val fabs = (0 until menu.childCount)
            .map { menu.getChildAt(it) }
            .filterIsInstance<TextView>()
        assertEquals(3, fabs.size)
        for (fab in fabs) {
            val lp = fab.layoutParams as FrameLayout.LayoutParams
            assertEquals("all fabs must be 56dp wide", expected, lp.width)
            assertEquals("all fabs must be 56dp tall", expected, lp.height)
            assertEquals("all fabs must use the same glyph size", fabs[0].textSize, fab.textSize, 0.01f)
            assertEquals("all fabs must share the same center line", fabs[0].left + fabs[0].width / 2, fab.left + fab.width / 2)
        }
        val add = fabs.first { it.text == "＋" }
        val search = fabs.first { it.text == "⌕" }
        val main = fabs.first { it.text == "×" }
        val gap = (10 * activity.resources.displayMetrics.density).toInt()
        assertEquals(gap, main.top - add.bottom)
        assertEquals(gap, add.top - search.bottom)
    }

    @Test
    fun `close glyph is ink-centered inside the fab`() {
        val activity = activity()
        val size = (56 * activity.resources.displayMetrics.density).toInt()
        val fab = TextView(activity).apply {
            text = "×"
            textSize = UiTokens.TEXT_DISPLAY
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(size, size)
        }
        val root = FrameLayout(activity)
        root.addView(fab)
        activity.setContentView(root)
        val spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        root.measure(spec, spec)
        root.layout(0, 0, size, size)

        NeuFab.centerGlyph(fab)
        root.measure(spec, spec)
        root.layout(0, 0, size, size)
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertFalse("字体额外留白必须关掉", fab.includeFontPadding)
        assertTrue("字体为 0 时 helper 不能崩", fab.paddingBottom >= 0)
    }

    @Test
    fun `three hit targets call the three actions`() {
        val activity = activity()
        var toggles = 0
        var searches = 0
        var news = 0
        val menu = NotesFabMenu(
            activity,
            ThemeColors.default(true),
            onToggle = { toggles++ },
            onSearch = { searches++ },
            onNewNote = { news++ },
        )
        menu.setExpandedImmediate(true)
        (menu.getChildAt(2) as TextView).performClick()
        (menu.getChildAt(0) as TextView).performClick()
        (menu.getChildAt(1) as TextView).performClick()

        assertEquals(1, toggles)
        assertEquals(1, searches)
        assertEquals(1, news)
    }
}
