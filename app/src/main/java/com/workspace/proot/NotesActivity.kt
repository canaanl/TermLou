package com.workspace.proot

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 笔记主页：列表态（默认）/ 编辑态。编辑态底部上行 [＋标签][＋待办]，
 * 固定下行 [新建][保存][待办][标签]。草稿只放内存，切页不丢，点保存才落盘。
 */
class NotesActivity : NotesPageActivity() {

    private var current: String? = null
    private var dirty = false
    private var suppressWatch = false
    private val drafts = HashMap<String, String>()

    private lateinit var listScroll: ScrollView
    private lateinit var listInner: LinearLayout
    private lateinit var editorBox: LinearLayout
    private lateinit var editRow: LinearLayout
    private lateinit var editorTitle: TextView
    private lateinit var tagRow: LinearLayout
    private lateinit var body: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildRoot()
        watchText(body) {
            if (!suppressWatch && current != null) {
                dirty = true
                refreshEditorTitle()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (current != null) showList() else finish()
            }
        })
        val open = intent.getStringExtra(EXTRA_OPEN)
        if (open != null && store.listNotes().any { it.name == open }) openNote(open) else showList()
    }

    override fun onResume() {
        super.onResume()
        store.reload()
        val cur = current
        if (cur != null && store.listNotes().none { it.name == cur }) {
            current = null
            drafts.remove(cur)
            showList()
        } else if (cur != null) {
            refreshEditorTitle()
            refreshTagRow()
        } else {
            renderList()
        }
    }

    private fun buildRoot() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
        }
        root.addView(buildTitleBar(getString(R.string.notes_title)))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f }
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
        root.addView(content)
        root.addView(buildBottomBar())
        setContentView(root)
    }

    private fun buildEditorBox(): LinearLayout {
        val d = density()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        editorTitle = TextView(this).apply {
            setTextColor(theme.onSurface)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_BODY
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        box.addView(editorTitle)
        val tagScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        tagRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), (4 * d).toInt())
        }
        tagScroll.addView(tagRow)
        box.addView(tagScroll)
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

    private fun buildBottomBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
        }
        editRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.surface)
            setPadding(4, 0, 4, 0)
            addView(segButton(getString(R.string.notes_insert_tag)) {
                current?.let { showTagDialog(it, body) }
            })
            addView(segButton(getString(R.string.notes_insert_todo)) {
                showTodoDialog(body)
            })
            visibility = android.view.View.GONE
        }
        SegmentStyle.applyRow(
            editRow,
            listOf(null, null),
            theme.outline,
            theme.onSurface,
            SegmentStyle.Bar(0f, false)
        )
        val fixed = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.surface)
            setPadding(4, 0, 4, 0)
            addView(segButton(getString(R.string.notes_new)) { onNewPressed() })
            addView(segButton(getString(R.string.save)) { onSavePressed() })
            addView(segButton(getString(R.string.notes_todo_tab)) {
                startActivity(android.content.Intent(this@NotesActivity, NotesTodoActivity::class.java))
            })
            addView(segButton(getString(R.string.notes_tags_tab)) {
                startActivity(android.content.Intent(this@NotesActivity, NotesTagsActivity::class.java))
            })
        }
        SegmentStyle.applyRow(
            fixed,
            listOf(null, null, null, null),
            theme.outline,
            theme.onSurface,
            SegmentStyle.Bar(0f, false)
        )
        bar.addView(editRow)
        bar.addView(fixed)
        return bar
    }

    private fun showList() {
        stashDraft()
        current = null
        dirty = false
        editRow.visibility = android.view.View.GONE
        editorBox.visibility = android.view.View.GONE
        listScroll.visibility = android.view.View.VISIBLE
        renderList()
    }

    private fun openNote(name: String) {
        stashDraft()
        current = name
        val saved = store.readNote(name)
        val restored = drafts.remove(name)
        suppressWatch = true
        body.setText(restored ?: saved)
        suppressWatch = false
        dirty = restored != null && restored != saved
        refreshEditorTitle()
        refreshTagRow()
        listScroll.visibility = android.view.View.GONE
        editorBox.visibility = android.view.View.VISIBLE
        editRow.visibility = android.view.View.VISIBLE
    }

    /** 切走前把未保存正文暂存内存草稿。 */
    private fun stashDraft() {
        val cur = current
        if (cur != null && dirty) drafts[cur] = body.text?.toString().orEmpty()
    }

    private fun refreshEditorTitle() {
        val cur = current ?: return
        editorTitle.text = "$cur.${NotesStore.EXT}" + if (dirty) " *" else ""
    }

    private fun refreshTagRow() {
        val cur = current ?: return
        tagRow.removeAllViews()
        val d = density()
        for (tag in store.tagsOf(cur)) {
            tagRow.addView(TextView(this).apply {
                text = "#$tag"
                setTextColor(theme.onSurface)
                textSize = UiTokens.TEXT_COMPACT
                setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = (14 * d)
                    setStroke((1 * d).toInt().coerceAtLeast(1), theme.outline)
                    setColor(android.graphics.Color.TRANSPARENT)
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = (6 * d).toInt()
                layoutParams = lp
                setOnClickListener {
                    store.detachTag(cur, tag)
                    refreshTagRow()
                    showHint(getString(R.string.notes_tag_removed))
                }
            })
        }
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
            listInner.addView(textRow("${entry.name}.${NotesStore.EXT}", rowSub(entry)) { openNote(entry.name) }.apply {
                setOnLongClickListener { showRenameDialog(entry.name); true }
            })
            listInner.addView(divider())
        }
    }

    private fun rowSub(entry: NoteEntry): String {
        val tags = entry.tags.joinToString(" ") { "#$it" }
        val date = ROW_FMT.format(Date(entry.updatedAt))
        return if (tags.isEmpty()) date else "$tags · $date"
    }

    private fun onNewPressed() {
        inputDialog(
            getString(R.string.notes_new),
            getString(R.string.notes_name_hint),
            "",
            getString(R.string.notes_new)
        ) { raw -> openNote(store.createNote(raw)) }
    }

    private fun onSavePressed() {
        val cur = current
        if (cur == null) {
            showHint(getString(R.string.notes_empty_hint))
            return
        }
        drafts.remove(cur)
        store.saveNote(cur, body.text?.toString().orEmpty())
        dirty = false
        refreshEditorTitle()
        showHint(getString(R.string.notes_saved))
    }

    private fun showRenameDialog(old: String) {
        inputDialog(
            getString(R.string.notes_rename),
            getString(R.string.notes_name_hint),
            old,
            getString(R.string.save)
        ) { raw ->
            val name = store.renameNote(old, raw)
            if (current == old) {
                drafts[name] = drafts.remove(old).orEmpty().ifEmpty { body.text?.toString().orEmpty() }
                current = name
                refreshEditorTitle()
                refreshTagRow()
            }
            if (current == null) renderList()
            showHint(getString(R.string.notes_renamed))
        }
    }

    /** 挂标签：存索引 +（编辑态）正文光标处插入 #标签。 */
    private fun showTagDialog(noteName: String, target: EditText?) {
        val d = density()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        }
        val input = EditText(this).apply {
            hint = getString(R.string.notes_tag_hint)
            FieldStyle.applyOutlined(this, theme.outline, theme.onSurface, theme.onSurfaceVariant, UiTokens.TEXT_BODY)
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
                chips.addView(toolButton("#$tag") {
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
                if (target != null) insertAtCursor(target, picked.joinToString(" ") { "#$it" }, false)
                if (current == noteName) refreshTagRow()
                if (current == null) renderList()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
    }

    /** 加待办：进全局列表 + 正文光标处插入 "- [ ] 内容"行。 */
    private fun showTodoDialog(target: EditText?) {
        inputDialog(
            getString(R.string.notes_add_todo),
            getString(R.string.notes_todo_hint),
            "",
            getString(R.string.notes_add_todo)
        ) { raw ->
            val item = store.addTodo(raw) ?: return@inputDialog
            if (target != null) insertAtCursor(target, "- [ ] ${item.text}", true)
        }
    }

    private fun insertAtCursor(edit: EditText, snippet: String, lineBreak: Boolean) {
        val pos = edit.selectionStart.coerceAtLeast(0)
        val cur = edit.text?.toString().orEmpty()
        val glue = if (pos <= 0) "" else {
            val prev = cur[pos - 1]
            if (prev == '\n') "" else if (lineBreak) "\n" else " "
        }
        edit.text?.insert(pos, glue + snippet)
    }

    private fun parseTags(raw: String): List<String> =
        raw.split(TAG_SPLIT).map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()

    companion object {
        private val ROW_FMT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        private val TAG_SPLIT = Regex("[\\s,，、]+")
    }
}
