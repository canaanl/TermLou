package com.workspace.proot

/**
 * 默认 TERMLOU 点阵，已按 2×2 放大（1 像素拆 4）。
 * 原 9×6 → 现 18×12，清晰度 4 倍，屏尺寸不变。
 */
object SplashLetters {
    const val ROWS = 18
    const val COLS_PER_LETTER = 12

    val T = arrayOf(
        booleanArrayOf(true, true, true, true, true, true, true, true, true, true, true, true),
        booleanArrayOf(true, true, true, true, true, true, true, true, true, true, true, true),
        booleanArrayOf(false, false, false, false, true, true, true, true, false, false, false, false),
        booleanArrayOf(false, false, false, false, true, true, true, true, false, false, false, false),
        booleanArrayOf(false, false, false, false, true, true, true, true, false, false, false, false),
        booleanArrayOf(false, false, false, false, true, true, true, true, false, false, false, false),
        booleanArrayOf(false, false, false, false, true, true, true, true, false, false, false, false),
        booleanArrayOf(false, false, false, false, true, true, true, true, false, false, false, false),
        booleanArrayOf(false, false, false, false, true, true, true, true, false, false, false, false),
        booleanArrayOf(false, false, false, false, true, true, true, true, false, false, false, false),
        booleanArrayOf(false, false, false, false, true, true, true, true, false, false, false, false),
        booleanArrayOf(false, false, false, false, true, true, true, true, false, false, false, false),
        booleanArrayOf(false, false, false, false, true, true, true, true, false, false, false, false),
        booleanArrayOf(false, false, false, false, true, true, true, true, false, false, false, false),
        booleanArrayOf(false, false, false, false, true, true, true, true, false, false, false, false),
        booleanArrayOf(false, false, false, false, true, true, true, true, false, false, false, false),
        booleanArrayOf(false, false, false, false, true, true, true, true, false, false, false, false),
        booleanArrayOf(false, false, false, false, true, true, true, true, false, false, false, false)
    )
    val E = arrayOf(
        booleanArrayOf(true, true, true, true, true, true, true, true, true, true, true, true),
        booleanArrayOf(true, true, true, true, true, true, true, true, true, true, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, true, true, true, true, true, true, false, false, false, false),
        booleanArrayOf(true, true, true, true, true, true, true, true, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, true, true, true, true, true, true, true, true, true, true),
        booleanArrayOf(true, true, true, true, true, true, true, true, true, true, true, true)
    )
    val R = arrayOf(
        booleanArrayOf(true, true, true, true, true, true, true, true, true, true, false, false),
        booleanArrayOf(true, true, true, true, true, true, true, true, true, true, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, true, true, true, true, true, true, true, true, false, false),
        booleanArrayOf(true, true, true, true, true, true, true, true, true, true, false, false),
        booleanArrayOf(true, true, false, false, false, false, true, true, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, true, true, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, true, true, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, true, true, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, true, true, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, true, true, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, true, true, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, true, true, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true)
    )
    val M = arrayOf(
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, true, true, false, false, false, false, true, true, true, true),
        booleanArrayOf(true, true, true, true, false, false, false, false, true, true, true, true),
        booleanArrayOf(true, true, true, true, true, true, true, true, true, true, true, true),
        booleanArrayOf(true, true, true, true, true, true, true, true, true, true, true, true),
        booleanArrayOf(true, true, false, false, true, true, true, true, false, false, true, true),
        booleanArrayOf(true, true, false, false, true, true, true, true, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true)
    )
    val L = arrayOf(
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, true, true, true, true, true, true, true, true, true, true),
        booleanArrayOf(true, true, true, true, true, true, true, true, true, true, true, true)
    )
    val O = arrayOf(
        booleanArrayOf(false, false, true, true, true, true, true, true, true, true, false, false),
        booleanArrayOf(false, false, true, true, true, true, true, true, true, true, false, false),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(false, false, true, true, true, true, true, true, true, true, false, false),
        booleanArrayOf(false, false, true, true, true, true, true, true, true, true, false, false)
    )
    val U = arrayOf(
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false, true, true),
        booleanArrayOf(false, false, true, true, true, true, true, true, true, true, false, false),
        booleanArrayOf(false, false, true, true, true, true, true, true, true, true, false, false)
    )
}
