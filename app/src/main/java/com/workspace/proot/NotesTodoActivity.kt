package com.workspace.proot

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 待办看板：顶部搜索框实时过滤（一次过滤同时分到两列）；
 * 中间灰线左右分栏，左已完成、右未完成，各列独立滚动、标题冻结；
 * 正文折行（副行归属笔记保持单行省略），checkbox 相对整行居中；
 * 整行点按切换：本列右飞摘除，对列按排序位置重建飞入、
 * 自动滚到该行、dirRowBg 底色闪两次；长按删除；变更即落盘。
 * 新增待办只在笔记编辑器的 [＋待办] 里做。
 */
class NotesTodoActivity : NotesPageActivity() {

    private var query = ""

    private lateinit var doneTitle: TextView
    private lateinit var openTitle: TextView
    private lateinit var doneInner: LinearLayout
    private lateinit var openInner: LinearLayout
    private lateinit var doneScroll: ScrollView
    private lateinit var openScroll: ScrollView
    private var doneSeq = 0
    private var openSeq = 0
    private val doneTransition = newChangeTransition()
    private val openTransition = newChangeTransition()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
        }
        root.addView(buildTitleBar(getString(R.string.notes_todo_tab)))
        root.addView(buildSearchRow())
        root.addView(
            buildBoard(),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        )
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        store.reload()
        render()
    }

    private fun newChangeTransition(): LayoutTransition = LayoutTransition().apply {
        enableTransitionType(LayoutTransition.CHANGE_DISAPPEARING)
        disableTransitionType(LayoutTransition.APPEARING)
        disableTransitionType(LayoutTransition.DISAPPEARING)
        disableTransitionType(LayoutTransition.CHANGE_APPEARING)
        disableTransitionType(LayoutTransition.CHANGING)
    }

    private fun buildSearchRow(): LinearLayout {
        val d = density()
        val input = EditText(this).apply {
            hint = getString(R.string.notes_search_todo)
            setSingleLine(true)
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

    /** 双列看板：左已完成｜灰线｜右未完成，标题在滚动区外天然冻结。 */
    private fun buildBoard(): LinearLayout {
        val d = density()
        doneTitle = headerTitle()
        openTitle = headerTitle()
        doneInner = columnInner()
        openInner = columnInner()
        doneScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(doneInner, columnScrollParams())
        }
        openScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(openInner, columnScrollParams())
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(buildColumn(doneTitle, doneScroll))
            addView(View(this@NotesTodoActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (1 * d).toInt().coerceAtLeast(1),
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(theme.outline)
            })
            addView(buildColumn(openTitle, openScroll))
        }
    }

    private fun columnScrollParams(): ViewGroup.LayoutParams =
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

    private fun buildColumn(title: TextView, scroll: ScrollView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            addView(title)
            addView(
                scroll,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
            )
        }

    private fun headerTitle(): TextView {
        val d = density()
        return TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(theme.onSurfaceVariant)
            textSize = UiTokens.TEXT_META
            setPadding(0, (8 * d).toInt(), 0, (4 * d).toInt())
        }
    }

    private fun columnInner(): LinearLayout {
        val d = density()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (8 * d).toInt())
        }
    }

    /** 一次遍历同时算出两列的过滤结果。 */
    private fun columnItems(toDone: Boolean): List<TodoItem> {
        val q = query.trim().lowercase()
        return store.todos().filter {
            it.done == toDone && (q.isEmpty() || it.text.lowercase().contains(q))
        }
    }

    private fun render() {
        val (doneCount, openCount) = store.todoCounts()
        doneTitle.text = getString(R.string.notes_done_fmt, doneCount)
        openTitle.text = getString(R.string.notes_open_fmt, openCount)
        fillColumn(doneInner, columnItems(true))
        fillColumn(openInner, columnItems(false))
    }

    private fun fillColumn(inner: LinearLayout, items: List<TodoItem>) {
        inner.layoutTransition = null
        inner.removeAllViews()
        if (items.isEmpty()) {
            inner.addView(TextView(this).apply {
                text = getString(R.string.notes_todo_empty_short)
                setTextColor(theme.onSurfaceVariant)
                textSize = UiTokens.TEXT_BODY
                gravity = Gravity.CENTER
                val d = density()
                setPadding(0, (48 * d).toInt(), 0, 0)
            })
            return
        }
        for (item in items) {
            inner.addView(todoRow(item))
            inner.addView(divider().apply { tag = "div:" + item.id })
        }
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
                // 正文折行不限行数；归属副行保持单行省略
                addView(TextView(this@NotesTodoActivity).apply {
                    text = item.text
                    setTextColor(if (item.done) theme.onSurfaceVariant else theme.onSurface)
                    textSize = UiTokens.TEXT_BODY
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

    /**
     * 点按整行切换：本列向右飞出摘除（下方行上滑补位），
     * 对列按排序位置重建、该行从右侧飞入、滚到该行、底色闪两次。
     */
    private fun toggleTodoAnimated(row: LinearLayout, item: TodoItem) {
        row.isClickable = false
        row.animate().cancel()
        val fromDone = item.done
        val srcInner = if (fromDone) doneInner else openInner
        val srcTransition = if (fromDone) doneTransition else openTransition
        val distance = (srcInner.width - row.left).toFloat().coerceAtLeast(row.width.toFloat())
        if (fromDone) doneSeq++ else openSeq++
        val seq = if (fromDone) doneSeq else openSeq
        row.animate()
            .translationX(distance)
            .alpha(0f)
            .setDuration(TOGGLE_ANIM_MS)
            .withEndAction {
                store.setTodoDone(item.id, !item.done)
                srcInner.layoutTransition = srcTransition
                srcInner.removeView(row)
                srcInner.findViewWithTag<View>("div:" + item.id)?.let {
                    srcInner.removeView(it)
                }
                val curSeq = if (fromDone) doneSeq else openSeq
                if (srcInner.childCount == 0) {
                    srcInner.layoutTransition = null
                    fillColumn(srcInner, emptyList())
                } else {
                    srcInner.postDelayed({
                        if ((if (fromDone) doneSeq else openSeq) == seq) srcInner.layoutTransition = null
                    }, TRANSITION_CLEAR_MS)
                }
                enterDestColumn(item.id, !fromDone)
            }
            .start()
    }

    /** 对列重建（顺序与 store 一致即对应位置），新行飞入＋定位＋闪两次。 */
    private fun enterDestColumn(itemId: String, toDone: Boolean) {
        val dstInner = if (toDone) doneInner else openInner
        val dstScroll = if (toDone) doneScroll else openScroll
        val (doneCount, openCount) = store.todoCounts()
        doneTitle.text = getString(R.string.notes_done_fmt, doneCount)
        openTitle.text = getString(R.string.notes_open_fmt, openCount)
        fillColumn(dstInner, columnItems(toDone))
        // 切换不改文字，目标列必含该条目
        val row = dstInner.findViewWithTag<LinearLayout>("row:" + itemId) ?: return
        val w = dstInner.width.toFloat().coerceAtLeast(row.width.toFloat())
        row.translationX = w
        row.alpha = 0f
        row.animate().translationX(0f).alpha(1f).setDuration(TOGGLE_ANIM_MS).start()
        dstInner.post {
            dstScroll.smoothScrollTo(0, row.top)
            flashRow(row)
        }
    }

    /** 与文件 tab 文件夹高亮同底色，亮→灭→亮→灭共两次后清掉。 */
    private fun flashRow(row: LinearLayout) {
        ValueAnimator.ofArgb(UiTokens.dirRowBg, Color.TRANSPARENT).apply {
            duration = FLASH_HALF_MS
            repeatCount = FLASH_REPEATS
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { row.setBackgroundColor(it.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    row.background = null
                }
            })
        }.start()
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
        private const val FLASH_HALF_MS = 250L
        private const val FLASH_REPEATS = 3
    }
}
