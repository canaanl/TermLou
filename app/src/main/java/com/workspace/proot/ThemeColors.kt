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

        fun default(): ThemeColors {
            val dark = Scheme.dark(SEED)
            val light = Scheme.light(SEED)
            return ThemeColors(
                surface = dark.surface,
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
    }
}
