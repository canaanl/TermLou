package com.workspace.proot

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 标签页：顶部搜索框过滤标签；点标签 = 筛选出该标签的笔记；
 * 点笔记直跳编辑器。复用 AppPickerDialog 的搜索框模式。
 */
class NotesTagsActivity : NotesPageActivity() {

    private var query = ""
    private var filterTag: String? = null

    private lateinit var searchInput: EditText
    private lateinit var listInner: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = density()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
        }
        root.addView(buildTitleBar(getString(R.string.notes_tags_tab)))
        searchInput = EditText(this).apply {
            hint = getString(R.string.notes_search_tag)
            FieldStyle.applyOutlined(this, theme.outline, theme.onSurface, theme.onSurfaceVariant, UiTokens.TEXT_BODY)
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())
            }
        }
        root.addView(searchInput)
        val scrolled = scrollColumn()
        listInner = scrolled.second
        root.addView(
            scrolled.first,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        )
        setContentView(root)
        watchText(searchInput) {
            query = searchInput.text?.toString().orEmpty()
            if (filterTag != null && query.isNotEmpty()) filterTag = null
            render()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        store.reload()
        render()
    }

    private fun render() {
        listInner.removeAllViews()
        val tag = filterTag
        if (tag == null) renderTags() else renderTaggedNotes(tag)
    }

    private fun renderTags() {
        val q = query.trim().lowercase()
        val tags = store.allTags().filter { q.isEmpty() || it.first.lowercase().contains(q) }
        if (tags.isEmpty()) {
            listInner.addView(emptyText(getString(R.string.notes_no_tags)))
            return
        }
        for ((name, count) in tags) {
            listInner.addView(tagRow(name, count) {
                filterTag = name
                query = ""
                if (searchInput.text?.isNotEmpty() == true) searchInput.setText("") else render()
            })
            listInner.addView(divider())
        }
    }

    private fun tagRow(name: String, count: Int, onClick: () -> Unit): LinearLayout {
        val d = density()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            addView(tagCapsule(name) { onClick() })
            addView(TextView(this@NotesTagsActivity).apply {
                text = getString(R.string.notes_pieces_fmt, count)
                setTextColor(theme.onSurfaceVariant)
                textSize = UiTokens.TEXT_COMPACT
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = (8 * d).toInt() }
            })
            setOnClickListener { onClick() }
        }
    }

    private fun renderTaggedNotes(tag: String) {
        val d = density()
        listInner.addView(TextView(this).apply {
            text = getString(R.string.notes_back_tags)
            setTextColor(theme.primary)
            textSize = UiTokens.TEXT_BODY
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())
            setOnClickListener {
                filterTag = null
                render()
            }
        })
        val notes = store.notesWithTag(tag)
        listInner.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (2 * d).toInt(), (16 * d).toInt(), (6 * d).toInt())
            addView(tagCapsule(tag) {
                filterTag = null
                render()
            })
            addView(TextView(this@NotesTagsActivity).apply {
                text = getString(R.string.notes_pieces_fmt, notes.size)
                setTextColor(theme.onSurfaceVariant)
                textSize = UiTokens.TEXT_COMPACT
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = (8 * d).toInt() }
            })
        })
        if (notes.isEmpty()) {
            listInner.addView(emptyText(getString(R.string.notes_empty_list)))
            return
        }
        for (entry in notes) {
            val sub = entry.tags.filter { it != tag }.joinToString(" ") { "#$it" }
            listInner.addView(textRow("${entry.name}.${NotesStore.EXT}", sub) {
                startActivity(Intent(this@NotesTagsActivity, NotesActivity::class.java).apply {
                    putExtra(EXTRA_OPEN, entry.name)
                })
            })
            listInner.addView(divider())
        }
    }

    private fun emptyText(text: String): TextView {
        val d = density()
        return TextView(this).apply {
            this.text = text
            setTextColor(theme.onSurfaceVariant)
            textSize = UiTokens.TEXT_BODY
            gravity = Gravity.CENTER
            setPadding(0, (48 * d).toInt(), 0, 0)
        }
    }
}
