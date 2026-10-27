@file:Suppress("MagicNumber")

package com.workspace.proot

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ScrollView 会用 UNSPECIFIED 高度测量内容子 View；这个测试在 JVM 上锁住
 * “CanvasDialogView 必须报告 intrinsic height” 的回归，防止再次塌成标题条。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CanvasDialogLayoutTest {

    private val palette = CanvasDialogPalette(
        onSurface = 0xFFE6E6E6.toInt(),
        onSurfaceVariant = 0xFF9A9A9A.toInt(),
        accent = 0xFF2D7D46.toInt(),
        danger = 0xFFC0392B.toInt(),
        buttonNormal = 0xFF3A3A3A.toInt(),
        onButton = 0xFFFFFFFF.toInt(),
        inputBg = 0xFF2A2A2A.toInt()
    )

    @Test
    fun `content view has intrinsic height under unspecified measure`() {
        val ctx = RuntimeEnvironment.getApplication()
        val scene = CanvasScene(
            width = 360f,
            height = 220f,
            nodes = listOf(
                CanvasNode(
                    id = "btn",
                    type = CanvasSpec.TYPE_BUTTON,
                    x = 18f,
                    y = 160f,
                    w = 324f,
                    h = CanvasWidgetLayout.BUTTON_H,
                    text = "OK",
                    action = "submit:ok"
                )
            )
        )
        val view = CanvasDialogView(ctx, palette)
        view.setScene(scene, resetClock = true, preserveValues = false, resetFilters = true)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(950, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val expected = CanvasDialogSizing.contentHeightPx(scene, 950)
        assertTrue("height=${view.measuredHeight}", view.measuredHeight > 100)
        assertEquals(expected.toFloat(), view.measuredHeight.toFloat(), 2f)
    }

    @Test
    fun `real renderer places non zero content inside scroll view`() {
        val ctx = RuntimeEnvironment.getApplication()
        val request = ScriptDialogSpec.Request(
            id = "render",
            ui = ScriptDialogSpec.Ui(
                title = "T",
                rows = listOf(
                    ScriptDialogSpec.Row.Input(ScriptDialogSpec.InputRow("name", "Name", "Ada")),
                    ScriptDialogSpec.Row.Buttons(
                        ScriptDialogSpec.ButtonsRow(listOf(ScriptDialogSpec.Button("OK", "ok")))
                    )
                )
            )
        )
        val root = ScriptDialogRenderer(ctx).buildRoot(request) {}
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2400)
        val dialog = findDialog(root)
        assertTrue("CanvasDialogView missing", dialog != null)
        assertTrue("dialog height=${dialog!!.measuredHeight}", dialog.measuredHeight > 100)
        var buttonSeen = false
        for (n in dialog.host.scene.flatten()) {
            if (n.type == CanvasSpec.TYPE_BUTTON) buttonSeen = true
        }
        assertTrue("canvas button missing", buttonSeen)
    }

    @Test
    fun `legacy form with every control type keeps real content height`() {
        val ctx = RuntimeEnvironment.getApplication()
        val request = fullLegacyRequest()
        val root = ScriptDialogRenderer(ctx).buildRoot(request) {}
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2400)
        val dialog = findDialog(root)
        assertTrue("CanvasDialogView missing", dialog != null)
        val view = dialog!!
        assertTrue("content collapsed: ${view.measuredHeight}", view.measuredHeight > 400)
        assertEquals(
            CanvasDialogSizing.contentHeightPx(view.host.scene, view.measuredWidth).toFloat(),
            view.measuredHeight.toFloat(),
            2f
        )
        val types = view.host.scene.flatten().map { it.type }.toSet()
        assertTrue(
            "missing widget types: $types",
            types.containsAll(
                listOf(
                    CanvasSpec.TYPE_TEXT,
                    CanvasSpec.TYPE_INPUT,
                    CanvasSpec.TYPE_SELECT,
                    CanvasSpec.TYPE_TOGGLE,
                    CanvasSpec.TYPE_BUTTON
                )
            )
        )
        val buttons = view.host.scene.flatten().filter { it.type == CanvasSpec.TYPE_BUTTON }
        assertEquals(2, buttons.size)
        assertTrue("button outside scene: ${buttons.map { it.y + it.h }}", buttons.all { it.y + it.h <= view.host.scene.height + 1f })
    }

    private fun fullLegacyRequest(): ScriptDialogSpec.Request = ScriptDialogSpec.Request(
        id = "full",
        ui = ScriptDialogSpec.Ui(
            title = "Full",
            message = "hello",
            rows = listOf(
                ScriptDialogSpec.Row.Text(ScriptDialogSpec.TextRow("out\u001b[32m green\u001b[0m")),
                ScriptDialogSpec.Row.Input(ScriptDialogSpec.InputRow("name", "Name", "Ada")),
                ScriptDialogSpec.Row.Select(
                    ScriptDialogSpec.SelectRow("host", "Host", (1..12).map { "host $it" })
                ),
                ScriptDialogSpec.Row.Select(
                    ScriptDialogSpec.SelectRow("tags", "Tags", listOf("a", "b", "c"), multi = true)
                ),
                ScriptDialogSpec.Row.Toggle(ScriptDialogSpec.ToggleRow("only", "Only")),
                ScriptDialogSpec.Row.Buttons(
                    ScriptDialogSpec.ButtonsRow(
                        listOf(
                            ScriptDialogSpec.Button("OK", "ok", ScriptDialogSpec.BTN_PRIMARY),
                            ScriptDialogSpec.Button("Cancel", "cancel")
                        )
                    )
                )
            )
        )
    )

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

    @Test
    fun `content view grows when scene height grows`() {
        val ctx = RuntimeEnvironment.getApplication()
        val view = CanvasDialogView(ctx, palette)
        val short = CanvasScene(width = 360f, height = 200f)
        view.setScene(short, resetClock = true, preserveValues = false, resetFilters = true)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(950, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val shortH = view.measuredHeight
        view.setScene(short.copy(height = 400f), resetClock = false, preserveValues = true, resetFilters = false)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(950, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        assertTrue("short=$shortH, tall=${view.measuredHeight}", view.measuredHeight > shortH)
    }
}
