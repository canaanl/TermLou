@file:Suppress("MagicNumber")

package com.workspace.proot

/** 工坊外壳的 request 组装；工坊只剩一个内容 Canvas，不再生成线性 rows。 */
object DialogWorkshopSpec {

    const val CANVAS_KEY = "main"

    fun buildRequest(
        id: String,
        title: String,
        scene: CanvasScene,
        style: ScriptDialogSpec.Style
    ): ScriptDialogSpec.Request = ScriptDialogSpec.Request(
        id = id,
        ui = ScriptDialogSpec.Ui(
            title = title,
            rows = listOf(
                ScriptDialogSpec.Row.Canvas(
                    ScriptDialogSpec.CanvasRow(
                        scene = scene,
                        heightDp = scene.height.toInt().coerceIn(64, 1600),
                        key = CANVAS_KEY
                    )
                )
            )
        ),
        style = style
    )
}
