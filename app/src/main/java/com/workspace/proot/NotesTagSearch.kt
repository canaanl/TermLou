@file:Suppress("MagicNumber")

package com.workspace.proot

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/**
 * 笔记搜索的标签筛选协调器：主界面笔记 Tab 和独立笔记页共用同一套焦点、
 * 标签数据、悬浮筛选条、FAB 淡出和列表底部留白逻辑，保证两处行为逐项一致。
 *
 * 页面负责把 [searchField] 放进内容区、把 [filterRow] 叠在底部按键上方，
 * 再调用 [attach] 把列表和 FAB 交给它。
 */
class NotesTagSearch(
    private val context: Context,
    theme: ThemeColors,
    private val tagsProvider: () -> List<String>
) {

    val searchField = NotesSearchField(context, theme)
    val filterRow = NotesTagFilterRow(context, theme)

    /** true = 标签胶囊变化（立即刷新列表）；false = 文字输入（页面自行防抖）。 */
    var onQueryChanged: ((Boolean) -> Unit)? = null

    private var listInner: LinearLayout? = null
    private var fab: View? = null
    private var fabWasVisible = false
    private var fabToken = 0

    init {
        searchField.onQueryChanged = { tagsChanged ->
            if (tagsChanged && filterRow.isShowing) {
                filterRow.updateSelection(searchField.selectedTags().toSet())
            }
            onQueryChanged?.invoke(tagsChanged)
        }
        searchField.input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showFilters() else hideFilters()
        }
        filterRow.onTagClick = { tag ->
            searchField.toggleTag(tag)
            searchField.focusInput()
        }
        // 筛选条本身不抢焦点：手指按下时把焦点钉回搜索框，横向滚动/点标签都不会让筛选条消失。
        filterRow.setOnTouchListener { _, _ ->
            searchField.focusInput()
            false
        }
    }

    fun attach(listInner: LinearLayout, fab: View?) {
        this.listInner = listInner
        this.fab = fab
    }

    fun query(): String = searchField.query()

    fun hasQuery(): Boolean = !NoteQuery.parse(query()).isEmpty

    fun clear() = searchField.clear()

    fun focusInput() = searchField.focusInput()

    /** 列表刷新/从编辑器返回后调用：标签可能被删光，剪掉失效的选中项。 */
    fun refreshTags() {
        val available = tagsProvider().distinct()
        val current = searchField.selectedTags()
        val kept = current.filter { tag ->
            available.any { it.equals(tag, ignoreCase = true) }
        }
        if (kept.size != current.size) searchField.setSelectedTags(kept)
        if (filterRow.isShowing) {
            filterRow.bind(available, searchField.selectedTags().toSet())
        }
    }

    /** 打开笔记时收起筛选条并释放焦点，避免键盘和浮层跟到编辑态。 */
    fun hideForEditor() {
        searchField.input.clearFocus()
        hideFilters()
    }

    private fun showFilters() {
        val tags = tagsProvider().distinct()
        if (tags.isEmpty()) {
            hideFilters()
            return
        }
        filterRow.bind(tags, searchField.selectedTags().toSet())
        filterRow.showAnimated()
        val f = fab
        if (f != null && f.visibility == View.VISIBLE) {
            fabWasVisible = true
            val token = ++fabToken
            f.animate().cancel()
            f.animate()
                .alpha(0f)
                .setDuration(FAB_FADE_MS)
                .withEndAction {
                    if (token == fabToken && filterRow.isShowing) f.visibility = View.INVISIBLE
                }
                .start()
        } else {
            fabWasVisible = false
        }
        applyListClearance()
    }

    private fun hideFilters() {
        val hadFilters = filterRow.isShowing
        if (hadFilters) filterRow.hideAnimated()
        fabToken++
        val f = fab
        if (f != null && (fabWasVisible || f.visibility == View.INVISIBLE)) {
            f.animate().cancel()
            f.visibility = View.VISIBLE
            f.alpha = 0f
            f.animate().alpha(1f).setDuration(FAB_FADE_MS).start()
        } else if (f != null && f.visibility == View.VISIBLE) {
            f.alpha = 1f
        }
        fabWasVisible = false
        val target = listInner
        if (target != null && (hadFilters || target.paddingBottom != 0)) {
            target.setPadding(0, 0, 0, 0)
        }
    }

    /** 筛选条是叠在列表上的浮层：显示时给列表补同样高度的底部留白，最后一行不被盖住。 */
    private fun applyListClearance() {
        val target = listInner ?: return
        val d = context.resources.displayMetrics.density
        filterRow.post {
            if (!filterRow.isShowing) return@post
            val height = filterRow.height.takeIf { it > 0 } ?: (52 * d).toInt()
            target.setPadding(0, 0, 0, height + (12 * d).toInt())
        }
    }

    private companion object {
        const val FAB_FADE_MS = 120L
    }
}
