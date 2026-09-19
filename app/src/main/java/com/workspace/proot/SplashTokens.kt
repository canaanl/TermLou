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

    /** 灰阶档数（含灭）：0=灭，1=弱，2=中，3=强。 */
    const val GRAY_LEVELS = 4
    const val LEVEL_OFF = 0
    const val LEVEL_FULL = 3

    /** 各档渲染透明度（弱/中/强）。 */
    val LEVEL_ALPHAS = intArrayOf(0, 90, 160, 255)

    /** 照片转化单元：行、列、灰阶档（缺省满级，兼容老文件）。 */
    data class SplashCell(val r: Int, val c: Int, val v: Int = LEVEL_FULL)

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

    /** 块面映射：暗→强，中→中，亮→灭。 */
    fun quantizeBlock(gray: Int, t1: Int, t2: Int): Int =
        if (gray < t1) 3 else if (gray < t2) 2 else 0

    /** 边缘映射：强边→强，弱边→弱，无边→灭。 */
    fun quantizeEdge(mag: Float, hi: Float, lo: Float): Int =
        if (mag >= hi) 3 else if (mag >= lo) 1 else 0

    /** 反选：档位取反（灭↔强，弱↔中）。 */
    fun invertLevel(v: Int): Int = (LEVEL_FULL - v).coerceIn(LEVEL_OFF, LEVEL_FULL)

    /**
     * Otsu 双阈值：枚举 t1<t2 使三类类间方差最大。
     * 返回 (t1, t2)，恒满足 0<=t1<t2<=255。
     */
    fun otsu2(hist: IntArray, total: Int): Pair<Int, Int> {
        val p = DoubleArray(256)
        for (i in 0 until 256) p[i] = hist[i].toDouble() / total
        val cumW = DoubleArray(256)
        val cumM = DoubleArray(256)
        var w = 0.0
        var m = 0.0
        for (i in 0 until 256) {
            w += p[i]
            m += i * p[i]
            cumW[i] = w
            cumM[i] = m
        }
        val totalMean = m
        var bestT1 = 0
        var bestT2 = 1
        var bestVar = -1.0
        for (t1 in 0 until 255) {
            val w0 = cumW[t1]
            if (w0 <= 0.0) continue
            val m0 = cumM[t1] / w0
            for (t2 in t1 + 1 until 256) {
                val w1 = cumW[t2] - cumW[t1]
                if (w1 <= 0.0) continue
                val w2 = 1.0 - cumW[t2]
                if (w2 <= 0.0) continue
                val m1 = (cumM[t2] - cumM[t1]) / w1
                val m2 = (totalMean - cumM[t2]) / w2
                val v = w0 * (m0 - totalMean) * (m0 - totalMean) +
                    w1 * (m1 - totalMean) * (m1 - totalMean) +
                    w2 * (m2 - totalMean) * (m2 - totalMean)
                if (v > bestVar) {
                    bestVar = v
                    bestT1 = t1
                    bestT2 = t2
                }
            }
        }
        return bestT1 to bestT2
    }

    /** 默认 TERMLOU 点阵（18 行 × 7 字母，1 拆 4），映射到 ROWS 网格：行 + LOGO_ROW_OFFSET。 */
    fun defaultCells(): List<SplashCell> {
        val out = mutableListOf<SplashCell>()
        var cx = 0
        val letters = listOf(
            SplashLetters.T, SplashLetters.E, SplashLetters.R,
            SplashLetters.M, SplashLetters.L, SplashLetters.O,
            SplashLetters.U
        )
        for (letter in letters) {
            for (r in 0 until LOGO_ROWS) {
                for (c in 0 until COLS_PER_LETTER) {
                    if (letter[r][c]) out.add(SplashCell(r + LOGO_ROW_OFFSET, cx + c))
                }
            }
            cx += COLS_PER_LETTER + 1
        }
        return out
    }
}
