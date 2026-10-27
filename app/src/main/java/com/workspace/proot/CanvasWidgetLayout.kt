package com.workspace.proot

import kotlin.math.max
import kotlin.math.min

/**
 * 弹窗 Canvas 控件的统一尺寸与命中计算。
 *
 * 全部使用场景坐标（dp 语义），builder、绘制和触摸共用同一套数字，避免
 * “画出来的位置”和“点到位置”各算一遍。纯 Kotlin，可 JVM 单测。
 */
object CanvasWidgetLayout {

    const val LABEL_SIZE = UiTokens.TEXT_META
    const val LABEL_LINE = UiTokens.LINE_META
    const val LABEL_H = LABEL_SIZE * LABEL_LINE
    const val FIELD_TEXT_SIZE = UiTokens.TEXT_COMPACT
    const val FIELD_H = 30f
    const val FIELD_RADIUS = UiTokens.CORNER_SM
    const val FIELD_PAD_X = 10f
    const val FIELD_PAD_Y = 6f
    const val LABEL_GAP = 2f
    const val ROW_GAP = UiTokens.SPACE_2.toFloat()

    const val OPTION_SIZE = UiTokens.TEXT_META
    const val OPTION_H = 28f
    const val OPTION_PAD_X = 10f
    const val OPTION_PAD_Y = 4f
    const val SELECT_PAD_TOP = 2f
    const val SELECT_PAD_BOTTOM = 2f
    const val SELECT_MAX_ROWS = 6
    const val SELECT_MAX_H = 240f

    const val TOGGLE_H = 20f
    const val TOGGLE_PILL_W = 48f
    const val TOGGLE_PILL_H = 20f

    const val BUTTON_H = 44f

    fun inputHeight(): Float = LABEL_H + LABEL_GAP + FIELD_H

    fun selectLabelHeight(node: CanvasNode): Float =
        if (node.label.isEmpty()) 0f else LABEL_H

    fun selectBodyTop(node: CanvasNode): Float =
        selectLabelHeight(node) + if (node.label.isEmpty()) 0f else SELECT_PAD_TOP

    fun selectBodyHeight(node: CanvasNode): Float =
        (node.h - selectBodyTop(node) - SELECT_PAD_BOTTOM).coerceAtLeast(0f)

    /** builder 推荐的选项框高度：最多 6 行，整体不超过 240dp。 */
    fun recommendedSelectHeight(label: String, optionCount: Int): Float {
        val labelH = if (label.isEmpty()) 0f else LABEL_H + SELECT_PAD_TOP
        val rows = optionCount.coerceIn(1, SELECT_MAX_ROWS)
        val h = labelH + rows * OPTION_H + SELECT_PAD_BOTTOM
        return min(h, SELECT_MAX_H)
    }

    data class Option(val label: String, val tag: String?)

    /** 旧格式 "标签#过滤标签"：只在中间出现 # 时拆分，和旧 renderer 一致。 */
    fun parseOptions(raw: List<String>): List<Option> = raw.map { option ->
        val idx = option.lastIndexOf('#')
        if (idx > 0 && idx < option.length - 1) {
            Option(option.substring(0, idx), option.substring(idx + 1))
        } else {
            Option(option, null)
        }
    }

    fun visibleOptions(node: CanvasNode, filters: Set<String>): List<Option> {
        val all = parseOptions(node.options)
        if (filters.isEmpty()) return all
        return all.filter { it.tag == null || it.tag in filters }
    }

    @Suppress("ReturnCount")
    fun optionAt(node: CanvasNode, localY: Float, optionCount: Int, scroll: Float): Int? {
        if (optionCount <= 0) return null
        val top = selectBodyTop(node)
        val bottom = node.h - SELECT_PAD_BOTTOM
        if (localY < top || localY > bottom) return null
        val idx = ((localY - top + scroll) / OPTION_H).toInt()
        return idx.takeIf { it in 0 until optionCount }
    }

    fun maxScroll(node: CanvasNode, optionCount: Int): Float {
        val content = optionCount * OPTION_H
        return max(0f, content - selectBodyHeight(node))
    }

    fun clampScroll(node: CanvasNode, optionCount: Int, offset: Float): Float =
        offset.coerceIn(0f, maxScroll(node, optionCount))
}
