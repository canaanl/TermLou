package com.workspace.proot

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat

/**
 * 聚合按钮的图标（5.12.6）。
 *
 * 为什么不用 `TextView.setCompoundDrawables`：
 * 复合图标的 drawable 挂在**文字布局**的起点上。聚合按钮文字为空时，
 * `Gravity.CENTER` 只居中零宽文字，drawable 从那个点向一侧排，表现为严重居左；
 * 而且复合图标取到的是共享 ConstantState 的实例，`setTint` 不保证触发重绘，
 * 颜色会露出矢量图里的原始黑色。
 *
 * 这里改成把图标**按 bounds 中心显式绘制**，尺寸与颜色都由本类决定，
 * 不依赖 TextView 的任何布局行为。颜色语义与 [FileBackFab] 完全一致：
 * 启用 = [ThemeColors.primaryVisible]，禁用 = [NeuFab.disabledIconColor]。
 */
internal class DigestIconDrawable(
    context: Context,
    private val iconDp: Int = ICON_DP,
) : Drawable() {

    private val density = context.resources.displayMetrics.density
    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_notes_digest)
        ?.mutate()
        ?: error("ic_notes_digest missing")

    /** 显式设置图标颜色；内部对 mutate 后的实例 setTint，保证触发重绘。 */
    var color: Int = 0
        set(value) {
            field = value
            icon.setTint(value)
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val rect = iconRect()
        icon.setBounds(rect.left, rect.top, rect.right, rect.bottom)
        icon.draw(canvas)
    }

    /**
     * 图标在自身 bounds 内的绘制矩形：边长 [iconDp]，居中对齐。
     *
     * 抽成方法便于单测直接验证几何，不依赖 Robolectric 的矢量光栅化。
     */
    internal fun iconRect(): android.graphics.Rect {
        val size = (iconDp * density).toInt().coerceAtLeast(1)
        val left = (bounds.exactCenterX() - size / 2f).toInt()
        val top = (bounds.exactCenterY() - size / 2f).toInt()
        return android.graphics.Rect(left, top, left + size, top + size)
    }

    override fun setAlpha(alpha: Int) {
        icon.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        icon.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    internal companion object {
        /** 与 28sp 字形的墨迹大小对齐（约 17–18dp），比原 24dp 明显小一圈。 */
        const val ICON_DP = 18
    }
}
