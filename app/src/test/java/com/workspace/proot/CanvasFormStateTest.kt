@file:Suppress("MagicNumber")

package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasFormStateTest {

    private fun scene(): CanvasScene = CanvasScene(
        width = 360f,
        height = 500f,
        nodes = listOf(
            CanvasNode(
                id = "in",
                type = CanvasSpec.TYPE_INPUT,
                key = "name",
                label = "Name",
                text = "Ada"
            ),
            CanvasNode(
                id = "single",
                type = CanvasSpec.TYPE_SELECT,
                key = "host",
                label = "Host",
                options = listOf("a", "b", "c"),
                y = 50f,
                h = 220f
            ),
            CanvasNode(
                id = "multi",
                type = CanvasSpec.TYPE_SELECT,
                key = "tags",
                label = "Tags",
                options = listOf("x", "y", "z"),
                multi = true,
                y = 280f,
                h = 160f
            ),
            CanvasNode(
                id = "filter",
                type = CanvasSpec.TYPE_TOGGLE,
                key = "onlyA",
                label = "Only A",
                filter = "up",
                checked = true,
                y = 450f
            ),
            CanvasNode(
                id = "hidden",
                type = CanvasSpec.TYPE_SELECT,
                key = "filtered",
                label = "Filtered",
                options = listOf("a#up", "b#down", "c"),
                y = 480f,
                h = 100f
            )
        )
    )

    @Test
    fun `initial values defaults filters and result shape`() {
        val state = CanvasFormState()
        state.reset(scene(), preserveValues = false, resetFilters = true)
        assertEquals("Ada", state.inputValue(scene().findNode("in")!!))
        assertTrue(state.toggleValue(scene().findNode("filter")!!))
        assertEquals(setOf("up"), state.activeFilters)

        val node = scene().findNode("single")!!
        assertTrue(state.tapOption(node, CanvasWidgetLayout.selectBodyTop(node) + 1f))
        val values = state.collect(scene())
        assertEquals("Ada", values["name"])
        assertEquals("a", values["host"])
        assertEquals("[]", values["tags"])
        assertEquals("1", values["onlyA"])
    }

    @Test
    fun `multi select keeps order and serializes json array`() {
        val state = CanvasFormState()
        val s = scene()
        state.reset(s, preserveValues = false, resetFilters = false)
        val node = s.findNode("multi")!!
        val top = CanvasWidgetLayout.selectBodyTop(node)
        state.tapOption(node, top + 1f)
        state.tapOption(node, top + CanvasWidgetLayout.OPTION_H + 1f)
        assertEquals(listOf("x", "y"), state.selected(node))
        assertEquals("""["x","y"]""", state.collect(s)["tags"])
        state.tapOption(node, top + 1f)
        assertEquals(listOf("y"), state.selected(node))
        assertEquals("y", state.collect(s)["tags"])
    }

    @Test
    fun `filter hides options but keeps node geometry and selection`() {
        val state = CanvasFormState()
        val s = scene()
        state.reset(s, preserveValues = false, resetFilters = true)
        val filtered = s.findNode("hidden")!!
        val visible = CanvasWidgetLayout.visibleOptions(filtered, state.activeFilters)
        assertEquals(listOf("a", "c"), visible.map { it.label })

        val top = CanvasWidgetLayout.selectBodyTop(filtered)
        state.tapOption(filtered, top + 1f)
        assertEquals(listOf("a"), state.selected(filtered))
        state.toggle(s.findNode("filter")!!)
        assertFalse(state.toggleValue(s.findNode("filter")!!))
        assertEquals(emptySet<String>(), state.activeFilters)
        assertEquals(listOf("a", "b", "c"), CanvasWidgetLayout.visibleOptions(filtered, state.activeFilters).map { it.label })
        assertEquals(listOf("a"), state.selected(filtered))
    }

    @Test
    fun `scroll clamps and tap uses scrolled offset`() {
        val state = CanvasFormState()
        val s = scene()
        state.reset(s, preserveValues = false, resetFilters = false)
        val node = s.findNode("single")!!
        assertEquals(0f, state.scrollOffset(node), 0.001f)
        assertFalse(state.scrollBy(node, 10f))
        val tall = node.copy(h = 80f, options = (0 until 8).map { "o$it" })
        val max = CanvasWidgetLayout.maxScroll(tall, 8)
        assertTrue(max > 0f)
        assertTrue(state.scrollBy(tall, 20f))
        assertEquals(20f, state.scrollOffset(tall), 0.001f)
        assertTrue(state.scrollBy(tall, 999f))
        assertEquals(max, state.scrollOffset(tall), 0.001f)
        val top = CanvasWidgetLayout.selectBodyTop(tall)
        assertEquals(5, CanvasWidgetLayout.optionAt(tall, top + 1f, 8, state.scrollOffset(tall)))
    }

    @Test
    fun `reset preserves values for same keys and drops removed keys`() {
        val state = CanvasFormState()
        val s = scene()
        state.reset(s, preserveValues = false, resetFilters = true)
        state.setInput(s.findNode("in")!!, "Grace")
        state.tapOption(s.findNode("single")!!, CanvasWidgetLayout.selectBodyTop(s.findNode("single")!!) + 1f)
        state.toggle(s.findNode("filter")!!)

        val next = s.copy(nodes = s.nodes.filterNot { it.id == "multi" })
        state.reset(next, preserveValues = true, resetFilters = true)
        assertEquals("Grace", state.collect(next)["name"])
        assertEquals("a", state.collect(next)["host"])
        // 鍚?key 鐨勫紑鍏冲€间繚鐣欙紝杩囨护鐘舵€佸繀椤讳笌寮€鍏充竴鑷?        assertFalse(state.toggleValue(next.findNode("filter")!!))
        assertEquals(emptySet<String>(), state.activeFilters)
        assertFalse(state.collect(next).containsKey("tags"))
    }

    @Test
    fun `duplicate select keys share selection like old renderer`() {
        val state = CanvasFormState()
        val s = CanvasScene(
            nodes = listOf(
                CanvasNode(id = "a", type = CanvasSpec.TYPE_SELECT, key = "k", options = listOf("x", "y"), multi = true, y = 0f, h = 100f),
                CanvasNode(id = "b", type = CanvasSpec.TYPE_SELECT, key = "k", options = listOf("z"), multi = true, y = 110f, h = 60f)
            )
        )
        state.reset(s, preserveValues = false, resetFilters = false)
        state.tapOption(s.findNode("a")!!, CanvasWidgetLayout.selectBodyTop(s.findNode("a")!!) + 1f)
        state.tapOption(s.findNode("b")!!, CanvasWidgetLayout.selectBodyTop(s.findNode("b")!!) + 1f)
        assertEquals(listOf("x", "z"), state.selected(s.findNode("a")!!))
        assertEquals("""["x","z"]""", state.collect(s)["k"])
    }

    @Test
    fun `empty key widgets do not leak result fields`() {
        val state = CanvasFormState()
        val s = CanvasScene(
            nodes = listOf(
                CanvasNode(id = "in", type = CanvasSpec.TYPE_INPUT, key = "", text = "x"),
                CanvasNode(id = "tg", type = CanvasSpec.TYPE_TOGGLE, key = "", checked = true)
            )
        )
        state.reset(s, preserveValues = false, resetFilters = false)
        assertTrue(state.collect(s).isEmpty())
    }
}
