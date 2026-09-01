package com.workspace.proot

import android.view.View
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView

class SkipEmptySnapHelper : LinearSnapHelper() {

    var isEmptyCheck: ((Int) -> Boolean)? = null

    override fun findSnapView(layoutManager: RecyclerView.LayoutManager): View? {
        val snapView = super.findSnapView(layoutManager) ?: return null
        val check = isEmptyCheck ?: return snapView
        val position = layoutManager.getPosition(snapView)
        if (!check(position)) return snapView

        val center = layoutManager.width / 2f
        var bestView: View? = null
        var bestDist = Float.MAX_VALUE
        for (i in 0 until layoutManager.childCount) {
            val child = layoutManager.getChildAt(i) ?: continue
            if (check(layoutManager.getPosition(child))) continue
            val childCenter = child.left + child.width / 2f
            val dist = Math.abs(childCenter - center)
            if (dist < bestDist) {
                bestDist = dist
                bestView = child
            }
        }
        return bestView ?: snapView
    }

    override fun findTargetSnapPosition(
        layoutManager: RecyclerView.LayoutManager,
        velocityX: Int,
        velocityY: Int
    ): Int {
        val target = super.findTargetSnapPosition(layoutManager, velocityX, velocityY)
        if (target == RecyclerView.NO_POSITION) return target
        val check = isEmptyCheck ?: return target
        if (!check(target)) return target

        val direction = if (velocityX > 0) 1 else -1
        for (i in 1..10) {
            val next = target + i * direction
            if (!check(next)) return next
        }
        return target
    }
}
