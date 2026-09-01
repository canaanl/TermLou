package com.workspace.proot

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.widget.Button

object ButtonStyle {
    internal const val CORNER_RADIUS_DP = 20f
    private const val EDGE_HEIGHT_DP = 2f

    private fun ripple(content: Drawable, radius: Float, density: Float): RippleDrawable {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radius
        }
        return RippleDrawable(ColorStateList.valueOf(0x1AFFFFFF.toInt()), content, mask)
    }

    fun apply(button: Button, bgColor: Int) {
        val d = button.resources.displayMetrics.density
        val radius = CORNER_RADIUS_DP * d
        val edgeH = EDGE_HEIGHT_DP * d

        val surface = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = radius
        }

        val edge = GradientDrawable().apply {
            setColor(darken(bgColor, 0.3f))
            cornerRadius = radius
        }

        val ld = LayerDrawable(arrayOf(edge, surface))
        // edge 层四周收进约 2dp（按密度换算，纯像素在高密度屏上不可见）：
        // 圆角切口处直接露出父容器背景色，避免暗色边污染按键间隙；底部立体沿保留
        val lip = (2 * d).toInt()
        ld.setLayerInset(0, lip, lip, lip, 0)
        ld.setLayerInset(1, 0, 0, 0, edgeH.toInt())
        button.background = ripple(ld, radius, d)

        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    applyPressed(button, bgColor)
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    apply(button, bgColor)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                }
            }
            false
        }
    }

    private fun applyPressed(button: Button, bgColor: Int) {
        val d = button.resources.displayMetrics.density
        val radius = CORNER_RADIUS_DP * d

        val surface = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = radius
        }

        val edge = GradientDrawable().apply {
            setColor(darken(bgColor, 0.3f))
            cornerRadius = radius
        }

        val ld = LayerDrawable(arrayOf(edge, surface))
        ld.setLayerInset(1, 0, 0, 0, 0)

        button.background = ripple(ld, radius, d)
    }

    private fun darken(color: Int, factor: Float): Int {
        val r = (Color.red(color) * (1 - factor)).toInt()
        val g = (Color.green(color) * (1 - factor)).toInt()
        val b = (Color.blue(color) * (1 - factor)).toInt()
        return Color.rgb(r, g, b)
    }
}
