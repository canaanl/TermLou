@file:Suppress("MagicNumber")

package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住 termlou-ui 旧参数的请求/结果契约。渲染换成 Canvas 后，这里必须仍然通过。
 */
class CanvasDialogApiCompatTest {

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

    @Test
    fun `legacy rows still produce the same values map`() {
        val json = """
            {"id":"req","ui":{"title":"T","message":"hello","rows":[
              {"kind":"text","text":"out"},
              {"kind":"input","key":"name","label":"Name","default":"Ada"},
              {"kind":"select","key":"host","label":"Host","multi":false,"options":["a#up","b#down"]},
              {"kind":"select","key":"tags","label":"Tags","multi":true,"options":["x","y","z"]},
              {"kind":"toggle","key":"only","label":"Only up","default":false,"filter":"up"},
              {"kind":"buttons","buttons":[
                {"text":"OK","id":"ok","kind":"primary","close":true},
                {"text":"Next","id":"next","kind":"normal","close":false}
              ]}
            ]},"style":{"theme":"dark"}}
        """.trimIndent()
        val request = ScriptDialogSpec.parseRequest(json)
        val built = CanvasDialogBuilder.build(
            request,
            palette,
            metrics,
            contentWidth = 328f,
            closeText = "Close"
        )
        val state = CanvasFormState()
        state.reset(built.scene, preserveValues = false, resetFilters = true)

        val input = built.scene.nodes.first { it.type == CanvasSpec.TYPE_INPUT }
        state.setInput(input, "Grace")
        val selects = built.scene.nodes.filter { it.type == CanvasSpec.TYPE_SELECT }
        val host = selects.first { it.key == "host" }
        val tags = selects.first { it.key == "tags" }
        state.tapOption(host, CanvasWidgetLayout.selectBodyTop(host) + CanvasWidgetLayout.OPTION_H + 1f)
        state.tapOption(tags, CanvasWidgetLayout.selectBodyTop(tags) + 1f)
        state.tapOption(tags, CanvasWidgetLayout.selectBodyTop(tags) + CanvasWidgetLayout.OPTION_H + 1f)
        val toggle = built.scene.nodes.first { it.type == CanvasSpec.TYPE_TOGGLE }
        state.toggle(toggle)

        val values = state.collect(built.scene)
        assertEquals("Grace", values["name"])
        assertEquals("b", values["host"])
        assertEquals("""["x","y"]""", values["tags"])
        assertEquals("1", values["only"])

        val buttons = built.scene.nodes.filter { it.type == CanvasSpec.TYPE_BUTTON }
        assertEquals("submit:ok", buttons[0].action)
        assertEquals("submit:next:keep", buttons[1].action)
    }

    @Test
    fun `legacy filter defaults and empty select keep old shape`() {
        val json = """
            {"id":"req","ui":{"rows":[
              {"kind":"select","key":"empty","label":"Empty","multi":false,"options":["a","b"]},
              {"kind":"toggle","key":"filter","label":"Only up","default":true,"filter":"up"},
              {"kind":"select","key":"filtered","label":"Filtered","multi":false,"options":["a#up","b#down","c"]}
            ]}}
        """.trimIndent()
        val request = ScriptDialogSpec.parseRequest(json)
        val built = CanvasDialogBuilder.build(request, palette, metrics, 320f, "Close")
        val state = CanvasFormState()
        state.reset(built.scene, preserveValues = false, resetFilters = true)
        assertEquals(setOf("up"), state.activeFilters)
        val values = state.collect(built.scene)
        assertEquals("[]", values["empty"])
        assertEquals("1", values["filter"])

        val filtered = built.scene.nodes.first { it.key == "filtered" }
        val visible = CanvasWidgetLayout.visibleOptions(filtered, state.activeFilters)
        assertEquals(listOf("a", "c"), visible.map { it.label })
        assertFalse(visible.any { it.label == "b" })
    }

    @Test
    fun `old canvas snapshot excludes appended form controls`() {
        val sourceJson = """
            {"width":360,"height":220,"background":"#101010","nodes":[
              {"id":"t","type":"text","x":10,"y":10,"w":100,"h":20,"text":"hi"}
            ],"animations":[{"target":"t","property":"alpha","from":0,"to":1}]}
        """.trimIndent()
        val source = CanvasSceneCodec.parseScene(sourceJson)
        val request = ScriptDialogSpec.Request(
            id = "req",
            ui = ScriptDialogSpec.Ui(
                rows = listOf(
                    ScriptDialogSpec.Row.Canvas(ScriptDialogSpec.CanvasRow(source, heightDp = 220, key = "main")),
                    ScriptDialogSpec.Row.Buttons(
                        ScriptDialogSpec.ButtonsRow(listOf(ScriptDialogSpec.Button("OK", "ok", close = false)))
                    )
                )
            )
        )
        val built = CanvasDialogBuilder.build(request, palette, metrics, 320f, "Close")
        val root = built.scene.copy(nodes = built.scene.nodes + CanvasNode("patched", CanvasSpec.TYPE_RECT))
        val block = built.canvasBlocks.single()
        val snap = CanvasDialogSnapshots.capture(root, block, built.formNodeIds)
        assertEquals(2, snap.nodes.size)
        assertTrue(snap.nodes.any { it.id == "patched" })
        assertTrue(snap.nodes.none { it.type == CanvasSpec.TYPE_BUTTON })
        assertEquals(220f, snap.height, 0.001f)
        assertEquals(1, snap.animations.size)
    }
}
