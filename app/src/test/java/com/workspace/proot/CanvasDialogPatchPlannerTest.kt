@file:Suppress("MagicNumber")

package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasDialogPatchPlannerTest {

    private fun block(key: String, ids: Set<String>, group: String? = null): CanvasDialogBlock =
        CanvasDialogBlock(
            key = key,
            scene = CanvasScene(),
            nodeIds = ids,
            groupId = group,
            root = group == null
        )

    @Test
    fun `patch key scopes to matching canvas block and node`() {
        val blocks = listOf(block("a", setOf("n1"), "g1"), block("b", setOf("n2"), "g2"))
        assertFalse(CanvasDialogPatchPlanner.accepts(emptyList(), null, null))
        assertTrue(CanvasDialogPatchPlanner.accepts(blocks, null, "n2"))
        assertTrue(CanvasDialogPatchPlanner.accepts(blocks, "a", "n1"))
        assertTrue(CanvasDialogPatchPlanner.accepts(blocks, "a", "g1"))
        assertTrue(CanvasDialogPatchPlanner.accepts(blocks, "a", null))
        assertFalse(CanvasDialogPatchPlanner.accepts(blocks, "a", "n2"))
        assertFalse(CanvasDialogPatchPlanner.accepts(blocks, "missing", null))
    }

    @Test
    fun `scene replacement keeps form rows and replaces only matching canvas`() {
        val a = CanvasDialogBuildRequestFixtures.request(
            listOf(
                ScriptDialogSpec.Row.Text(ScriptDialogSpec.TextRow("before")),
                ScriptDialogSpec.Row.Canvas(ScriptDialogSpec.CanvasRow(CanvasScene(width = 100f), key = "a")),
                ScriptDialogSpec.Row.Canvas(ScriptDialogSpec.CanvasRow(CanvasScene(width = 200f), key = "b")),
                ScriptDialogSpec.Row.Buttons(ScriptDialogSpec.ButtonsRow(listOf(ScriptDialogSpec.Button("OK", "ok"))))
            )
        )
        val newScene = CanvasScene(width = 300f, height = 500f)
        val replaced = CanvasDialogPatchPlanner.replaceScenes(a, "a", newScene)
        assertNotNull(replaced)
        val rows = replaced!!.ui.rows
        assertEquals(4, rows.size)
        assertEquals(300f, (rows[1] as ScriptDialogSpec.Row.Canvas).row.scene.width, 0.001f)
        assertEquals(200f, (rows[2] as ScriptDialogSpec.Row.Canvas).row.scene.width, 0.001f)
        assertTrue(rows[0] is ScriptDialogSpec.Row.Text)
        assertTrue(rows[3] is ScriptDialogSpec.Row.Buttons)
    }

    @Test
    fun `scene replacement with null key replaces every canvas and missing key returns null`() {
        val a = CanvasDialogBuildRequestFixtures.request(
            listOf(
                ScriptDialogSpec.Row.Canvas(ScriptDialogSpec.CanvasRow(CanvasScene(width = 100f), key = "a")),
                ScriptDialogSpec.Row.Canvas(ScriptDialogSpec.CanvasRow(CanvasScene(width = 200f), key = "b"))
            )
        )
        val newScene = CanvasScene(width = 300f)
        val all = CanvasDialogPatchPlanner.replaceScenes(a, null, newScene)!!
        assertEquals(300f, (all.ui.rows[0] as ScriptDialogSpec.Row.Canvas).row.scene.width, 0.001f)
        assertEquals(300f, (all.ui.rows[1] as ScriptDialogSpec.Row.Canvas).row.scene.width, 0.001f)
        assertNull(CanvasDialogPatchPlanner.replaceScenes(a, "missing", newScene))
    }
}

private object CanvasDialogBuildRequestFixtures {
    fun request(rows: List<ScriptDialogSpec.Row>): ScriptDialogSpec.Request =
        ScriptDialogSpec.Request(id = "req", ui = ScriptDialogSpec.Ui(rows = rows))
}
