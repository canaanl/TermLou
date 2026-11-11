@file:Suppress("MagicNumber")

package com.workspace.proot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 5.11.1：横滑切 Tab 是最高优先级。
 *
 * 这组锁不测“某个布尔值”，而是扫真实代码，防止以后有人给遮罩/面板加
 * `requestDisallowInterceptTouchEvent(true)`，把 ViewPager2 的横滑彻底掐死。
 */
class NotesSwipePriorityTest {

    private fun code(name: String): String {
        var d: File? = File("").absoluteFile
        var hops = 0
        while (d != null && hops < 6) {
            val f = File(d, "app/src/main/java/com/workspace/proot/$name")
            if (f.isFile) {
                return f.readText().lines()
                    .map { it.trimStart().let { t -> if (t.startsWith("*") || t.startsWith("/*")) "" else it } }
                    .joinToString("\n")
            }
            d = d.parentFile
            hops++
        }
        throw AssertionError("找不到 $name，这条检查等于没跑")
    }

    @Test
    fun `notes scrim must never disallow parent interception`() {
        val scrim = code("NotesScrimView.kt")
        assertFalse(
            "笔记遮罩不许 requestDisallowInterceptTouchEvent，否则横滑切 Tab 会被它吃掉：\n$scrim",
            scrim.contains("requestDisallowInterceptTouchEvent")
        )
        assertFalse(
            "遮罩不许挂 setOnTouchListener（同样会绕开 ViewPager2 的拦截）：\n$scrim",
            scrim.contains("setOnTouchListener")
        )
    }

    @Test
    fun `only the search drag handle may disallow parent`() {
        val panel = code("NotesSearchPanel.kt")
        val handle = panel.substringAfter("private fun onHandleTouch(")
            .substringBefore("private fun maxTranslation(")
        assertTrue("拖动把手必须先判断方向：\n$handle", handle.contains("dx > dy"))
        assertTrue("纵向拖动才抢父级：\n$handle", handle.contains("requestDisallowInterceptTouchEvent(true)"))
        assertTrue("横向要主动放开父级：\n$handle", handle.contains("requestDisallowInterceptTouchEvent(false)"))
        assertFalse(
            "除了拖动把手，面板其它地方不许抢父级：\n$panel",
            panel.replace(handle, "").contains("requestDisallowInterceptTouchEvent")
        )
    }

    @Test
    fun `leaving notes tab collapses the fab from the single page-selection exit`() {
        val main = code("MainActivity.kt")
        assertTrue(
            "MainActivity 必须在离开笔记页时调用 onTabSwitch：\n$main",
            main.contains("notesController.onTabSwitch()")
        )
        assertTrue(
            "判断必须是 prev == 1 && tabIndex != 1：\n$main",
            main.contains("prev == 1 && tabIndex != 1")
        )
    }

    @Test
    fun `both notes screens use the shared overlay and no permanent search field`() {
        for (name in listOf("NotesController.kt", "NotesStandaloneActivity.kt")) {
            val c = code(name)
            assertTrue("$name 必须用 NotesSearchOverlay：\n$c", c.contains("NotesSearchOverlay("))
            assertFalse(
                "$name 不许再把搜索框常驻在内容区：\n$c",
                c.contains("content.addView(tagSearch.searchField)")
            )
            assertFalse(
                "$name 不许直接控制搜索框显隐：\n$c",
                c.contains("tagSearch.searchField.visibility")
            )
            assertTrue("$name 打开笔记时必须藏起 FAB 组：\n$c", c.contains("searchOverlay.hideForEditor()"))
            assertTrue(
                "$name 回列表时必须恢复搜索会话或收起 FAB：\n$c",
                c.contains("searchOverlay.restoreAfterEditor()")
            )
        }
    }

    @Test
    fun `bottom todo and calendar collapse the fab before opening`() {
        for (name in listOf("NotesController.kt", "NotesStandaloneActivity.kt")) {
            val c = code(name)
            val nav = c.substringAfter("private fun buildNavRow()")
                .substringBefore("private fun buildEditRow()")
            assertTrue(
                "$name 的待办/日历必须先把 FAB 收起：\n$nav",
                nav.contains("searchOverlay.collapseAll(animate = false)") &&
                    nav.indexOf("collapseAll") < nav.indexOf("NotesTodoActivity")
            )
        }
    }

    @Test
    fun `search panel is full width and owns the tag row`() {
        val panel = code("NotesSearchPanel.kt")
        assertTrue("面板必须左右全宽：\n$panel", panel.contains("FrameLayout.LayoutParams.MATCH_PARENT"))
        assertTrue("标签栏必须在面板内部：\n$panel", panel.contains("tagSearch.filterRow"))
        assertTrue("面板必须有拖动把手：\n$panel", panel.contains("onHandleTouch"))
    }
}
