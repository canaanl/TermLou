@file:Suppress("MagicNumber", "ReturnCount")

package com.workspace.proot

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * 全宽、可上下拖动的笔记搜索面板（5.11.1）。
 *
 * 结构：
 * ```
 * 拖动把手
 * NotesSearchField（输入 + 已选标签胶囊）
 * NotesTagFilterRow（标签推荐，紧贴搜索框）
 * ```
 *
 * 只从把手拖动；输入框仍保留正常的点击/光标/选择手势。
 * 横向移动超过阈值时**主动放开父级拦截**，让 ViewPager2 继续拥有切 Tab 的最高优先级。
 */
class NotesSearchPanel(
    private val activity: Activity,
    private val theme: ThemeColors,
    private val tagSearch: NotesTagSearch,
) : LinearLayout(activity) {

    private val d = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var startTranslation = 0f
    private var verticalDrag = false
    private var hideToken = 0
    private var imeToken = 0
    private var lastY = 0f

    val isOpen: Boolean get() = visibility == VISIBLE

    init {
        orientation = VERTICAL
        visibility = GONE
        alpha = 0f
        clipChildren = false
        clipToPadding = false
        elevation = 8f * d
        background = GradientDrawable().apply {
            cornerRadius = snapCorner(UiTokens.CORNER_LG) * d
            setColor(theme.surfaceContainerHigh)
            setStroke((1 * d).toInt().coerceAtLeast(1), theme.outline)
        }
        setPadding((8 * d).toInt(), 0, (8 * d).toInt(), (8 * d).toInt())
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        )

        addView(buildHandle(), LayoutParams(LayoutParams.MATCH_PARENT, (22 * d).toInt()))
        addView(
            tagSearch.searchField,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        tagSearch.filterRow.setEmbedded()
        addView(
            tagSearch.filterRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
    }

    private fun buildHandle(): View = FrameLayout(activity).apply {
        isClickable = true
        isFocusable = false
        setOnTouchListener { _, event -> onHandleTouch(event) }
        addView(View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = snapCorner(UiTokens.CORNER_XS) * d
                setColor(theme.onSurfaceVariant)
            }
            alpha = 0.65f
            layoutParams = FrameLayout.LayoutParams((36 * d).toInt(), (4 * d).toInt(), Gravity.CENTER)
        })
    }

    private fun onHandleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startTranslation = translationY
                verticalDrag = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.rawX - downRawX)
                val dy = abs(event.rawY - downRawY)
                if (!verticalDrag) {
                    if (dx <= touchSlop && dy <= touchSlop) return true
                    if (dx > dy) {
                        // 横向是切 Tab 的最高权限：不抢，交给 ViewPager2。
                        requestDisallowInterceptTouchEvent(false)
                        return false
                    }
                    verticalDrag = true
                    requestDisallowInterceptTouchEvent(true)
                }
                translationY = (startTranslation + event.rawY - downRawY).coerceIn(0f, maxTranslation())
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (verticalDrag) {
                    lastY = translationY.coerceIn(0f, maxTranslation())
                    requestDisallowInterceptTouchEvent(false)
                }
                verticalDrag = false
                return true
            }
        }
        return false
    }

    private fun maxTranslation(): Float {
        val parentH = (parent as? View)?.height ?: 0
        return (parentH - height).coerceAtLeast(0).toFloat()
    }

    /** 父容器（键盘 adjustResize）高度变化后重新夹取，避免面板被顶出屏幕。 */
    fun clampToParent() {
        val clamped = translationY.coerceIn(0f, maxTranslation())
        if (clamped != translationY) {
            translationY = clamped
            lastY = clamped
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post {
            val clamped = translationY.coerceIn(0f, maxTranslation())
            if (clamped != translationY) translationY = clamped
        }
    }

    fun show() {
        hideToken++
        animate().cancel()
        visibility = VISIBLE
        alpha = 0f
        translationY = lastY
        animate().alpha(1f).setDuration(SHOW_MS).start()
        tagSearch.refreshTags()
        post {
            translationY = lastY.coerceIn(0f, maxTranslation())
            focusAndShowIme()
        }
    }

    fun hide() {
        hide(animate = true)
    }

    fun hide(animate: Boolean) {
        val token = ++hideToken
        imeToken++
        tagSearch.close()
        hideIme()
        animate().cancel()
        if (!animate) {
            visibility = GONE
            alpha = 1f
            translationY = lastY
            return
        }
        animate().alpha(0f).setDuration(HIDE_MS).start()
        postDelayed({
            if (token == hideToken) {
                visibility = GONE
                alpha = 1f
                translationY = lastY
            }
        }, HIDE_MS)
    }

    private fun focusAndShowIme() {
        val input = tagSearch.searchField.input
        val token = ++imeToken
        input.requestFocus()
        input.postDelayed({
            if (token != imeToken || !isOpen) return@postDelayed
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, IME_DELAY_MS)
    }

    private fun hideIme() {
        ImeFocus.clearAndHide(activity)
    }

    private companion object {
        const val SHOW_MS = 170L
        const val HIDE_MS = 120L
        const val IME_DELAY_MS = 80L
    }
}
