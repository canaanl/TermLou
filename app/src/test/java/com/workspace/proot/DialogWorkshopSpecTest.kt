@file:Suppress("MagicNumber")

package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogWorkshopSpecTest {

    @Test
    fun `workshop request has exactly one content canvas and no linear form rows`() {
        val scene = CanvasScene(width = 320f, height = 480f)
        val request = DialogWorkshopSpec.buildRequest(
            id = "preview",
            title = "Hello",
            scene = scene,
            style = ScriptDialogSpec.Style(theme = "light", radiusDp = 10)
        )
        assertEquals(1, request.ui.rows.size)
        val canvas = request.ui.rows.single() as ScriptDialogSpec.Row.Canvas
        assertEquals(DialogWorkshopSpec.CANVAS_KEY, canvas.row.key)
        assertEquals(320f, canvas.row.scene.width, 0.001f)
        assertEquals(480, canvas.row.heightDp)
        assertEquals("Hello", request.ui.title)
        assertEquals("light", request.style.theme)
        assertTrue(request.ui.message.isEmpty())
    }

    @Test
    fun `workshop canvas is the only source of form widgets`() {
        val scene = CanvasScene(
            width = 320f,
            height = 400f,
            nodes = listOf(
                CanvasNode(
                    id = "in",
                    type = CanvasSpec.TYPE_INPUT,
                    x = 16f,
                    y = 8f,
                    w = 288f,
                    h = CanvasWidgetLayout.inputHeight(),
                    key = "name",
                    label = "Name"
                ),
                CanvasNode(
                    id = "ok",
                    type = CanvasSpec.TYPE_BUTTON,
                    x = 18f,
                    y = 300f,
                    w = 284f,
                    h = CanvasWidgetLayout.BUTTON_H,
                    text = "OK",
                    action = "submit:ok"
                )
            )
        )
        val request = DialogWorkshopSpec.buildRequest(
            "test",
            "T",
            scene,
            ScriptDialogSpec.Style()
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
            contentWidth = 320f,
            closeText = "Close"
        )
        assertTrue(built.scene.flatten().any { it.type == CanvasSpec.TYPE_INPUT && it.key == "name" })
        assertTrue(built.scene.flatten().any { it.type == CanvasSpec.TYPE_BUTTON && it.action == "submit:ok" })
        assertTrue(built.formNodeIds.isEmpty())
    }
}
