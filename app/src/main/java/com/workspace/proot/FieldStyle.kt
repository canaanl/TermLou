package com.workspace.proot

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.EditText

/** M3 Outlined 文本框统一外观：透明底 + 圆角描边，只改样式不动逻辑。 */
object FieldStyle {
    private const val RADIUS_DP = 8f
    private const val STROKE_DP = 1f

    fun applyOutlined(
        edit: EditText,
        outline: Int,
        onSurface: Int,
        onSurfaceVariant: Int,
        textSizeSp: Float
    ) {
        val d = edit.resources.displayMetrics.density
        edit.background = GradientDrawable().apply {
            cornerRadius = RADIUS_DP * d
            setStroke((STROKE_DP * d).toInt(), outline)
            setColor(Color.TRANSPARENT)
        }
        edit.setTextColor(onSurface)
        edit.setHintTextColor(onSurfaceVariant)
        edit.textSize = textSizeSp
    }
}
