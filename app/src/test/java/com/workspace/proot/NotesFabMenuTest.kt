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

    // ---------- 聚合按钮的图标与颜色（5.12.6） ----------

    @Test
    fun `digest mode uses a centered foreground icon instead of compound drawable`() {
        val activity = activity()
        val menu = NotesFabMenu(activity, ThemeColors.default(true), {}, {}, {})
        menu.setExpandedImmediate(true)
        val search = menu.searchFabForTest()

        menu.setDigestMode(enabled = true, available = true)

        assertEquals("聚合模式必须清空字形：", "", search.text.toString())
        assertTrue("必须用前景 Drawable 居中绘制：", search.foreground is DigestIconDrawable)
        assertEquals("不能用复合图标（会居左）：", 0, search.compoundDrawablesRelative.count { it != null })
        assertEquals("不能残留墨迹补偿 padding：", 0, search.paddingLeft + search.paddingTop + search.paddingRight + search.paddingBottom)
        assertEquals("禁用态不能用 alpha 假装：", 1f, search.alpha, 0.001f)
    }

    @Test
    fun `digest icon color follows the same rule as the file back fab`() {
        val activity = activity()
        val theme = ThemeColors.default(true)
        val menu = NotesFabMenu(activity, theme, {}, {}, {})
        menu.setExpandedImmediate(true)

        menu.setDigestMode(enabled = true, available = true)
        assertEquals("可用时必须用品牌色：", theme.primaryVisible, menu.digestIconColorForTest())

        menu.setDigestMode(enabled = true, available = false)
        assertEquals("不可用时必须用同一套禁用色：", NeuFab.disabledIconColor(theme), menu.digestIconColorForTest())
        assertFalse("不可用时不可点：", menu.searchFabForTest().isClickable)

        menu.setDigestMode(enabled = false, available = false)
        assertTrue("退出聚合模式：", menu.digestIconColorForTest() == null)
        assertEquals("必须恢复 ⌕：", "⌕", menu.searchFabForTest().text.toString())
        assertTrue("恢复后必须可点：", menu.searchFabForTest().isClickable)
        assertTrue("必须清掉前景图标：", menu.searchFabForTest().foreground == null)
    }

    @Test
    fun `digest icon is drawn centered and smaller than the fab`() {
        val activity = activity()
        val drawable = DigestIconDrawable(activity)
        val size = (56 * activity.resources.displayMetrics.density).toInt()
        drawable.setBounds(0, 0, size, size)

        val rect = drawable.iconRect()
        val iconPx = (DigestIconDrawable.ICON_DP * activity.resources.displayMetrics.density).toInt()
        assertEquals("图标边长必须是 ICON_DP：", iconPx, rect.width())
        assertTrue("图标必须明显小于 FAB：$iconPx vs $size", iconPx < size / 2)

        val centerX = (rect.left + rect.right) / 2f
        val centerY = (rect.top + rect.bottom) / 2f
        assertEquals("水平必须居中：", size / 2f, centerX, 2.5f)
        assertEquals("垂直必须居中：", size / 2f, centerY, 2.5f)

        // 非正方形 bounds（真机 foreground 可能带内边距）也必须居中。
        drawable.setBounds(10, 20, 10 + size, 20 + size)
        val rect2 = drawable.iconRect()
        assertEquals("偏移 bounds 下仍要水平居中：", 10 + size / 2f, (rect2.left + rect2.right) / 2f, 2.5f)
        assertEquals("偏移 bounds 下仍要垂直居中：", 20 + size / 2f, (rect2.top + rect2.bottom) / 2f, 2.5f)
    }
}
