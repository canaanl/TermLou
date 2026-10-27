package com.workspace.proot

/**
 * Canvas 弹窗的运行时表单状态。
 *
 * 场景节点只保存默认值/结构；用户输入、选中、开关、过滤和选项框滚动都放在这里。
 * 这样 patch/动画改场景时不会把用户正在编辑的值冲掉。纯 Kotlin，可 JVM 单测。
 */
@Suppress("TooManyFunctions")
class CanvasFormState {

    private val inputs = LinkedHashMap<String, String>()
    private val selects = LinkedHashMap<String, MutableList<String>>()
    private val toggles = LinkedHashMap<String, Boolean>()
    private val filters = LinkedHashSet<String>()
    private val scroll = HashMap<String, Float>()

    var focusedNodeId: String? = null
        private set

    val activeFilters: Set<String> get() = filters.toSet()

    @Suppress("CyclomaticComplexMethod")
    fun reset(scene: CanvasScene, preserveValues: Boolean, resetFilters: Boolean = true) {
        val inputKeys = LinkedHashSet<String>()
        val selectKeys = LinkedHashSet<String>()
        val toggleKeys = LinkedHashSet<String>()
        val keepNodes = HashSet<String>()
        var focusStillExists = false
        for (n in scene.flatten()) {
            when (n.type) {
                CanvasSpec.TYPE_INPUT -> {
                    val k = stateKey(n)
                    inputKeys.add(k)
                    if (!preserveValues || k !in inputs) inputs[k] = n.text
                }
                CanvasSpec.TYPE_SELECT -> {
                    val k = stateKey(n)
                    selectKeys.add(k)
                    selects.getOrPut(k) { mutableListOf() }
                }
                CanvasSpec.TYPE_TOGGLE -> {
                    val k = stateKey(n)
                    toggleKeys.add(k)
                    if (!preserveValues || k !in toggles) toggles[k] = n.checked
                }
            }
            keepNodes.add(n.id)
            if (focusedNodeId == n.id) focusStillExists = true
        }
        inputs.keys.retainAll(inputKeys)
        selects.keys.retainAll(selectKeys)
        toggles.keys.retainAll(toggleKeys)
        scroll.keys.retainAll(keepNodes)
        if (!focusStillExists) focusedNodeId = null
        if (resetFilters) {
            filters.clear()
            for (n in scene.flatten()) {
                if (n.type != CanvasSpec.TYPE_TOGGLE || n.filter.isEmpty()) continue
                // 保留同 key 开关值时，过滤也必须跟它一致，不能出现 UI 显示 OFF 但过滤仍生效。
                val on = toggles[stateKey(n)] ?: n.checked
                if (on) filters.add(n.filter)
            }
        }
        clampAllScroll(scene)
    }

    /** patch 后只补齐/裁剪状态，不重置用户值，也不重置过滤。 */
    fun sync(scene: CanvasScene) = reset(scene, preserveValues = true, resetFilters = false)

    fun inputValue(node: CanvasNode): String =
        inputs[stateKey(node)] ?: node.text

    fun setInput(node: CanvasNode, value: String) {
        inputs[stateKey(node)] = value
    }

    fun toggleValue(node: CanvasNode): Boolean =
        toggles[stateKey(node)] ?: node.checked

    fun selected(node: CanvasNode): List<String> =
        selects[stateKey(node)] ?: emptyList()

    fun focus(node: CanvasNode?) {
        focusedNodeId = node?.takeIf { it.type == CanvasSpec.TYPE_INPUT }?.id
    }

    fun isFocused(node: CanvasNode): Boolean = focusedNodeId == node.id

    /** 返回 true 表示过滤条件变了，需要重绘。 */
    fun toggle(node: CanvasNode): Boolean {
        val k = stateKey(node)
        val next = !(toggles[k] ?: node.checked)
        toggles[k] = next
        if (node.filter.isNotEmpty()) {
            if (next) filters.add(node.filter) else filters.remove(node.filter)
            return true
        }
        return false
    }

    fun tapOption(node: CanvasNode, localY: Float): Boolean {
        val visible = CanvasWidgetLayout.visibleOptions(node, filters)
        val idx = CanvasWidgetLayout.optionAt(node, localY, visible.size, scroll[node.id] ?: 0f)
            ?: return false
        val label = visible[idx].label
        val k = stateKey(node)
        val values = selects.getOrPut(k) { mutableListOf() }
        if (node.multi) {
            if (values.contains(label)) values.remove(label) else values.add(label)
        } else {
            values.clear()
            values.add(label)
        }
        return true
    }

    fun scrollOffset(node: CanvasNode): Float = scroll[node.id] ?: 0f

    fun scrollBy(node: CanvasNode, dy: Float): Boolean {
        val count = CanvasWidgetLayout.visibleOptions(node, filters).size
        val max = CanvasWidgetLayout.maxScroll(node, count)
        if (max <= 0f) return false
        val old = scroll[node.id] ?: 0f
        val next = (old + dy).coerceIn(0f, max)
        scroll[node.id] = next
        return next != old
    }

    fun clampScroll(node: CanvasNode) {
        val count = CanvasWidgetLayout.visibleOptions(node, filters).size
        val max = CanvasWidgetLayout.maxScroll(node, count)
        scroll[node.id] = (scroll[node.id] ?: 0f).coerceIn(0f, max)
    }

    /** 结果字段和旧 renderer 完全一致：input → select → toggle，select 单项为字符串，否则 JSON 数组。 */
    @Suppress("CyclomaticComplexMethod")
    fun collect(scene: CanvasScene): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (n in scene.flatten()) {
            if (n.type != CanvasSpec.TYPE_INPUT || n.key.isEmpty()) continue
            out[n.key] = inputs[stateKey(n)] ?: n.text
        }
        for ((k, values) in selects) {
            if (k.startsWith(NODE_PREFIX)) continue
            out[k] = if (values.size == 1) values[0] else {
                val arr = MiniJson.Arr()
                for (item in values) arr.put(item)
                arr.toString()
            }
        }
        for ((k, value) in toggles) {
            if (k.startsWith(NODE_PREFIX)) continue
            out[k] = if (value) "1" else "0"
        }
        return out
    }

    private fun clampAllScroll(scene: CanvasScene) {
        for (n in scene.flatten()) {
            if (n.type == CanvasSpec.TYPE_SELECT) clampScroll(n)
        }
    }

    private fun stateKey(node: CanvasNode): String =
        node.key.ifEmpty { NODE_PREFIX + node.id }

    companion object {
        private const val NODE_PREFIX = "\u0000node:"
    }
}
