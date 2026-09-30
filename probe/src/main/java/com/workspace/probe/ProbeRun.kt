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
}

/** 画到 Bitmap。`draw()` 是官方文档里的软件快照路径；`capturePicture()` 只作辅助指标（已废弃）。 */
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
