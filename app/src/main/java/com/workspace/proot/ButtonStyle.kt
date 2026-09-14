package com.workspace.proot

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button

/** 按钮统一外壳：纯色圆角 + M3 状态层 ripple，无立体沿、无按压缩放。 */
object ButtonStyle {
    internal const val CORNER_RADIUS_DP = 20f
    private val rippleColor = ColorStateList.valueOf(0x1FFFFFFF.toInt())

    fun apply(button: Button, bgColor: Int) {
        val d = button.resources.displayMetrics.density
        val radius = CORNER_RADIUS_DP * d

        val content = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = radius
        }
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radius
        }
button.background = RippleDrawable(rippleColor, content, mask)
        button.isAllCaps = false
    }

    /** 空心描边按钮：透明底 + 主题色圆角描边 + 涟漪，文字色由调用方定。 */
    fun outlined(button: Button, borderColor: Int) {
        val d = button.resources.displayMetrics.density
        val radius = CORNER_RADIUS_DP * d

        val content = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = radius
            setStroke(d.toInt().coerceAtLeast(1), borderColor)
        }
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radius
        }
        button.background = RippleDrawable(rippleColor, content, mask)
        button.isAllCaps = false
    }
}
