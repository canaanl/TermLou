package com.workspace.proot

import android.graphics.drawable.GradientDrawable
import android.widget.EditText

/** 输入框统一外观：浅填充 + 圆角描边（夜间用半透明白填底，同选应用弹窗搜索框），只改样式不动逻辑。 */
object FieldStyle {
    private const val RADIUS_DP = 8f
    private const val STROKE_DP = 1f
    private const val HINT_ALPHA = 0x80
    private const val ALPHA_SHIFT = 24
    private const val RGB_MASK = 0x00FFFFFF

    fun applyOutlined(edit: EditText, theme: ThemeColors, textSizeSp: Float) {
        val d = edit.resources.displayMetrics.density
        edit.background = GradientDrawable().apply {
            cornerRadius = RADIUS_DP * d
            setStroke(
                (STROKE_DP * d).toInt(),
                if (theme.night) theme.onSurfaceVariant else theme.outline
            )
            // 夜间弹窗底色偏深，纯描边不可见；填充半透明白与选应用搜索框一致
            setColor(if (theme.night) UiTokens.searchBg else theme.surfaceContainerHighest)
        }
        edit.setTextColor(theme.onSurface)
        edit.setHintTextColor((theme.onSurfaceVariant and RGB_MASK) or (HINT_ALPHA shl ALPHA_SHIFT))
        edit.textSize = textSizeSp
    }
}