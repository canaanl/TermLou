package com.workspace.proot

/**
 * 从单根场景里还原某个 Canvas 块的旧快照。
 *
 * 旧 API 的 `values[canvasKey]` 必须仍然是该 Canvas 行自己的 scene JSON，
 * 不能把表单节点、按钮栏或外层布局混进去。纯 Kotlin，可 JVM 单测。
 */
object CanvasDialogSnapshots {

    fun capture(
        rootScene: CanvasScene,
        block: CanvasDialogBlock,
        formNodeIds: Set<String>
    ): CanvasScene {
        val nodes = if (block.root) {
            rootScene.nodes.filterNot { it.id in formNodeIds }
        } else {
            rootScene.findNode(block.groupId ?: "")?.children ?: block.scene.nodes
        }
        return block.scene.copy(nodes = nodes)
    }
}
