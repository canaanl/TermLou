package com.workspace.proot

import android.view.View
import android.widget.Button
import java.util.WeakHashMap

/**
 * 按钮标签放不下时的降级规则（5.9.4），纯逻辑、可单测。
 *
 * 设置区两行都是"半宽双按钮"，再叠上系统超大字体，`maxLines=1 + ellipsize`
 * 会把标签截成 "需认…"，反而看不出是什么意思。所以宁可换成更短的同义标签，
 * 也不要半个词——**文字不许溢出按钮**。
 */
object ButtonLabel {

    /** 用完整标签。 */
    const val FULL = 0

    /** 完整标签放不下，用缩写。 */
    const val SHORT = 1

    /**
     * 该显示哪个标签。
     *
     * @param availablePx 扣掉内边距后的可用宽度
     * @param fullWidthPx 完整标签的测量宽度
     * @param shortWidthPx 缩写标签的测量宽度
     *
     * [availablePx] 传 0 表示还没完成布局（量不到宽度）：此时保持完整标签，
     * 等布局好了 [applyFittingLabel] 会再算一次。
     */
    fun pick(availablePx: Float, fullWidthPx: Float, shortWidthPx: Float): Int =
        if (availablePx > 0f && fullWidthPx > availablePx) SHORT else FULL
}

/** 已挂上降级监听的按钮：`full` 可变（认证状态会切），`short` 与监听器一次注册不再变。 */
private class Fitting(
    var full: CharSequence,
    val short: CharSequence,
    val listener: View.OnLayoutChangeListener
)

// WeakHashMap：按钮被回收时条目自动消失，不会因为反复 refresh 而堆积监听器
private val fitting = WeakHashMap<Button, Fitting>()

/**
 * 给按钮套上 [ButtonLabel] 的降级规则：先显示完整标签，布局完成后若量出来放不下
 * 就换成缩写。宽度变化（旋屏、分屏、字体缩放）时会重算。
 *
 * **幂等**：同一个按钮反复调用只注册一个监听器 —— `refreshLanRow` 在开关服务后
 * 还会再调几次，每次都 `addOnLayoutChangeListener` 的话监听器会越挂越多。
 */
fun Button.applyFittingLabel(full: CharSequence, short: CharSequence) {
    val existing = fitting[this]
    if (existing == null) {
        fitting[this] = Fitting(
            full,
            short,
            View.OnLayoutChangeListener { v, l, t, r, b, oldL, oldT, oldR, oldB ->
                val btn = v as? Button ?: return@OnLayoutChangeListener
                val cur = fitting[btn] ?: return@OnLayoutChangeListener
                btn.refitLabel(cur.full, cur.short, r - l)
            }
        )
        addOnLayoutChangeListener(fitting[this]!!.listener)
    } else {
        existing.full = full
    }
    text = full
    post { refitLabel(full, short, width) }
}

private fun Button.refitLabel(full: CharSequence, short: CharSequence, totalWidth: Int) {
    val available = totalWidth - paddingStart - paddingEnd
    val want = when (
        ButtonLabel.pick(
            available.toFloat(),
            paint.measureText(full.toString()),
            paint.measureText(short.toString())
        )
    ) {
        ButtonLabel.SHORT -> short
        else -> full
    }
    if (text.toString() != want.toString()) text = want
}
