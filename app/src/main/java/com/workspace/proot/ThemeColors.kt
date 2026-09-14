package com.workspace.proot

import com.google.android.material.color.utilities.Scheme

/** 语义色槽位，值由 Material 3 tonal palette（seed = 品牌绿 #2D7D46）生成。
 *
 *  混合决策：surface 体系取 Scheme.dark（深色终端定位），primary/error 取
 *  Scheme.light 的 tone-40 档，保证全站白字按钮对比度 ≥4.5:1。
 *  outline 槽映射为 M3 outlineVariant（tone 30），保持分隔线/次要按钮深色观感。
 */
data class ThemeColors(
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

        private const val TONE_LOWEST = 4f
        private const val TONE_LOW = 10f
        private const val TONE_BASE = 6f
        private const val TONE_CONTAINER = 12f
        private const val TONE_HIGH = 17f
        private const val TONE_HIGHEST = 22f
        private const val TONE_MAX = 100f
        private const val ALPHA_SHIFT = 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private const val BYTE_MASK = 0xFF

        fun default(): ThemeColors {
            val dark = Scheme.dark(SEED)
            val light = Scheme.light(SEED)
            return ThemeColors(
                surface = dark.surface,
                surfaceContainerLowest = containerTone(dark.surface, TONE_LOWEST),
                surfaceContainerLow = containerTone(dark.surface, TONE_LOW),
                surfaceContainer = containerTone(dark.surface, TONE_CONTAINER),
                surfaceContainerHigh = containerTone(dark.surface, TONE_HIGH),
                surfaceContainerHighest = containerTone(dark.surface, TONE_HIGHEST),
                surfaceVariant = dark.surfaceVariant,
                primaryContainer = dark.primaryContainer,
                outline = dark.outlineVariant,
                onSurface = dark.onSurface,
                onSurfaceVariant = dark.onSurfaceVariant,
                primary = SEED,
                error = light.error,
                tertiary = dark.tertiary,
                onPrimary = light.onPrimary,
                onPrimaryContainer = dark.onPrimaryContainer,
                secondary = dark.secondary,
                onSecondary = dark.onSecondary,
                secondaryContainer = dark.secondaryContainer,
                onSecondaryContainer = dark.onSecondaryContainer,
                onTertiary = dark.onTertiary,
                tertiaryContainer = dark.tertiaryContainer,
                onTertiaryContainer = dark.onTertiaryContainer,
                onError = light.onError,
                errorContainer = dark.errorContainer,
                onErrorContainer = dark.onErrorContainer,
                outlineVariant = dark.outlineVariant,
                surfaceTint = SEED
            )
        }

        /** M3 dark surface 基 tone≈6；容器档按标准 tone 阶梯向黑/白内插近似。 */
        private fun containerTone(surface: Int, tone: Float): Int {
            val fraction = if (tone <= TONE_BASE) (TONE_BASE - tone) / TONE_BASE else (tone - TONE_BASE) / (TONE_MAX - TONE_BASE)
            val endpoint = if (tone <= TONE_BASE) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
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
