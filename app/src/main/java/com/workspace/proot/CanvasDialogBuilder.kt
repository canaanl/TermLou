@file:Suppress("MagicNumber")

package com.workspace.proot

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** 弹窗主题色，builder 不直接依赖 Android。 */
data class CanvasDialogPalette(
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val accent: Int,
    val danger: Int,
    val buttonNormal: Int,
    val onButton: Int,
    val inputBg: Int
)

/** 文本度量注入，生产用 Android Paint，单测用固定字宽。 */
interface CanvasDialogMetrics {
    fun measureText(text: String, size: Float, bold: Boolean = false): Float
    fun lineHeight(size: Float): Float
}

data class CanvasDialogBlock(
    val key: String,
    val scene: CanvasScene,
    val nodeIds: Set<String>,
    val groupId: String?,
    val root: Boolean
)

data class CanvasDialogBuildResult(
    val scene: CanvasScene,
    val formNodeIds: Set<String>,
    val canvasBlocks: List<CanvasDialogBlock>
)

/**
 * 旧 rows → 新单 Canvas 场景。
 *
 * 默认布局继续使用 UiTokens 的间距/字号，所以老脚本不改变参数时看起来仍是
 * 原来的竖排；生成后的每个控件都是普通 CanvasNode，可以自由移动。
 */
object CanvasDialogBuilder {

    private const val ROOT_PAD_TOP = 8f
    private const val ROOT_PAD_BOTTOM = 8f
    private const val CONTENT_INSET = 16f
    private const val TEXT_INSET = 20f
    private const val BUTTON_BAR_TOP = 10f
    private const val BUTTON_BAR_BOTTOM = 12f
    private const val BUTTON_BAR_INSET = 12f
    private const val BUTTON_MARGIN = 6f
    private const val BUTTON_GAP = 12f

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun build(
        request: ScriptDialogSpec.Request,
        palette: CanvasDialogPalette,
        metrics: CanvasDialogMetrics,
        contentWidth: Float,
        closeText: String
    ): CanvasDialogBuildResult {
        val rows = request.ui.rows
        val canvasRows = rows.filterIsInstance<ScriptDialogSpec.Row.Canvas>()
        val hasMessage = request.ui.message.isNotEmpty()
        val hasFormRow = rows.any { it !is ScriptDialogSpec.Row.Canvas && it !is ScriptDialogSpec.Row.Buttons }
        val rootCanvas = canvasRows.size == 1 && !hasMessage && !hasFormRow
        return if (rootCanvas) {
            buildCanvasRoot(request, canvasRows.first(), palette, closeText)
        } else {
            buildMixed(request, palette, metrics, contentWidth, closeText)
        }
    }

