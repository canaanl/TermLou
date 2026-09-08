package com.workspace.proot

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.recyclerview.widget.RecyclerView

class WheelAdapter(
    private val items: List<ShortcutItem>,
    private val emptyCount: Int = 0,
    private val onHighlightClick: (ShortcutItem) -> Unit
) : RecyclerView.Adapter<WheelAdapter.VH>() {

    private var rv: RecyclerView? = null
    private var darkRounded: GradientDrawable? = null

    override fun getItemCount() = Int.MAX_VALUE

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        rv = recyclerView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        darkRounded = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
        }
        val cardSize = parent.context.resources.displayMetrics.widthPixels / 3
        val btn = Button(parent.context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            isAllCaps = true
            layoutParams = RecyclerView.LayoutParams(cardSize, RecyclerView.LayoutParams.MATCH_PARENT).apply {
                setMargins(3, 4, 3, 4)
            }
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            isSingleLine = true
            marqueeRepeatLimit = -1
            isSelected = true
            background = darkRounded
        }
        return VH(btn)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val slot = position % (items.size + emptyCount)
        val empty = slot < emptyCount
        holder.btn.apply {
            if (empty) {
                text = ""
                tag = null
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = false
                isFocusable = false
                setOnClickListener(null)
            } else {
                val item = items[slot - emptyCount]
                text = displayText(item)
                tag = item
                background = darkRounded
                isClickable = true
                isFocusable = true
                setOnClickListener(null)
                setOnClickListener {
                    if (isAtHighlight(this)) {
                        pressFeedback(this)
                        (tag as? ShortcutItem)?.let { onHighlightClick(it) }
                    }
                }
            }
        }
    }

    private fun displayText(item: ShortcutItem): String = when (item) {
        is ShortcutItem.Command -> item.label.ifBlank { item.cmd.take(8) + "…" }
        is ShortcutItem.Group -> "\uD83D\uDCC1 " + item.name
    }

    fun isEmpty(position: Int): Boolean {
        val slot = position % (items.size + emptyCount)
        return slot < emptyCount
    }

    fun itemIndexAtPosition(position: Int): Int? {
        val slot = position % (items.size + emptyCount)
        if (slot < emptyCount) return null
        return slot - emptyCount
    }

    private fun isAtHighlight(v: View): Boolean {
        val r = rv ?: return false
        val rvCenter = r.width / 2f
        val vCenter = v.left + v.width / 2f
        return Math.abs(vCenter - rvCenter) < v.width / 2f
    }

    private fun pressFeedback(v: View) {
        v.animate().cancel()
        v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
        v.postDelayed({
            if (isAtHighlight(v)) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
        }, 150)
    }

    class VH(val btn: Button) : RecyclerView.ViewHolder(btn)
}
