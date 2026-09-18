package com.workspace.proot

import android.app.AlertDialog
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 待办页：顶部输入新增；行内点框切换完成、点文字改内容、长按删除；
 * 底部 [已完成 n][未完成 n] 并排过滤，再点一次回到全部。变更即落盘。
 */
class NotesTodoActivity : NotesPageActivity() {

    /** null = 全部，true = 只看已完成，false = 只看未完成。 */
    private var filter: Boolean? = null

    private lateinit var listInner: LinearLayout
    private lateinit var doneBtn: android.widget.Button
    private lateinit var openBtn: android.widget.Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = density()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
        }
        root.addView(buildTitleBar(getString(R.string.notes_todo_tab)))
        root.addView(buildInputRow())
        val scrolled = scrollColumn()
        listInner = scrolled.second
        listInner.setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
        root.addView(
            scrolled.first,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        )
        root.addView(buildFilterBar())
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        store.reload()
        render()
    }

    private fun buildInputRow(): LinearLayout {
        val d = density()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (6 * d).toInt())
            val input = EditText(this@NotesTodoActivity).apply {
                hint = getString(R.string.notes_todo_hint)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                FieldStyle.applyOutlined(this, theme.outline, theme.onSurface, theme.onSurfaceVariant, UiTokens.TEXT_BODY)
                setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
                imeOptions = EditorInfo.IME_ACTION_DONE
                setOnEditorActionListener { v, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        addFromInput(v as EditText)
                        true
                    } else false
                }
            }
            addView(input)
            addView(toolButton(getString(R.string.notes_add)) { addFromInput(input) }.apply {
                layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { weight = 0f }
            })
        }
    }

    private fun addFromInput(input: EditText) {
        if (store.addTodo(input.text?.toString().orEmpty()) != null) {
            input.setText("")
            render()
        }
    }

    private fun buildFilterBar(): LinearLayout {
        val d = density()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.surfaceVariant)
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
        }
        doneBtn = segButton("") { filter = if (filter == true) null else true; render() }
        openBtn = segButton("") { filter = if (filter == false) null else false; render() }
        row.addView(doneBtn)
        row.addView(openBtn)
        return row
    }

    private fun render() {
        val (done, open) = store.todoCounts()
        doneBtn.text = getString(R.string.notes_done_fmt, done)
        openBtn.text = getString(R.string.notes_open_fmt, open)
        val bar = doneBtn.parent as LinearLayout
        SegmentStyle.applyRow(
            bar,
            listOf(
                if (filter == true) SegmentStyle.Fill(theme.primary, theme.onPrimary) else null,
                if (filter == false) SegmentStyle.Fill(theme.primary, theme.onPrimary) else null
            ),
            theme.outline,
            theme.onSurface
        )
        listInner.removeAllViews()
        val items = store.todos().filter { filter == null || it.done == filter }
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
            listInner.addView(divider())
        }
    }

    private fun todoRow(item: TodoItem): LinearLayout {
        val d = density()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            addView(TextView(this@NotesTodoActivity).apply {
                text = if (item.done) "☑" else "☐"
                setTextColor(if (item.done) theme.primary else theme.onSurface)
                textSize = UiTokens.TEXT_TITLE
                setPadding(0, 0, (10 * d).toInt(), 0)
                setOnClickListener {
                    store.setTodoDone(item.id, !item.done)
                    render()
                }
            })
            addView(TextView(this@NotesTodoActivity).apply {
                text = item.text
                setTextColor(if (item.done) theme.onSurfaceVariant else theme.onSurface)
                textSize = UiTokens.TEXT_BODY
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (item.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                setOnClickListener { editTodoDialog(item) }
            })
            setOnLongClickListener { confirmDeleteTodo(item); true }
        }
    }

    private fun editTodoDialog(item: TodoItem) {
        inputDialog(
            getString(R.string.notes_edit_todo),
            getString(R.string.notes_todo_hint),
            item.text,
            getString(R.string.save)
        ) { raw ->
            store.setTodoText(item.id, raw)
            render()
        }
    }

    private fun confirmDeleteTodo(item: TodoItem) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.notes_confirm_delete_fmt, item.text))
            .setPositiveButton(getString(R.string.notes_delete)) { _, _ ->
                store.deleteTodo(item.id)
                render()
                showHint(getString(R.string.notes_deleted))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
    }
}
