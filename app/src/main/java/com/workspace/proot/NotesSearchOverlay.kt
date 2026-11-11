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
        onDigest = { launchDigest() },
    )

    var isExpanded: Boolean = false
        private set
    var isSearchOpen: Boolean = false
        private set
    var resultsAvailable: Boolean = false
        private set

    /** 进编辑器时挂起的搜索会话；返回列表时原样恢复，只有显式关闭才结束会话。 */
    private var suspendedSearch = false

    /** true = 刚从上一次搜索会话恢复，下一次返回键直接回到初始态。 */
    private var restoredFromEditor = false

    /** 聚合页请求码；结果回传给宿主的 launcher。 */
    var digestLauncher: ((android.content.Intent) -> Unit)? = null

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
        menu.setDigestMode(enabled = true, available = false)
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
        menu.setDigestMode(enabled = false, available = false)
        panel.hide(animate)
        if (clearQuery && !tagSearch.isIdle()) tagSearch.clear()
        if (wasOpen) updateBlocking()
    }

    /**
     * 收起整个 FAB 组（S1/S2 → S0）。
     *
     * 切 Tab、点组 FAB、打开笔记、点底部待办/日历都走这里。
     * 这是**真正结束搜索会话**的唯一入口，会清空查询与标签。
     */
    fun collapseAll(animate: Boolean = true) {
        suspendedSearch = false
        restoredFromEditor = false
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

    /**
     * 打开笔记/进入编辑态：只把搜索层**挂起**，不清查询。
     *
     * 从搜索结果点进笔记、发现不是想要的那篇再退回时，搜索框内容、标签、
     * 结果列表和 FAB 展开态都要原样保留，由 [restoreAfterEditor] 恢复。
     */
    fun hideForEditor() {
        suspendedSearch = isSearchOpen
        restoredFromEditor = false
        isSearchOpen = false
        resultsAvailable = false
        isExpanded = false
        panel.hide(animate = false)
        menu.visibility = View.GONE
        menu.setExpandedImmediate(false)
        scrim.visibility = View.GONE
        scrim.setBlocking(false)
        // 只释放焦点、收起筛选条；查询词与已选标签保留。
        tagSearch.hideForEditor()
    }

    /**
     * 从编辑器回到列表。
     *
     * 有挂起的搜索会话就原样恢复（面板可见、结果可点、FAB 展开，但不抢焦点/不弹键盘）；
     * 没有则退回普通收起态。
     *
     * @return true 表示恢复了搜索会话，此时列表过滤条件仍在。
     */
    fun restoreAfterEditor(): Boolean {
        if (!suspendedSearch) {
            showMenuCollapsed()
            return false
        }
        suspendedSearch = false
        restoredFromEditor = true
        menu.visibility = View.VISIBLE
        isExpanded = true
        menu.setExpandedImmediate(true)
        isSearchOpen = true
        resultsAvailable = false
        panel.show(focusInput = false)
        menu.setDigestMode(enabled = true, available = false)
        updateBlocking()
        return true
    }

    /** 从编辑器/子页面回到列表：FAB 组收起但可见。 */
    fun showMenuCollapsed() {
        suspendedSearch = false
        restoredFromEditor = false
        panel.hide(animate = false)
        isSearchOpen = false
        resultsAvailable = false
        isExpanded = false
        menu.setDigestMode(enabled = false, available = false)
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

    /** 返回键：恢复自编辑器的搜索先一步回到初始，再关搜索，最后收 FAB。 */
    fun handleBack(): Boolean {
        if (restoredFromEditor) {
            collapseAll()
            return true
        }
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

    /**
     * 聚合按钮可用性（5.12.5）。
     *
     * 只由**正文/待办内容命中**决定；纯标签筛选即使有结果也保持灰色。
     */
    fun setDigestAvailable(value: Boolean) {
        menu.setDigestMode(enabled = isSearchOpen, available = value)
    }

    /** 测试用：聚合按钮当前是否可用。 */
    internal fun digestAvailableForTest(): Boolean = menu.digestAvailableForTest()

    /** 测试用：直接拿到搜索/聚合那颗 FAB。 */
    internal fun searchFabForTest(): android.widget.TextView = menu.searchFabForTest()

    private fun launchDigest() {
        val query = tagSearch.query()
        if (NoteQuery.parse(query).textTerms.isEmpty()) return
        val intent = android.content.Intent(activity, NotesDigestActivity::class.java)
            .putExtra(NotesDigestActivity.EXTRA_QUERY, query)
        val launcher = digestLauncher
        if (launcher != null) launcher(intent) else activity.startActivity(intent)
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
