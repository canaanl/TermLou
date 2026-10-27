@file:Suppress("MagicNumber")

package com.workspace.proot

import kotlin.math.max

/** 画布内新增控件时的标准几何；和旧 rows 布局共用同一套 UiTokens。 */
object CanvasWidgetPlacement {

    private const val CONTENT_INSET = 16f
    private const val TEXT_INSET = 20f
    private const val BUTTON_INSET = 18f
    private const val BUTTON_GAP = 12f
    private const val BUTTON_TOP_GAP = 26f
    private const val TEXT_HEIGHT = CanvasWidgetLayout.LABEL_H

    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float)

    fun contentBottom(scene: CanvasScene): Float =
        scene.nodes.maxOfOrNull { it.y + it.h } ?: 0f

    fun nextY(scene: CanvasScene): Float {
        val bottom = scene.nodes.maxByOrNull { it.y + it.h }
        val gap = if (bottom?.type == CanvasSpec.TYPE_TEXT) 0f else CanvasWidgetLayout.ROW_GAP
        return max(8f, contentBottom(scene) + gap)
    }

    fun input(scene: CanvasScene, y: Float = nextY(scene)): Rect =
        Rect(CONTENT_INSET, y, contentWidth(scene), CanvasWidgetLayout.inputHeight())

    fun select(scene: CanvasScene, label: String, optionCount: Int, y: Float = nextY(scene)): Rect =
        Rect(
            CONTENT_INSET,
            y,
            contentWidth(scene),
            CanvasWidgetLayout.recommendedSelectHeight(label, optionCount)
        )

    fun toggle(scene: CanvasScene, y: Float = nextY(scene)): Rect =
        Rect(CONTENT_INSET, y, contentWidth(scene), CanvasWidgetLayout.TOGGLE_H)

    fun text(scene: CanvasScene, y: Float = nextY(scene)): Rect =
        Rect(TEXT_INSET, y, (scene.width - TEXT_INSET * 2).coerceAtLeast(1f), TEXT_HEIGHT)

    fun button(scene: CanvasScene): Rect = buttonBar(scene, 1).first()

    fun buttonBar(scene: CanvasScene, count: Int): List<Rect> {
        val n = count.coerceAtLeast(1)
        val bottom = scene.nodes.maxByOrNull { it.y + it.h }
        val topGap = if (bottom?.type == CanvasSpec.TYPE_TEXT) 18f else BUTTON_TOP_GAP
        val top = max(8f, contentBottom(scene) + topGap)
        val available = (scene.width - BUTTON_INSET * 2 - BUTTON_GAP * (n - 1)).coerceAtLeast(n * 24f)
        val w = available / n
        return (0 until n).map { i ->
            Rect(BUTTON_INSET + i * (w + BUTTON_GAP), top, w, CanvasWidgetLayout.BUTTON_H)
        }
    }

    private fun contentWidth(scene: CanvasScene): Float =
        (scene.width - CONTENT_INSET * 2).coerceAtLeast(1f)
}
