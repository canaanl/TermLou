package com.workspace.proot

import android.animation.LayoutTransition
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 待办页：顶部搜索框实时过滤；整行点按切换状态、长按删除；
 * 文字只能在所属笔记里改。底部 [已完成 n][未完成 n] 二选一，
 * 默认未完成（error 红填充）。变更即落盘。
 * 新增待办只在笔记编辑器的 [＋待办] 里做。
 */
class NotesTodoActivity : NotesPageActivity() {

    /** true = 只看已完成，false = 只看未完成（默认）。 */
    private var filter = false
    private var query = ""

    private lateinit var listInner: LinearLayout
    private lateinit var doneBtn: android.widget.Button
    private lateinit var openBtn: android.widget.Button
    private var transitionSeq = 0
    private val changeTransition = LayoutTransition().apply {
        enableTransitionType(LayoutTransition.CHANGE_DISAPPEARING)
        disableTransitionType(LayoutTransition.APPEARING)
        disableTransitionType(LayoutTransition.DISAPPEARING)
        disableTransitionType(LayoutTransition.CHANGE_APPEARING)
        disableTransitionType(LayoutTransition.CHANGING)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = density()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
        }
        root.addView(buildTitleBar(getString(R.string.notes_todo_tab)))
        root.addView(buildSearchRow())
        val scrolled = scrollColumn()
        listInner = scrolled.second
        listInner.setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
        root.addView(
            scrolled.first,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        )
        root.addView(divider())
        root.addView(buildFilterBar())
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        store.reload()
        render()
    }

    private fun buildSearchRow(): LinearLayout {
        val d = density()
        val input = EditText(this).apply {
            hint = getString(R.string.notes_search_todo)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            FieldStyle.applyOutlined(this, theme, UiTokens.TEXT_BODY)
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        }
        watchText(input) {
            query = input.text?.toString().orEmpty()
            render()
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (6 * d).toInt())
            addView(input)
        }
    }

    private fun buildFilterBar(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.surface)
            setPadding(4, 0, 4, 0)
        }
        doneBtn = segButton("") { filter = true; render() }
        openBtn = segButton("") { filter = false; render() }
        row.addView(doneBtn)
        row.addView(openBtn)
        return row
    }

    private fun render() {
        updateFilterBar()
        listInner.layoutTransition = null
        listInner.removeAllViews()
        val q = query.trim().lowercase()
        val items = store.todos().filter {
            it.done == filter && (q.isEmpty() || it.text.lowercase().contains(q))
        }
        if (items.isEmpty()) {
            listInner.addView(TextView(this).apply {
                text = getString(R.string.notes_todo_empty)
                setTextColor(theme.onSurfaceVariant)
                textSize = UiTokens.TEXT_BODY
                gravity = Gravity.CENTER
                val d = density()
                setPadding(0, (48 * d).toInt(), 0, 0)
            })
            return
        }
        for (item in items) {
            listInner.addView(todoRow(item))
            listInner.addView(divider().apply { tag = "div:" + item.id })
        }
    }

    private fun updateFilterBar() {
        val (done, open) = store.todoCounts()
        doneBtn.text = getString(R.string.notes_done_fmt, done)
        openBtn.text = getString(R.string.notes_open_fmt, open)
        SegmentStyle.applyRow(
            doneBtn.parent as LinearLayout,
            listOf(
                if (filter) SegmentStyle.Fill(theme.primary, theme.onPrimary) else null,
                if (!filter) SegmentStyle.Fill(theme.error, Color.WHITE) else null
            ),
            theme.outline,
            theme.onSurface,
            SegmentStyle.Bar(0f, false)
        )
    }

    private fun todoRow(item: TodoItem): LinearLayout {
        val d = density()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            tag = "row:" + item.id
            addView(TextView(this@NotesTodoActivity).apply {
                text = if (item.done) "☑" else "☐"
                setTextColor(if (item.done) theme.primary else theme.onSurface)
                textSize = UiTokens.TEXT_TITLE
                setPadding(0, 0, (10 * d).toInt(), 0)
            })
            addView(LinearLayout(this@NotesTodoActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@NotesTodoActivity).apply {
                    text = item.text
                    setTextColor(if (item.done) theme.onSurfaceVariant else theme.onSurface)
                    textSize = UiTokens.TEXT_BODY
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    if (item.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                })
                if (item.note.isNotEmpty()) {
                    addView(TextView(this@NotesTodoActivity).apply {
                        text = item.note
                        setTextColor(theme.onSurfaceVariant)
                        textSize = UiTokens.TEXT_META
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }
            })
            setOnClickListener { toggleTodoAnimated(this, item) }
            setOnLongClickListener { confirmDeleteTodo(item); true }
        }
    }

    /** 点按整行切换：本行向右飞出，下方行上滑补位（只摘除本行，不全量重绘）。 */
    private fun toggleTodoAnimated(row: LinearLayout, item: TodoItem) {
        row.isClickable = false
        row.animate().cancel()
        val distance = (listInner.width - row.left).toFloat().coerceAtLeast(row.width.toFloat())
        transitionSeq++
        val seq = transitionSeq
        row.animate()
            .translationX(distance)
            .alpha(0f)
            .setDuration(TOGGLE_ANIM_MS)
            .withEndAction {
                store.setTodoDone(item.id, !item.done)
                listInner.layoutTransition = changeTransition
                listInner.removeView(row)
                listInner.findViewWithTag<android.view.View>("div:" + item.id)?.let {
                    listInner.removeView(it)
                }
                updateFilterBar()
                if (listInner.childCount == 0) {
                    listInner.layoutTransition = null
                    render()
                } else {
                    listInner.postDelayed({
                        if (transitionSeq == seq) listInner.layoutTransition = null
                    }, TRANSITION_CLEAR_MS)
                }
            }
            .start()
    }

    private fun confirmDeleteTodo(item: TodoItem) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.notes_confirm_delete_fmt, item.text))
            .setPositiveButton(getString(R.string.notes_delete)) { _, _ ->
                store.deleteTodo(item.id)
                render()
                NoteNotify.post("Notes | ${getString(R.string.notes_deleted)}「${item.text}」")
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
    }

    companion object {
        private const val TOGGLE_ANIM_MS = 220L
        private const val TRANSITION_CLEAR_MS = 350L
    }
}
