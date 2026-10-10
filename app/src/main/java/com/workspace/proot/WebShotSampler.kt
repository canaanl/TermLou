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
     * 下三分之一的颜色数上限：超过才算"渲染出来了"（5.9.9 整页截图用）。
     *
     * 同色块 + 文字 + 链接 + 抗锯齿杂色，正常内容轻松几十种；
     * 纯白画布或纯底色铺满只有 1 种。3 卡在中间的空档里。
     */
    const val FULL_COLOR_MIN = 3

    /** 下三分之一的抽样步长（设备像素）。900 万像素的位图也只要扫几万个点。 */
    const val FULL_SAMPLE_STEP = 8

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

    /**
     * 下三分之一是不是"渲染出来了"（5.9.9 整页截图用）。
     *
     * ## 为什么数颜色数，而不是数非白像素
     *
     * 没渲染出来的下半截有两种样子：纯白画布（`draw` 什么都没画到那儿），
     * 或纯底色铺满（底色层回放了、内容层没有 —— 真机 v5 量到的就是整屏 `#e9e9e9`）。
     * 两种都是**整块同色**。数"非白"分不清"底色铺满"和"有内容" ——
     * 底色本身就算非白。数颜色数一次管住两种：同色块 1 种，有内容几十种。
     *
     * ## 误伤与取舍
     *
     * 底栏纯色块（无文字、无 logo）会被判成"没渲染" —— 那是没有信息量的区域，
     * 报错重试一次，结果一样，agent 看到的是明确的失败而不是半张白图。
     * 反过来（白图判成有内容）是绝不允许的：那会让 agent 拿空白做判断。
     * 两害取其轻。
     *
     * @param pixelAt 取色函数（(x, y) -> 颜色值），alpha 位会被忽略
     */
    fun lowerThirdRendered(width: Int, height: Int, pixelAt: (Int, Int) -> Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val colors = HashSet<Int>(16)
        var y = height * 2 / 3
        while (y < height) {
            var x = 0
            while (x < width) {
                colors.add(pixelAt(x, y) and 0xFFFFFF)
                if (colors.size > FULL_COLOR_MIN) return true
                x += FULL_SAMPLE_STEP
            }
            y += FULL_SAMPLE_STEP
        }
        return false
    }

    /**
     * 内容指纹：抽样几个点的颜色**按顺序**混成一个整数（5.9.30）。
     *
     * ## 它抓什么
     *
     * 整页截图滚着拍时，最阴的一种失败是**第二屏拍出来的和第一屏一模一样** ——
     * 滚动没生效，或者渲染没跟上。但两张图都"有内容"，判空查不出来，
     * 于是一张首页的复制品被当成整页交出去了。
     *
     * 真机 5.9.29 就是这个症状：第一屏正常，后面几屏是**首页的缩小版**加大片空白。
     *
     * 指纹相同就说明**没滚到新地方**，此时必须报错并报出是第几屏，
     * 不能交一张看起来有图、其实没滚的图。
     *
     * ## 为什么够用
     *
     * 两屏内容真的完全一样的概率极低；而抓错的代价只是"多报一次错、让 agent 重试"，
     * 远小于"交出一张假整页"。
     */
    fun fingerprint(width: Int, height: Int, pixelAt: (Int, Int) -> Int): Long {
        if (width <= 0 || height <= 0) return 0L
        var h = 1125899906842597L
        val stepX = (width / 4).coerceAtLeast(1)
        val stepY = (height / 6).coerceAtLeast(1)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                h = (h xor (pixelAt(x, y) and 0xFFFFFF).toLong()) * 31L
                x += stepX
            }
            y += stepY
        }
        return h
    }

    /**
     * 两张图的指纹一样 = 没滚到新地方。
     *
     * 指纹为 `0` 表示尺寸非法（空图），那种情况交给 [looksBlank] 管，
     * **不在这里当成"相同"** —— 否则第一张空图会把后面每一张都带下水。
     */
    fun sameContent(a: Long, b: Long): Boolean = a != 0L && a == b
}
