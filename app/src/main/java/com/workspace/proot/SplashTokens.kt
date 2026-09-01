package com.workspace.proot

/**
 * 开屏点阵抽象常量与工具：网格/像素/渐变/飞入参数统一来源。
 * SplashView 与 SplashMakerActivity 共用，保证工坊所见即所得。
 */
object SplashTokens {

    const val ROWS = 80
    const val COLS = 96
    const val COLS_PER_LETTER = 12
    const val LOGO_ROWS = 18
    const val LOGO_ROW_OFFSET = 31  // 默认 LOGO 18 行在 80 行网格中垂直居中（上下各留 31 行），屏尺寸不变、默认 logo 外观不变

    const val GRID_WIDTH_PCT = 0.9f

    const val CONVERGE_MS = 2000
    const val FADE_OUT_MS = 240

    const val FLY_DURATION_BASE = 550f
    const val FLY_DURATION_RANGE = 250f
    const val FLY_DELAY_LETTER = 70f
    const val FLY_DELAY_COL = 16f
    const val FLY_DELAY_RAND = 90
    const val PARTICLE_MARGIN_DP = 30f

    const val PIXEL_RADIUS_FACTOR = 0.26f
    const val PARTICLE_SIZE_FACTOR = 0.8f
    const val PARTICLE_CORNER_FACTOR = 0.3f
    const val GLOW_FACTOR = 1.1f

    val GREEN = UiTokens.primaryGreen
    val CYAN = UiTokens.tertiaryBlue

    fun pixelSize(screenW: Float): Float = (screenW * GRID_WIDTH_PCT) / COLS

    fun lerpColor(a: Int, b: Int, f: Float): Int {
        val ar = (a shr 16) and 0xFF
        val ag = (a shr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b shr 16) and 0xFF
        val bg = (b shr 8) and 0xFF
        val bb = b and 0xFF
        val r = (ar + (br - ar) * f).toInt()
        val g = (ag + (bg - ag) * f).toInt()
        val bl = (ab + (bb - ab) * f).toInt()
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or bl
    }

    fun lerpAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /** 像素渐变色：按列从左到右 brand 渐变。 */
    fun cellColor(col: Int): Int = lerpColor(GREEN, CYAN, col / (COLS - 1f))

    /** 默认 TERMLOU 点阵（18 行 × 7 字母，1 拆 4），映射到 ROWS 网格：行 + LOGO_ROW_OFFSET。 */
    fun defaultCells(): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var cx = 0
        val letters = listOf(
            SplashLetters.T, SplashLetters.E, SplashLetters.R,
            SplashLetters.M, SplashLetters.L, SplashLetters.O,
            SplashLetters.U
        )
        for (letter in letters) {
            for (r in 0 until LOGO_ROWS) {
                for (c in 0 until COLS_PER_LETTER) {
                    if (letter[r][c]) out.add(r + LOGO_ROW_OFFSET to cx + c)
                }
            }
            cx += COLS_PER_LETTER + 1
        }
        return out
    }
}
