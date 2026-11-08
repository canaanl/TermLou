@file:Suppress("MagicNumber")

package com.workspace.proot

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotesSearchOverlayTest {

    private class Fixture(onNewNote: () -> Unit = {}) {
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = FrameLayout(activity)
        val tagSearch = NotesTagSearch(activity, ThemeColors.default(true)) { listOf("工作") }
        val overlay = NotesSearchOverlay(activity, ThemeColors.default(true), tagSearch, onNewNote)

        init {
            activity.setContentView(host)
            overlay.attach(host)
        }
    }

    @Test
    fun `collapsed has no scrim and search is closed`() {
        val f = Fixture()
        assertFalse(f.overlay.isExpanded)
        assertFalse(f.overlay.isSearchOpen)
        assertFalse(f.overlay.scrim.isBlocking)
        assertFalse(f.overlay.menu.isExpanded)
        assertTrue(f.overlay.menu.visibility == View.VISIBLE)
    }

    @Test
    fun `expanded blocks the notes content`() {
        val f = Fixture()
        f.overlay.expandMenu(animate = false)
        assertTrue(f.overlay.isExpanded)
        assertTrue(f.overlay.menu.isExpanded)
        assertTrue(f.overlay.scrim.isBlocking)
        assertTrue(f.overlay.scrim.visibility == View.VISIBLE)
    }

    @Test
    fun `search results are the only unblocked path`() {
        val f = Fixture()
        f.overlay.expandMenu(animate = false)
        f.overlay.openSearch()
        assertTrue(f.overlay.isSearchOpen)
        assertTrue("empty query must stay blocked", f.overlay.scrim.isBlocking)

        f.overlay.setResultsAvailable(true)
        assertFalse("search results must be clickable", f.overlay.scrim.isBlocking)

        f.overlay.setResultsAvailable(false)
        assertTrue("no results must block again", f.overlay.scrim.isBlocking)
    }

    @Test
    fun `closing search keeps fab expanded and clears query`() {
        val f = Fixture()
        f.overlay.expandMenu(animate = false)
        f.overlay.openSearch()
        f.tagSearch.searchField.toggleTag("工作")
        assertTrue(f.tagSearch.hasQuery())

        f.overlay.toggleSearch()

        assertFalse(f.overlay.isSearchOpen)
        assertTrue("search FAB only closes search", f.overlay.isExpanded)
        assertFalse(f.tagSearch.hasQuery())
        assertTrue(f.overlay.scrim.isBlocking)
    }

    @Test
    fun `back closes search first then collapses fab`() {
        val f = Fixture()
        f.overlay.expandMenu(animate = false)
        f.overlay.openSearch()

        assertTrue(f.overlay.handleBack())
        assertFalse(f.overlay.isSearchOpen)
        assertTrue(f.overlay.isExpanded)

        assertTrue(f.overlay.handleBack())
        assertFalse(f.overlay.isExpanded)
        assertFalse(f.overlay.scrim.isBlocking)

        assertFalse(f.overlay.handleBack())
    }

    @Test
    fun `tab switch collapses everything immediately`() {
        val f = Fixture()
        f.overlay.expandMenu(animate = false)
        f.overlay.openSearch()
        f.tagSearch.searchField.toggleTag("工作")

        f.overlay.onTabSwitch()

        assertFalse(f.overlay.isExpanded)
        assertFalse(f.overlay.isSearchOpen)
        assertFalse(f.tagSearch.hasQuery())
        assertFalse(f.overlay.scrim.isBlocking)
        assertFalse(f.overlay.menu.isExpanded)
    }

    @Test
    fun `editor hides the whole fab group and list state restores it collapsed`() {
        val f = Fixture()
        f.overlay.expandMenu(animate = false)
        f.overlay.openSearch()

        f.overlay.hideForEditor()
        assertFalse(f.overlay.isExpanded)
        assertFalse(f.overlay.isSearchOpen)
        assertTrue(f.overlay.menu.visibility == View.GONE)

        f.overlay.showMenuCollapsed()
        assertTrue(f.overlay.menu.visibility == View.VISIBLE)
        assertFalse(f.overlay.menu.isExpanded)
    }

    @Test
    fun `panel is full width and menu fills the content host`() {
        val f = Fixture()
        f.host.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        f.host.layout(0, 0, 1080, 1920)

        assertTrue(f.overlay.menu.width == 1080)
        f.overlay.expandMenu(animate = false)
        f.overlay.openSearch()
        f.host.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        f.host.layout(0, 0, 1080, 1920)
        assertTrue("search panel must be full width", f.overlay.panel.width == 1080)
        assertTrue("search panel must be visible", f.overlay.panel.visibility == View.VISIBLE)
    }

    @Test
    fun `new note collapses the menu before opening the dialog`() {
        var opened = false
        val f = Fixture { opened = true }
        f.overlay.expandMenu(animate = false)

        val add = (0 until f.overlay.menu.childCount)
            .map { f.overlay.menu.getChildAt(it) }
            .filterIsInstance<android.widget.TextView>()
            .first { it.text == "＋" }
        add.performClick()

        assertTrue("add FAB must open the new-note flow", opened)
        assertFalse("add FAB must collapse the group", f.overlay.isExpanded)
    }
}
