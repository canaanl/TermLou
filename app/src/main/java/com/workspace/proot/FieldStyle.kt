package com.workspace.proot

import android.graphics.drawable.GradientDrawable
import android.view.View
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

    /** 描边圆角背景：EditText 和复合搜索框容器共用，避免两处各写一套圆角/描边。 */
    internal fun outlinedBackground(theme: ThemeColors, density: Float): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = snapCorner(RADIUS_DP) * density
            setStroke(
                (STROKE_DP * density).toInt(),
                if (theme.night) theme.onSurfaceVariant else theme.outline
            )
            // 夜间弹窗底色偏深，纯描边不可见；填充半透明白与选应用搜索框一致
            setColor(if (theme.night) UiTokens.searchBg else theme.surfaceContainerHighest)
        }

    /** 描边容器（复合搜索框）版：背景画在容器上，内部 EditText 透明。 */
    fun applyOutlinedContainer(view: View, theme: ThemeColors) {
        view.background = outlinedBackground(theme, view.resources.displayMetrics.density)
    }

    /** 提示文字色：与输入框 hint 完全同源，复合搜索框的内嵌 EditText 也用它。 */
    internal fun hintColor(theme: ThemeColors): Int =
        (theme.onSurfaceVariant and RGB_MASK) or (HINT_ALPHA shl ALPHA_SHIFT)

    fun applyOutlined(edit: EditText, theme: ThemeColors, textSizeSp: Float) {
        edit.background = outlinedBackground(theme, edit.resources.displayMetrics.density)
        edit.setTextColor(theme.onSurface)
        edit.setHintTextColor(hintColor(theme))
        edit.textSize = textSizeSp
    }
}