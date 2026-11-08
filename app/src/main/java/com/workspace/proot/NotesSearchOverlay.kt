@file:Suppress("TooManyFunctions", "ReturnCount")

package com.workspace.proot

import android.animation.ValueAnimator
import android.app.Activity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * 笔记搜索 / FAB 组的总状态机（5.11.1）。
 *
 * ```
 * S0 收起：组 FAB，无遮罩
 * S1 展开：组 + 搜索 + 新建，内容区遮罩，列表锁死
 * S2 搜索：S1 + 全宽可拖搜索面板；查询无结果时列表仍锁死
 * S3 搜索结果：遮罩仍在，但列表可滚动/可点（搜索行为与旧版完全一致）
 * ```
 *
 * 横滑切 Tab 是最高优先级：遮罩和 FAB 根布局都不拦截横向手势；
 * 只有搜索面板的拖动把手在确认是纵向拖动后才 requestDisallowIntercept。
 */
class NotesSearchOverlay(
    private val activity: Activity,
    private val theme: ThemeColors,
    val tagSearch: NotesTagSearch,
    private val onNewNote: () -> Unit,
) {

    val scrim = NotesScrimView(activity)
    val panel = NotesSearchPanel(activity, theme, tagSearch)
    val menu = NotesFabMenu(
        activity,
        theme,
        onToggle = { toggleMenu() },
        onSearch = { toggleSearch() },
        onNewNote = {
            collapseAll()
            onNewNote()
        },
    )

    var isExpanded: Boolean = false
        private set
    var isSearchOpen: Boolean = false
        private set
    var resultsAvailable: Boolean = false
        private set

    private var scrimAnimator: ValueAnimator? = null
    private var host: FrameLayout? = null

    fun attach(host: FrameLayout) {
        this.host = host
        scrim.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        menu.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        host.addView(scrim)
        host.addView(panel)
        host.addView(menu)

        val update = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateScrimAnchor()
            panel.clampToParent()
        }
        host.addOnLayoutChangeListener(update)
        menu.addOnLayoutChangeListener(update)
        menu.post { updateScrimAnchor() }
        showMenuCollapsed()
    }

    fun toggleMenu() {
        if (isExpanded) collapseAll() else expandMenu()
    }

    fun expandMenu(animate: Boolean = true) {
        if (isExpanded) return
        isExpanded = true
        menu.setExpanded(true, animate)
        animateScrim(1f, animate)
        updateBlocking()
    }

    fun toggleSearch() {
        if (isSearchOpen) {
            closeSearch()
        } else {
            openSearch()
        }
    }

    fun openSearch() {
        if (!isExpanded) expandMenu()
        isSearchOpen = true
        resultsAvailable = false
        panel.show()
        updateBlocking()
    }

    /**
     * 关闭搜索，但保持 FAB 展开（S2 → S1）。
     * 关闭时清空搜索词与标签筛选，这是用户明确要求。
     */
    fun closeSearch(clearQuery: Boolean = true, animate: Boolean = true) {
        val wasOpen = isSearchOpen || panel.isOpen
        isSearchOpen = false
        resultsAvailable = false
        panel.hide(animate)
        if (clearQuery && !tagSearch.isIdle()) tagSearch.clear()
        if (wasOpen) updateBlocking()
    }

    /**
     * 收起整个 FAB 组（S1/S2 → S0）。
     *
     * 切 Tab、点组 FAB、打开笔记、点底部待办/日历都走这里。
     */
    fun collapseAll(animate: Boolean = true) {
        closeSearch(clearQuery = true, animate = animate)
        if (!isExpanded) {
            menu.setExpandedImmediate(false)
            hideScrimImmediate()
            return
        }
        isExpanded = false
        menu.setExpanded(false, animate)
        animateScrim(0f, animate)
        updateBlocking()
    }

    /** 打开笔记/进入编辑态：整个搜索层立即消失，不播动画。 */
    fun hideForEditor() {
        collapseAll(animate = false)
        panel.hide(animate = false)
        menu.visibility = View.GONE
        scrim.visibility = View.GONE
        scrim.setBlocking(false)
    }

    /** 从编辑器/子页面回到列表：FAB 组收起但可见。 */
    fun showMenuCollapsed() {
        panel.hide(animate = false)
        isSearchOpen = false
        resultsAvailable = false
        isExpanded = false
        // 防止“搜索框已经藏了、列表却还在过滤”的幽灵状态。
        if (!tagSearch.isIdle()) tagSearch.clear()
        menu.visibility = View.VISIBLE
        menu.setExpandedImmediate(false)
        hideScrimImmediate()
    }

    /** 横滑切到其他 Tab：立即复位，不留下半截动画。 */
    fun onTabSwitch() {
        collapseAll(animate = false)
    }

    /** 返回键：先关搜索，再收 FAB。 */
    fun handleBack(): Boolean {
        if (isSearchOpen) {
            closeSearch()
            return true
        }
        if (isExpanded) {
            collapseAll()
            return true
        }
        return false
    }

    /**
     * 由笔记列表在 [renderList] 后调用。
     *
     * 只有“搜索面板开着 + 查询非空 + 确实有结果”才允许列表穿透遮罩；
     * 其余情况遮罩吞点击，点击笔记进不去。
     */
    fun setResultsAvailable(value: Boolean) {
        resultsAvailable = value && isSearchOpen
        updateBlocking()
    }

    fun cancelAnimations() {
        scrimAnimator?.cancel()
        scrimAnimator = null
        menu.cancelAnimation()
    }

    private fun updateBlocking() {
        val block = isExpanded && !(isSearchOpen && resultsAvailable)
        scrim.setBlocking(block)
    }

    private fun updateScrimAnchor() {
        if (menu.width <= 0 || menu.height <= 0) return
        val p = menu.mainCenter()
        scrim.setAnchor(p.x, p.y)
    }

    private fun animateScrim(target: Float, animate: Boolean) {
        scrimAnimator?.cancel()
        scrimAnimator = null
        if (target > 0f) scrim.visibility = View.VISIBLE
        if (!animate) {
            scrim.setProgress(target)
            if (target <= 0f) {
                scrim.visibility = View.GONE
                scrim.setBlocking(false)
            }
            return
        }
        val from = scrim.shownProgress
        val anim = ValueAnimator.ofFloat(from, target).apply {
            duration = SCRIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { scrim.setProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (scrimAnimator !== animation) return
                    scrimAnimator = null
                    if (target <= 0f) {
                        scrim.visibility = View.GONE
                        scrim.setBlocking(false)
                    }
                }
            })
        }
        scrimAnimator = anim
        anim.start()
    }

    private fun hideScrimImmediate() {
        scrimAnimator?.cancel()
        scrimAnimator = null
        scrim.setProgress(0f)
        scrim.visibility = View.GONE
        scrim.setBlocking(false)
    }

    private companion object {
        const val SCRIM_MS = 180L
    }
}
