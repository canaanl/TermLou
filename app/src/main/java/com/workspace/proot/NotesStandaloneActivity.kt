package com.workspace.proot

import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 独立笔记页（磁贴直起）：顶部无返回标题栏 + 列表态 / 编辑态，
 * 与主界面笔记 tab 外观交互一致，但实现与 NotesController 完全解耦、
 * 且全程不碰 Linux（只用 NotesStore 纯文件读写）。
 * 列表态按返回直接 finish（回到桌面）；编辑态按返回退回列表。
 * 退出保存：退列表/切笔记/onPause 前比对落盘一次。
 */
class NotesStandaloneActivity : NotesPageActivity() {

    private var current: String? = null

    private lateinit var listScroll: ScrollView
    private lateinit var listInner: LinearLayout
    private lateinit var editorBox: LinearLayout
    private lateinit var navRow: LinearLayout
    private lateinit var editRow: LinearLayout
    private lateinit var fab: TextView
    private lateinit var editorTitle: TextView
    private lateinit var tagRow: TagFlowLayout
    private lateinit var todoBox: LinearLayout
    private lateinit var body: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
        }
        root.addView(buildTitleBar(getString(R.string.notes_standalone_title), showBack = false))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val scrolled = scrollColumn()
        listScroll = scrolled.first
        listInner = scrolled.second
        content.addView(
            listScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        )
        editorBox = buildEditorBox()
        content.addView(
            editorBox,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        )
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f }
            addView(content)
            fab = buildFab()
            addView(fab)
        }
        root.addView(frame)
        root.addView(divider())
        navRow = buildNavRow()
        editRow = buildEditRow()
        root.addView(navRow)
        root.addView(editRow)
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (current != null) {
                    showList()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        showList()
        handleOpenExtra(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenExtra(intent)
    }

    private fun handleOpenExtra(intent: Intent?) {
        val name = intent?.getStringExtra(EXTRA_OPEN) ?: return
        openNote(name)
    }

    override fun onResume() {
        super.onResume()
        store.reload()
        val cur = current
        if (cur != null && store.listNotes().none { it.name == cur }) {
            current = null
            showList()
        } else if (cur != null) {
            refreshEditorTitle()
            refreshTagRow()
            refreshTodoBox()
        } else {
            renderList()
        }
    }

    override fun onPause() {
        super.onPause()
        flushPendingSave()
    }

    /** 退出保存：正文与落盘不一致才写回一次。退列表/切笔记/onPause 前调用。 */
    fun flushPendingSave() {
        val cur = current ?: return
        val text = body.text?.toString().orEmpty()
        if (text != store.readNote(cur)) {
            store.saveNote(cur, text)
        }
    }

    private fun showList() {
        flushPendingSave()
        current = null
        fab.visibility = View.VISIBLE
        navRow.visibility = View.VISIBLE
        editRow.visibility = View.GONE
        editorBox.visibility = View.GONE
        listScroll.visibility = View.VISIBLE
        renderList()
    }

    fun openNote(name: String) {
        flushPendingSave()
        if (store.listNotes().none { it.name == name }) {
            showList()
            return
        }
        current = name
        body.setText(store.readNote(name))
        refreshEditorTitle()
        refreshTagRow()
        refreshTodoBox()
        listScroll.visibility = View.GONE
        editorBox.visibility = View.VISIBLE
        fab.visibility = View.GONE
        navRow.visibility = View.GONE
        editRow.visibility = View.VISIBLE
    }

    private fun buildEditorBox(): LinearLayout {
        val d = density()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        editorTitle = TextView(this).apply {
            setTextColor(theme.onSurface)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_TITLE
            gravity = Gravity.CENTER
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        box.addView(editorTitle)
        tagRow = TagFlowLayout(this).apply {
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), (4 * d).toInt())
        }
        val tagScroll = MaxHeightScrollView(this).apply {
            maxHeight = resources.displayMetrics.heightPixels / TAG_AREA_DIVISOR
            isVerticalScrollBarEnabled = false
            addView(tagRow)
        }
        box.addView(
            tagScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        todoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        box.addView(todoBox)
        body = EditText(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(theme.onSurface)
            setHintTextColor(theme.onSurfaceVariant)
            textSize = UiTokens.TEXT_BODY
            gravity = Gravity.TOP or Gravity.START
            setPadding((16 * d).toInt(), (6 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f }
        }
        box.addView(body)
        return box
    }

    private fun buildNavRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.surface)
            setPadding(4, 0, 4, 0)
            addView(segButton(getString(R.string.notes_todo_tab)) {
                flushPendingSave()
                startActivity(Intent(this@NotesStandaloneActivity, NotesTodoActivity::class.java))
            })
            addView(segButton(getString(R.string.notes_tags_tab)) {
                flushPendingSave()
                startActivity(
                    Intent(this@NotesStandaloneActivity, NotesTagsActivity::class.java)
                        .putExtra(EXTRA_FROM_STANDALONE, true)
                )
            })
        }
        SegmentStyle.applyRow(
            row,
            listOf(null, null),
            theme.outline,
            theme.onSurface,
            SegmentStyle.Bar(0f, false)
        )
        return row
    }

    private fun buildEditRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.surface)
            setPadding(4, 0, 4, 0)
            addView(segButton(getString(R.string.notes_insert_todo)) {
                showTodoDialog()
            })
            addView(segButton(getString(R.string.notes_insert_tag)) {
                current?.let { showTagDialog(it) }
            })
            visibility = View.GONE
        }
        SegmentStyle.applyRow(
            row,
            listOf(null, null),
            theme.outline,
            theme.onSurface,
            SegmentStyle.Bar(0f, false)
        )
        return row
    }

    private fun buildFab(): TextView {
        val d = density()
        val size = (56 * d).toInt()
        val shadow = (NEU_SHADOW_DP * d).toInt()
        val face = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.surface)
        }
        val darkShadow = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(mixColor(theme.surface, 0xFF000000.toInt(), NEU_DARK_RATIO))
        }
        val lightShadow = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(mixColor(theme.surface, 0xFFFFFFFF.toInt(), NEU_LIGHT_RATIO))
        }
        return TextView(this).apply {
            text = "＋"
            gravity = Gravity.CENTER
            setTextColor(theme.primary)
            textSize = FAB_PLUS_SP
            background = android.graphics.drawable.LayerDrawable(
                arrayOf(darkShadow, lightShadow, face)
            ).apply {
                setLayerInset(0, shadow, shadow, 0, 0)
                setLayerInset(1, 0, 0, shadow, shadow)
            }
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                marginEnd = (16 * d).toInt()
                bottomMargin = (16 * d).toInt()
            }
            elevation = (6 * d)
            contentDescription = getString(R.string.notes_new)
            setOnClickListener { onNewPressed() }
        }
    }

    private fun mixColor(base: Int, target: Int, ratio: Float): Int {
        val r = (android.graphics.Color.red(base) * (1f - ratio) +
            android.graphics.Color.red(target) * ratio).toInt()
        val g = (android.graphics.Color.green(base) * (1f - ratio) +
            android.graphics.Color.green(target) * ratio).toInt()
        val b = (android.graphics.Color.blue(base) * (1f - ratio) +
            android.graphics.Color.blue(target) * ratio).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun refreshEditorTitle() {
        val cur = current ?: return
        editorTitle.text = cur
    }

    private fun refreshTagRow() {
        val cur = current ?: return
        tagRow.removeAllViews()
        for (tag in store.tagsOf(cur)) {
            tagRow.addView(tagCapsule(tag) { removeTagEverywhere(cur, tag) })
        }
    }

    private fun removeTagEverywhere(noteName: String, tag: String) {
        store.detachTag(noteName, tag)
        val before = body.text?.toString().orEmpty()
        val token = Regex("(?<![\\p{L}\\p{N}_])#" + Regex.escape(tag) + "(?![\\p{L}\\p{N}_])")
        val after = token.replace(before, "")
        if (after != before) {
            body.setText(after)
            store.saveNote(noteName, after)
        }
        refreshTagRow()
        refreshTodoBox()
        NoteNotify.post("Notes | ${getString(R.string.notes_tag_removed)}「$tag」")
    }

    private fun renderList() {
        listInner.removeAllViews()
        val notes = store.listNotes()
        if (notes.isEmpty()) {
            listInner.addView(TextView(this).apply {
                text = getString(R.string.notes_empty_list)
                setTextColor(theme.onSurfaceVariant)
                textSize = UiTokens.TEXT_BODY
                gravity = Gravity.CENTER
                val d = density()
                setPadding(0, (48 * d).toInt(), 0, 0)
            })
            return
        }
        for (entry in notes) {
            listInner.addView(
                noteRow(entry) { openNote(entry.name) }.apply {
                    setOnLongClickListener { showRenameDialog(entry.name); true }
                }
            )
            listInner.addView(divider())
        }
    }

    private fun noteRow(entry: NoteEntry, onClick: () -> Unit): LinearLayout {
        val d = density()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            addView(LinearLayout(this@NotesStandaloneActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@NotesStandaloneActivity).apply {
                    text = entry.name
                    setTextColor(theme.onSurface)
                    textSize = UiTokens.TEXT_BODY
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                addView(TextView(this@NotesStandaloneActivity).apply {
                    text = ROW_FMT.format(Date(entry.updatedAt))
                    setTextColor(theme.onSurfaceVariant)
                    textSize = UiTokens.TEXT_META
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = (8 * d).toInt() }
                })
            })
            if (entry.tags.isNotEmpty()) {
                addView(TagFlowLayout(this@NotesStandaloneActivity).apply {
                    setPadding(0, (4 * d).toInt(), 0, 0)
                    for (tag in entry.tags) {
                        addView(tagCapsule(tag) { onClick() })
                    }
                })
            }
            setOnClickListener { onClick() }
        }
    }

    private fun onNewPressed() {
        singleLineInputDialog(
            getString(R.string.notes_new),
            getString(R.string.notes_name_hint),
            "",
            getString(R.string.notes_new)
        ) { raw -> openNote(store.createNote(raw)) }
    }

    private fun showRenameDialog(old: String) {
        singleLineInputDialog(
            getString(R.string.notes_rename),
            getString(R.string.notes_name_hint),
            old,
            getString(R.string.save)
        ) { raw ->
            flushPendingSave()
            val name = store.renameNote(old, raw)
            if (current == old) {
                current = name
                refreshEditorTitle()
                refreshTagRow()
                refreshTodoBox()
            }
            if (current == null) renderList()
            NoteNotify.post("Notes | ${getString(R.string.notes_renamed)}「$name」")
        }
    }

    /** 挂标签：只写 header 索引，不再往正文插入 #标签。 */
    private fun showTagDialog(noteName: String) {
        val d = density()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        }
        val input = EditText(this).apply {
            hint = getString(R.string.notes_tag_hint)
            setSingleLine(true)
            FieldStyle.applyOutlined(this, theme, UiTokens.TEXT_BODY)
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        }
        box.addView(input)
        val existing = store.allTags().map { it.first } - store.tagsOf(noteName).toSet()
        if (existing.isNotEmpty()) {
            box.addView(TextView(this).apply {
                text = getString(R.string.notes_common_tags)
                setTextColor(theme.onSurfaceVariant)
                textSize = UiTokens.TEXT_META
                setPadding(0, (12 * d).toInt(), 0, (4 * d).toInt())
            })
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (tag in existing) {
                chips.addView(tagCapsule(tag) {
                    val cur = input.text?.toString().orEmpty()
                    val sep = if (cur.isBlank()) "" else " "
                    input.setText("$cur$sep$tag")
                    input.setSelection(input.text?.length ?: 0)
                })
            }
            val scroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            }
            box.addView(scroll)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_add_tags))
            .setView(box)
            .setPositiveButton(getString(R.string.notes_add_tags)) { _, _ ->
                val picked = parseTags(input.text?.toString().orEmpty())
                if (picked.isEmpty()) return@setPositiveButton
                store.attachTags(noteName, picked)
                if (current == noteName) refreshTagRow()
                if (current == null) renderList()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
    }

    /** 加待办：只进本笔记 header，不再往正文插入。 */
    private fun showTodoDialog() {
        val cur = current ?: return
        singleLineInputDialog(
            getString(R.string.notes_add_todo),
            getString(R.string.notes_todo_hint),
            "",
            getString(R.string.notes_add_todo)
        ) { raw ->
            if (store.addTodo(raw, cur) != null) refreshTodoBox()
        }
    }

    private fun refreshTodoBox() {
        val cur = current ?: return
        todoBox.removeAllViews()
        val items = store.todosOf(cur)
        todoBox.visibility =
            if (items.isEmpty()) View.GONE else View.VISIBLE
        for (item in items) {
            todoBox.addView(noteTodoRow(item))
        }
    }

    private fun noteTodoRow(item: TodoItem): LinearLayout {
        val d = density()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (6 * d).toInt(), (16 * d).toInt(), (6 * d).toInt())
            addView(TextView(this@NotesStandaloneActivity).apply {
                text = if (item.done) "☑" else "☐"
                setTextColor(if (item.done) theme.primary else theme.onSurface)
                textSize = UiTokens.TEXT_TITLE
                setPadding(0, 0, (10 * d).toInt(), 0)
                setOnClickListener { toggleTodo(item) }
            })
            addView(TextView(this@NotesStandaloneActivity).apply {
                text = item.text
                setTextColor(if (item.done) theme.onSurfaceVariant else theme.onSurface)
                textSize = UiTokens.TEXT_BODY
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (item.done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                setOnClickListener { editTodoText(item) }
            })
            setOnClickListener { toggleTodo(item) }
            setOnLongClickListener { confirmDeleteNoteTodo(item); true }
        }
    }

    private fun toggleTodo(item: TodoItem) {
        store.setTodoDone(item.id, !item.done)
        refreshTodoBox()
    }

    private fun editTodoText(item: TodoItem) {
        singleLineInputDialog(
            getString(R.string.notes_edit_todo),
            getString(R.string.notes_todo_hint),
            item.text,
            getString(R.string.save)
        ) { raw ->
            store.setTodoText(item.id, raw)
            refreshTodoBox()
        }
    }

    private fun confirmDeleteNoteTodo(item: TodoItem) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.notes_confirm_delete_fmt, item.text))
            .setPositiveButton(getString(R.string.notes_delete)) { _, _ ->
                store.deleteTodo(item.id)
                refreshTodoBox()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
    }

    private fun parseTags(raw: String): List<String> =
        raw.split(TAG_SPLIT).map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()

    /** 基类单输入弹窗的单行版（回车不换行，与主界面笔记 tab 一致）。 */
    private fun singleLineInputDialog(
        title: String,
        hint: String,
        initial: String,
        confirmText: String,
        onConfirm: (String) -> Unit
    ) {
        val d = density()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        }
        val input = EditText(this).apply {
            this.hint = hint
            setText(initial)
            setSelection(initial.length)
            setSingleLine(true)
            FieldStyle.applyOutlined(this, theme, UiTokens.TEXT_BODY)
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        }
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(confirmText) { _, _ -> onConfirm(input.text?.toString().orEmpty()) }
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
    }

    companion object {
        private val ROW_FMT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        private val TAG_SPLIT = Regex("[\\s,，、]+")
        private const val FAB_PLUS_SP = 28f
        private const val NEU_SHADOW_DP = 3
        private const val NEU_DARK_RATIO = 0.45f
        private const val NEU_LIGHT_RATIO = 0.18f
        private const val TAG_AREA_DIVISOR = 3
    }
}
