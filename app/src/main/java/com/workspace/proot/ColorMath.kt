package com.workspace.proot

import com.google.android.material.color.utilities.Hct
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 调色盘颜色数学：WCAG 相对亮度、对比度、背景「反色」（按亮度择优选纯黑/纯白）、
 * 混色与可读性混色。是调色盘全部推导规则的唯一出处；纯函数，JVM 单测可锁死
 * （见 PaletteThemeTest：字体/图标对任意背景 ≥4.5:1）。
 */
object ColorMath {

    /** 墨色交叉点亮度：≤ 此值用白字更清晰（黑白对比度曲线交点，两侧均 ≥4.58:1）。 */
    private const val INK_WHITE_MAX_LUM = 0.1791

    /** 可读性混色的起始混入比与步进。 */
    private const val READABLE_START = 0.45f
    private const val READABLE_STEP = 0.05f

    /**
     * 可见性推导的「舒适区」对比度：≥ 此值推导色与品牌色逐位一致（零漂移），
     * 低于后按连续平方曲线向反色偏移——是平滑过渡的尺度参数，不是开关式阈值。
     */
    private const val VISIBLE_SHIFT_START = 2.6

    private fun linearize(channel: Int): Double {
        val s = channel / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    /** WCAG 相对亮度（0..1）。 */
    fun relativeLuminance(color: Int): Double {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)
    }

    /** WCAG 对比度（1..21）。 */
    fun contrast(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = max(la, lb)
        val lo = min(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * 背景的「反色」墨色：按相对亮度择优选纯白/纯黑（≤0.1791 用白）。
     * 中间调取更优的一侧，交叉点两侧均 ≥4.58:1 —— 不做字面按位取反
     * （#808080 按位取反 ≈ 自身会看不清）。
     */
    fun inkOf(background: Int): Int =
        if (relativeLuminance(background) <= INK_WHITE_MAX_LUM) {
            0xFFFFFFFF.toInt()
        } else {
            0xFF000000.toInt()
        }

    /** a→b 线性混色，t∈[0,1]；alpha 恒 0xFF（不透明）。 */
    fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val av = (a shr shift) and 0xFF
            val bv = (b shr shift) and 0xFF
            return (av + (bv - av) * u).toInt()
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** HCT 色相旋转（度，负值/超 360 均可）：彩度与明度不变——调色盘点阵渐变第二色的推导规则。 */
    fun hueRotate(color: Int, degrees: Double): Int {
        val h = Hct.fromInt(color)
        val hue = ((h.hue + degrees) % 360.0 + 360.0) % 360.0
        return Hct.from(hue, h.chroma, h.tone).toInt()
    }

    /**
     * 沿 bg→ink 方向找满足最小对比度的**最低**混入比（0.45..0.95 步进 0.05）；
     * 兜底返回 ink（ink 自身 ≥4.58:1 必达标）。中间调背景会自动升到近墨色。
     */
    fun readableMix(background: Int, ink: Int, minContrast: Double = 4.5): Int {
        var t = READABLE_START
        while (t < 1f) {
            val c = mix(background, ink, t)
            if (contrast(c, background) >= minContrast) return c
            t += READABLE_STEP
        }
        return ink
    }

    /**
     * 品牌色在背景上的可见变体（纯系数推导，无阈值开关）：
     * `推导色 = 品牌色 + k × (反色(背景) − 品牌色)`，k 随两色对比度连续变化——
     * 相差远 → k=0 与品牌色逐位一致；越接近 → k↑ 越向背景的反色（纯黑/纯白）偏移，
     * 同色时 k=1 直接落到反色上，保证任何配色下元素都可辨识。
     * 只用于描边/文字/图标/粒子等「需要在背景上可见」的场合；品牌填充仍用原色。
     */
    fun visibleVariant(brand: Int, bg: Int): Int {
        val k = visibilityShift(contrast(brand, bg))
        return if (k <= 0f) brand else mix(brand, inkOf(bg), k)
    }

    /** 连续系数：c≥VISIBLE_SHIFT_START 为 0；到 1.0（同色）平滑升到 1（平方曲线，接缝处斜率为 0）。 */
    private fun visibilityShift(c: Double): Float {
        if (c >= VISIBLE_SHIFT_START) return 0f
        val t = ((VISIBLE_SHIFT_START - c) / (VISIBLE_SHIFT_START - 1.0)).coerceIn(0.0, 1.0)
        return (t * t).toFloat()
    }

    /** "#RRGGBB"（大写、去 alpha）——终端配色槽与色号直显共用。 */
    fun hex(color: Int): String =
        String.format(Locale.US, "#%06X", color and 0xFFFFFF)
}
