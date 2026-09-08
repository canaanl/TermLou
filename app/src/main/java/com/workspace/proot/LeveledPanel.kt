package com.workspace.proot

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/** FrameLayout 的轻量子类：仅在自己可见性变化时通知，供 WheelLevelFrame 独立观察层级。 */
class LeveledPanel(context: Context) : FrameLayout(context) {

    var onVisibilityChange: ((Boolean) -> Unit)? = null

    override fun onVisibilityChanged(changedView: View, newVisibility: Int) {
        super.onVisibilityChanged(changedView, newVisibility)
        if (changedView === this) {
            onVisibilityChange?.invoke(newVisibility == View.VISIBLE)
        }
    }
}