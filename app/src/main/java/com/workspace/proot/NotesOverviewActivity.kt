package com.workspace.proot

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 总览页：笔记/标签/待办计数 + 标签占比饼图与图例 + 待办完成进度。
 * 饼图复用 StorageDialog.PieChartView，配色沿用网络页 chartPalette 语义。
 */
class NotesOverviewActivity : NotesPageActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = density()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
        }
        root.addView(buildTitleBar(getString(R.string.notes_overview)))
        val scrolled = scrollColumn()
        val inner = scrolled.second
        inner.setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
        inner.addView(buildStats())
        inner.addView(sectionTitle(getString(R.string.notes_tags_tab)))
        inner.addView(buildTagChart())
        inner.addView(sectionTitle(getString(R.string.notes_todo_tab)))
        inner.addView(buildTodoProgress())
        root.addView(
            scrolled.first,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        )
        setContentView(root)
    }

    private fun buildStats(): TextView {
        val notes = store.listNotes().size
        val tags = store.allTags().size
        val all = store.todos()
        val done = all.count { it.done }
        return TextView(this).apply {
            text = getString(R.string.notes_stat_fmt, notes, tags, all.size) + "\n" +
                getString(R.string.notes_stat_todo_fmt, done, all.size - done)
            setTextColor(theme.onSurface)
            textSize = UiTokens.TEXT_BODY
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun sectionTitle(text: String): TextView {
        val d = density()
        return TextView(this).apply {
            this.text = text
            setTextColor(theme.onSurfaceVariant)
            textSize = UiTokens.TEXT_COMPACT
            setPadding(0, (14 * d).toInt(), 0, (6 * d).toInt())
        }
    }

    private fun buildTagChart(): LinearLayout {
        val d = density()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tags = store.allTags()
        if (tags.isEmpty()) {
            box.addView(TextView(this).apply {
                text = getString(R.string.notes_no_tags)
                setTextColor(theme.onSurfaceVariant)
                textSize = UiTokens.TEXT_BODY
            })
            return box
        }
        val palette = notePalette()
        val shown = tags.take(MAX_SLICES)
        val rest = tags.drop(MAX_SLICES).sumOf { it.second }
        val slices = ArrayList<Pair<Long, Int>>()
        for (i in shown.indices) slices.add(shown[i].second.toLong() to palette[i % palette.size])
        if (rest > 0) slices.add(rest.toLong() to palette[MAX_SLICES % palette.size])
        val total = tags.sumOf { it.second }.toLong()
        box.addView(PieChartView(this, theme).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (CHART_H_DP * d).toInt()
            )
            setData(slices)
            startSweep()
        })
        for (i in shown.indices) {
            box.addView(legendRow(palette[i % palette.size], shown[i].first, shown[i].second.toLong(), total))
        }
        if (rest > 0) {
            box.addView(legendRow(palette[MAX_SLICES % palette.size], getString(R.string.notes_others), rest.toLong(), total))
        }
        return box
    }

    private fun buildTodoProgress(): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val (done, open) = store.todoCounts()
        val total = done + open
        if (total == 0) {
            box.addView(TextView(this).apply {
                text = getString(R.string.notes_todo_empty)
                setTextColor(theme.onSurfaceVariant)
                textSize = UiTokens.TEXT_BODY
            })
            return box
        }
        box.addView(StackedBarView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (BAR_H_DP * density()).toInt()
            )
            setData(listOf(done.toLong() to UiTokens.primaryGreen, open.toLong() to theme.outline))
        })
        box.addView(TextView(this).apply {
            val d = density()
            text = getString(R.string.notes_stat_todo_fmt, done, open)
            setTextColor(theme.onSurface)
            textSize = UiTokens.TEXT_BODY
            setPadding(0, (6 * d).toInt(), 0, 0)
        })
        return box
    }

    private fun legendRow(color: Int, label: String, value: Long, total: Long): LinearLayout {
        val d = density()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (2 * d).toInt(), 0, (2 * d).toInt())
            addView(android.view.View(this@NotesOverviewActivity).apply {
                layoutParams = LinearLayout.LayoutParams((8 * d).toInt(), (8 * d).toInt())
                setBackgroundColor(color)
            })
            addView(TextView(this@NotesOverviewActivity).apply {
                text = label
                setTextColor(theme.onSurface)
                textSize = UiTokens.TEXT_META
                typeface = Typeface.MONOSPACE
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding((6 * d).toInt(), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@NotesOverviewActivity).apply {
                val pct = if (total > 0L) (value * 100 / total).toInt() else 0
                text = getString(R.string.notes_pieces_fmt, value.toInt()) + " $pct%"
                setTextColor(theme.onSurfaceVariant)
                textSize = UiTokens.TEXT_META
                typeface = Typeface.MONOSPACE
                setPadding((6 * d).toInt(), 0, 0, 0)
            })
        }
    }

    /** 沿用网络页语义：绿/黄/青/灰打头，不够再用主题 secondary 系补。 */
    private fun notePalette(): List<Int> = listOf(
        UiTokens.primaryGreen,
        UiTokens.amber,
        UiTokens.linkCyan,
        UiTokens.totalText,
        theme.secondary,
        theme.tertiary,
        theme.error,
        theme.onSurfaceVariant
    )

    companion object {
        private const val MAX_SLICES = 7
        private const val CHART_H_DP = 200
        private const val BAR_H_DP = 36
    }
}
