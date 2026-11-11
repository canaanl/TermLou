package com.workspace.proot

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 搜索聚合展示页（5.12.5）。
 *
 * 把当前搜索命中的笔记内容临时汇到一页：
 *  - 待办命中 → 灰色「待办」标签 + 该条完整文本；
 *  - 正文命中 → 灰色「正文」标签 + 命中处附近片段。
 *
 * 不显示笔记名（只用于跳转）。点击任意一条把笔记名回传给宿主，
 * 由宿主调用已有的 `openNote()` 打开，因此返回行为与“从搜索结果点进笔记”
 * 完全一致：一次返回回到保留搜索状态的列表。
 */
@Suppress("MagicNumber")
class NotesDigestActivity : NotesPageActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        val terms = NoteQuery.parse(query).textTerms

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
        }
        root.addView(buildTitleBar(getString(R.string.notes_digest_title)))

        val items = if (terms.isEmpty()) {
            emptyList()
        } else {
            NoteDigest.build(store.digestSources(store.searchNotesFull(query)), terms)
        }

        root.addView(
            buildBody(items),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f },
        )
        setContentView(root)
    }

    private fun buildBody(items: List<NoteDigest.Item>): ScrollView {
        val d = density()
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (items.isEmpty()) {
            inner.addView(
                TextView(this).apply {
                    text = getString(R.string.notes_digest_empty)
                    setTextColor(theme.onSurfaceVariant)
                    textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
                    gravity = Gravity.CENTER
                    setPaddingMd(24, 48, 24, 0)
                },
            )
        }
        var previousNote: String? = null
        for (item in items) {
            // 换笔记时插一条分隔线（不显示名字，仅视觉分区）。
            if (previousNote != null && item.note != previousNote) {
                inner.addView(SegmentStyle.hDivider(this, theme.outline))
            }
            previousNote = item.note
            inner.addView(buildRow(item, d))
        }
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(inner)
        }
    }

    private fun buildRow(item: NoteDigest.Item, d: Float): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingMd(16, 12, 16, 12)
            addView(
                TextView(this@NotesDigestActivity).apply {
                    text = getString(
                        if (item.kind == NoteDigest.Kind.TODO) {
                            R.string.notes_digest_todo
                        } else {
                            R.string.notes_digest_body
                        },
                    )
                    setTextColor(theme.onSurfaceVariant)
                    textMd(UiTokens.TEXT_META, UiTokens.LINE_META)
                },
            )
            addView(
                TextView(this@NotesDigestActivity).apply {
                    text = item.text
                    setTextColor(theme.onSurface)
                    textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
                    setPaddingMd(0, 4, 0, 0)
                },
            )
            isClickable = true
            setOnClickListener { pick(item.note) }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
            }
            // 触摸反馈用最小侵入方式：按下时轻微降低不透明度。
            setOnTouchListener { v, event ->
                v.alpha = if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) 0.6f else 1f
                false
            }
            minimumHeight = (48 * d).toInt()
        }

    private fun pick(note: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PICK, note))
        finish()
    }

    companion object {
        const val EXTRA_QUERY = "digest_query"
        const val EXTRA_PICK = "digest_pick_note"
    }
}
