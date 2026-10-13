package com.workspace.proot

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan

/**
 * 5.9.45：状态栏**特征字**上色规则（唯一实现）。
 *
 * ## 做什么
 *
 * 状态栏文字形如：
 *
 * ```
 * Files | /workspace/notes     有竖线：竖线之前是特征字
 * Settings                     无竖线：整段都是特征字
 * 已保存                        不是页签名：原样，保持单色
 * ```
 *
 * 规则：**第一个 `|` 及其之前**上 [ThemeColors.statusAccent]，其余保持原色。
 *
 * ## 为什么只切第一段
 *
 * 路径里自己可能带 `|`（`a|b/c`）。只切第一段则不会出现"路径被切成两截、
 * 路径中间也染色"的怪事。
 *
 * ## 为什么临时提示不上色
 *
 * 临时消息（"已保存"、"正在启动…"）是**事件**，不是"当前位置"，
 * 不该和页签名用同一个颜色 —— 那会让"你��哪个页"和"刚刚发生了什么"混在一起。
 *
 * ## 为什么单独成文件
 *
 * 调色盘预览要用**同一条规则**渲染状态栏示意（见 PaletteActivity.renderPreview）。
 * 规则写两份的话，预览好看而真机不一样，等于没有预览。
 */
object StatusAccent {

    /** 五个页签的特征字。全项目拼状态栏文字的地方都必须用这几个词。 */
    private val TAB_NAMES = setOf("Terminal", "Notes", "Files", "Network", "Settings")

    /** 给状态栏文字上色；不是页签名的原样返回。 */
    fun apply(text: String, accent: Int): CharSequence {
        val bar = text.indexOf('|')
        return when {
            bar < 0 && TAB_NAMES.contains(text) -> colorize(text, 0, text.length, accent)
            bar > 0 && TAB_NAMES.contains(text.substring(0, bar).trim()) ->
                colorize(text, 0, bar + 1, accent)
            else -> text
        }
    }

    private fun colorize(text: String, start: Int, end: Int, color: Int): CharSequence =
        SpannableString(text).apply {
            setSpan(
                ForegroundColorSpan(color),
                start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
}