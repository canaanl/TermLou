package com.workspace.proot

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 弹窗渲染：原生标题栏 + 一个 CanvasDialogView 内容区。
 *
 * 旧 `rows` 由 CanvasDialogBuilder 转成 CanvasNode；结果 values、Canvas 快照和
 * `key.action` 仍保持旧格式。输入框聚焦时只在节点位置临时叠一个 EditText。
 */
class ScriptDialogRenderer(private val ctx: Context) {

    private val d get() = ctx.resources.displayMetrics.density

    private var palette: Palette? = null
    private var dialogRef: CanvasDialogView? = null
    private var scrollRef: MaxHeightScrollView? = null
    private var cardRef: LinearLayout? = null
    private var titleRef: TextView? = null
    private var rootRef: FrameLayout? = null
    private var buildResult: CanvasDialogBuildResult? = null
    private var lastRequest: ScriptDialogSpec.Request? = null
    private var currentOnResult: ((ScriptDialogSpec.Result) -> Unit)? = null
    private val canvasActions = HashMap<String, String>()

    fun captureValues(): Map<String, String> {
        val form = dialogRef?.collectValues() ?: emptyMap()
        return form + canvasValues()
    }

    fun buildRoot(request: ScriptDialogSpec.Request, onResult: (ScriptDialogSpec.Result) -> Unit): FrameLayout {
        val style = request.style
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.TRANSPARENT) }
        rootRef = root
        val card = buildCard(request, onResult)
        val lp = FrameLayout.LayoutParams(
            screenWidthPx(style.widthPct),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (style.position == ScriptDialogSpec.POSITION_BOTTOM) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
            bottomMargin = (28 * d).toInt()
            topMargin = (28 * d).toInt()
        }
        root.addView(card, lp)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateScrollLimit() }
        root.post { updateScrollLimit() }
        return root
    }

    fun buildCard(request: ScriptDialogSpec.Request, onResult: (ScriptDialogSpec.Result) -> Unit): LinearLayout {
        val style = request.style
        val p = paletteOf(style)
        palette = p
        currentOnResult = onResult
        lastRequest = request
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(p.surface)
                cornerRadius = style.radiusDp * d
                setStroke((1 * d).toInt(), p.border)
            }
            clipToOutline = true
            setTag(style.radiusDp)
        }
        cardRef = card
        val title = titleBar(request.ui.title, style, p)
        titleRef = title
        card.addView(title)
        val scroll = MaxHeightScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
            clipToPadding = false
        }
        scrollRef = scroll
        val dialog = CanvasDialogView(ctx, p.toCanvasPalette()).apply {
            onSubmit = { id, keepOpen -> submit(id, keepOpen) }
            onNodeAction = { id, action -> recordAction(id, action) }
        }
        dialogRef = dialog
        scroll.addView(
            dialog,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        card.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        rebuild(request, resetClock = true, preserveValues = false, resetFilters = true)
        card.post { updateScrollLimit() }
        return card
    }

    fun updateCard(card: LinearLayout, newRequest: ScriptDialogSpec.Request, onResult: (ScriptDialogSpec.Result) -> Unit) {
        currentOnResult = onResult
        lastRequest = newRequest
        val style = newRequest.style
        val p = paletteOf(style)
        palette = p
        (card.background as? GradientDrawable)?.let { bg ->
            bg.setColor(p.surface)
            bg.cornerRadius = style.radiusDp * d
            bg.setStroke((1 * d).toInt(), p.border)
            card.invalidateOutline()
        }
        card.setTag(style.radiusDp)
        titleRef?.let { updateTitleBar(it, newRequest.ui.title, style, p) }
        dialogRef?.updatePalette(p.toCanvasPalette())
        dialogRef?.resetSubmitLock()
        canvasActions.clear()
        rebuild(newRequest, resetClock = true, preserveValues = true, resetFilters = true)
        card.post { updateScrollLimit() }
    }

    private fun rebuild(
        request: ScriptDialogSpec.Request,
        resetClock: Boolean,
        preserveValues: Boolean,
        resetFilters: Boolean
    ) {
        val dialog = dialogRef ?: return
        val p = palette ?: paletteOf(request.style).also { palette = it }
        val contentWidthPx = contentWidthPx(request.style)
        val contentWidthDp = contentWidthPx / d
        val result = CanvasDialogBuilder.build(
            request = request,
            palette = p.toCanvasPalette(),
            metrics = PaintDialogMetrics,
            contentWidth = contentWidthDp,
            closeText = ctx.getString(R.string.close)
        )
        buildResult = result
        dialog.setScene(
            result.scene,
            resetClock = resetClock,
            preserveValues = preserveValues,
            resetFilters = resetFilters
        )
        val scale = contentWidthPx / result.scene.width.coerceAtLeast(1f)
        val height = (result.scene.height * scale).roundToInt().coerceAtLeast(1)
        dialog.layoutParams = (dialog.layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            height
        )).apply { this.height = height }
        dialog.requestLayout()
        scrollRef?.requestLayout()
        scrollRef?.post { updateScrollLimit() }
    }

    @Suppress("ReturnCount")
    private fun updateScrollLimit() {
        val root = rootRef ?: return
        val scroll = scrollRef ?: return
        val card = cardRef ?: return
        val available = root.height - 2 * (28 * d).toInt()
        if (available <= 0 || card.width <= 0) return
        val title = titleRef
        val titleH = if (title != null) {
            title.measure(
                View.MeasureSpec.makeMeasureSpec(card.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            title.measuredHeight
        } else 0
        val next = (available - titleH).coerceAtLeast((120 * d).toInt())
        if (scroll.maxHeight != next) {
            scroll.maxHeight = next
            scroll.requestLayout()
        }

    }

    private fun submit(id: String, keepOpen: Boolean) {
        val cb = currentOnResult ?: return
        val values = (dialogRef?.collectValues() ?: emptyMap()) + canvasValues()
        cb(ScriptDialogSpec.Result(id, values, keepOpen = keepOpen))
    }

    @Suppress("ReturnCount")
    private fun canvasValues(): Map<String, String> {
        val result = buildResult ?: return emptyMap()
        val dialog = dialogRef ?: return emptyMap()
        val rootScene = dialog.host.scene
        val out = LinkedHashMap<String, String>()
        for (block in result.canvasBlocks) {
            val snapshot = CanvasDialogSnapshots.capture(rootScene, block, result.formNodeIds)
            out[block.key] = CanvasSceneCodec.sceneToJsonString(snapshot)
            canvasActions[block.key]?.let { out["${block.key}.action"] = it }
        }
        return out
    }

    private fun recordAction(nodeId: String?, action: String) {
        val result = buildResult ?: return
        val block = result.canvasBlocks.firstOrNull { block ->
            nodeId != null && (nodeId == block.groupId || nodeId in block.nodeIds)
        } ?: result.canvasBlocks.firstOrNull { it.root }
            ?: result.canvasBlocks.singleOrNull()?.takeIf { nodeId == null }
            ?: return
        canvasActions[block.key] = action
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun applyCanvasPatch(key: String?, patch: CanvasPatch): Boolean {
        val result = buildResult ?: return false
        if (result.canvasBlocks.isEmpty()) return false
        val scopeNode = if (patch.scene != null) null else patch.node
        if (!CanvasDialogPatchPlanner.accepts(result.canvasBlocks, key, scopeNode)) return false
        val request = lastRequest ?: return false
        if (patch.scene != null) {
            val newRequest = CanvasDialogPatchPlanner.replaceScenes(request, key, patch.scene) ?: return false
            lastRequest = newRequest
            rebuild(newRequest, resetClock = false, preserveValues = true, resetFilters = false)
            if (key == null) canvasActions.clear() else canvasActions.remove(key)
            return true
        }
        val applied = dialogRef?.applyPatch(patch) ?: false
        if (applied) {
            if (key == null) canvasActions.clear() else canvasActions.remove(key)
        }
        return applied
    }

    fun replayCanvasAnimations() {
        dialogRef?.replayAnimations()
    }

    fun hideInput() {
        dialogRef?.hideInput()
    }

    private fun contentWidthPx(style: ScriptDialogSpec.Style): Int =
        screenWidthPx(style.widthPct).coerceAtLeast((120 * d).toInt())

    fun screenWidthPx(widthPct: Int): Int {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val pct = widthPct.coerceIn(40, 100)
        return (metrics.widthPixels * pct / 100).coerceAtMost(metrics.widthPixels - (16 * d).toInt() * 2)
    }

    private fun titleBar(title: String, style: ScriptDialogSpec.Style, palette: Palette): TextView {
        val titleSize = 14f
        val dash = "─"
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = titleSize * d
        }
        val dashW = max(paint.measureText(dash), 1f)
        val avail = screenWidthPx(style.widthPct) - 2 * (8 * d).toInt() - (6 * d).toInt()
        val core = if (title.isEmpty()) "TermLou" else title
        fun frameOf(c: String) = "┌[ $c ]┐"
        fun innerOf(c: String) = "[ $c ]"
        val frameW = paint.measureText(frameOf(core))
        val text = if (frameW + 2 * dashW <= avail) {
            val side = ((avail - frameW) / 2f / dashW).toInt().coerceAtLeast(1)
            "┌" + dash.repeat(side) + innerOf(core) + dash.repeat(side) + "┐"
        } else {
            val maxCoreW = avail - 2 * dashW - paint.measureText("┌[  ]┐")
            var shown = core
            while (shown.length > 1 && paint.measureText(innerOf(shown + "…")) > maxCoreW) shown = shown.dropLast(1)
            "┌" + dash + innerOf(shown + "…") + dash + "┐"
        }
        return TextView(ctx).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            setTextColor(palette.accent)
            textSize = titleSize
            gravity = Gravity.CENTER_HORIZONTAL
            isSingleLine = true
            setPaddingMd(8, 10, 8, 6)
        }
    }

    private fun updateTitleBar(tv: TextView, title: String, style: ScriptDialogSpec.Style, palette: Palette) {
        val newTitle = titleBar(title, style, palette)
        val newText = newTitle.text.toString()
        if (tv.text.toString() == newText) {
            tv.setTextColor(palette.accent)
            return
        }
        tv.animate().alpha(0f).setDuration(60).withEndAction {
            tv.text = newText
            tv.setTextColor(palette.accent)
            tv.animate().alpha(1f).setDuration(60).start()
        }.start()
    }

    private fun paletteOf(style: ScriptDialogSpec.Style): Palette {
        val accent = style.accent ?: when (style.theme) {
            ScriptDialogSpec.THEME_LIGHT -> 0xFF2D7D46.toInt()
            ScriptDialogSpec.THEME_GLASS -> 0xFF4DD0E1.toInt()
            else -> 0xFF2D7D46.toInt()
        }
        return when (style.theme) {
            ScriptDialogSpec.THEME_LIGHT -> Palette(
                0xFFF5F5F5.toInt(), 0xFFCCCCCC.toInt(), 0xFF111111.toInt(),
                0xFF666666.toInt(), accent, 0xFFC0392B.toInt(),
                0xFFDDDDDD.toInt(), 0xFF111111.toInt(), 0xFFFFFFFF.toInt()
            )
            ScriptDialogSpec.THEME_GLASS -> Palette(
                0xE61E1E1E.toInt(), 0x55FFFFFF.toInt(), 0xFFE6E6E6.toInt(),
                0xFF9A9A9A.toInt(), accent, 0xFFFF5252.toInt(),
                0xAA333333.toInt(), 0xFFE6E6E6.toInt(), 0x55333333.toInt()
            )
            else -> Palette(
                0xFF1E1E1E.toInt(), 0xFF3A3A3A.toInt(), 0xFFE6E6E6.toInt(),
                0xFF9A9A9A.toInt(), accent, 0xFFC0392B.toInt(),
                0xFF3A3A3A.toInt(), 0xFFE6E6E6.toInt(), 0xFF2A2A2A.toInt()
            )
        }
    }

    private data class Palette(
        val surface: Int,
        val border: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val accent: Int,
        val danger: Int,
        val buttonNormal: Int,
        val onButton: Int,
        val inputBg: Int
    ) {
        fun toCanvasPalette(): CanvasDialogPalette = CanvasDialogPalette(
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant,
            accent = accent,
            danger = danger,
            buttonNormal = buttonNormal,
            onButton = onButton,
            inputBg = inputBg
        )
    }

    private object PaintDialogMetrics : CanvasDialogMetrics {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
        }

        override fun measureText(text: String, size: Float, bold: Boolean): Float {
            paint.textSize = size
            paint.typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            return paint.measureText(text)
        }

        override fun lineHeight(size: Float): Float = size * CanvasWidgetLayout.LABEL_LINE
    }
}
