package com.workspace.proot

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotesTagSearchTest {

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `query and hasQuery follow the search field`() {
        val activity = activity()
        val search = NotesTagSearch(activity, ThemeColors.default(true)) { listOf("工作") }

        assertFalse(search.hasQuery())
        search.searchField.toggleTag("工作")
        assertTrue(search.hasQuery())
        assertEquals("tag:工作", search.query())

        search.searchField.input.setText("苹果")
        assertEquals("tag:工作 苹果", search.query())

        search.clear()
        assertFalse(search.hasQuery())
    }

    @Test
    fun `refreshTags prunes selected tags that no longer exist`() {
        val activity = activity()
        val tags = mutableListOf("工作", "重要")
        val search = NotesTagSearch(activity, ThemeColors.default(true)) { tags }
        search.searchField.toggleTag("工作")
        search.searchField.toggleTag("重要")

        tags.remove("工作")
        search.refreshTags()

        assertEquals(listOf("重要"), search.searchField.selectedTags())
        assertEquals("tag:重要", search.query())
    }

    @Test
    fun `attach accepts the list and fab used for clearance and fade`() {
        val activity = activity()
        val search = NotesTagSearch(activity, ThemeColors.default(true)) { listOf() }
        search.attach(LinearLayout(activity), TextView(activity))
        // 没有标签时聚焦不能显示空筛选条
        search.searchField.input.requestFocus()
        assertFalse(search.filterRow.isShowing)
    }

    @Test
    fun `focus shows the filter row and reserves list clearance`() {
        val activity = activity()
        val tags = mutableListOf("工作")
        val search = NotesTagSearch(activity, ThemeColors.default(true)) { tags }
        val list = LinearLayout(activity)
        val fab = TextView(activity).apply { visibility = View.VISIBLE }
        val root = FrameLayout(activity).apply {
            addView(search.searchField)
            addView(list)
            addView(search.filterRow)
            addView(fab)
        }
        activity.setContentView(root)
        search.attach(list, fab)

        search.searchField.input.onFocusChangeListener?.onFocusChange(search.searchField.input, true)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertTrue(search.filterRow.isShowing)
        assertTrue("list must reserve room for the floating row", list.paddingBottom > 0)

        search.hideForEditor()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertFalse(search.filterRow.isShowing)
        assertEquals(0, list.paddingBottom)
    }
}
