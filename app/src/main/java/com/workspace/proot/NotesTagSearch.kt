@file:Suppress("MagicNumber")

package com.workspace.proot

import android.content.Context

/**
 * 笔记搜索的标签筛选协调器：主界面笔记 Tab 和独立笔记页共用同一套焦点、
 * 标签数据、悬浮筛选条逻辑，保证两处行为逐项一致。
 *
 * 5.11.1 起筛选条不再自己贴在页面底部，而是由 [NotesSearchPanel] 放进
 * 搜索面板里、紧挨搜索框；因此这里不再负责 FAB 淡出和列表底部留白。
 * 搜索面板负责显示/隐藏与输入法，本类只负责查询与标签状态。
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
        filterRow.setEmbedded()
    }

    fun query(): String = searchField.query()

    fun hasQuery(): Boolean = !NoteQuery.parse(query()).isEmpty

    /** 输入框与标签都为空时返回 true；用于避免每次收 FAB 都白刷一遍列表。 */
    fun isIdle(): Boolean =
        searchField.selectedTags().isEmpty() && searchField.input.text?.isEmpty() != false

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

    /** 搜索面板关闭：释放焦点并立即收起筛选条。 */
    fun close() {
        searchField.input.clearFocus()
        filterRow.hideNow()
    }

    private fun showFilters() {
        val tags = tagsProvider().distinct()
        if (tags.isEmpty()) {
            hideFilters()
            return
        }
        filterRow.bind(tags, searchField.selectedTags().toSet())
        filterRow.showAnimated()
    }

    private fun hideFilters() {
        filterRow.hideAnimated()
    }
}
