@file:Suppress("MagicNumber")

package com.workspace.proot

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotesSearchFieldTest {

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun field(activity: Activity): NotesSearchField =
        NotesSearchField(activity, ThemeColors.default(true))

    private fun chips(field: NotesSearchField): LinearLayout {
        val scroll = field.getChildAt(0) as HorizontalScrollView
        return scroll.getChildAt(0) as LinearLayout
    }

    @Test
    fun `tag becomes a real capsule and composes tag syntax`() {
        val activity = activity()
        val field = field(activity)
        val immediate = ArrayList<Boolean>()
        field.onQueryChanged = { immediate.add(it) }

        field.toggleTag("工作")

        assertEquals(listOf("工作"), field.selectedTags())
        assertEquals("tag:工作", field.query())
        assertEquals(1, chips(field).childCount)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, chips(field).layoutParams.width)
        assertEquals(TextView::class.java, chips(field).getChildAt(0)::class.java)
        assertTrue(immediate.last())
        assertEquals(
            activity.getString(R.string.notes_tag_filter_remove_fmt, "工作"),
            chips(field).getChildAt(0).contentDescription,
        )
        assertNotEquals(View.GONE, (field.getChildAt(0) as HorizontalScrollView).visibility)
    }

    @Test
    fun `free text and multiple tags compose together`() {
        val activity = activity()
        val field = field(activity)
        val textEvents = ArrayList<Boolean>()
        field.onQueryChanged = { textEvents.add(it) }

        field.toggleTag("工作")
        field.toggleTag("重要")
        field.input.setText("苹果")

        assertEquals(listOf("工作", "重要"), field.selectedTags())
        assertEquals("tag:工作 tag:重要 苹果", field.query())
        assertEquals(false, textEvents.last())
    }

    @Test
    fun `tapping a capsule removes only that filter`() {
        val activity = activity()
        val field = field(activity)
        field.toggleTag("工作")
        field.toggleTag("重要")
        field.input.setText("苹果")

        (chips(field).getChildAt(0) as TextView).performClick()

        assertEquals(listOf("重要"), field.selectedTags())
        assertEquals("tag:重要 苹果", field.query())
        assertEquals(1, chips(field).childCount)
    }

    @Test
    fun `toggle twice removes and empty tags hide the capsule row`() {
        val activity = activity()
        val field = field(activity)
        field.toggleTag("工作")
        field.toggleTag("工作")

        assertTrue(field.selectedTags().isEmpty())
        assertEquals("", field.query())
        assertEquals(0, chips(field).childCount)
        assertEquals(View.GONE, (field.getChildAt(0) as HorizontalScrollView).visibility)
    }

    @Test
    fun `clear removes text and tags together`() {
        val activity = activity()
        val field = field(activity)
        field.toggleTag("工作")
        field.input.setText("苹果")

        field.clear()

        assertTrue(field.selectedTags().isEmpty())
        assertEquals("", field.input.text?.toString())
        assertEquals("", field.query())
        assertEquals(0, chips(field).childCount)
    }

    @Test
    fun `setSelectedTags prunes to the given set`() {
        val activity = activity()
        val field = field(activity)
        field.toggleTag("工作")
        field.toggleTag("重要")

        field.setSelectedTags(listOf("重要"))

        assertEquals(listOf("重要"), field.selectedTags())
        assertEquals("tag:重要", field.query())
        assertFalse(field.getChildAt(0).visibility == View.VISIBLE && chips(field).childCount > 1)
    }
}
