package com.workspace.probe

import android.graphics.Bitmap
import android.graphics.Canvas
import org.json.JSONObject

/**
 * 一次测量的结果。**只记观测值，不下判断** —— 判断留给 [ProbeVerdict]，
 * 这样报告里印出来的每个数字都是真的，不是算出来的。
 */
data class RunResult(
    val label: String,
    val attached: Boolean,
    val loaded: Boolean,
    val loadError: String?,
    val obs: JSONObject?,
    val drawNonWhitePixels: Int,
    val pictureEmpty: Boolean,
    val drawFailed: String?
) {
    /** 报告里显示的原始观测摘要。 */
    fun raw(): String {
        val o = obs
        if (o == null) return "无观测值"
        fun s(k: String, d: String = "-") = if (o.has(k)) o.optString(k, d) else d
        return "innerW=${s("innerW")} innerH=${s("innerH")} fiftyW=${s("fiftyW")} " +
            "innerTextLen=${s("innerTextLen")} tick=${s("tick")} imgComplete=${s("imgComplete")} " +
            "canvasPx=${s("canvasPx")} 非白像素=$drawNonWhitePixels"
    }
}

/** 判"画出来的 Bitmap 里有没有非白像素"。抄 TermLou 里 WebShotSampler 的思路，独立一份不依赖它。 */
object Ink {

    /** 数非白像素：白底上的正文、按钮、图片都算内容。 */
    fun nonWhite(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return 0
        var n = 0
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r < 245 || g < 245 || b < 245) n++
            }
        }
        return n
    }

    /** 抽几个点看颜色，用来判断"是不是画了但整张一样"。 */
    fun sample(bitmap: Bitmap): String {
        val pts = listOf(10 to 10, bitmap.width / 2 to bitmap.height / 2, bitmap.width - 10 to bitmap.height - 10)
        return pts.joinToString(" ") { (x, y) ->
            val xx = x.coerceIn(0, bitmap.width - 1)
            val yy = y.coerceIn(0, bitmap.height - 1)
            "%06X".format(bitmap.getPixel(xx, yy))
        }
    }

    /**
     * 数"纯红"像素 —— 滚动测试里**底部**那块色块的硬判据。
     *
     * 页面底部铺的是 `#ff0000`，滚到底之后它应当填住视口下沿。
     * 画出来有红 → 那块确实被光栅化了；没红 → 没有。
     *
     * 判据放宽到 R>180 且 G<90 且 B<90：抗压缩与抗混色，不用精确匹配。
     */
    fun countRed(bitmap: Bitmap): Int =
        countWhere(bitmap) { r, g, b -> r > 180 && g < 90 && b < 90 }

    /**
     * 数"纯绿"像素 —— 滚动测试里**顶部**那块色块的硬判据。
     *
     * 顶部铺的是 `#00c000`，**滚之前**就该在视口最上面。
     * 之所以要它：v5/v6 只铺了红，于是「长页面有没有画出来过」从没被量过，
     * 而"非白像素"在底色 `#e9e9e9` 的页面上恒为满值，根本当不了判据。
     *
     * 绿色与红色互不混淆（混色会变黄，两边都不认），所以两个信号能同时用。
     */
    fun countGreen(bitmap: Bitmap): Int =
        countWhere(bitmap) { r, g, b -> g > 90 && r < 90 && b < 90 }

    /**
     * 数"纯白"像素 —— 画布底（`Drawer` 建位图时铺的白）。
     *
     * v9 用来和底色分开：白多 = 连页面底色都没回放出来；
     * 底色多 = 底色层回放成功、内容层单独掉了。v5 只报了"非白=100%"，
     * 这两种情况没分开。
     */
    fun countWhite(bitmap: Bitmap): Int =
        countWhere(bitmap) { r, g, b -> r >= 245 && g >= 245 && b >= 245 }

    /**
     * 数"页面底色"像素 —— `#e9e9e9`（233）附近。
     *
     * 取 220..242 三个通道：和白色（≥245）、纯红（G<90）、纯绿（R<90）
     * 三个桶**互不重叠**，四个桶加起来不超总数。
     */
    fun countPageBg(bitmap: Bitmap): Int =
        countWhere(bitmap) { r, g, b ->
            r in 220..242 && g in 220..242 && b in 220..242
        }

    /** 逐行扫一遍，把满足 `test` 的像素数出来。 */
    private inline fun countWhere(bitmap: Bitmap, test: (Int, Int, Int) -> Boolean): Int {        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return 0
        var n = 0
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                if (test((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)) n++
            }
        }
        return n
    }
}

/**
 * 从 `capturePicture()` 取一张图。
 *
 * ## 为什么要单独量这条路
 *
 * 本体 `WebAutomationService.capturePage` 里有两条路：`draw()` 为主，
 * `capturePicture()` 为兜底。v6 只测了 `draw()`，于是「两条路都坏」是**推测**。
 * v7 把它当一等公民：每个滚动位置两条路各量一次。
 *
 * ## 等比缩放，不是两个方向独立缩放
 *
 * `capturePicture()` 给的是**整页** Picture（长页面高 2676px 远大于视口），
 * 独立缩放会把长页面纵向压扁。取单一 `min` 因子再居中裁剪 ——
 * 与本体 `capturePage` 的做法一致，量出来的数才有参考价值。
 */
object PictureShot {

    /**
     * @param extraScale 视图比位图大时要额外缩的系数（全高视图那条），
     *                   `1f` = 位图与视图同尺寸。
     */
    fun toBitmap(
        width: Int,
        height: Int,
        capture: () -> android.graphics.Picture?,
        extraScale: Float = 1f
    ): Pair<Bitmap?, String?> =
        runCatching {
            val pic = capture() ?: return@runCatching null to "capturePicture 返回 null"
            if (pic.width <= 0 || pic.height <= 0) return@runCatching null to "Picture 空 ${pic.width}x${pic.height}"
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            val scale = extraScale * minOf(width.toFloat() / pic.width, height.toFloat() / pic.height)
            canvas.translate((width - pic.width * scale) / 2f, (height - pic.height * scale) / 2f)
            canvas.scale(scale, scale)
            pic.draw(canvas)
            bmp to (null as String?)
        }.getOrElse { null to (it.message ?: it.javaClass.simpleName) }
}

/** 画到 Bitmap。`draw()` 是官方文档里的软件快照路径；另一条 `capturePicture()` 见 [PictureShot]。 */
object Drawer {

    fun drawToBitmap(width: Int, height: Int, draw: (Canvas) -> Unit): Pair<Bitmap?, String?> =
        runCatching {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            draw(canvas)
            bmp to null as String?
        }.getOrElse { null to (it.message ?: it.javaClass.simpleName) }
}
