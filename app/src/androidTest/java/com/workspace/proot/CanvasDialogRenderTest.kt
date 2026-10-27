@file:Suppress("MagicNumber")

package com.workspace.proot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * 真机渲染冒烟：新单 Canvas 弹窗能在真实 Android View/Canvas 里完成
 * measure/layout/draw，不崩、不空白。输入法交互仍需人工验收。
 */
@RunWith(AndroidJUnit4::class)
class CanvasDialogRenderTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    @Test
    fun canvasContentReportsHeightUnderScrollViewUnspecifiedMeasure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val palette = CanvasDialogPalette(
            onSurface = 0xFFE6E6E6.toInt(),
            onSurfaceVariant = 0xFF9A9A9A.toInt(),
            accent = 0xFF2D7D46.toInt(),
            danger = 0xFFC0392B.toInt(),
            buttonNormal = 0xFF3A3A3A.toInt(),
            onButton = 0xFFFFFFFF.toInt(),
            inputBg = 0xFF2A2A2A.toInt()
        )
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
        instrumentation.runOnMainSync {
            val view = CanvasDialogView(ctx, palette)
            view.setScene(scene, resetClock = true, preserveValues = false, resetFilters = true)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(950, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val expected = CanvasDialogSizing.contentHeightPx(scene, 950)
            assertTrue("height=${view.measuredHeight}, expected=$expected", view.measuredHeight > 100)
            assertTrue("height=${view.measuredHeight}, expected=$expected", kotlin.math.abs(view.measuredHeight - expected) <= 2)
        }
    }

    @Test
    fun legacyFormDialogDraws() {
        val request = ScriptDialogSpec.Request(
            id = "render",
            ui = ScriptDialogSpec.Ui(
                title = "Render",
                message = "hello",
                rows = listOf(
                    ScriptDialogSpec.Row.Text(ScriptDialogSpec.TextRow("out\u001b[32m green\u001b[0m")),
                    ScriptDialogSpec.Row.Input(ScriptDialogSpec.InputRow("name", "Name", "Ada", multiline = true)),
                    ScriptDialogSpec.Row.Select(
                        ScriptDialogSpec.SelectRow("host", "Host", (1..12).map { "host $it" }, multi = false)
                    ),
                    ScriptDialogSpec.Row.Select(
                        ScriptDialogSpec.SelectRow("tags", "Tags", listOf("a", "b", "c"), multi = true)
                    ),
                    ScriptDialogSpec.Row.Toggle(ScriptDialogSpec.ToggleRow("filter", "Only up", def = true, filter = "up")),
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
        drawAndAssert(request)
    }

    @Test
    fun canvasOnlyDialogWithWidgetsDraws() {
        val scene = CanvasScene(
            width = 360f,
            height = 520f,
            background = "#101010",
            nodes = listOf(
                CanvasNode(
                    id = "in",
                    type = CanvasSpec.TYPE_INPUT,
                    x = 24f,
                    y = 24f,
                    w = 312f,
                    h = CanvasWidgetLayout.inputHeight(),
                    label = "Name",
                    key = "name",
                    text = "Ada"
                ),
                CanvasNode(
                    id = "sel",
                    type = CanvasSpec.TYPE_SELECT,
                    x = 24f,
                    y = 90f,
                    w = 312f,
                    h = CanvasWidgetLayout.recommendedSelectHeight("Host", 8),
                    label = "Host",
                    key = "host",
                    options = (1..8).map { "host $it" }
                ),
                CanvasNode(
                    id = "tg",
                    type = CanvasSpec.TYPE_TOGGLE,
                    x = 24f,
                    y = 210f,
                    w = 312f,
                    h = CanvasWidgetLayout.TOGGLE_H,
                    label = "On",
                    key = "on",
                    checked = true
                ),
                CanvasNode(
                    id = "ok",
                    type = CanvasSpec.TYPE_BUTTON,
                    x = 24f,
                    y = 250f,
                    w = 120f,
                    h = CanvasWidgetLayout.BUTTON_H,
                    text = "OK",
                    action = "submit:ok",
                    fill = "#2D7D46",
                    textColor = "#FFFFFF"
                )
            )
        )
        val request = ScriptDialogSpec.Request(
            id = "render-canvas",
            ui = ScriptDialogSpec.Ui(
                title = "Canvas",
                rows = listOf(ScriptDialogSpec.Row.Canvas(ScriptDialogSpec.CanvasRow(scene)))
            )
        )
        drawAndAssert(request)
    }

    private fun drawAndAssert(request: ScriptDialogSpec.Request) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val metrics = ctx.resources.displayMetrics
        var root: FrameLayout? = null
        instrumentation.runOnMainSync {
            root = ScriptDialogRenderer(ctx).buildRoot(request) {}
            val view = root!!
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            var painted = false
            var y = 0
            while (y < height && !painted) {
                var x = 0
                while (x < width) {
                    if (bitmap.getPixel(x, y) != 0) {
                        painted = true
                        break
                    }
                    x += 8
                }
                y += 8
            }
            assertTrue("dialog draw produced a blank bitmap", painted)
        }
    }
}
