@file:Suppress("MagicNumber", "ReturnCount")

package com.workspace.proot

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

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
            includeFontPadding = false
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
        centerGlyph(view)
    }

    /**
     * 把字形**墨迹**（而不是字体行盒）垂直居中到 FAB 圆心。
     *
     * `TextView` 的 `Gravity.CENTER` 居中行盒；乘法号 `×` 的墨迹本身偏下，
     * 所以看起来会“掉下去”。这里用 `Paint.getTextBounds` 量出墨迹中心，
     * 用上下 padding 补偿（不移动 FAB 背景），并关掉字体额外留白。
     * 每种字体/字号都重新计算，不写死 dp 偏移。
     */
    internal fun centerGlyph(view: TextView) {
        view.includeFontPadding = false
        val apply = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                if (v.width <= 0 || v.height <= 0) return
                v.removeOnLayoutChangeListener(this)
                applyGlyphPadding(view)
            }
        }
        view.addOnLayoutChangeListener(apply)
        view.post {
            if (view.width > 0 && view.height > 0) {
                view.removeOnLayoutChangeListener(apply)
                applyGlyphPadding(view)
            }
        }
    }

    private fun applyGlyphPadding(view: TextView) {
        val text = view.text?.toString().orEmpty()
        view.setPadding(0, 0, 0, 0)
        if (text.isEmpty()) return
        val paint = view.paint
        if (paint.textSize <= 0f) return
        val fm = paint.fontMetrics
        // Robolectric 的 Paint 不实现字体度量；真机上正常。没有度量时不乱加 padding。
        if (fm.ascent == 0f && fm.descent == 0f) return
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val lineCenter = (fm.ascent + fm.descent) / 2f
        val inkCenter = bounds.centerY().toFloat()
        val pad = ((inkCenter - lineCenter) * 2f).roundToInt()
        val maxPad = (4 * view.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        when {
            pad > maxPad -> view.setPadding(0, 0, 0, maxPad)
            pad < -maxPad -> view.setPadding(0, maxPad, 0, 0)
            pad > 0 -> view.setPadding(0, 0, 0, pad)
            pad < 0 -> view.setPadding(0, -pad, 0, 0)
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
