package com.workspace.proot

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 拟态悬浮圆键的**唯一视觉出处**。
 *
 * 笔记页的「＋」、独立笔记页的「＋」、文件页的「←」全部走这里，
 * 不再各写一份 56dp/三图层/16dp 边距的字面量 —— 三处只要有一处改了参数，
 * 另外两处就会悄悄走样。
 *
 * 颜色规则：
 * - 启用态符号 = [ThemeColors.primaryVisible]（品牌色在背景上的可见变体，
 *   品牌色≈背景色时也不会消失）；
 * - 禁用态符号 = [disabledIconColor]，由 FAB 表面色与主题中性色算出，
 *   是「看得见但明显弱化」的中性灰，不随品牌色换色而失效。
 */
internal object NeuFab {

    private const val FACE_SIZE_DP = 56
    private const val SHADOW_DP = 3
    private const val DARK_SHADOW_RATIO = 0.45f
    private const val LIGHT_SHADOW_RATIO = 0.18f
    private const val ELEVATION_DP = 6
    private const val MARGIN_DP = 16

    /** 与笔记页原实现逐项一致：56dp 正圆 + 右下暗影 + 左上高光 + 6dp elevation。 */
    fun create(
        context: Context,
        theme: ThemeColors,
        glyph: String,
        contentDescription: CharSequence,
        onClick: () -> Unit
    ): TextView = TextView(context).also {
        style(it, theme, glyph, contentDescription, onClick)
    }

    /** 把一颗已存在的 TextView 装扮成同一款 FAB；[FileBackFab] 用它保留自己的状态机。 */
    fun style(
        view: TextView,
        theme: ThemeColors,
        glyph: String,
        contentDescription: CharSequence,
        onClick: () -> Unit
    ) {
        val d = view.resources.displayMetrics.density
        val size = (FACE_SIZE_DP * d).toInt()
        val shadow = (SHADOW_DP * d).toInt()
        val face = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.surface)
        }
        val darkShadow = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorMath.mix(theme.surface, 0xFF000000.toInt(), DARK_SHADOW_RATIO))
        }
        val lightShadow = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorMath.mix(theme.surface, 0xFFFFFFFF.toInt(), LIGHT_SHADOW_RATIO))
        }
        view.apply {
            text = glyph
            gravity = Gravity.CENTER
            setTextColor(theme.primaryVisible)
            textSize = UiTokens.TEXT_DISPLAY
            background = LayerDrawable(arrayOf(darkShadow, lightShadow, face)).apply {
                setLayerInset(0, shadow, shadow, 0, 0)
                setLayerInset(1, 0, 0, shadow, shadow)
            }
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                marginEnd = (MARGIN_DP * d).toInt()
                bottomMargin = (MARGIN_DP * d).toInt()
            }
            elevation = ELEVATION_DP * d
            this.contentDescription = contentDescription
            setOnClickListener { onClick() }
        }
    }

    /**
     * 禁用态符号色：从 FAB 表面色朝主题中性色混到约 2:1 对比度。
     *
     * 不写死 `#888888`，也不直接拿品牌色降透明度：
     * - 背景换成纯黑/纯白/中灰/极端彩时，2:1 仍然「看得见」；
     * - 中性色与背景几乎同色时，[ColorMath.disabledTint] 会退到背景的反色方向，
     *   保证不会算出与表面完全融为一体的颜色。
     */
    fun disabledIconColor(theme: ThemeColors): Int =
        ColorMath.disabledTint(theme.surface, theme.onSurfaceVariant)

    /** 列表底部留白：FAB 直径 + 上下各一份 16dp 边距，最后一行能滚到键上方。 */
    fun clearancePx(context: Context): Int =
        ((FACE_SIZE_DP + MARGIN_DP * 2) * context.resources.displayMetrics.density).toInt()
}
