package com.workspace.proot

/** ANSI SGR → 纯数据 span。无 Android 依赖，可 JVM 单测；渲染时由 Android 层转 Spannable。 */
object AnsiParser {

    data class Span(
        val start: Int,
        val end: Int,
        val fg: Int?,
        val bg: Int?,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val inverse: Boolean
    )

    data class Result(val clean: String, val spans: List<Span>)

    private val basic16 = intArrayOf(
        0xFF000000.toInt(), 0xFFCD0000.toInt(), 0xFF00CD00.toInt(), 0xFFCDCD00.toInt(),
        0xFF0000EE.toInt(), 0xFFCD00CD.toInt(), 0xFF00CDCD.toInt(), 0xFFE5E5E5.toInt(),
        0xFF7F7F7F.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
        0xFF5C5CFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt()
    )

    /** 解析含 SGR（\e[...m）的文本；非 SGR 的 CSI 序列整体剥离。 */
    fun parse(input: String): Result {
        val sb = StringBuilder()
        val spans = mutableListOf<Span>()
        val st = State()
        var i = 0
        val n = input.length
        var runStart = -1
        var runStyle: StyleFlags? = null
        while (i < n) {
            val c = input[i]
            if (c == '\u001b' && i + 1 < n && input[i + 1] == '[') {
                var j = i + 2
                while (j < n && input[j] !in '\u0040'..'\u007e') j++
                if (j >= n) break
                val terminator = input[j]
                if (terminator == 'm') {
                    val params = input.substring(i + 2, j)
                    for (p in params.split(';')) applyParam(p, st)
                }
                i = j + 1
            } else {
                if (c == '\u001b') {
                    i++
                    continue
                }
                val start = sb.length
                sb.append(c)
                val cur = activeStyle(st)
                if (cur != null) {
                    if (runStyle == null) {
                        runStart = start
                    } else if (cur != runStyle) {
                        spans.add(runStart.toSpan(start, runStyle))
                        runStart = start
                    }
                    runStyle = cur
                } else if (runStyle != null) {
                    spans.add(runStart.toSpan(start, runStyle))
                    runStyle = null
                    runStart = -1
                }
                i++
            }
        }
        if (runStyle != null) {
            spans.add(runStart.toSpan(sb.length, runStyle))
        }
        return Result(sb.toString(), spans)
    }

    /** 当前有效样式快照；全部为默认则返回 null（不需要 span）。 */
    private fun activeStyle(st: State): StyleFlags? {
        if (st.fg == null && st.bg == null && !st.bold && !st.italic && !st.underline && !st.inverse) {
            return null
        }
        return StyleFlags(st.fg, st.bg, st.bold, st.italic, st.underline, st.inverse)
    }

    private data class StyleFlags(
        val fg: Int?,
        val bg: Int?,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val inverse: Boolean
    )

    private fun Int.toSpan(end: Int, s: StyleFlags): Span =
        Span(this, end, s.fg, s.bg, s.bold, s.italic, s.underline, s.inverse)

    private class State {
        var fg: Int? = null
        var bg: Int? = null
        var bold = false
        var italic = false
        var underline = false
        var inverse = false
    }

    private fun applyParam(p: String, st: State) {
        val code = p.toIntOrNull() ?: return
        when (code) {
            0 -> { st.fg = null; st.bg = null; st.bold = false; st.italic = false; st.underline = false; st.inverse = false }
            1 -> st.bold = true
            3 -> st.italic = true
            4 -> st.underline = true
            7 -> st.inverse = true
            in 30..37 -> st.fg = basic16[code - 30]
            in 40..47 -> st.bg = basic16[code - 40]
            in 90..97 -> st.fg = basic16[code - 90 + 8]
            in 100..107 -> st.bg = basic16[code - 100 + 8]
            else -> Unit
        }
    }
}
