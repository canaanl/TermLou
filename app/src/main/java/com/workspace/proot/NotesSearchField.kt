@file:Suppress("MagicNumber")

package com.workspace.proot

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 笔记搜索框：外框仍是全站统一的描边输入框，内部上方放已选标签胶囊，
 * 下方保持普通文字输入。
 *
 * 胶囊不是普通文字——点选标签后它作为**标签字段过滤**存在，底层查询串由
 * [NoteQuery.compose] 生成 `tag:标签 ...`；点胶囊即移除该过滤条件。
 */
class NotesSearchField(context: Context, private val theme: ThemeColors) : LinearLayout(context) {

    val input: EditText

    private val chipRow: LinearLayout
    private val chipScroll: HorizontalScrollView
    private val selected = LinkedHashSet<String>()
    private var suppressTextCallback = false

    /** true = 标签胶囊变了（立即刷新）；false = 文字输入（交给页面防抖）。 */
    var onQueryChanged: ((Boolean) -> Unit)? = null

    init {
        orientation = VERTICAL
        isFocusable = false
        isClickable = true
        setPaddingMd(8, 6, 8, 6)
        FieldStyle.applyOutlinedContainer(this, theme)

        chipRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPaddingMd(0, 0, 0, 4)
        }
        chipScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            visibility = GONE
            addView(
                chipRow,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        addView(
            chipScroll,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        input = EditText(context).apply {
            hint = context.getString(R.string.notes_search_notes)
            setSingleLine(true)
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(theme.onSurface)
            setHintTextColor(FieldStyle.hintColor(theme))
            textSize = UiTokens.TEXT_BODY
            minHeight = (36 * resources.displayMetrics.density).toInt()
            setPaddingMd(4, 4, 4, 4)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        addView(input)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!suppressTextCallback) onQueryChanged?.invoke(false)
            }
        })
        setOnClickListener { focusInput() }
    }

    fun focusInput() {
        input.requestFocus()
    }

    fun selectedTags(): List<String> = selected.toList()

    /** 点筛选行里的标签：未选中则加胶囊，已选中则移除。 */
    fun toggleTag(tag: String) {
        val clean = tag.trim().removePrefix("#").trim()
        if (clean.isEmpty()) return
        if (!selected.remove(clean)) selected.add(clean)
        rebuildChips()
        onQueryChanged?.invoke(true)
    }

    fun removeTag(tag: String) {
        if (selected.remove(tag)) {
            rebuildChips()
            onQueryChanged?.invoke(true)
        }
    }

    /** 外部刷新时剪掉已不存在的标签；保持选中顺序。 */
    fun setSelectedTags(tags: List<String>) {
        val clean = tags.map { it.trim().removePrefix("#").trim() }.filter { it.isNotEmpty() }
        if (selected.toList() == clean) return
        selected.clear()
        selected.addAll(clean)
        rebuildChips()
        onQueryChanged?.invoke(true)
    }

    fun clear() {
        suppressTextCallback = true
        input.setText("")
        suppressTextCallback = false
        selected.clear()
        rebuildChips()
        onQueryChanged?.invoke(true)
    }

    fun query(): String = NoteQuery.compose(selected.toList(), input.text?.toString().orEmpty())

    private fun rebuildChips() {
        chipRow.removeAllViews()
        if (selected.isEmpty()) {
            chipScroll.visibility = GONE
            return
        }
        chipScroll.visibility = VISIBLE
        val d = resources.displayMetrics.density
        for (tag in selected) {
            val (fill, edge) = tagCapsuleColors(tag)
            chipRow.addView(TextView(context).apply {
                text = "#$tag ×"
                setTextColor(TAG_TEXT_COLOR)
                textMd(UiTokens.TEXT_COMPACT, UiTokens.LINE_COMPACT)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPaddingMd(10, 3, 10, 3)
                background = GradientDrawable().apply {
                    cornerRadius = snapCorner(UiTokens.CORNER_MD) * d
                    setStroke((1 * d).toInt().coerceAtLeast(1), edge)
                    setColor(fill)
                }
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (6 * d).toInt()
                }
                contentDescription = context.getString(R.string.notes_tag_filter_remove_fmt, tag)
                setOnClickListener {
                    removeTag(tag)
                    focusInput()
                }
            })
        }
    }
}
