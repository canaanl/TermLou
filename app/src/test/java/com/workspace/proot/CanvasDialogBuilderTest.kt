@file:Suppress("MagicNumber")

package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasDialogBuilderTest {

    private val palette = CanvasDialogPalette(
        onSurface = 0xFFE6E6E6.toInt(),
        onSurfaceVariant = 0xFF9A9A9A.toInt(),
        accent = 0xFF2D7D46.toInt(),
        danger = 0xFFC0392B.toInt(),
        buttonNormal = 0xFF3A3A3A.toInt(),
        onButton = 0xFFFFFFFF.toInt(),
        inputBg = 0xFF2A2A2A.toInt()
    )

    private val metrics = object : CanvasDialogMetrics {
        override fun measureText(text: String, size: Float, bold: Boolean): Float =
            text.length * size * 0.6f

        override fun lineHeight(size: Float): Float = size * 1.35f
    }

    private fun request(rows: List<ScriptDialogSpec.Row>, message: String = ""): ScriptDialogSpec.Request =
        ScriptDialogSpec.Request(
            id = "req",
            ui = ScriptDialogSpec.Ui(title = "T", message = message, rows = rows),
            style = ScriptDialogSpec.Style()
        )

    @Test
    fun `legacy form rows become standard canvas widgets`() {
        val req = request(
            listOf(
                ScriptDialogSpec.Row.Text(ScriptDialogSpec.TextRow("hello")),
                ScriptDialogSpec.Row.Input(ScriptDialogSpec.InputRow("name", "Name", "Ada")),
                ScriptDialogSpec.Row.Select(
                    ScriptDialogSpec.SelectRow("host", "Host", listOf("a#up", "b#down"), multi = false)
                ),
                ScriptDialogSpec.Row.Select(
                    ScriptDialogSpec.SelectRow("tags", "Tags", listOf("x", "y"), multi = true)
                ),
                ScriptDialogSpec.Row.Toggle(ScriptDialogSpec.ToggleRow("only", "Only up", def = true, filter = "up")),
                ScriptDialogSpec.Row.Buttons(
                    ScriptDialogSpec.ButtonsRow(
                        listOf(
                            ScriptDialogSpec.Button("OK", "ok", ScriptDialogSpec.BTN_PRIMARY),
                            ScriptDialogSpec.Button("Cancel", "cancel", ScriptDialogSpec.BTN_NORMAL)
                        )
                    )
                )
            )
        )
        val out = CanvasDialogBuilder.build(req, palette, metrics, 328f, "Close")
        assertEquals(328f, out.scene.width, 0.001f)
        val nodes = out.scene.nodes
        assertEquals(CanvasSpec.TYPE_TEXT, nodes[0].type)
        assertEquals(CanvasSpec.TYPE_INPUT, nodes[1].type)
        assertEquals("name", nodes[1].key)
        assertEquals("Ada", nodes[1].text)
        assertEquals(CanvasSpec.TYPE_SELECT, nodes[2].type)
        assertFalse(nodes[2].multi)
        assertEquals(CanvasSpec.TYPE_SELECT, nodes[3].type)
        assertTrue(nodes[3].multi)
        assertEquals(CanvasSpec.TYPE_TOGGLE, nodes[4].type)
        assertEquals("up", nodes[4].filter)
        assertEquals(CanvasSpec.TYPE_BUTTON, nodes[5].type)
        assertEquals("submit:ok", nodes[5].action)
        assertEquals(CanvasSpec.TYPE_BUTTON, nodes[6].type)
        assertEquals("submit:cancel", nodes[6].action)
        assertTrue(out.formNodeIds.contains(nodes[5].id))
        assertTrue(out.scene.height > nodes[6].y)
    }

    @Test
    fun `default close button keeps old dismiss id`() {
        val out = CanvasDialogBuilder.build(
            request(listOf(ScriptDialogSpec.Row.Text(ScriptDialogSpec.TextRow("x")))),
            palette,
            metrics,
            320f,
            "鍏抽棴"
        )
        val button = out.scene.nodes.last()
        assertEquals(CanvasSpec.TYPE_BUTTON, button.type)
        assertEquals("鍏抽棴", button.text)
        assertEquals("submit:dismiss", button.action)
    }

    @Test
    fun `select box caps visible rows and keeps full options`() {
        val options = (1..20).map { "option $it" }
        val out = CanvasDialogBuilder.build(
            request(
                listOf(
                    ScriptDialogSpec.Row.Select(
                        ScriptDialogSpec.SelectRow("k", "Label", options, multi = true)
                    )
                )
            ),
            palette,
            metrics,
            320f,
            "Close"
        )
        val select = out.scene.nodes.first { it.type == CanvasSpec.TYPE_SELECT }
        assertEquals(options, select.options)
        assertTrue(select.h <= CanvasWidgetLayout.SELECT_MAX_H + CanvasWidgetLayout.LABEL_H + CanvasWidgetLayout.SELECT_PAD_TOP)
        assertTrue(select.h < options.size * CanvasWidgetLayout.OPTION_H)
    }

    @Test
    fun `canvas only content is root scene and appends button nodes`() {
        val source = CanvasScene(
            width = 360f,
            height = 200f,
            background = "#101010",
            nodes = listOf(CanvasNode("title", CanvasSpec.TYPE_TEXT, x = 20f, y = 20f, w = 200f, h = 30f, text = "Hi")),
            animations = listOf(CanvasAnimation(target = "title", property = "alpha", from = 0f, to = 1f))
        )
        val out = CanvasDialogBuilder.build(
            request(
                listOf(
                    ScriptDialogSpec.Row.Canvas(ScriptDialogSpec.CanvasRow(source, heightDp = 220, key = "main")),
                    ScriptDialogSpec.Row.Buttons(
                        ScriptDialogSpec.ButtonsRow(listOf(ScriptDialogSpec.Button("OK", "ok", close = false)))
                    )
                )
            ),
            palette,
            metrics,
            320f,
            "Close"
        )
        assertEquals(1, out.canvasBlocks.size)
        val block = out.canvasBlocks[0]
        assertTrue(block.root)
        assertEquals("main", block.key)
        assertEquals(360f, out.scene.width, 0.001f)
        assertTrue(out.scene.nodes.size >= 2)
        val button = out.scene.nodes.last()
        assertEquals(CanvasSpec.TYPE_BUTTON, button.type)
        assertEquals("submit:ok:keep", button.action)
        assertTrue(button.y >= 220f)
        assertTrue(out.formNodeIds.contains(button.id))
        assertEquals(1, out.scene.animations.size)
    }

    @Test
    fun `canvas button suppresses default close`() {
        val source = CanvasScene(
            width = 360f,
            height = 220f,
            nodes = listOf(
                CanvasNode("ok", CanvasSpec.TYPE_BUTTON, text = "OK", action = "submit:ok", w = 100f, h = 38f)
            )
        )
        val out = CanvasDialogBuilder.build(
            request(listOf(ScriptDialogSpec.Row.Canvas(ScriptDialogSpec.CanvasRow(source)))),
            palette,
            metrics,
            320f,
            "Close"
        )
        assertEquals(1, out.scene.nodes.size)
        assertTrue(out.formNodeIds.isEmpty())
    }

    @Test
    fun `mixed canvas becomes clipped group in row order`() {
        val source = CanvasScene(
            width = 300f,
            height = 150f,
            nodes = listOf(
                CanvasNode("bar", CanvasSpec.TYPE_PROGRESS, x = 0f, y = 0f, w = 100f, h = 10f, progress = 0.5f)
            ),
            animations = listOf(CanvasAnimation(target = "bar", property = "progress", from = 0f, to = 1f))
        )
        val req = request(
            listOf(
                ScriptDialogSpec.Row.Text(ScriptDialogSpec.TextRow("before")),
                ScriptDialogSpec.Row.Canvas(ScriptDialogSpec.CanvasRow(source, heightDp = 180, key = "chart")),
                ScriptDialogSpec.Row.Input(ScriptDialogSpec.InputRow("name", "Name")),
                ScriptDialogSpec.Row.Buttons(
                    ScriptDialogSpec.ButtonsRow(listOf(ScriptDialogSpec.Button("OK", "ok")))
                )
            )
        )
        val out = CanvasDialogBuilder.build(req, palette, metrics, 320f, "Close")
        assertEquals(1, out.canvasBlocks.size)
        val block = out.canvasBlocks[0]
        assertFalse(block.root)
        assertEquals("chart", block.key)
        assertNotNull(block.groupId)
        val group = out.scene.findNode(block.groupId!!)
        assertNotNull(group)
        assertEquals(CanvasSpec.TYPE_GROUP, group!!.type)
        assertTrue(group.clip)
        assertEquals(1, group.children.size)
        assertTrue(out.scene.animations.any { it.target == "bar" })
        val groupIndex = out.scene.nodes.indexOfFirst { it.id == group.id }
        val inputIndex = out.scene.nodes.indexOfFirst { it.type == CanvasSpec.TYPE_INPUT }
        assertTrue(groupIndex < inputIndex)
    }

    @Test
    fun `text height uses measured lines and wrap flag`() {
        val long = "x".repeat(120)
        val out = CanvasDialogBuilder.build(
            request(listOf(ScriptDialogSpec.Row.Text(ScriptDialogSpec.TextRow(long)))),
            palette,
            metrics,
            200f,
            "Close"
        )
        val text = out.scene.nodes.first { it.type == CanvasSpec.TYPE_TEXT }
        assertTrue(text.wrap)
        assertTrue(text.h > metrics.lineHeight(CanvasWidgetLayout.LABEL_SIZE))
    }

    @Test
    fun `old canvas scene has no new default fields in snapshot`() {
        val old = CanvasScene(
            width = 360f,
            height = 220f,
            nodes = listOf(CanvasNode("t", CanvasSpec.TYPE_TEXT, text = "x"))
        )
        val json = CanvasSceneCodec.sceneToJsonString(old)
        assertFalse(json.contains("\"wrap\""))
        assertFalse(json.contains("\"key\""))
        assertFalse(json.contains("\"clip\""))
    }
}
