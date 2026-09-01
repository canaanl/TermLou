package com.workspace.proot

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

fun applyFrostedCard(card: FrameLayout, activity: Activity) {
    val cardW = card.width
    val cardH = card.height
    val decor = activity.window.decorView
    val dw = decor.width
    val dh = decor.height
    if (dw <= 0 || dh <= 0 || cardW <= 0 || cardH <= 0) {
        card.setBackgroundColor(UiTokens.surfaceFallback)
        return
    }
    val s = 0.25f
    val smallW = (dw * s).toInt()
    val smallH = (dh * s).toInt()
    val raw = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
    val c = Canvas(raw)
    c.scale(s, s)
    decor.draw(c)
    val blurred = try {
        val rs = RenderScript.create(activity)
        val input = Allocation.createFromBitmap(rs, raw)
        val output = Allocation.createTyped(rs, input.type)
        ScriptIntrinsicBlur.create(rs, Element.U8_4(rs)).apply {
            setRadius(25f)
            setInput(input)
            forEach(output)
        }
        output.copyTo(raw)
        rs.destroy()
        raw
    } catch (_: Exception) {
        raw
    }

    val decLoc = IntArray(2)
    val cardLoc = IntArray(2)
    decor.getLocationOnScreen(decLoc)
    card.getLocationOnScreen(cardLoc)
    val sx = cardLoc[0] - decLoc[0]
    val sy = cardLoc[1] - decLoc[1]
    val left = (sx * s).toInt().coerceIn(0, (blurred.width - 1).coerceAtLeast(0))
    val top = (sy * s).toInt().coerceIn(0, (blurred.height - 1).coerceAtLeast(0))
    val rw = (cardW * s).toInt().coerceIn(1, blurred.width - left)
    val rh = (cardH * s).toInt().coerceIn(1, blurred.height - top)
    val region = runCatching { Bitmap.createBitmap(blurred, left, top, rw, rh) }.getOrNull()
    val cardFull = region?.let { Bitmap.createScaledBitmap(it, cardW, cardH, true) }
    if (cardFull == null) {
        card.setBackgroundColor(UiTokens.surfaceFallback)
        return
    }

    val d = activity.resources.displayMetrics.density
    val corner = (26 * d).toFloat()
    val feather = (14 * d).toFloat()
    val tintA = 0.80f
    val cx = cardW / 2f
    val cy = cardH / 2f
    val hw = cardW / 2f
    val hh = cardH / 2f
    val out = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888)
    for (y in 0 until cardH) {
        for (x in 0 until cardW) {
            val qx = abs(x.toFloat() - cx) - hw + corner
            val qy = abs(y.toFloat() - cy) - hh + corner
            val dist = sqrt(max(0f, qx) * max(0f, qx) + max(0f, qy) * max(0f, qy))
                    + min(max(qx, qy), 0f) - corner
            val t = (1f - dist / feather).coerceIn(0f, 1f)
            val a = t * t * (3f - 2f * t)
            if (a <= 0f) continue
            val p = cardFull.getPixel(x, y)
            val cr = (Color.red(p) * (1f - tintA) + 0x1E * tintA).toInt()
            val cg = (Color.green(p) * (1f - tintA) + 0x1E * tintA).toInt()
            val cb = (Color.blue(p) * (1f - tintA) + 0x1E * tintA).toInt()
            out.setPixel(x, y, Color.argb((a * 255f).toInt(), cr, cg, cb))
        }
    }
    card.background = BitmapDrawable(activity.resources, out)
}
