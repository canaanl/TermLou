package com.workspace.proot

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ShortcutSettingsAdapter(
    private val items: MutableList<ShortcutItem>,
    private val theme: ThemeColors,
    private val memberMode: Boolean = false,
    private val onCommandClick: (Int, String, String) -> Unit,
    private val onGroupClick: (Int, String) -> Unit,
    private val onCommandSwiped: (Int) -> Unit,
    private val onGroupSwiped: (Int, String) -> Unit,
    private val onMove: (Int, Int) -> Unit,
    private val onMerge: (Int, Int) -> Unit,
    private val onJoin: (Int, Int) -> Unit
) : RecyclerView.Adapter<ShortcutSettingsAdapter.VH>() {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        val item = items[position]
        return when (item) {
            is ShortcutItem.Command -> ("c\u0000" + item.label + "\u0000" + item.cmd).hashCode().toLong()
            is ShortcutItem.Group -> ("g\u0000" + item.name + "\u0000" + item.members.joinToString { m -> m.label + "\u0000" + m.cmd }).hashCode().toLong()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(theme.surfaceVariant)
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
        }

        val textContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val labelTv = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, UiTokens.TEXT_BODY)
            setSingleLine(true)
            typeface = Typeface.MONOSPACE
        }
        val cmdTv = TextView(ctx).apply {
            setTextColor(theme.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, UiTokens.TEXT_META)
            setSingleLine(true)
            typeface = Typeface.MONOSPACE
            setPadding(0, (2 * d).toInt(), 0, 0)
        }
        textContainer.addView(labelTv)
        textContainer.addView(cmdTv)
        row.addView(textContainer)

        return VH(row, labelTv, cmdTv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        when (item) {
            is ShortcutItem.Command -> {
                holder.labelTv.text = item.label.ifBlank { item.cmd.take(20) + "\u2026" }
                holder.cmdTv.text = item.cmd
            }
            is ShortcutItem.Group -> {
                holder.labelTv.text = "\uD83D\uDCC1 " + item.name
                holder.cmdTv.text = holder.itemView.context.getString(R.string.sc_group_fmt, item.members.size)
            }
        }
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos < 0 || pos >= items.size) return@setOnClickListener
            when (val it = items[pos]) {
                is ShortcutItem.Command -> onCommandClick(pos, it.label, it.cmd)
                is ShortcutItem.Group -> onGroupClick(pos, it.name)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun moveItem(from: Int, to: Int) {
        if (from < 0 || from >= items.size || to < 0 || to >= items.size) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyDataSetChanged()
        onMove(from, to)
    }

    fun requestMergeOrJoin(from: Int, target: Int) {
        if (from < 0 || from >= items.size || target < 0 || target >= items.size) return
        if (memberMode) return
        val src = items[from]
        val tgt = items[target]
        when {
            src is ShortcutItem.Command && tgt is ShortcutItem.Command -> onMerge(from, target)
            src is ShortcutItem.Command && tgt is ShortcutItem.Group -> onJoin(from, target)
        }
    }

    fun onSwiped(position: Int) {
        if (position < 0 || position >= items.size) return
        when (val item = items[position]) {
            is ShortcutItem.Command -> {
                if (memberMode) {
                    notifyItemChanged(position)
                    onCommandSwiped(position)
                } else {
                    onCommandSwiped(position)
                }
            }
            is ShortcutItem.Group -> {
                notifyItemChanged(position)
                onGroupSwiped(position, item.name)
            }
        }
    }

    class VH(itemView: View, val labelTv: TextView, val cmdTv: TextView) :
        RecyclerView.ViewHolder(itemView)

    companion object {
        private const val MERGE_OVERLAP = 0.8f
        private const val MERGE_HOVER_MS = 1000L

        fun createItemTouchHelper(adapter: ShortcutSettingsAdapter): ItemTouchHelper {
            val callback = object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                ItemTouchHelper.LEFT
            ) {
                private var dragStart = -1
                private var lastTarget = -1
                private var mergeTarget = -1
                private var mergeArmed = false
                private var hoverRunnable: Runnable? = null
                private var currentRv: RecyclerView? = null
                private val handler = Handler(Looper.getMainLooper())

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    val tp = target.bindingAdapterPosition
                    val fp = viewHolder.bindingAdapterPosition
                    if (tp >= 0 && tp != fp) lastTarget = tp
                    return false
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    adapter.onSwiped(viewHolder.bindingAdapterPosition)
                }

                override fun isLongPressDragEnabled(): Boolean = true

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        dragStart = viewHolder?.bindingAdapterPosition ?: -1
                        lastTarget = -1
                        mergeTarget = -1
                        mergeArmed = false
                        cancelHover()
                    }
                }

                override fun onChildDraw(
                    c: Canvas,
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    dX: Float,
                    dY: Float,
                    actionState: Int,
                    isCurrentlyActive: Boolean
                ) {
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && isCurrentlyActive) {
                        currentRv = recyclerView
                        updateTargetHit(recyclerView, viewHolder)
                        drawTargetHighlight(c, recyclerView)
                    }
                    if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                        val itemView = viewHolder.itemView
                        if (dX < 0) {
                            val bg = GradientDrawable().apply {
                                setColor(adapter.theme.error)
                                cornerRadius = 0f
                            }
                            bg.setBounds(
                                itemView.left + dX.toInt(), itemView.top,
                                itemView.right, itemView.bottom
                            )
                            bg.draw(c)

                            val paint = Paint().apply {
                                color = Color.WHITE
                                textSize = 16f * itemView.resources.displayMetrics.density
                                isAntiAlias = true
                            }
                            val pos = viewHolder.bindingAdapterPosition
                            val isGroup = pos in 0 until adapter.items.size && adapter.items[pos] is ShortcutItem.Group
                            val text = if (isGroup) itemView.context.getString(R.string.sc_disband) else itemView.context.getString(R.string.sc_delete)
                            val textWidth = paint.measureText(text)
                            val textX = itemView.right - textWidth - 24f * itemView.resources.displayMetrics.density
                            val textY = itemView.top + (itemView.height + paint.textSize) / 2f
                            c.drawText(text, textX, textY, paint)
                        }
                        val alpha = 1.0f - Math.abs(dX) / itemView.width.toFloat()
                        itemView.alpha = Math.max(0.3f, alpha)
                    }
                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                }

                private fun updateTargetHit(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                    val lm = rv.layoutManager as? LinearLayoutManager ?: return
                    val dragged = vh.itemView
                    val dragTop = dragged.top + dragged.translationY
                    val dragBottom = dragged.bottom + dragged.translationY
                    val dragH = dragged.height.toFloat().coerceAtLeast(1f)
                    val dragCenter = dragTop + dragH / 2f

                    var bestCard = -1
                    var bestRatio = 0f
                    for (i in 0 until lm.childCount) {
                        val child = lm.getChildAt(i) ?: continue
                        if (child === dragged) continue
                        val pos = rv.getChildAdapterPosition(child)
                        val top = child.top.toFloat()
                        val bottom = child.bottom.toFloat()
                        if (dragCenter >= top && dragCenter < bottom) {
                            lastTarget = pos
                        }
                        val overlap = Math.min(dragBottom, bottom) - Math.max(dragTop, top)
                        if (overlap > 0f) {
                            val ratio = overlap / dragH
                            if (ratio > bestRatio) {
                                bestRatio = ratio
                                bestCard = pos
                            }
                        }
                    }

                    val newMergeTarget = if (bestRatio >= MERGE_OVERLAP) bestCard else -1
                    if (newMergeTarget != mergeTarget) {
                        mergeTarget = newMergeTarget
                        cancelHover()
                        mergeArmed = false
                        if (mergeTarget >= 0 && !adapter.memberMode) {
                            startHover()
                        }
                    }
                }

                private fun startHover() {
                    cancelHover()
                    val r = Runnable {
                        if (mergeTarget >= 0 && !adapter.memberMode) {
                            mergeArmed = true
                            currentRv?.invalidate()
                        }
                    }
                    hoverRunnable = r
                    handler.postDelayed(r, MERGE_HOVER_MS)
                }

                private fun cancelHover() {
                    hoverRunnable?.let { handler.removeCallbacks(it) }
                    hoverRunnable = null
                }

                private fun drawTargetHighlight(c: Canvas, rv: RecyclerView) {
                    val target = if (mergeTarget >= 0) mergeTarget else lastTarget
                    if (target < 0) return
                    val lm = rv.layoutManager as? LinearLayoutManager ?: return
                    for (i in 0 until lm.childCount) {
                        val child = lm.getChildAt(i) ?: continue
                        if (rv.getChildAdapterPosition(child) == target) {
                            val d = child.resources.displayMetrics.density
                            val rect = RectF(child.left + 2f, child.top + 2f, child.right - 2f, child.bottom - 2f)
                            val p = Paint().apply {
                                color = if (mergeArmed) UiTokens.mergeOrange else adapter.theme.primary
                                style = Paint.Style.STROKE
                                strokeWidth = if (mergeArmed) 4f * d else 3f * d
                                isAntiAlias = true
                            }
                            c.drawRoundRect(rect, 8f * d, 8f * d, p)
                            break
                        }
                    }
                }

                override fun clearView(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ) {
                    super.clearView(recyclerView, viewHolder)
                    viewHolder.itemView.alpha = 1.0f
                    recyclerView.invalidate()
                    cancelHover()
                    val from = dragStart
                    val armed = mergeArmed
                    val mergeIdx = mergeTarget
                    val target = lastTarget
                    dragStart = -1
                    lastTarget = -1
                    mergeTarget = -1
                    mergeArmed = false
                    currentRv = null
                    if (from < 0) return
                    if (armed && mergeIdx >= 0) {
                        adapter.requestMergeOrJoin(from, mergeIdx)
                    } else if (target >= 0 && target != from) {
                        adapter.moveItem(from, target)
                    }
                }
            }
            return ItemTouchHelper(callback)
        }
    }
}
