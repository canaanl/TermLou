package com.workspace.proot

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

/**
 * RecyclerView + DiffUtil 真原位渲染器：边框稳、内容 cross-fade、高度平滑
 * 仅重编 Renderer/Overlay，Spec/shell 契约不变
 */
class ScriptDialogRenderer(private val ctx: Context) {

    private val d get() = ctx.resources.displayMetrics.density

    private var _state: InteractiveState? = null
    private var adapterRef: InnerAdapter? = null
    private var cardRef: LinearLayout? = null

    data class RowKey(val kind: String, val id: String)

    fun captureValues(): Map<String, String> {
        val s = _state ?: return emptyMap()
        return collectValues(s)
    }

    fun buildRoot(request: ScriptDialogSpec.Request, onResult: (ScriptDialogSpec.Result) -> Unit): FrameLayout {
        val style = request.style
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.TRANSPARENT) }
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
        return root
    }

    fun buildCard(request: ScriptDialogSpec.Request, onResult: (ScriptDialogSpec.Result) -> Unit): LinearLayout {
        val style = request.style
        val palette = paletteOf(style)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = style.radiusDp * d
                setStroke((1 * d).toInt(), palette.border)
            }
        }
        cardRef = card
        // 标题
        val titleView = titleBar(request.ui.title, style, palette)
        card.addView(titleView)
        // Recycler 替代 ScrollView+body
        val recycler = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            isNestedScrollingEnabled = false
            // 同步 Diff 下局部行动画安全：异构（removed/added）淡出淡入，同构 change 平滑
            itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
                addDuration = 120
                removeDuration = 120
                changeDuration = 120
                moveDuration = 120
            }
            setHasFixedSize(false)
        }
        val state = InteractiveState()
        _state = state
        val adapter = InnerAdapter(ctx, d, palette, state)
        adapterRef = adapter
        recycler.adapter = adapter
        // 首建 filter 预建
        for (row in request.ui.rows.filterIsInstance<ScriptDialogSpec.Row.Toggle>()) {
            if (row.row.def && row.row.filter.isNotEmpty()) adapter.addFilteredTag(row.row.filter)
        }
        // 高度：WRAP_CONTENT，由 Recycler 测量；外层 card WRAP_CONTENT 会随之撑开
        recycler.setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        card.addView(recycler, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        submitList(adapter, request)
        // 按钮栏
        val buttons = (request.ui.rows.firstOrNull { it is ScriptDialogSpec.Row.Buttons } as? ScriptDialogSpec.Row.Buttons)?.row?.buttons
        card.addView(buttonBar(buttons, palette) { id ->
            onResult(ScriptDialogSpec.Result(id, collectValues(state)))
        })
        return card
    }

    private var lastPalette: Palette? = null
    private var lastButtons: List<ScriptDialogSpec.Button>? = null
    fun updateCard(card: LinearLayout, newRequest: ScriptDialogSpec.Request, onResult: (ScriptDialogSpec.Result) -> Unit) {
        val palette = paletteOf(newRequest.style)
        val bg = card.background as? GradientDrawable
        val last = lastPalette
        val paletteChanged = last == null || last.surface != palette.surface || last.border != palette.border || last.accent != palette.accent
        if (paletteChanged) {
            bg?.setColor(palette.surface)
            bg?.cornerRadius = newRequest.style.radiusDp * d
            bg?.setStroke((1 * d).toInt(), palette.border)
            lastPalette = palette
        } else if (card.tag as? Int != newRequest.style.radiusDp) {
            bg?.cornerRadius = newRequest.style.radiusDp * d
            card.setTag(newRequest.style.radiusDp)
        }
        // 标题 cross-fade
        if (card.childCount > 0) {
            val tv = card.getChildAt(0) as? TextView
            if (tv != null) updateTitleBar(tv, newRequest.ui.title, newRequest.style, palette)
        }
        // Recycler
        val recycler = card.getChildAt(1) as? RecyclerView
        val adapter = recycler?.adapter as? InnerAdapter ?: adapterRef
        if (adapter != null && recycler != null) {
            // 清理旧状态：仅保留新页面存在的 key，选项不跨页残留
            val newInputKeys = newRequest.ui.rows.filterIsInstance<ScriptDialogSpec.Row.Input>().map { it.row.key }.toSet()
            val newSelectKeys = newRequest.ui.rows.filterIsInstance<ScriptDialogSpec.Row.Select>().map { it.row.key }.toSet()
            val newToggleKeys = newRequest.ui.rows.filterIsInstance<ScriptDialogSpec.Row.Toggle>().map { it.row.key }.toSet()
            _state?.input?.keys?.retainAll(newInputKeys)
            _state?.select?.keys?.retainAll(newSelectKeys)
            _state?.toggle?.keys?.retainAll(newToggleKeys)
            adapter.clearFilteredTags()
            // 按新 toggle 的默认值预置过滤标签
            for (row in newRequest.ui.rows.filterIsInstance<ScriptDialogSpec.Row.Toggle>()) {
                if (row.row.def && row.row.filter.isNotEmpty()) adapter.addFilteredTag(row.row.filter)
            }
            val snapshot = captureValues()
            val oldH = card.height
            // 高度平滑（边框渐进）：整卡冻结旧高，post 量得目标高后 120ms 拉伸整卡
            submitList(adapter, newRequest)
            if (oldH > 0) {
                card.layoutParams.height = oldH
                card.requestLayout()
                card.post {
                    card.measure(
                        View.MeasureSpec.makeMeasureSpec(card.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    val newH = card.measuredHeight
                    if (newH != oldH && newH > 0) {
                        val anim = android.animation.ValueAnimator.ofInt(oldH, newH).apply {
                            duration = 120
                            interpolator = android.view.animation.DecelerateInterpolator()
                            addUpdateListener { v ->
                                card.layoutParams.height = v.animatedValue as Int
                                card.requestLayout()
                            }
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    card.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                }
                            })
                        }
                        anim.start()
                    } else {
                        card.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                }
            }
            // 仅回填输入框文本（同 key 保留），select/toggle 按新默认值（已清）
            for ((k, v) in snapshot) {
                if (_state?.input?.containsKey(k) == true) {
                    _state?.input?.get(k)?.let { if (it.text.toString() != v) it.setText(v) }
                }
            }
        }
        // 按钮栏：同构（文本/类型/id 相同）不重建，仅更新 palette；异构才重建
        if (card.childCount >= 3) {
            val buttons = (newRequest.ui.rows.firstOrNull { it is ScriptDialogSpec.Row.Buttons } as? ScriptDialogSpec.Row.Buttons)?.row?.buttons ?: emptyList()
            val same = lastButtons != null && lastButtons == buttons
            if (!same) {
                val newBar = buttonBar(buttons, palette) { id ->
                    val s = _state ?: InteractiveState().also { _state = it }
                    onResult(ScriptDialogSpec.Result(id, collectValues(s)))
                }
                card.removeViewAt(card.childCount - 1)
                card.addView(newBar)
                lastButtons = buttons
            }
        }
    }

    private fun submitList(adapter: InnerAdapter, request: ScriptDialogSpec.Request) {
        val rows = mutableListOf<DisplayRow>()
        if (request.ui.message.isNotEmpty()) {
            rows.add(DisplayRow("__message__", TYPE_TEXT, ScriptDialogSpec.Row.Text(ScriptDialogSpec.TextRow(request.ui.message))))
        }
        // 文本不合并，每行独立，便于 Diff 稳定 key（text 用内容 hash，不含序号，插入/删除不重判）
        val snap = adapter.currentFilterSnapshot()
        for (row in request.ui.rows) {
            when (row) {
                is ScriptDialogSpec.Row.Text -> rows.add(DisplayRow("text:${row.row.text.hashCode()}", TYPE_TEXT, row, snap))
                is ScriptDialogSpec.Row.Input -> rows.add(DisplayRow("input:${row.row.key}", TYPE_INPUT, row, snap))
                is ScriptDialogSpec.Row.Select -> rows.add(DisplayRow("select:${row.row.key}", TYPE_SELECT, row, snap))
                is ScriptDialogSpec.Row.Toggle -> rows.add(DisplayRow("toggle:${row.row.key}", TYPE_TOGGLE, row, snap))
                is ScriptDialogSpec.Row.Buttons -> Unit
            }
        }
        adapter.submitList(rows.toList())
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

    private class InteractiveState(
        val input: MutableMap<String, EditText> = mutableMapOf(),
        val select: MutableMap<String, MutableList<String>> = mutableMapOf(),
        val toggle: MutableMap<String, Boolean> = mutableMapOf()
    )

    private class OptionMeta(val label: String, val tag: String?)

    data class DisplayRow(
        val id: String,
        val type: Int,
        val row: ScriptDialogSpec.Row,
        val filterSnapshot: Set<String> = emptySet()
    )

    companion object {
        const val TYPE_TEXT = 0
        const val TYPE_INPUT = 1
        const val TYPE_SELECT = 2
        const val TYPE_TOGGLE = 3
    }

    private object RowDiff : DiffUtil.ItemCallback<DisplayRow>() {
        override fun areItemsTheSame(a: DisplayRow, b: DisplayRow): Boolean = a.id == b.id && a.type == b.type
        override fun areContentsTheSame(a: DisplayRow, b: DisplayRow): Boolean = a == b
    }

    private inner class InnerAdapter(
        private val context: Context,
        private val density: Float,
        private var palette: Palette,
        private val state: InteractiveState
    ) : ListAdapter<DisplayRow, RecyclerView.ViewHolder>(AsyncDifferConfig.Builder(RowDiff).setBackgroundThreadExecutor { r -> r.run() }.build()) {
        private val filteredTags = mutableSetOf<String>()
        fun clearFilteredTags() = filteredTags.clear()
        fun addFilteredTag(tag: String) { if (tag.isNotEmpty()) filteredTags.add(tag) }
        fun currentFilterSnapshot(): Set<String> = filteredTags.toSet()

        override fun getItemViewType(position: Int): Int = getItem(position).type

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                TYPE_INPUT -> InputVH(inputRowView(parent))
                TYPE_SELECT -> SelectVH(selectRowView(parent))
                TYPE_TOGGLE -> ToggleVH(toggleRowView(parent))
                else -> TextVH(textRowView(parent))
            }
        }

        private fun textRowView(parent: ViewGroup): View {
            val wrap = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            }
            val tv = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                setTextColor(palette.onSurface)
                textSize = 12f
            }
            wrap.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            return wrap
        }

        private fun inputRowView(parent: ViewGroup): View {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
                setPadding(0, 0, 0, (8 * density).toInt())
            }
            val label = TextView(context).apply { typeface = Typeface.MONOSPACE; setTextColor(palette.onSurfaceVariant); textSize = 11f }
            val edit = EditText(context).apply {
                setTextColor(palette.onSurface)
                textSize = 13f
                background = GradientDrawable().apply { setColor(palette.inputBg); cornerRadius = (6 * density).toFloat() }
                setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            }
            container.addView(label)
            container.addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            return container
        }

        private fun selectRowView(parent: ViewGroup): View {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
                setPadding(0, 0, 0, (8 * density).toInt())
            }
            val label = TextView(context).apply { typeface = Typeface.MONOSPACE; setTextColor(palette.onSurfaceVariant); textSize = 11f }
            val options = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            container.addView(label)
            container.addView(options)
            return container
        }

        private fun toggleRowView(parent: ViewGroup): View {
            val container = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT); setPadding(0, 0, 0, (8 * density).toInt()) }
            val label = TextView(context).apply { typeface = Typeface.MONOSPACE; setTextColor(palette.onSurfaceVariant); textSize = 11f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            val pill = TextView(context).apply {
                typeface = Typeface.MONOSPACE; textSize = 12f
                setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
                background = GradientDrawable().apply { setColor(palette.inputBg); cornerRadius = (6 * density).toFloat() }
            }
            container.addView(label)
            container.addView(pill)
            return container
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = getItem(position)
            when (holder) {
                is TextVH -> holder.bind(item)
                is InputVH -> holder.bind(item)
                is SelectVH -> holder.bind(item)
                is ToggleVH -> holder.bind(item)
            }
        }

        inner class TextVH(private val wrap: View) : RecyclerView.ViewHolder(wrap) {
            private val tv: TextView = (wrap as LinearLayout).getChildAt(0) as TextView
            fun bind(item: DisplayRow) {
                val row = item.row as ScriptDialogSpec.Row.Text
                val text = row.row.text
                tv.text = if (text.contains('\u001b')) ansiSpannable(text, palette.onSurface) else text
                tv.setTextColor(palette.onSurface)
            }
        }

        inner class InputVH(private val container: View) : RecyclerView.ViewHolder(container) {
            private val label: TextView = (container as LinearLayout).getChildAt(0) as TextView
            private val edit: EditText = ((container as LinearLayout).getChildAt(1) as EditText)
            fun bind(item: DisplayRow) {
                val row = (item.row as ScriptDialogSpec.Row.Input).row
                label.text = row.label
                label.setTextColor(palette.onSurfaceVariant)
                // 保留已输入文本
                val existing = state.input[row.key]
                if (existing !== edit) state.input[row.key] = edit
                if (!edit.isFocused) {
                    val keep = edit.text.toString()
                    // 若 default 与当前空文本，填 default
                    if (keep.isEmpty() && row.default.isNotEmpty()) edit.setText(row.default)
                }
                // 尊重 multiline：多行不锁单行，单行自适应高度
                edit.isSingleLine = !row.multiline
                edit.inputType = if (row.password) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else if (row.multiline) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                } else {
                    InputType.TYPE_CLASS_TEXT
                }
                edit.hint = row.default
                edit.setTextColor(palette.onSurface)
                (edit.background as? GradientDrawable)?.setColor(palette.inputBg)
            }
        }

        inner class SelectVH(private val container: View) : RecyclerView.ViewHolder(container) {
            private val label: TextView = (container as LinearLayout).getChildAt(0) as TextView
            private val options: LinearLayout = (container as LinearLayout).getChildAt(1) as LinearLayout
            fun bind(item: DisplayRow) {
                val row = (item.row as ScriptDialogSpec.Row.Select).row
                label.text = row.label
                label.setTextColor(palette.onSurfaceVariant)
                val sel = state.select.getOrPut(row.key) { mutableListOf() }
                val hasFilter = row.options.any { it.contains('#') }
                val metas = row.options.map { opt ->
                    val idx = opt.lastIndexOf('#')
                    if (hasFilter && idx > 0 && idx < opt.length - 1) OptionMeta(opt.substring(0, idx), opt.substring(idx + 1)) else OptionMeta(opt, null)
                }
                options.removeAllViews()
                val active = item.filterSnapshot
                for (m in metas) {
                    if (m.tag != null && active.isNotEmpty() && m.tag !in active) continue
                    val checked = m.label in sel
                    val tv = TextView(context).apply {
                        text = if (row.multi) { if (checked) "[x] ${m.label}" else "[ ] ${m.label}" } else { if (checked) "● ${m.label}" else "○ ${m.label}" }
                        typeface = Typeface.MONOSPACE
                        setTextColor(if (checked) palette.accent else palette.onSurface)
                        textSize = 12f
                        setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                        setOnClickListener {
                            if (row.multi) {
                                if (sel.contains(m.label)) sel.remove(m.label) else sel.add(m.label)
                            } else {
                                sel.clear(); sel.add(m.label)
                            }
                            // 模型驱动：重建当前列表（仅该行内容变化 → Diff 触发该行 Change）
                            val pos = bindingAdapterPosition
                            if (pos != RecyclerView.NO_POSITION && pos in 0 until itemCount) {
                                notifyItemChanged(pos, 1)
                            }
                        }
                    }
                    val wrap = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding((8 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt()); addView(tv) }
                    options.addView(wrap)
                }
            }
        }

        inner class ToggleVH(private val container: View) : RecyclerView.ViewHolder(container) {
            private val label: TextView = (container as LinearLayout).getChildAt(0) as TextView
            private val pill: TextView = (container as LinearLayout).getChildAt(1) as TextView
            fun bind(item: DisplayRow) {
                val row = (item.row as ScriptDialogSpec.Row.Toggle).row
                label.text = row.label
                label.setTextColor(palette.onSurfaceVariant)
                val cur = state.toggle[row.key] ?: row.def
                state.toggle[row.key] = cur
                pill.text = if (cur) "[ON]" else "[OFF]"
                pill.setTextColor(if (cur) palette.accent else palette.onSurfaceVariant)
                pill.background = GradientDrawable().apply { setColor(palette.inputBg); cornerRadius = (6 * density).toFloat() }
                pill.setOnClickListener {
                    val next = !(state.toggle[row.key] ?: false)
                    state.toggle[row.key] = next
                    pill.text = if (next) "[ON]" else "[OFF]"
                    pill.setTextColor(if (next) palette.accent else palette.onSurfaceVariant)
                    if (row.filter.isNotEmpty()) {
                        if (next) filteredTags.add(row.filter) else filteredTags.remove(row.filter)
                    }
                    // 模型驱动：重建列表（带 filterSnapshot）+ submitList（DiffUtil 只触发 select 行 Change）
                    val newList = currentList.map { it.copy(filterSnapshot = filteredTags.toSet()) }
                    submitList(newList)
                }
            }
        }
    }

    private fun collectValues(state: InteractiveState): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for ((k, edit) in state.input) out[k] = edit.text.toString()
        for ((k, v) in state.select) {
            out[k] = if (v.size == 1) v[0] else {
                val arr = MiniJson.Arr()
                for (item in v) arr.put(item)
                arr.toString()
            }
        }
        for ((k, v) in state.toggle) out[k] = if (v) "1" else "0"
        return out
    }

    // ===== 子视图（保留供 buildCard 初建标题等复用） =====

    private fun titleBar(title: String, style: ScriptDialogSpec.Style, palette: Palette): TextView {
        val titleSize = 14f
        val dash = "─"
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
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
            setPadding((8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }
    }

    private fun textBlock(text: String, palette: Palette): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(palette.onSurface)
        textSize = 13f
        setPadding(0, 0, 0, (8 * d).toInt())
    }

    private fun textOutputView(text: String, palette: Palette): View {
        val tv = TextView(ctx).apply {
            this.text = if (text.contains('\u001b')) ansiSpannable(text, palette.onSurface) else text
            typeface = Typeface.MONOSPACE
            setTextColor(palette.onSurface)
            textSize = 12f
        }
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        wrap.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    private fun inputRow(row: ScriptDialogSpec.InputRow, views: MutableMap<String, EditText>, palette: Palette): View {
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, (8 * d).toInt()) }
        container.addView(TextView(ctx).apply { text = row.label; typeface = Typeface.MONOSPACE; setTextColor(palette.onSurfaceVariant); textSize = 11f })
        val edit = EditText(ctx).apply {
            hint = row.default
            isSingleLine = !row.multiline
            if (row.password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(palette.onSurface)
            textSize = 13f
            background = GradientDrawable().apply { setColor(palette.inputBg); cornerRadius = (6 * d).toFloat() }
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            if (row.default.isNotEmpty()) setText(row.default)
        }
        container.addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        views[row.key] = edit
        return container
    }

    private fun selectRow(row: ScriptDialogSpec.SelectRow, values: MutableMap<String, MutableList<String>>, palette: Palette, hasFilter: Boolean): View {
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, (8 * d).toInt()) }
        container.addView(TextView(ctx).apply { text = row.label; typeface = Typeface.MONOSPACE; setTextColor(palette.onSurfaceVariant); textSize = 11f })
        val selected = mutableListOf<String>()
        values[row.key] = selected
        val meta = row.options.map { if (hasFilter) splitTag(it) else OptionMeta(it, null) }
        val fs = FilteredSelect(row.key, container, row.multi, meta, palette, values)
        // 旧逻辑保留兼容，但新 Adapter 已接管
        return container
    }

    private fun splitTag(opt: String): OptionMeta {
        val idx = opt.lastIndexOf('#')
        if (idx > 0 && idx < opt.length - 1) return OptionMeta(opt.substring(0, idx), opt.substring(idx + 1))
        return OptionMeta(opt, null)
    }

    private class FilteredSelect(val key: String, val container: LinearLayout, val multi: Boolean, val meta: List<OptionMeta>, val palette: Palette, val values: MutableMap<String, MutableList<String>>)
    private class FilterToggle(val key: String, val tag: String, var on: Boolean, val pill: TextView, val palette: Palette)

    private fun toggleRow(row: ScriptDialogSpec.ToggleRow, values: MutableMap<String, Boolean>, palette: Palette): View {
        values[row.key] = row.def
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, (8 * d).toInt()) }
        container.addView(TextView(ctx).apply { text = row.label; typeface = Typeface.MONOSPACE; setTextColor(palette.onSurfaceVariant); textSize = 11f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        val toggle = TextView(ctx).apply {
            text = if (row.def) "[ON]" else "[OFF]"
            typeface = Typeface.MONOSPACE
            setTextColor(if (row.def) palette.accent else palette.onSurfaceVariant)
            textSize = 12f
            setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
            background = GradientDrawable().apply { setColor(palette.inputBg); cornerRadius = (6 * d).toFloat() }
        }
        container.addView(toggle)
        return container
    }

    private fun buttonBar(buttons: List<ScriptDialogSpec.Button>?, palette: Palette, onClick: (String) -> Unit): View {
        val list = if (buttons.isNullOrEmpty()) listOf(ScriptDialogSpec.Button(ctx.getString(R.string.close), ScriptDialogSpec.RESULT_ID_DISMISS)) else buttons
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (12 * d).toInt()) }
        for (b in list) {
            val bg = when (b.kind) { ScriptDialogSpec.BTN_DANGER -> palette.danger; ScriptDialogSpec.BTN_PRIMARY -> palette.accent; else -> palette.buttonNormal }
            val btn = Button(ctx).apply {
                text = b.text; isAllCaps = true; setTextColor(palette.onButton); textSize = 13f
                setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                setOnClickListener { onClick(b.id) }
                background = GradientDrawable().apply { setColor(bg); cornerRadius = (8 * d).toFloat() }
            }
            bar.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (6 * d).toInt(); marginEnd = (6 * d).toInt() })
        }
        return bar
    }

    private fun ansiSpannable(text: String, defaultFg: Int): CharSequence {
        val parsed = AnsiParser.parse(text)
        val sp = SpannableString(parsed.clean)
        for (s in parsed.spans) {
            if (s.end <= s.start) continue
            val fg = if (s.inverse) (s.bg ?: defaultFg) else (s.fg ?: defaultFg)
            sp.setSpan(ForegroundColorSpan(fg), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (s.bg != null && !s.inverse) sp.setSpan(BackgroundColorSpan(s.bg), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (s.bold) sp.setSpan(StyleSpan(Typeface.BOLD), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (s.italic) sp.setSpan(StyleSpan(Typeface.ITALIC), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (s.underline) sp.setSpan(UnderlineSpan(), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }

    fun screenWidthPx(widthPct: Int): Int {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val pct = widthPct.coerceIn(40, 100)
        return (metrics.widthPixels * pct / 100).coerceAtMost(metrics.widthPixels - (16 * d).toInt() * 2)
    }

    private fun paletteOf(style: ScriptDialogSpec.Style): Palette {
        val accent = style.accent ?: when (style.theme) {
            ScriptDialogSpec.THEME_LIGHT -> 0xFF2D7D46.toInt()
            ScriptDialogSpec.THEME_GLASS -> 0xFF4DD0E1.toInt()
            else -> 0xFF2D7D46.toInt()
        }
        return when (style.theme) {
            ScriptDialogSpec.THEME_LIGHT -> Palette(0xFFF5F5F5.toInt(), 0xFFCCCCCC.toInt(), 0xFF111111.toInt(), 0xFF666666.toInt(), accent, 0xFFC0392B.toInt(), 0xFFDDDDDD.toInt(), 0xFF111111.toInt(), 0xFFFFFFFF.toInt())
            ScriptDialogSpec.THEME_GLASS -> Palette(0xE61E1E1E.toInt(), 0x55FFFFFF.toInt(), 0xFFE6E6E6.toInt(), 0xFF9A9A9A.toInt(), accent, 0xFFFF5252.toInt(), 0xAA333333.toInt(), 0xFFE6E6E6.toInt(), 0x55333333.toInt())
            else -> Palette(0xFF1E1E1E.toInt(), 0xFF3A3A3A.toInt(), 0xFFE6E6E6.toInt(), 0xFF9A9A9A.toInt(), accent, 0xFFC0392B.toInt(), 0xFF3A3A3A.toInt(), 0xFFE6E6E6.toInt(), 0xFF2A2A2A.toInt())
        }
    }

    private data class Palette(val surface: Int, val border: Int, val onSurface: Int, val onSurfaceVariant: Int, val accent: Int, val danger: Int, val buttonNormal: Int, val onButton: Int, val inputBg: Int)
}
