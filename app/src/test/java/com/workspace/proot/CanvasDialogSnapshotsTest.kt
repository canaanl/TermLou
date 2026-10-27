@file:Suppress("MagicNumber")

package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasDialogSnapshotsTest {

    @Test
    fun `root canvas snapshot excludes form buttons but keeps patched canvas nodes`() {
        val source = CanvasScene(
            width = 360f,
            height = 220f,
            background = "#101010",
            nodes = listOf(CanvasNode("t", CanvasSpec.TYPE_TEXT, text = "old")),
            animations = listOf(CanvasAnimation(target = "t", property = "alpha", from = 0f, to = 1f))
        )
        val root = source.copy(
            height = 320f,
            nodes = listOf(
                CanvasNode("t", CanvasSpec.TYPE_TEXT, text = "new"),
                CanvasNode("added", CanvasSpec.TYPE_RECT),
                CanvasNode("form:button", CanvasSpec.TYPE_BUTTON, text = "OK")
            )
        )
        val block = CanvasDialogBlock(
            key = "main",
            scene = source,
            nodeIds = setOf("t"),
            groupId = null,
            root = true
        )
        val snap = CanvasDialogSnapshots.capture(root, block, setOf("form:button"))
        assertEquals(220f, snap.height, 0.001f)
        assertEquals(2, snap.nodes.size)
        assertEquals("new", snap.nodes[0].text)
        assertTrue(snap.nodes.none { it.id == "form:button" })
        assertEquals(1, snap.animations.size)
    }

    @Test
    fun `mixed canvas snapshot returns group children only`() {
        val source = CanvasScene(
            width = 300f,
            height = 150f,
            background = "#222222",
            nodes = listOf(CanvasNode("bar", CanvasSpec.TYPE_PROGRESS))
        )
        val group = CanvasNode(
            id = "g",
            type = CanvasSpec.TYPE_GROUP,
            children = listOf(CanvasNode("bar", CanvasSpec.TYPE_PROGRESS, progress = 0.7f))
        )
        val root = CanvasScene(
            nodes = listOf(
                CanvasNode("form:text", CanvasSpec.TYPE_TEXT),
                group,
                CanvasNode("form:input", CanvasSpec.TYPE_INPUT)
            )
        )
        val block = CanvasDialogBlock(
            key = "chart",
            scene = source,
            nodeIds = setOf("bar"),
            groupId = "g",
            root = false
        )
        val snap = CanvasDialogSnapshots.capture(root, block, setOf("form:text", "form:input"))
        assertEquals(1, snap.nodes.size)
        assertEquals(0.7f, snap.nodes[0].progress, 0.001f)
        assertEquals(300f, snap.width, 0.001f)
        assertFalse(snap.nodes.any { it.id == "form:input" })
    }
}
