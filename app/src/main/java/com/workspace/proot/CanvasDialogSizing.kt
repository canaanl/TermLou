package com.workspace.proot

import kotlin.math.roundToInt

/** 弹窗内容 View 的 intrinsic 高度；抽成纯函数便于回归测试。 */
object CanvasDialogSizing {

    fun contentHeightPx(scene: CanvasScene, viewWidthPx: Int): Int {
        val width = viewWidthPx.coerceAtLeast(1)
        val sceneWidth = scene.width.coerceAtLeast(1f)
        return (scene.height * (width / sceneWidth)).roundToInt().coerceAtLeast(1)
    }
}
