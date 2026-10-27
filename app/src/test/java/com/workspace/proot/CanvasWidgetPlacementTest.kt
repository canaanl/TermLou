@file:Suppress("MagicNumber")

package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasWidgetPlacementTest {

    @Test
    fun `empty scene inserts standard full width widgets`() {
        val scene = CanvasScene(width = 360f, height = 320f)
        val input = CanvasWidgetPlacement.input(scene)
        assertEquals(16f, input.x, 0.001f)
        assertEquals(8f, input.y, 0.001f)
        assertEquals(328f, input.w, 0.001f)
        assertEquals(CanvasWidgetLayout.inputHeight(), input.h, 0.001f)

        val select = CanvasWidgetPlacement.select(scene, "Label", 3)
        assertEquals(16f, select.x, 0.001f)
        assertEquals(328f, select.w, 0.001f)

        val toggle = CanvasWidgetPlacement.toggle(scene)
        assertEquals(16f, toggle.x, 0.001f)
        assertEquals(20f, toggle.h, 0.001f)

        val text = CanvasWidgetPlacement.text(scene)
        assertEquals(20f, text.x, 0.001f)
        assertEquals(320f, text.w, 0.001f)
    }

    @Test
    fun `button keeps old bottom bar geometry`() {
        val scene = CanvasScene(width = 360f, height = 320f)
        val button = CanvasWidgetPlacement.button(scene)
        assertEquals(18f, button.x, 0.001f)
        assertEquals(26f, button.y, 0.001f)
        assertEquals(324f, button.w, 0.001f)
        assertEquals(CanvasWidgetLayout.BUTTON_H, button.h, 0.001f)
    }

    @Test
    fun `two buttons share standard bar with gap`() {
        val scene = CanvasScene(width = 360f, height = 320f)
        val bar = CanvasWidgetPlacement.buttonBar(scene, 2)
        assertEquals(2, bar.size)
        assertEquals(18f, bar[0].x, 0.001f)
        assertEquals(18f + bar[0].w + 12f, bar[1].x, 0.001f)
        assertEquals(bar[0].w, bar[1].w, 0.001f)
        assertTrue(bar[1].x + bar[1].w <= scene.width - 18f + 0.001f)
    }

    @Test
    fun `placement matches legacy builder geometry`() {
        val request = ScriptDialogSpec.Request(
            id = "req",
            ui = ScriptDialogSpec.Ui(
                rows = listOf(
                    ScriptDialogSpec.Row.Input(ScriptDialogSpec.InputRow("name", "Name", "Ada")),
                    ScriptDialogSpec.Row.Select(
                        ScriptDialogSpec.SelectRow("host", "Label", listOf("a", "b", "c"))
                    ),
                    ScriptDialogSpec.Row.Toggle(ScriptDialogSpec.ToggleRow("only", "Only")),
                    ScriptDialogSpec.Row.Buttons(
                        ScriptDialogSpec.ButtonsRow(listOf(ScriptDialogSpec.Button("OK", "ok")))
                    )
                )
            )
        )
        val built = CanvasDialogBuilder.build(
            request,
            CanvasDialogPalette(
                onSurface = 0xFFE6E6E6.toInt(),
                onSurfaceVariant = 0xFF9A9A9A.toInt(),
                accent = 0xFF2D7D46.toInt(),
                danger = 0xFFC0392B.toInt(),
                buttonNormal = 0xFF3A3A3A.toInt(),
                onButton = 0xFFFFFFFF.toInt(),
                inputBg = 0xFF2A2A2A.toInt()
            ),
            object : CanvasDialogMetrics {
                override fun measureText(text: String, size: Float, bold: Boolean): Float =
                    text.length * size * 0.6f

                override fun lineHeight(size: Float): Float = size * 1.3f
            },
            contentWidth = 328f,
            closeText = "Close"
        )
        val scene = CanvasScene(width = 328f, height = 500f)
        val input = built.scene.nodes.first { it.type == CanvasSpec.TYPE_INPUT }
        val select = built.scene.nodes.first { it.type == CanvasSpec.TYPE_SELECT }
        val toggle = built.scene.nodes.first { it.type == CanvasSpec.TYPE_TOGGLE }
        val button = built.scene.nodes.first { it.type == CanvasSpec.TYPE_BUTTON }
        val inputP = CanvasWidgetPlacement.input(scene)
        val selectP = CanvasWidgetPlacement.select(scene, "Label", 3)
        val toggleP = CanvasWidgetPlacement.toggle(scene)
        val buttonP = CanvasWidgetPlacement.button(scene)
        assertEquals(inputP.x, input.x, 0.001f)
        assertEquals(inputP.w, input.w, 0.001f)
        assertEquals(inputP.h, input.h, 0.001f)
        assertEquals(selectP.x, select.x, 0.001f)
        assertEquals(selectP.w, select.w, 0.001f)
        assertEquals(selectP.h, select.h, 0.001f)
        assertEquals(toggleP.x, toggle.x, 0.001f)
        assertEquals(toggleP.w, toggle.w, 0.001f)
        assertEquals(toggleP.h, toggle.h, 0.001f)
        assertEquals(buttonP.x, button.x, 0.001f)
        assertEquals(buttonP.w, button.w, 0.001f)
        assertEquals(buttonP.h, button.h, 0.001f)
    }

    @Test
    fun `text rows keep old tight spacing`() {
        val scene = CanvasScene(
            width = 360f,
            height = 320f,
            nodes = listOf(
                CanvasNode(
                    id = "t",
                    type = CanvasSpec.TYPE_TEXT,
                    x = 20f,
                    y = 8f,
                    w = 320f,
                    h = 20f,
                    text = "hello"
                )
            )
        )
        assertEquals(28f, CanvasWidgetPlacement.nextY(scene), 0.001f)
        assertEquals(46f, CanvasWidgetPlacement.button(scene).y, 0.001f)
    }

    @Test
    fun `new rows stack below existing content`() {
        val scene = CanvasScene(
            width = 360f,
            height = 500f,
            nodes = listOf(
                CanvasNode(
                    id = "in",
                    type = CanvasSpec.TYPE_INPUT,
                    x = 16f,
                    y = 8f,
                    w = 328f,
                    h = CanvasWidgetLayout.inputHeight()
                )
            )
        )
        val inputBottom = 8f + CanvasWidgetLayout.inputHeight()
        val toggle = CanvasWidgetPlacement.toggle(scene)
        assertEquals(inputBottom + CanvasWidgetLayout.ROW_GAP, toggle.y, 0.001f)
        val button = CanvasWidgetPlacement.button(scene)
        assertEquals(inputBottom + 26f, button.y, 0.001f)
    }
}
