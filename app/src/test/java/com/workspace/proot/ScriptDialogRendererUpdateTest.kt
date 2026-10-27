@file:Suppress("MagicNumber")

package com.workspace.proot

import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 浮窗原位更新：换请求必须复用同一个 CanvasDialogView，不能重建窗口/内容宿主。
 * 这条测试锁的是 5.9.79 单 Canvas 重写后丢失的更新语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScriptDialogRendererUpdateTest {

    private fun legacyRequest(title: String, default: String): ScriptDialogSpec.Request =
        ScriptDialogSpec.Request(
            id = "update",
            ui = ScriptDialogSpec.Ui(
                title = title,
                rows = listOf(
                    ScriptDialogSpec.Row.Text(ScriptDialogSpec.TextRow("line $title")),
                    ScriptDialogSpec.Row.Input(
                        ScriptDialogSpec.InputRow("name", "Name", default)
                    ),
                    ScriptDialogSpec.Row.Buttons(
                        ScriptDialogSpec.ButtonsRow(
                            listOf(ScriptDialogSpec.Button("OK", "ok", ScriptDialogSpec.BTN_PRIMARY))
                        )
                    )
                )
            )
        )

    @Test
    fun `updateCard keeps the same dialog and updates content`() {
        val ctx = RuntimeEnvironment.getApplication()
        val renderer = ScriptDialogRenderer(ctx)
        val root = renderer.buildRoot(legacyRequest("A", "Ada")) {}
        measureAndLayout(root)
        val card = root.getChildAt(0) as LinearLayout
        val title = card.getChildAt(0) as TextView
        val dialog = findDialog(root)!!
        val beforeInput = dialog.host.scene.flatten().first { it.type == CanvasSpec.TYPE_INPUT }
        dialog.formState.setInput(beforeInput, "typed-by-user")
        val dialogIdentity = System.identityHashCode(dialog)

        renderer.updateCard(card, legacyRequest("B", "Grace"), animate = false) {}
        measureAndLayout(root)

        val after = findDialog(root)!!
        assertSame("updateCard must reuse the CanvasDialogView", dialog, after)
        assertEquals(dialogIdentity, System.identityHashCode(after))
        assertTrue("title not updated: ${title.text}", title.text.contains("B"))
        assertTrue("content collapsed: ${after.measuredHeight}", after.measuredHeight > 100)
        assertTrue(
            "input value lost",
            after.collectValues()["name"] == "typed-by-user"
        )
        assertNotNull(
            "new scene missing input",
            after.host.scene.flatten().firstOrNull { it.type == CanvasSpec.TYPE_INPUT && it.key == "name" }
        )
    }

    @Test
    fun `updateCard keeps focus on the same input node`() {
        val ctx = RuntimeEnvironment.getApplication()
        val renderer = ScriptDialogRenderer(ctx)
        val root = renderer.buildRoot(legacyRequest("A", "Ada")) {}
        measureAndLayout(root)
        val card = root.getChildAt(0) as LinearLayout
        val dialog = findDialog(root)!!
        val input = dialog.host.scene.flatten().first { it.type == CanvasSpec.TYPE_INPUT }

        dialog.host.onInputFocus?.invoke(input)
        assertEquals(input.id, dialog.formState.focusedNodeId)

        renderer.updateCard(card, legacyRequest("B", "Grace"), animate = false) {}
        measureAndLayout(root)

        assertEquals(
            "focused input must survive an in-place update",
            input.id,
            dialog.formState.focusedNodeId
        )
    }

    @Test
    fun `canvas patch applies to the same dialog`() {
        val ctx = RuntimeEnvironment.getApplication()
        val scene = CanvasScene(
            width = 360f,
            height = 220f,
            nodes = listOf(
                CanvasNode(
                    id = "bar",
                    type = CanvasSpec.TYPE_PROGRESS,
                    x = 20f,
                    y = 80f,
                    w = 320f,
                    h = 24f,
                    progress = 0.1f
                )
            )
        )
        val request = ScriptDialogSpec.Request(
            id = "patch",
            ui = ScriptDialogSpec.Ui(
                title = "Patch",
                rows = listOf(
                    ScriptDialogSpec.Row.Canvas(
                        ScriptDialogSpec.CanvasRow(scene = scene, key = "main")
                    )
                )
            )
        )
        val renderer = ScriptDialogRenderer(ctx)
        val root = renderer.buildRoot(request) {}
        measureAndLayout(root)
        val dialog = findDialog(root)!!

        val patch = CanvasPatch(
            key = "main",
            node = "bar",
            props = mapOf("progress" to CanvasValue.Num(0.8f))
        )
        assertTrue(renderer.applyCanvasPatch("main", patch))
        assertEquals(0.8f, dialog.host.scene.findNode("bar")!!.progress, 0.001f)
        assertSame(dialog, findDialog(root))
        assertFalse(renderer.applyCanvasPatch("missing", patch))
    }

    @Test
    fun `animated update fades content and grows the card smoothly`() {
        val ctx = RuntimeEnvironment.getApplication()
        val renderer = ScriptDialogRenderer(ctx)
        val root = renderer.buildRoot(legacyRequest("A", "Ada")) {}
        measureAndLayout(root)
        val card = root.getChildAt(0) as LinearLayout
        val dialog = findDialog(root)!!
        val oldH = card.height
        assertTrue("baseline card not laid out: $oldH", oldH > 0)

        val taller = ScriptDialogSpec.Request(
            id = "tall",
            ui = ScriptDialogSpec.Ui(
                title = "B",
                rows = listOf(
                    ScriptDialogSpec.Row.Text(ScriptDialogSpec.TextRow("line B")),
                    ScriptDialogSpec.Row.Input(ScriptDialogSpec.InputRow("name", "Name", "Grace")),
                    ScriptDialogSpec.Row.Select(
                        ScriptDialogSpec.SelectRow("host", "Host", (1..8).map { "host $it" })
                    ),
                    ScriptDialogSpec.Row.Toggle(ScriptDialogSpec.ToggleRow("only", "Only")),
                    ScriptDialogSpec.Row.Buttons(
                        ScriptDialogSpec.ButtonsRow(
                            listOf(ScriptDialogSpec.Button("OK", "ok", ScriptDialogSpec.BTN_PRIMARY))
                        )
                    )
                )
            )
        )

        renderer.updateCard(card, taller) {}
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        measureAndLayout(root)
        val after = findDialog(root)!!
        assertSame("animated update must reuse the dialog", dialog, after)
        assertTrue("card should grow: old=$oldH new=${card.height}", card.height > oldH)
        assertEquals("new content should be fully visible", 1f, after.alpha, 0.01f)
    }

    private fun measureAndLayout(root: View) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2400)
    }

    @Suppress("ReturnCount")
    private fun findDialog(view: View): CanvasDialogView? {
        if (view is CanvasDialogView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findDialog(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