    private fun buildCanvasRoot(
        request: ScriptDialogSpec.Request,
        row: ScriptDialogSpec.Row.Canvas,
        palette: CanvasDialogPalette,
        closeText: String
    ): CanvasDialogBuildResult {
        val source = row.row.scene
        val key = row.row.key.ifEmpty { "canvas0" }
        val baseHeight = max(source.height, row.row.heightDp.toFloat())
        val used = source.flatten().map { it.id }.toHashSet()
        val formIds = LinkedHashSet<String>()
        val nodes = source.nodes.toMutableList()
        val hasCanvasButton = source.flatten().any { it.type == CanvasSpec.TYPE_BUTTON && it.visible }
        val explicit = (request.ui.rows.firstOrNull { it is ScriptDialogSpec.Row.Buttons } as? ScriptDialogSpec.Row.Buttons)?.row?.buttons
        val buttons = explicit?.takeIf { it.isNotEmpty() }
            ?: if (hasCanvasButton) emptyList()
            else listOf(ScriptDialogSpec.Button(closeText, ScriptDialogSpec.RESULT_ID_DISMISS))
        var height = baseHeight
        if (buttons.isNotEmpty()) {
            val y = baseHeight + BUTTON_BAR_TOP
            addButtons(nodes, buttons, y, source.width, palette, used, formIds, "root")
            height = y + CanvasWidgetLayout.BUTTON_H + BUTTON_BAR_BOTTOM
        }
        val scene = source.copy(height = max(source.height, height), nodes = nodes)
        return CanvasDialogBuildResult(
            scene = scene,
            formNodeIds = formIds,
            canvasBlocks = listOf(
                CanvasDialogBlock(
                    key = key,
                    scene = source,
                    nodeIds = source.flatten().map { it.id }.toSet(),
                    groupId = null,
                    root = true
                )
            )
        )
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun buildMixed(
        request: ScriptDialogSpec.Request,
        palette: CanvasDialogPalette,
        metrics: CanvasDialogMetrics,
        contentWidth: Float,
        closeText: String
    ): CanvasDialogBuildResult {
        val width = contentWidth.coerceAtLeast(120f)
        val nodes = mutableListOf<CanvasNode>()
        val animations = mutableListOf<CanvasAnimation>()
        val formIds = LinkedHashSet<String>()
        val blocks = mutableListOf<CanvasDialogBlock>()
        val used = HashSet<String>()
        for (row in request.ui.rows) {
            if (row is ScriptDialogSpec.Row.Canvas) {
                for (n in row.row.scene.flatten()) used.add(n.id)
            }
        }
        var y = ROOT_PAD_TOP
        var idSeq = 0
        fun nextId(prefix: String): String {
            var id = "form:$prefix:${idSeq++}"
            while (!used.add(id)) id = "form:$prefix:${idSeq++}"
            formIds.add(id)
            return id
        }

        if (request.ui.message.isNotEmpty()) {
            val h = textHeight(request.ui.message, CanvasWidgetLayout.LABEL_SIZE, width - TEXT_INSET * 2, metrics)
            nodes.add(
                CanvasNode(
                    id = nextId("text"),
                    type = CanvasSpec.TYPE_TEXT,
                    x = TEXT_INSET,
                    y = y,
                    w = width - TEXT_INSET * 2,
                    h = h,
                    text = CanvasSpec.safeText(request.ui.message),
                    textSize = CanvasWidgetLayout.LABEL_SIZE,
                    textColor = CanvasSpec.colorHex(palette.onSurface),
                    wrap = true,
                    ansi = request.ui.message.contains('\u001b'),
                    name = "message"
                )
            )
            y += h
        }

        var canvasIndex = 0
        for (row in request.ui.rows) {
            when (row) {
                is ScriptDialogSpec.Row.Text -> {
                    val w = width - TEXT_INSET * 2
                    val h = textHeight(row.row.text, CanvasWidgetLayout.LABEL_SIZE, w, metrics)
                    nodes.add(
                        CanvasNode(
                            id = nextId("text"),
                            type = CanvasSpec.TYPE_TEXT,
                            x = TEXT_INSET,
                            y = y,
                            w = w,
                            h = h,
                            text = CanvasSpec.safeText(row.row.text),
                            textSize = CanvasWidgetLayout.LABEL_SIZE,
                            textColor = CanvasSpec.colorHex(palette.onSurface),
                            wrap = true,
                            ansi = row.row.text.contains('\u001b'),
                            name = "text"
                        )
                    )
                    y += h
                }
                is ScriptDialogSpec.Row.Input -> {
                    val h = CanvasWidgetLayout.inputHeight() + if (row.row.multiline) 30f else 0f
                    nodes.add(
                        CanvasNode(
                            id = nextId("input"),
                            type = CanvasSpec.TYPE_INPUT,
                            x = CONTENT_INSET,
                            y = y,
                            w = width - CONTENT_INSET * 2,
                            h = h,
                            key = row.row.key,
                            label = row.row.label,
                            text = row.row.default,
                            password = row.row.password,
                            multiline = row.row.multiline,
                            labelSize = CanvasWidgetLayout.LABEL_SIZE,
                            labelColor = CanvasSpec.colorHex(palette.onSurfaceVariant),
                            textSize = CanvasWidgetLayout.FIELD_TEXT_SIZE,
                            textColor = CanvasSpec.colorHex(palette.onSurface),
                            fill = CanvasSpec.colorHex(palette.inputBg),
                            radius = CanvasWidgetLayout.FIELD_RADIUS,
                            name = "input ${row.row.key}"
                        )
                    )
                    y += h + CanvasWidgetLayout.ROW_GAP
                }
                is ScriptDialogSpec.Row.Select -> {
                    val h = CanvasWidgetLayout.recommendedSelectHeight(row.row.label, row.row.options.size)
                    nodes.add(
                        CanvasNode(
                            id = nextId("select"),
                            type = CanvasSpec.TYPE_SELECT,
                            x = CONTENT_INSET,
                            y = y,
                            w = width - CONTENT_INSET * 2,
                            h = h,
                            key = row.row.key,
                            label = row.row.label,
                            options = row.row.options.take(CanvasSpec.MAX_OPTIONS),
                            multi = row.row.multi,
                            labelSize = CanvasWidgetLayout.LABEL_SIZE,
                            labelColor = CanvasSpec.colorHex(palette.onSurfaceVariant),
                            textSize = CanvasWidgetLayout.OPTION_SIZE,
                            textColor = CanvasSpec.colorHex(palette.onSurface),
                            barColor = CanvasSpec.colorHex(palette.accent),
                            fill = CanvasSpec.colorHex(palette.inputBg),
                            radius = CanvasWidgetLayout.FIELD_RADIUS,
                            name = if (row.row.multi) "check ${row.row.key}" else "select ${row.row.key}"
                        )
                    )
                    y += h + CanvasWidgetLayout.ROW_GAP
                }
                is ScriptDialogSpec.Row.Toggle -> {
                    nodes.add(
                        CanvasNode(
                            id = nextId("toggle"),
                            type = CanvasSpec.TYPE_TOGGLE,
                            x = CONTENT_INSET,
                            y = y,
                            w = width - CONTENT_INSET * 2,
                            h = CanvasWidgetLayout.TOGGLE_H,
                            key = row.row.key,
                            label = row.row.label,
                            checked = row.row.def,
                            filter = row.row.filter,
                            labelSize = CanvasWidgetLayout.LABEL_SIZE,
                            labelColor = CanvasSpec.colorHex(palette.onSurfaceVariant),
                            textSize = CanvasWidgetLayout.LABEL_SIZE,
                            textColor = CanvasSpec.colorHex(palette.onSurfaceVariant),
                            barColor = CanvasSpec.colorHex(palette.accent),
                            fill = CanvasSpec.colorHex(palette.inputBg),
                            radius = CanvasWidgetLayout.FIELD_RADIUS,
                            name = "toggle ${row.row.key}"
                        )
                    )
                    y += CanvasWidgetLayout.TOGGLE_H + CanvasWidgetLayout.ROW_GAP
                }
                is ScriptDialogSpec.Row.Canvas -> {
                    val key = row.row.key.ifEmpty { "canvas$canvasIndex" }
                    canvasIndex++
                    val blockH = row.row.heightDp.toFloat().coerceAtLeast(64f)
                    val source = row.row.scene
                    val blockW = width - CONTENT_INSET * 2
                    val fit = min(blockW / source.width.coerceAtLeast(1f), blockH / source.height.coerceAtLeast(1f))
                    val drawW = source.width * fit
                    val drawH = source.height * fit
                    val offsetX = CONTENT_INSET + (blockW - drawW) / 2f
                    val offsetY = y + (blockH - drawH) / 2f
                    val cx = source.width / 2f
                    val cy = source.height / 2f
                    val groupId = nextId("canvas")
                    nodes.add(
                        CanvasNode(
                            id = nextId("canvasbg"),
                            type = CanvasSpec.TYPE_ROUND_RECT,
                            x = CONTENT_INSET,
                            y = y,
                            w = blockW,
                            h = blockH + CanvasWidgetLayout.ROW_GAP,
                            fill = CanvasSpec.colorHex(
                                if (CanvasSpec.color(source.background, 0) != 0) CanvasSpec.color(source.background, 0) else palette.inputBg
                            ),
                            radius = CanvasWidgetLayout.FIELD_RADIUS,
                            name = "canvas bg $key"
                        )
                    )
                    nodes.add(
                        CanvasNode(
                            id = groupId,
                            type = CanvasSpec.TYPE_GROUP,
                            x = offsetX - cx * (1f - fit),
                            y = offsetY - cy * (1f - fit),
                            w = source.width,
                            h = source.height,
                            scaleX = fit,
                            scaleY = fit,
                            children = source.nodes,
                            clip = true,
                            name = "canvas $key"
                        )
                    )
                    formIds.remove(groupId)
                    animations.addAll(source.animations)
                    blocks.add(
                        CanvasDialogBlock(
                            key = key,
                            scene = source,
                            nodeIds = source.flatten().map { it.id }.toSet(),
                            groupId = groupId,
                            root = false
                        )
                    )
                    y += blockH + CanvasWidgetLayout.ROW_GAP
                }
                is ScriptDialogSpec.Row.Buttons -> Unit
            }
        }

        val explicit = (request.ui.rows.firstOrNull { it is ScriptDialogSpec.Row.Buttons } as? ScriptDialogSpec.Row.Buttons)?.row?.buttons
        val canvasButton = request.ui.rows.filterIsInstance<ScriptDialogSpec.Row.Canvas>()
            .any { row -> row.row.scene.flatten().any { it.type == CanvasSpec.TYPE_BUTTON && it.visible } }
        val buttons = explicit?.takeIf { it.isNotEmpty() }
            ?: if (canvasButton) emptyList()
            else listOf(ScriptDialogSpec.Button(closeText, ScriptDialogSpec.RESULT_ID_DISMISS))
        if (buttons.isNotEmpty()) {
            val buttonY = y + ROOT_PAD_BOTTOM + BUTTON_BAR_TOP
            addButtons(nodes, buttons, buttonY, width, palette, used, formIds, "bar")
            y = buttonY + CanvasWidgetLayout.BUTTON_H + BUTTON_BAR_BOTTOM
        } else {
            y += ROOT_PAD_BOTTOM
        }

        val scene = CanvasScene(
            width = width,
            height = max(ROOT_PAD_TOP + ROOT_PAD_BOTTOM, y),
            background = "#00000000",
            nodes = nodes,
            animations = animations
        )
        return CanvasDialogBuildResult(scene, formIds, blocks)
    }

    @Suppress("LongParameterList")
    private fun addButtons(
        nodes: MutableList<CanvasNode>,
        buttons: List<ScriptDialogSpec.Button>,
        y: Float,
        width: Float,
        palette: CanvasDialogPalette,
        used: MutableSet<String>,
        formIds: MutableSet<String>,
        prefix: String
    ) {
        if (buttons.isEmpty()) return
        val available = width - (BUTTON_BAR_INSET + BUTTON_MARGIN) * 2 - BUTTON_GAP * (buttons.size - 1)
        val w = (available / buttons.size).coerceAtLeast(24f)
        var x = BUTTON_BAR_INSET + BUTTON_MARGIN
        for ((i, button) in buttons.withIndex()) {
            var id = "form:$prefix:button:$i"
            var n = 1
            while (!used.add(id)) id = "form:$prefix:button:$i:${n++}"
            formIds.add(id)
            val fill = when (button.kind) {
                ScriptDialogSpec.BTN_DANGER -> palette.danger
                ScriptDialogSpec.BTN_PRIMARY -> palette.accent
                else -> palette.buttonNormal
            }
            val action = "submit:${button.id}" + if (button.close) "" else ":keep"
            nodes.add(
                CanvasNode(
                    id = id,
                    type = CanvasSpec.TYPE_BUTTON,
                    x = x,
                    y = y,
                    w = w,
                    h = CanvasWidgetLayout.BUTTON_H,
                    text = button.text,
                    textSize = 13f,
                    textColor = CanvasSpec.colorHex(palette.onButton),
                    fill = CanvasSpec.colorHex(fill),
                    radius = CanvasWidgetLayout.FIELD_RADIUS,
                    bold = true,
                    action = action,
                    buttonKind = button.kind,
                    name = "button ${button.id}"
                )
            )
            x += w + BUTTON_GAP
        }
    }

    private fun textHeight(text: String, size: Float, maxWidth: Float, metrics: CanvasDialogMetrics): Float {
        val width = maxWidth.coerceAtLeast(1f)
        val measuredText = if (text.contains('\u001b')) AnsiParser.parse(text).clean else text
        var lines = 0
        for (line in measuredText.split('\n')) {
            val measured = metrics.measureText(line, size)
            lines += max(1, ceil(measured / width).toInt())
        }
        return max(metrics.lineHeight(size), lines * metrics.lineHeight(size))
    }
}
