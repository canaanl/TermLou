package com.workspace.proot

import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.Scheme

/** 调色盘自定义配色：手动可设仅两项——背景与主题绿（seed），其余槽位按对比度规则推导。 */
data class PaletteSpec(val background: Int, val seed: Int)

/** 语义色槽位，值由 Material 3 tonal palette（seed = 品牌绿 #2D7D46）生成。
 *
 *  夜间（默认）：surface 体系取 Scheme.dark（深色终端定位），primary/error 取
 *  Scheme.light 的 tone-40 档，保证全站白字按钮对比度 ≥4.5:1。
 *  日间（night=false）：整体取 Scheme.light，primary 保持品牌绿 SEED。
 *  outline 槽夜间映射为 M3 outlineVariant（tone 30），日间用 light.outline（tone 50）。
 *  调色盘档（palette!=null）：surface=自定义背景，onSurface/图标由背景亮度择优选黑/白
 *  （≥4.5:1），容器阶梯与红/蓝明度档全部随背景推导（见 fromPalette）。
 */
data class ThemeColors(
    val night: Boolean = true,
    val palette: Boolean = false,
    val surface: Int = 0xFF1E1E1E.toInt(),
    val surfaceVariant: Int = 0xFF252526.toInt(),
    val surfaceContainerLowest: Int = 0xFF161617.toInt(),
    val surfaceContainerLow: Int = 0xFF1B1B1C.toInt(),
    val surfaceContainer: Int = 0xFF242426.toInt(),
    val surfaceContainerHigh: Int = 0xFF2A2A2C.toInt(),
    val surfaceContainerHighest: Int = 0xFF333335.toInt(),
    val primaryContainer: Int = 0xFF3A3A3A.toInt(),
    val outline: Int = 0xFF3E3E3E.toInt(),
    val onSurface: Int = 0xFFFFFFFF.toInt(),
    val onSurfaceVariant: Int = 0xFF888888.toInt(),
    val primary: Int = 0xFF2D7D46.toInt(),
    val error: Int = 0xFFC0392B.toInt(),
    val tertiary: Int = 0xFF0E639C.toInt(),
    val onPrimary: Int = 0xFFFFFFFF.toInt(),
    val onPrimaryContainer: Int = 0xFFE1FFE9.toInt(),
    val secondary: Int = 0xFFB2C7B6.toInt(),
    val onSecondary: Int = 0xFF1E3222.toInt(),
    val secondaryContainer: Int = 0xFF344837.toInt(),
    val onSecondaryContainer: Int = 0xFFCEE4D0.toInt(),
    val onTertiary: Int = 0xFF00363F.toInt(),
    val tertiaryContainer: Int = 0xFF004F5C.toInt(),
    val onTertiaryContainer: Int = 0xFFA1EFFF.toInt(),
    val onError: Int = 0xFFFFFFFF.toInt(),
    val errorContainer: Int = 0xFF93000A.toInt(),
    val onErrorContainer: Int = 0xFFFFDAD6.toInt(),
    val outlineVariant: Int = 0xFF3E4541.toInt(),
    val surfaceTint: Int = 0xFF2D7D46.toInt()
) {
    companion object {
        const val SEED = 0xFF2D7D46.toInt()

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val BLACK = 0xFF000000.toInt()
        private const val ERROR_TONE_DARK = 62f
        private const val ERROR_TONE_LIGHT = 45f
        private const val TERTIARY_TONE_DARK = 75f
        private const val TERTIARY_TONE_LIGHT = 45f

        /** 品牌锚点（默认 SEED 的昼档 scheme）：调色盘下红/蓝色相固定取自这里，保证可识别。 */
        private val ANCHOR_LIGHT: Scheme by lazy { Scheme.light(SEED) }

        private const val TONE_LOWEST = 4f
        private const val TONE_LOW = 10f
        private const val TONE_CONTAINER = 12f
        private const val TONE_HIGH = 17f
        private const val TONE_HIGHEST = 22f
        private const val TONE_LOWEST_DAY = 100f
        private const val TONE_LOW_DAY = 96f
        private const val TONE_BASE_NIGHT = 6f
        private const val TONE_BASE_DAY = 98f
        private const val TONE_CONTAINER_DAY = 94f
        private const val TONE_HIGH_DAY = 92f
        private const val TONE_HIGHEST_DAY = 90f
        private const val TONE_MAX = 100f
        private const val ALPHA_SHIFT = 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private const val BYTE_MASK = 0xFF

        /** 昼夜两档默认（palette=null，与历史行为逐位一致）；调色盘档见 fromPalette。 */
        fun default(night: Boolean = true, palette: PaletteSpec? = null): ThemeColors {
            palette?.let { return fromPalette(it) }
            val dark = Scheme.dark(SEED)
            val light = Scheme.light(SEED)
            val base = if (night) dark else light
            val baseTone = if (night) TONE_BASE_NIGHT else TONE_BASE_DAY
            val lowest = if (night) TONE_LOWEST else TONE_LOWEST_DAY
            val low = if (night) TONE_LOW else TONE_LOW_DAY
            val container = if (night) TONE_CONTAINER else TONE_CONTAINER_DAY
            val high = if (night) TONE_HIGH else TONE_HIGH_DAY
            val highest = if (night) TONE_HIGHEST else TONE_HIGHEST_DAY
            return ThemeColors(
                night = night,
                surface = base.surface,
                surfaceContainerLowest = containerTone(base.surface, lowest, baseTone),
                surfaceContainerLow = containerTone(base.surface, low, baseTone),
                surfaceContainer = containerTone(base.surface, container, baseTone),
                surfaceContainerHigh = containerTone(base.surface, high, baseTone),
                surfaceContainerHighest = containerTone(base.surface, highest, baseTone),
                surfaceVariant = base.surfaceVariant,
                primaryContainer = base.primaryContainer,
                outline = if (night) dark.outlineVariant else light.outline,
                onSurface = base.onSurface,
                onSurfaceVariant = base.onSurfaceVariant,
                primary = SEED,
                error = light.error,
                tertiary = base.tertiary,
                onPrimary = light.onPrimary,
                onPrimaryContainer = base.onPrimaryContainer,
                secondary = base.secondary,
                onSecondary = base.onSecondary,
                secondaryContainer = base.secondaryContainer,
                onSecondaryContainer = base.onSecondaryContainer,
                onTertiary = base.onTertiary,
                tertiaryContainer = base.tertiaryContainer,
                onTertiaryContainer = base.onTertiaryContainer,
                onError = light.onError,
                errorContainer = base.errorContainer,
                onErrorContainer = base.onErrorContainer,
                outlineVariant = base.outlineVariant,
                surfaceTint = SEED
            )
        }

        /** 调色盘档：手动可设仅背景+主题绿，其余槽位由背景按对比度规则推导。 */
        private fun fromPalette(p: PaletteSpec): ThemeColors {
            val bg = p.background
            val seed = p.seed
            val ink = ColorMath.inkOf(bg)
            // 对比度择优即明暗判定：交叉点亮度 ≤0.1791 → 深底白字。
            val dark = ink == WHITE
            val primaryContainer = ColorMath.mix(bg, seed, 0.20f)
            val secondary = ColorMath.mix(bg, seed, 0.50f)
            val secondaryContainer = ColorMath.mix(bg, seed, 0.28f)
            val surfaceVariant = ColorMath.mix(bg, ink, 0.07f)
            // 彩色语义槽：色相固定取品牌锚点（红/蓝可识别），明度档随背景亮度走。
            val error = retone(ANCHOR_LIGHT.error, if (dark) ERROR_TONE_DARK else ERROR_TONE_LIGHT)
            val tertiary = retone(ANCHOR_LIGHT.tertiary, if (dark) TERTIARY_TONE_DARK else TERTIARY_TONE_LIGHT)
            val tertiaryContainer = ColorMath.mix(bg, tertiary, 0.25f)
            val errorContainer = ColorMath.mix(bg, error, 0.25f)
            // 容器阶梯：最低档沉向极端色（深底→黑、浅底→白），其余逐级升向 ink；
            // 比例对齐昼夜两档的既有观感（夜 4/6/12/17% 白、昼 2/4/6/8% 黑）。
            val lowR: Float
            val containerR: Float
            val highR: Float
            val highestR: Float
            if (dark) {
                lowR = 0.04f; containerR = 0.06f; highR = 0.12f; highestR = 0.17f
            } else {
                lowR = 0.02f; containerR = 0.04f; highR = 0.06f; highestR = 0.08f
            }
            return ThemeColors(
                night = dark,
                palette = true,
                surface = bg,
                surfaceContainerLowest = ColorMath.mix(bg, if (dark) BLACK else WHITE, if (dark) 0.35f else 1f),
                surfaceContainerLow = ColorMath.mix(bg, ink, lowR),
                surfaceContainer = ColorMath.mix(bg, ink, containerR),
                surfaceContainerHigh = ColorMath.mix(bg, ink, highR),
                surfaceContainerHighest = ColorMath.mix(bg, ink, highestR),
                surfaceVariant = surfaceVariant,
                primaryContainer = primaryContainer,
                outline = ColorMath.mix(bg, ink, if (dark) 0.16f else 0.42f),
                onSurface = ink,
                onSurfaceVariant = ColorMath.readableMix(bg, ink),
                primary = seed,
                error = error,
                tertiary = tertiary,
                onPrimary = ColorMath.inkOf(seed),
                onPrimaryContainer = ColorMath.inkOf(primaryContainer),
                secondary = secondary,
                onSecondary = ColorMath.inkOf(secondary),
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = ColorMath.inkOf(secondaryContainer),
                onTertiary = ColorMath.inkOf(tertiary),
                tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = ColorMath.inkOf(tertiaryContainer),
                onError = ColorMath.inkOf(error),
                errorContainer = errorContainer,
                onErrorContainer = ColorMath.inkOf(errorContainer),
                outlineVariant = ColorMath.mix(bg, ink, if (dark) 0.12f else 0.32f),
                surfaceTint = seed
            )
        }

        /** 保留色相/彩度只换 tone（HCT）：红/蓝在任何背景下都可识别且明度档随背景走。 */
        private fun retone(color: Int, tone: Float): Int {
            val h = Hct.fromInt(color)
            return Hct.from(h.hue, h.chroma, tone.toDouble()).toInt()
        }

        /** M3 surface 基 tone（深 6 / 浅 98）；容器档按标准 tone 阶梯向黑/白内插近似。 */
        private fun containerTone(surface: Int, tone: Float, base: Float): Int {
            val fraction = if (tone <= base) (base - tone) / base else (tone - base) / (TONE_MAX - base)
            val endpoint = if (tone <= base) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            val sR = (surface shr RED_SHIFT) and BYTE_MASK
            val sG = (surface shr GREEN_SHIFT) and BYTE_MASK
            val sB = surface and BYTE_MASK
            val eR = (endpoint shr RED_SHIFT) and BYTE_MASK
            val eG = (endpoint shr GREEN_SHIFT) and BYTE_MASK
            val eB = endpoint and BYTE_MASK
            val r = (sR + (eR - sR) * fraction).toInt()
            val g = (sG + (eG - sG) * fraction).toInt()
            val b = (sB + (eB - sB) * fraction).toInt()
            return (BYTE_MASK shl ALPHA_SHIFT) or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
        }
    }
}
