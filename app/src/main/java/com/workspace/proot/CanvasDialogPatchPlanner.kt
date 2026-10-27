package com.workspace.proot

/** Canvas patch 的纯逻辑路由：key 作用域、scene 替换。可 JVM 单测。 */
object CanvasDialogPatchPlanner {

    @Suppress("ReturnCount")
    fun accepts(blocks: List<CanvasDialogBlock>, key: String?, node: String?): Boolean {
        if (blocks.isEmpty()) return false
        if (key == null) return true
        val block = blocks.firstOrNull { it.key == key } ?: return false
        if (node == null) return true
        return node == block.groupId || node in block.nodeIds
    }

    fun replaceScenes(
        request: ScriptDialogSpec.Request,
        key: String?,
        scene: CanvasScene
    ): ScriptDialogSpec.Request? {
        var index = 0
        var replaced = false
        val rows = request.ui.rows.map { row ->
            if (row is ScriptDialogSpec.Row.Canvas) {
                val rowKey = row.row.key.ifEmpty { "canvas$index" }
                index++
                if (key == null || key == rowKey) {
                    replaced = true
                    ScriptDialogSpec.Row.Canvas(row.row.copy(scene = scene))
                } else {
                    row
                }
            } else {
                row
            }
        }
        if (!replaced) return null
        return request.copy(ui = request.ui.copy(rows = rows))
    }
}
