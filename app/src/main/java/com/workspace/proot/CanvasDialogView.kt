package com.workspace.proot

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 单 Canvas 弹窗内容区。
 *
 * CanvasHostView 负责绘制/命中；输入框聚焦时在其位置叠一个真正的 EditText，
 * 只借它接系统输入法、光标、选区和密码。失焦后 EditText 隐藏，视觉仍由 Canvas 画。
 */
@Suppress("MagicNumber")
class CanvasDialogView(
    context: Context,
    private val palette: CanvasDialogPalette
) : FrameLayout(context) {

    val host = CanvasHostView(context)
    val formState = CanvasFormState()
    private val input = EditText(context).apply {
        visibility = GONE
        background = null
        includeFontPadding = false
        setPadding(0, 0, 0, 0)
        isSingleLine = true
        typeface = Typeface.MONOSPACE
        overScrollMode = OVER_SCROLL_NEVER
        setTextColor(palette.onSurface)
        setHintTextColor(palette.onSurfaceVariant)
        setSelectAllOnFocus(false)
    }
    private var bindingText = false
    private var boundInputId: String? = null

    var onSubmit: ((id: String, keepOpen: Boolean) -> Unit)? = null
    var onNodeAction: ((nodeId: String?, action: String) -> Unit)? = null

    init {
        addView(host, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(input, LayoutParams(0, 0))
        host.formState = formState
        host.onInputFocus = { focusInput(it) }
        host.onInputBlur = { hideInput() }
        host.onSubmit = { id, keepOpen -> onSubmit?.invoke(id, keepOpen) }
        host.onNodeAction = { id, action -> onNodeAction?.invoke(id, action) }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            @Suppress("ReturnCount")
            override fun afterTextChanged(s: Editable?) {
                if (bindingText) return
                val id = boundInputId ?: return
                val node = host.scene.findNode(id) ?: return
                formState.setInput(node, s?.toString() ?: "")
                host.invalidate()
            }
        })
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val wasBound = boundInputId
                boundInputId = null
                input.visibility = GONE
                if (wasBound != null) formState.focus(null)
                host.invalidate()
            }
        }
    }

    fun updatePalette(palette: CanvasDialogPalette) {
        input.setTextColor(palette.onSurface)
        input.setHintTextColor(palette.onSurfaceVariant)
    }

    fun setScene(scene: CanvasScene, resetClock: Boolean, preserveValues: Boolean, resetFilters: Boolean) {
        hideInput()
        host.setScene(scene, resetClock)
        formState.reset(scene, preserveValues = preserveValues, resetFilters = resetFilters)
        host.invalidate()
        requestLayout()
    }

    /**
     * ScrollView 会用 UNSPECIFIED 高度测量子 View，并忽略 layoutParams.height。
     * 这里根据场景宽高比报告真实内容高度，否则 FrameLayout 里的 MATCH_PARENT host
     * 会被量成 0，整张卡片只剩标题栏。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> widthSize.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            else -> widthSize
        }.coerceAtLeast(1)
        val height = CanvasDialogSizing.contentHeightPx(host.scene, width)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }

    fun applyPatch(patch: CanvasPatch): Boolean {
        val applied = host.applyPatch(patch)
        if (applied) {
            formState.sync(host.scene)
            val focused = formState.focusedNodeId?.let { host.scene.findNode(it) }
            if (focused?.type != CanvasSpec.TYPE_INPUT) hideInput()
        }
        return applied
    }

    fun collectValues(): Map<String, String> = formState.collect(host.scene)

    fun resetSubmitLock() = host.resetSubmitLock()

    fun replayAnimations() {
        host.seek(0)
        host.play()
    }

    fun hideInput() {
        boundInputId = null
        input.visibility = GONE
        if (input.isFocused) input.clearFocus()
        val token = input.windowToken
        if (token != null) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(token, 0)
        }
        formState.focus(null)
        host.invalidate()
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun focusInput(node: CanvasNode) {
        if (node.type != CanvasSpec.TYPE_INPUT) return
        if (boundInputId == node.id && input.isFocused) return
        val top = if (node.label.isEmpty()) 0f else CanvasWidgetLayout.LABEL_H + CanvasWidgetLayout.LABEL_GAP
        val tl = host.nodeLocalToView(node.id, 0f, top) ?: return
        val tr = host.nodeLocalToView(node.id, node.w, top) ?: return
        val bl = host.nodeLocalToView(node.id, 0f, node.h) ?: return
        val br = host.nodeLocalToView(node.id, node.w, node.h) ?: return
        val width = hypot(tr.first - tl.first, tr.second - tl.second)
        val height = hypot(bl.first - tl.first, bl.second - tl.second)
        if (width <= 1f || height <= 1f) return
        val cx = (tl.first + tr.first + bl.first + br.first) / 4f
        val cy = (tl.second + tr.second + bl.second + br.second) / 4f
        val rotation = Math.toDegrees(atan2((tr.second - tl.second).toDouble(), (tr.first - tl.first).toDouble())).toFloat()
        val scale = (width / node.w.coerceAtLeast(1f)).coerceAtLeast(0.05f)

        val lp = (input.layoutParams as? LayoutParams) ?: LayoutParams(0, 0)
        lp.width = width.roundToInt().coerceAtLeast(1)
        lp.height = height.roundToInt().coerceAtLeast(1)
        input.layoutParams = lp
        input.x = cx - width / 2f
        input.y = cy - height / 2f
        input.rotation = rotation
        input.pivotX = width / 2f
        input.pivotY = height / 2f
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, (node.textSize * scale).coerceAtLeast(1f))
        input.setTextColor(CanvasSpec.color(node.textColor, palette.onSurface))
        input.setPadding(
            (CanvasWidgetLayout.FIELD_PAD_X * scale).roundToInt(),
            (CanvasWidgetLayout.FIELD_PAD_Y * scale).roundToInt(),
            (CanvasWidgetLayout.FIELD_PAD_X * scale).roundToInt(),
            (CanvasWidgetLayout.FIELD_PAD_Y * scale).roundToInt()
        )
        input.gravity = if (node.multiline) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL or Gravity.START
        input.inputType = when {
            node.password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            node.multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT
        }
        input.isSingleLine = !node.multiline
        input.visibility = View.VISIBLE
        boundInputId = node.id
        formState.focus(node)
        bindingText = true
        val value = formState.inputValue(node)
        input.setText(value)
        input.setSelection(input.text.length)
        bindingText = false
        input.requestFocus()
        host.invalidate()
        post {
            if (!input.isAttachedToWindow) return@post
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            input.requestRectangleOnScreen(Rect(0, 0, input.width, input.height), true)
        }
    }

    override fun onDetachedFromWindow() {
        hideInput()
        super.onDetachedFromWindow()
    }
}
