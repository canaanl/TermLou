@file:Suppress("MagicNumber")

package com.workspace.proot

import android.app.Activity
import android.view.View
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
