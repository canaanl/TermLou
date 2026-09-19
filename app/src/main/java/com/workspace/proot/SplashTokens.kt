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

    /** 灰阶档数上限（含灭）：0=灭，19=最亮。滑块值+2=实际档数。 */
    const val MAX_LEVELS = 20
    const val LEVEL_OFF = 0
    const val LEVEL_FULL = 19

    /** 各档渲染透明度（等比 0..255）。 */
    val LEVEL_ALPHAS = IntArray(MAX_LEVELS) { 255 * it / (MAX_LEVELS - 1) }

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

    /** 分带映射：灰度下方的阈值越少档位越高（暗→强，亮→灭），返回 0..档数-1。 */
    fun quantizeBands(gray: Int, thresholds: IntArray): Int {
        var v = 0
        for (t in thresholds) {
            if (gray < t) v++
        }
        return v
    }

    /** 档位序号归一到 0..19（2 档→{0,19}），渲染与存盘统一口径。 */
    fun bandToLevel(band: Int, bands: Int): Int {
        if (bands <= 1) return LEVEL_FULL
        return (band * LEVEL_FULL / (bands - 1)).coerceIn(LEVEL_OFF, LEVEL_FULL)
    }

    /** 反选：档位取反（灭↔强）。 */
    fun invertLevel(v: Int): Int = (LEVEL_FULL - v).coerceIn(LEVEL_OFF, LEVEL_FULL)

    /**
     * 分位阈值：按像素量把直方图切成 (bands) 等份，返回 bands-1 个阈值。
     * 自适应明暗照片；退化直方图（空/单值）返回合法非降序列，不崩。
     */
    fun percentileThresholds(hist: IntArray, total: Int, bands: Int = MAX_LEVELS): IntArray {
        if (total <= 0 || bands <= 1) return IntArray(maxOf(0, bands - 1))
        val out = IntArray(bands - 1)
        var acc = 0L
        var k = 0
        for (i in 0 until 256) {
            acc += hist[i].coerceAtLeast(0)
            while (k < bands - 1 && acc * bands >= (k + 1L) * total) {
                out[k] = i
                k++
            }
        }
        while (k < bands - 1) {
            out[k] = 255
            k++
        }
        return out
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
