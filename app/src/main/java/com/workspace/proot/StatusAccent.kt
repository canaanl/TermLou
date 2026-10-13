package com.workspace.proot

/**
 * 5.9.47：状态栏两块结构的**文字拆分规则**（唯一实现）。
 *
 * ## 状态栏长什么样
 *
 * ```
 * ┌──────────────┬────────────────────────────────┐
 * │Files         │  /workspace/notes/long/pa… ←滚 │
 * └──────────────┴────────────────────────────────┘
 *   块① 固定          块② 滚动
 * ```
 *
 * 背景色画在父容器上，两块子 View 都透明 —— **视觉上是一整条，看不出分块**；
 * 逻辑上却是两块：块① 只放特征字（固定宽度、左对齐、永不滚动），
 * 块② 放实际内容（自己跑自己的跑马灯）。
 *
 * ## 拆分规则
 *
 * ```
 * "Files | /workspace"  ->  块① "Files"     块② "/workspace"
 * "Terminal"             ->  块① "Terminal"  块② ""
 * "Settings"             ->  块① "Settings"  块② ""
 * "已保存"（临时提示）      ->  块① 不动        块② "已保存"
 * ```
 *
 * - **竖线不显示**：它只是拼接时的分隔符，拆块时被丢掉（用户明确要求）。
 *   块① 与块② 的分界靠块① 的固定宽度本身。
 * - 临时提示（对不上特征字的）**不动块①**：那是"事件"，不是"当前位置"。
 * - 对不上特征字时 [tabNameOf] 返回 null，[contentOf] 原样返回整串。
 *
 * ## 为什么独立成文件
 *
 * 调色盘预览要用**同一条规则**渲染状态栏示意（PaletteActivity.renderPreview）。
 * 规则写两份的话，预览好看而真机不一样，等于没有预览。
 */
object StatusAccent {

    /** 五个页签的特征字。全项目拼状态栏文字的地方都必须用这几个词。 */
    val TAB_NAMES = listOf("Terminal", "Notes", "Files", "Network", "Settings")

    /**
     * 取特征字部分；不是页签名时返回 null（调用方据此保持块① 不动）。
     *
     * 只认**开头的**页签名 + 可选的 `|`：路径自带 `|`（`a|b/c`）不会被误切。
     */
    fun tabNameOf(text: String): String? {
        for (name in TAB_NAMES) {
            if (text == name) return name
            if (text.startsWith(name + " |")) return name
        }
        return null
    }

    /**
     * 取内容部分：去掉特征字**和紧随其后的竖线**，其余原样。
     *
     * 竖线只是拼接时的分隔符，**不显示**（用户明确要求"Files 后面的竖线就不要了"）。
     * 块① 与块② 之间的视觉分界靠块① 的固定宽度本身，不靠竖线。
     *
     * 不是页签格式（临时提示）时返回整串。
     */
    fun contentOf(text: String): String {
        for (name in TAB_NAMES) {
            if (text == name) return ""
            if (text.startsWith(name + " |")) {
                return text.substring(name.length + 2).trimStart(' ')
            }
        }
        return text
    }
}