package com.workspace.proot

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 笔记 tab 内容区（主界面第 2 位，可滑动切换）：列表态 / 编辑态。
 * 底部单排三键按状态切换，列表 [待办][标签] + 右下角新建 FAB，编辑 [＋待办][＋标签]。
 * 正文每次改动直写落盘，无保存键、无草稿。Dialog/跳转全部直调 MainActivity。
 */
class NotesController(
    private val activity: MainActivity,
    scope: AppScope,
    private val status: StatusController
) {

    private val theme: ThemeColors = scope.theme
    private val store: NotesStore =
        NotesStore(File(activity.filesDir, "workspace/Notes")).also { it.reload() }

    private var current: String? = null
    private var suppressWatch = false

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

    private fun density(): Float = activity.resources.displayMetrics.density

    fun buildInto(notesArea: LinearLayout) {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
        }
        val content = LinearLayout(activity).apply {
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
        val frame = FrameLayout(activity).apply {
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
        notesArea.addView(
            buildSwipe(root),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
        watchText(body) {
            val cur = current
            if (!suppressWatch && cur != null) {
                store.saveNote(cur, body.text?.toString().orEmpty())
            }
        }
        showList()
    }

    /** 横滑切换容器：左滑去网络，右滑编辑态退列表、列表态回文件。 */
    private fun buildSwipe(root: LinearLayout): TabSwipeLayout =
        TabSwipeLayout(
            activity,
            onSwipeRight = {
                if (activity.currentTab == 2 && !activity.isSetupVisible()) {
                    if (current != null) showList() else activity.showTab(1)
                }
            },
            onSwipeLeft = {
                if (activity.currentTab == 2 && !activity.isSetupVisible()) activity.showTab(3)
            }
        ).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                root,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

    /** 主界面 onResume 与切到本 tab 时都调：重载索引并刷新当前视图。 */
    fun refresh() {
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

    /** 返回键：编辑态退回列表并消费，否则放行。 */
    fun handleBack(): Boolean {
        if (activity.currentTab == 2 && current != null) {
            showList()
            return true
        }
        return false
    }

    private fun buildEditorBox(): LinearLayout {
        val d = density()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        editorTitle = TextView(activity).apply {
            setTextColor(theme.onSurface)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_TITLE
            gravity = Gravity.CENTER
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        box.addView(editorTitle)
        tagRow = TagFlowLayout(activity).apply {
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), (4 * d).toInt())
        }
        val tagScroll = MaxHeightScrollView(activity).apply {
            maxHeight = activity.resources.displayMetrics.heightPixels / TAG_AREA_DIVISOR
            isVerticalScrollBarEnabled = false
            addView(tagRow)
        }
        box.addView(
            tagScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        todoBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        box.addView(todoBox)
        body = EditText(activity).apply {
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
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.surface)
            setPadding(4, 0, 4, 0)
            addView(segButton(activity.getString(R.string.notes_todo_tab)) {
                activity.startActivity(
                    android.content.Intent(activity, NotesTodoActivity::class.java)
                )
            })
            addView(segButton(activity.getString(R.string.notes_tags_tab)) {
                activity.startActivity(
                    android.content.Intent(activity, NotesTagsActivity::class.java)
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
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.surface)
            setPadding(4, 0, 4, 0)
            addView(segButton(activity.getString(R.string.notes_insert_todo)) {
                showTodoDialog()
            })
            addView(segButton(activity.getString(R.string.notes_insert_tag)) {
                current?.let { showTagDialog(it) }
            })
            visibility = android.view.View.GONE
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

    /** 毛玻璃悬浮新建键：半透明圆 + 品牌绿加号，只在列表态显示。 */
    private fun buildFab(): TextView {
        val d = density()
        val size = (56 * d).toInt()
        val glass = (theme.onSurface and RGB_MASK) or (GLASS_ALPHA shl ALPHA_SHIFT)
        val glassEdge = (theme.onSurface and RGB_MASK) or (GLASS_EDGE_ALPHA shl ALPHA_SHIFT)
        return TextView(activity).apply {
            text = "＋"
            gravity = Gravity.CENTER
            setTextColor(theme.primary)
            textSize = FAB_PLUS_SP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(glass)
                setStroke((1 * d).toInt().coerceAtLeast(1), glassEdge)
            }
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                marginEnd = (16 * d).toInt()
                bottomMargin = (16 * d).toInt()
            }
            elevation = (6 * d)
            contentDescription = activity.getString(R.string.notes_new)
            setOnClickListener { onNewPressed() }
        }
    }

    private fun showList() {
        current = null
        fab.visibility = android.view.View.VISIBLE
        navRow.visibility = android.view.View.VISIBLE
        editRow.visibility = android.view.View.GONE
        editorBox.visibility = android.view.View.GONE
        listScroll.visibility = android.view.View.VISIBLE
        renderList()
    }

    fun openNote(name: String) {
        if (store.listNotes().none { it.name == name }) {
            showList()
            return
        }
        current = name
        suppressWatch = true
        body.setText(store.readNote(name))
        suppressWatch = false
        refreshEditorTitle()
        refreshTagRow()
        refreshTodoBox()
        listScroll.visibility = android.view.View.GONE
        editorBox.visibility = android.view.View.VISIBLE
        fab.visibility = android.view.View.GONE
        navRow.visibility = android.view.View.GONE
        editRow.visibility = android.view.View.VISIBLE
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
            suppressWatch = true
            body.setText(after)
            suppressWatch = false
            store.saveNote(noteName, after)
        }
        refreshTagRow()
        refreshTodoBox()
        status.showTempStatus("Notes | ${activity.getString(R.string.notes_tag_removed)}「$tag」")
    }

    private fun renderList() {
        listInner.removeAllViews()
        val notes = store.listNotes()
        if (notes.isEmpty()) {
            listInner.addView(TextView(activity).apply {
                text = activity.getString(R.string.notes_empty_list)
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
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(activity).apply {
                    text = entry.name
                    setTextColor(theme.onSurface)
                    textSize = UiTokens.TEXT_BODY
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                addView(TextView(activity).apply {
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
                addView(TagFlowLayout(activity).apply {
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
        inputDialog(
            activity.getString(R.string.notes_new),
            activity.getString(R.string.notes_name_hint),
            "",
            activity.getString(R.string.notes_new)
        ) { raw -> openNote(store.createNote(raw)) }
    }

    private fun showRenameDialog(old: String) {
        inputDialog(
            activity.getString(R.string.notes_rename),
            activity.getString(R.string.notes_name_hint),
            old,
            activity.getString(R.string.save)
        ) { raw ->
            val name = store.renameNote(old, raw)
            if (current == old) {
                current = name
                refreshEditorTitle()
                refreshTagRow()
                refreshTodoBox()
            }
            if (current == null) renderList()
            status.showTempStatus("Notes | ${activity.getString(R.string.notes_renamed)}「$name」")
        }
    }

    /** 挂标签：只写 header 索引，不再往正文插入 #标签。 */
    private fun showTagDialog(noteName: String) {
        val d = density()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        }
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.notes_tag_hint)
            FieldStyle.applyOutlined(this, theme.outline, theme.onSurface, theme.onSurfaceVariant, UiTokens.TEXT_BODY)
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        }
        box.addView(input)
        val existing = store.allTags().map { it.first } - store.tagsOf(noteName).toSet()
        if (existing.isNotEmpty()) {
            box.addView(TextView(activity).apply {
                text = activity.getString(R.string.notes_common_tags)
                setTextColor(theme.onSurfaceVariant)
                textSize = UiTokens.TEXT_META
                setPadding(0, (12 * d).toInt(), 0, (4 * d).toInt())
            })
            val chips = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (tag in existing) {
                chips.addView(tagCapsule(tag) {
                    val cur = input.text?.toString().orEmpty()
                    val sep = if (cur.isBlank()) "" else " "
                    input.setText("$cur$sep$tag")
                    input.setSelection(input.text?.length ?: 0)
                })
            }
            val scroll = HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            }
            box.addView(scroll)
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.notes_add_tags))
            .setView(box)
            .setPositiveButton(activity.getString(R.string.notes_add_tags)) { _, _ ->
                val picked = parseTags(input.text?.toString().orEmpty())
                if (picked.isEmpty()) return@setPositiveButton
                store.attachTags(noteName, picked)
                if (current == noteName) refreshTagRow()
                if (current == null) renderList()
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .showStyled(theme)
    }

    /** 加待办：只进本笔记 header，不再往正文插入。 */
    private fun showTodoDialog() {
        val cur = current ?: return
        inputDialog(
            activity.getString(R.string.notes_add_todo),
            activity.getString(R.string.notes_todo_hint),
            "",
            activity.getString(R.string.notes_add_todo)
        ) { raw ->
            if (store.addTodo(raw, cur) != null) refreshTodoBox()
        }
    }

    private fun refreshTodoBox() {
        val cur = current ?: return
        todoBox.removeAllViews()
        val items = store.todosOf(cur)
        todoBox.visibility =
            if (items.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        for (item in items) {
            todoBox.addView(noteTodoRow(item))
        }
    }

    private fun noteTodoRow(item: TodoItem): LinearLayout {
        val d = density()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (6 * d).toInt(), (16 * d).toInt(), (6 * d).toInt())
            addView(TextView(activity).apply {
                text = if (item.done) "☑" else "☐"
                setTextColor(if (item.done) theme.primary else theme.onSurface)
                textSize = UiTokens.TEXT_TITLE
                setPadding(0, 0, (10 * d).toInt(), 0)
                setOnClickListener { toggleTodo(item) }
            })
            addView(TextView(activity).apply {
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
        inputDialog(
            activity.getString(R.string.notes_edit_todo),
            activity.getString(R.string.notes_todo_hint),
            item.text,
            activity.getString(R.string.save)
        ) { raw ->
            store.setTodoText(item.id, raw)
            refreshTodoBox()
        }
    }

    private fun confirmDeleteNoteTodo(item: TodoItem) {
        AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.notes_confirm_delete_fmt, item.text))
            .setPositiveButton(activity.getString(R.string.notes_delete)) { _, _ ->
                store.deleteTodo(item.id)
                refreshTodoBox()
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .showStyled(theme)
    }

    private fun parseTags(raw: String): List<String> =
        raw.split(TAG_SPLIT).map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()

    private fun segButton(text: String, onClick: () -> Unit): Button =
        Button(activity).apply {
            this.text = text
            setTextColor(android.graphics.Color.WHITE)
            textSize = AppLang.bottomButtonTextSize(activity)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(3, 0, 3, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }

    private fun tagCapsule(tag: String, onClick: () -> Unit): TextView {
        val d = density()
        val (fill, edge) = tagCapsuleColors(tag)
        return TextView(activity).apply {
            text = tag
            setTextColor(theme.onSurface)
            textSize = UiTokens.TEXT_COMPACT
            maxLines = 1
            setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
            if (tag.length > MARQUEE_CHARS) {
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = MARQUEE_FOREVER
                maxWidth = (MARQUEE_MAX_DP * d).toInt()
                isSelected = true
            } else {
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            background = GradientDrawable().apply {
                cornerRadius = (12 * d)
                setStroke((1 * d).toInt().coerceAtLeast(1), edge)
                setColor(fill)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * d).toInt() }
            setOnClickListener { onClick() }
        }
    }

    private fun divider(): android.view.View = SegmentStyle.hDivider(activity, theme.onSurface)

    private fun scrollColumn(): Pair<ScrollView, LinearLayout> {
        val inner = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { addView(inner) }
        return scroll to inner
    }

    private fun inputDialog(
        title: String,
        hint: String,
        initial: String,
        confirmText: String,
        onConfirm: (String) -> Unit
    ) {
        val d = density()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        }
        val input = EditText(activity).apply {
            this.hint = hint
            setText(initial)
            setSelection(initial.length)
            FieldStyle.applyOutlined(this, theme.outline, theme.onSurface, theme.onSurfaceVariant, UiTokens.TEXT_BODY)
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        }
        box.addView(input)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(confirmText) { _, _ -> onConfirm(input.text?.toString().orEmpty()) }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .showStyled(theme)
    }

    private fun watchText(edit: EditText, onChange: () -> Unit) {
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onChange()
        })
    }

    companion object {
        private val ROW_FMT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        private val TAG_SPLIT = Regex("[\\s,，、]+")
        private const val RGB_MASK = 0x00FFFFFF
        private const val ALPHA_SHIFT = 24
        private const val GLASS_ALPHA = 0x33
        private const val GLASS_EDGE_ALPHA = 0x66
        private const val FAB_PLUS_SP = 28f
        private const val TAG_AREA_DIVISOR = 3
    }
}
