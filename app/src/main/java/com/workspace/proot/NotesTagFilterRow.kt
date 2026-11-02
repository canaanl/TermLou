@file:Suppress("MagicNumber")

package com.workspace.proot

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 搜索框聚焦时浮在底部按键上方的标签筛选条。
 *
 * 只有一行、横向滚动；点标签切换选中态（多标签 AND）。半透明底 + 轻 elevation，
 * 既能看到下面列表的轮廓，又不会被误认为列表内容。
 * 整条不接收焦点，点标签不会把输入焦点从搜索框抢走。
 */
class NotesTagFilterRow(context: Context, private val theme: ThemeColors) : HorizontalScrollView(context) {

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPaddingMd(10, 8, 10, 8)
    }
    private var tags: List<String> = emptyList()
    private var selected: Set<String> = emptySet()
    private var hideToken = 0

    var onTagClick: ((String) -> Unit)? = null

    val isShowing: Boolean get() = visibility == VISIBLE

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setPaddingMd(6, 0, 6, 0)
        background = GradientDrawable().apply {
            cornerRadius = snapCorner(UiTokens.CORNER_LG) * resources.displayMetrics.density
            setColor(withAlpha(theme.surfaceContainerHigh, 0xE8))
            setStroke(
                (1 * resources.displayMetrics.density).toInt().coerceAtLeast(1),
                withAlpha(theme.outline, 0xAA)
            )
        }
        elevation = 6 * resources.displayMetrics.density
        clipToOutline = true
        alpha = 0f
        visibility = GONE
        addView(
            row,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )
    }

    /**
     * 绑定标签集合与选中态。标签集合没变时只更新选中样式，不重建整排 View——
     * 标签很多时，每次输入/点按都重建会明显浪费。
     */
    fun bind(newTags: List<String>, newSelected: Set<String>) {
        val clean = newTags.map { it.trim().removePrefix("#").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        selected = newSelected
        if (clean == tags) {
            updateSelection()
            return
        }
        tags = clean
        rebuild()
    }

    fun updateSelection(newSelected: Set<String>) {
        selected = newSelected
        updateSelection()
    }

    fun showAnimated() {
        if (tags.isEmpty()) return
        hideToken++
        animate().cancel()
        visibility = VISIBLE
        alpha = 0f
        translationY = 8 * resources.displayMetrics.density
        animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(SHOW_MS)
            .start()
    }

    fun hideAnimated() {
        if (!isShowing) return
        val token = ++hideToken
        animate().cancel()
        animate()
            .alpha(0f)
            .translationY(8 * resources.displayMetrics.density)
            .setDuration(HIDE_MS)
            .start()
        // 用 postDelayed 而不是 withEndAction：Robolectric 下动画结束回调不走 Choreographer，
        // 这条延迟任务能直接被测试的 ShadowLooper 跑完；重新 show 时 token 变了，不会误隐藏。
        postDelayed({ if (token == hideToken) visibility = GONE }, HIDE_MS)
    }

    private fun rebuild() {
        row.removeAllViews()
        if (tags.isEmpty()) {
            visibility = GONE
            return
        }
        for (tag in tags) {
            row.addView(TextView(context).apply {
                setTag(tag)
                text = "#$tag"
                isFocusable = false
                isFocusableInTouchMode = false
                setTextColor(TAG_TEXT_COLOR)
                textMd(UiTokens.TEXT_COMPACT, UiTokens.LINE_COMPACT)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPaddingMd(12, 4, 12, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (6 * resources.displayMetrics.density).toInt()
                }
                setOnClickListener { onTagClick?.invoke(tag) }
            })
        }
        updateSelection()
    }

    private fun updateSelection() {
        val d = resources.displayMetrics.density
        for (i in 0 until row.childCount) {
            val chip = row.getChildAt(i) as? TextView
            val tag = chip?.tag as? String
            if (chip == null || tag == null) continue
            val on = selected.any { it.equals(tag, ignoreCase = true) }
            val (fill, edge) = tagCapsuleColors(tag)
            chip.background = GradientDrawable().apply {
                cornerRadius = snapCorner(UiTokens.CORNER_MD) * d
                setStroke(
                    ((if (on) 2 else 1) * d).toInt().coerceAtLeast(1),
                    if (on) theme.primaryVisible else withAlpha(edge, 0xC0)
                )
                setColor(withAlpha(fill, if (on) 0xFF else 0xD8))
            }
            chip.alpha = if (on) 1f else 0.9f
            chip.contentDescription = context.getString(
                if (on) R.string.notes_tag_filter_selected_fmt else R.string.notes_tag_filter_select_fmt,
                tag
            )
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private companion object {
        const val SHOW_MS = 140L
        const val HIDE_MS = 120L
    }
}
