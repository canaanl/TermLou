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
    fun `returning from a search result keeps the query and results`() {
        val f = Fixture()
        f.overlay.expandMenu(animate = false)
        f.overlay.openSearch()
        f.tagSearch.searchField.toggleTag("工作")
        f.overlay.setResultsAvailable(true)

        // 从搜索结果点进笔记。
        f.overlay.hideForEditor()
        assertFalse(f.overlay.isSearchOpen)

        // 发现不是想要的那篇，退回列表。
        assertTrue("必须恢复搜索会话：", f.overlay.restoreAfterEditor())
        // 真实流程里 showList() 紧接着 renderList()，会重新告知是否有结果。
        f.overlay.setResultsAvailable(true)
        assertTrue("搜索面板必须重新打开：", f.overlay.isSearchOpen)
        assertTrue("FAB 组必须保持展开：", f.overlay.isExpanded)
        assertTrue("查询词不能丢：", f.tagSearch.hasQuery())
        assertTrue("恢复后列表必须仍可点：", !f.overlay.scrim.isBlocking)
    }

    @Test
    fun `second back after restore returns to the initial state`() {
        val f = Fixture()
        f.overlay.expandMenu(animate = false)
        f.overlay.openSearch()
        f.tagSearch.searchField.toggleTag("工作")
        f.overlay.setResultsAvailable(true)

        f.overlay.hideForEditor()
        f.overlay.restoreAfterEditor()
        assertTrue(f.overlay.isSearchOpen)

        // 恢复后第一次返回：一步回到初始（清空搜索 + 收起 FAB）。
        assertTrue(f.overlay.handleBack())
        assertFalse(f.overlay.isSearchOpen)
        assertFalse(f.overlay.isExpanded)
        assertFalse("查询必须被清空：", f.tagSearch.hasQuery())

        // 已经回到初始，返回键不再拦截。
        assertFalse(f.overlay.handleBack())
    }

    @Test
    fun `editor without search returns to a plain collapsed list`() {
        val f = Fixture()
        assertFalse("没有搜索时不应恢复搜索会话：", f.overlay.restoreAfterEditor())
        assertFalse(f.overlay.isSearchOpen)
        assertFalse(f.overlay.isExpanded)
        assertTrue(f.overlay.menu.visibility == View.VISIBLE)
    }

    @Test
    fun `tab switch after restore ends the session completely`() {
        val f = Fixture()
        f.overlay.expandMenu(animate = false)
        f.overlay.openSearch()
        f.tagSearch.searchField.toggleTag("工作")
        f.overlay.hideForEditor()
        f.overlay.restoreAfterEditor()

        f.overlay.onTabSwitch()

        assertFalse(f.overlay.isExpanded)
        assertFalse(f.overlay.isSearchOpen)
        assertFalse("切 Tab 必须结束会话并清空查询：", f.tagSearch.hasQuery())
        // 会话已结束，再调恢复不应复活。
        assertFalse(f.overlay.restoreAfterEditor())
    }

    @Test
    fun `opening a note without search keeps the collapsed state`() {
        val f = Fixture()
        f.overlay.expandMenu(animate = false)
        f.overlay.hideForEditor()

        assertFalse("未开搜索时进编辑器不应挂起会话：", f.overlay.restoreAfterEditor())
        assertFalse(f.overlay.isSearchOpen)
        assertFalse(f.overlay.isExpanded)
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
