package com.workspace.proot

import android.graphics.drawable.GradientDrawable
import android.widget.EditText

/** 输入框统一外观：浅填充 + 圆角描边（夜间用半透明白填底，同选应用弹窗搜索框），只改样式不动逻辑。 */
object FieldStyle {
    /** 5.9.50：由 private 放开为 internal —— 它是"全站输入框圆角"的唯一真相来源，
     *  调色盘预览要断言自己与真实输入框对齐（见 UiDimsScanTest），得能读到。
     *  与 `SegmentStyle.RADIUS_DP` / `ButtonStyle.CORNER_RADIUS_DP` 同一档做法。 */
    internal const val RADIUS_DP = 8f
    private const val STROKE_DP = 1f
    private const val HINT_ALPHA = 0x80
    private const val ALPHA_SHIFT = 24
    private const val RGB_MASK = 0x00FFFFFF

    fun applyOutlined(edit: EditText, theme: ThemeColors, textSizeSp: Float) {
        val d = edit.resources.displayMetrics.density
        edit.background = GradientDrawable().apply {
            cornerRadius = snapCorner(RADIUS_DP) * d
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