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
                    applySgr(params, st)
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

    /**
     * SGR 参数序列解析。38/48 是带子参数的扩展色，必须整组消费：
     * 否则 `38;2;r;g;b` 里的 0..37 会被当成独立 SGR —— 其中的 0 直接触发 reset、
     * 30..37 改前景，真彩色序列因此把颜色改得乱七八糟。
     */
    private fun applySgr(params: String, st: State) {
        val toks = params.split(';')
        var i = 0
        while (i < toks.size) {
            val code = toks[i].toIntOrNull()
            if (code == null) { i++; continue } // 空/非法参数：维持旧行为直接忽略
            if (code == 38 || code == 48) {
                when (toks.getOrNull(i + 1)?.toIntOrNull()) {
                    5 -> { // 38;5;n 索引色
                        toks.getOrNull(i + 2)?.toIntOrNull()?.let { setColor(st, code, color256(it)) }
                        i += 3
                    }
                    2 -> { // 38;2;r;g;b 真彩色
                        val r = toks.getOrNull(i + 2)?.toIntOrNull()
                        val g = toks.getOrNull(i + 3)?.toIntOrNull()
                        val b = toks.getOrNull(i + 4)?.toIntOrNull()
                        if (r != null && g != null && b != null) {
                            setColor(
                                st, code,
                                0xFF000000.toInt() or
                                    (r.coerceIn(0, 255) shl 16) or
                                    (g.coerceIn(0, 255) shl 8) or
                                    b.coerceIn(0, 255)
                            )
                        }
                        i += 5
                    }
                    else -> i += 2 // 形态未知，消费掉 38/48 本身，避免污染后续参数
                }
                continue
            }
            applyParam(toks[i], st)
            i++
        }
    }

    private fun setColor(st: State, code: Int, color: Int) {
        if (code == 38) st.fg = color else st.bg = color
    }

    /** xterm 256 色板 → ARGB（0-15 基础 16 色，16-231 6×6×6 立方，232-255 灰阶）。 */
    private fun color256(n: Int): Int {
        val v = n.coerceIn(0, 255)
        if (v < 16) return basic16[v]
        if (v < 232) {
            val c = v - 16
            val r = CUBE[c / 36]
            val g = CUBE[(c / 6) % 6]
            val b = CUBE[c % 6]
            return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
        val gray = 8 + (v - 232) * 10
        return 0xFF000000.toInt() or (gray shl 16) or (gray shl 8) or gray
    }

    private val CUBE = intArrayOf(0, 95, 135, 175, 215, 255)
}
