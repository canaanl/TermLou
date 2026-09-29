package com.workspace.proot

/**
 * 截图"是不是空图"的判定（纯逻辑：只吃像素颜色，可单测）。
 *
 * 无头 WebView 截图最常见的假成功是**整屏同色**——view 在、尺寸对、文件也写了，
 * 但里面一个像素的内容都没有。所以截图落盘前先抽样判一下，别让 agent 拿到白图。
 */
object WebShotSampler {

    /** 抽样网格：8×12 足够抓住"整屏同色"，又不会为判空付出多少开销。 */
    const val COLS = 8
    const val ROWS = 12

    /** 超过这个颜色数就认定有内容（超过 1 即视为有内容）。 */
    const val COLOR_THRESHOLD = 2

    /**
     * @param width 位图宽
     * @param height 位图高
     * @param pixelAt 取色函数（(x, y) -> 颜色值）
     */
    fun looksBlank(width: Int, height: Int, pixelAt: (Int, Int) -> Int): Boolean {
        if (width <= 0 || height <= 0) return true
        val stepX = (width / COLS).coerceAtLeast(1)
        val stepY = (height / ROWS).coerceAtLeast(1)
        val colors = HashSet<Int>()
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                colors.add(pixelAt(x, y))
                if (colors.size >= COLOR_THRESHOLD) return false
                x += stepX
            }
            y += stepY
        }
        return true
    }
}
