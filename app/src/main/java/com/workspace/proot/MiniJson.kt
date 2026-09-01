package com.workspace.proot

/**
 * 极简 JSON 解析/序列化，无第三方依赖，可在 JVM 单测与 Android 运行。
 * 仅覆盖本项目协议所需的子集：object / array / string / number / bool / null。
 */
object MiniJson {

    class Obj {
        private val m = linkedMapOf<String, Any?>()

        fun put(key: String, value: Any?): Obj {
            m[key] = value
            return this
        }

        fun has(key: String): Boolean = m.containsKey(key)

        fun keys(): Set<String> = m.keys

        fun raw(key: String): Any? = m[key]

        fun optString(key: String, default: String): String {
            val v = m[key] ?: return default
            return (v as? String) ?: default
        }

        fun optInt(key: String, default: Int): Int {
            val v = m[key] ?: return default
            return when (v) {
                is Int -> v
                is Long -> v.toInt()
                is Double -> v.toInt()
                else -> default
            }
        }

        fun optDouble(key: String, default: Double): Double {
            val v = m[key] ?: return default
            return when (v) {
                is Double -> v
                is Int -> v.toDouble()
                is Long -> v.toDouble()
                else -> default
            }
        }

        fun optBoolean(key: String, default: Boolean): Boolean {
            val v = m[key] ?: return default
            return when (v) {
                is Boolean -> v
                is Int -> v != 0
                is Long -> v != 0L
                is Double -> v != 0.0
                is String -> when (v.trim().lowercase()) {
                    "true", "1", "yes" -> true
                    "false", "0", "no", "" -> false
                    else -> default
                }
                else -> default
            }
        }

        fun optObj(key: String): Obj? = m[key] as? Obj

        fun optArr(key: String): Arr? = m[key] as? Arr

        override fun toString(): String {
            val sb = StringBuilder("{")
            var first = true
            for ((k, v) in m) {
                if (!first) sb.append(",")
                first = false
                sb.append(str(k)).append(":").append(write(v))
            }
            sb.append("}")
            return sb.toString()
        }
    }

    class Arr {
        private val items = mutableListOf<Any?>()

        fun put(value: Any?): Arr {
            items.add(value)
            return this
        }

        fun length(): Int = items.size

        fun getObj(i: Int): Obj? = items.getOrNull(i) as? Obj

        fun getString(i: Int): String? = items.getOrNull(i) as? String

        fun raw(i: Int): Any? = items.getOrNull(i)

        override fun toString(): String {
            val sb = StringBuilder("[")
            var first = true
            for (v in items) {
                if (!first) sb.append(",")
                first = false
                sb.append(write(v))
            }
            sb.append("]")
            return sb.toString()
        }
    }

    fun parse(text: String): Obj {
        val p = Parser(text)
        val v = p.parseValue()
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("trailing chars in JSON at ${p.pos}")
        return v as? Obj ?: throw IllegalArgumentException("top-level JSON must be an object")
    }

    private class Parser(private val s: String) {
        var pos = 0
            private set

        fun atEnd(): Boolean = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWs()
            if (atEnd()) throw IllegalArgumentException("unexpected end")
            return when (s[pos]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }

        private fun expect(word: String) {
            if (!s.startsWith(word, pos)) throw IllegalArgumentException("invalid literal at $pos")
            pos += word.length
        }

        private fun parseObj(): Obj {
            expect("{")
            val obj = Obj()
            skipWs()
            if (!atEnd() && s[pos] == '}') { pos++; return obj }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(":")
                val value = parseValue()
                obj.put(key, value)
                skipWs()
                if (atEnd()) throw IllegalArgumentException("unterminated object")
                when (s[pos]) {
                    ',' -> { pos++; continue }
                    '}' -> { pos++; return obj }
                    else -> throw IllegalArgumentException("invalid char in object at $pos")
                }
            }
        }

        private fun parseArr(): Arr {
            expect("[")
            val arr = Arr()
            skipWs()
            if (!atEnd() && s[pos] == ']') { pos++; return arr }
            while (true) {
                val value = parseValue()
                arr.put(value)
                skipWs()
                if (atEnd()) throw IllegalArgumentException("unterminated array")
                when (s[pos]) {
                    ',' -> { pos++; continue }
                    ']' -> { pos++; return arr }
                    else -> throw IllegalArgumentException("invalid char in array at $pos")
                }
            }
        }

        private fun parseString(): String {
            expect("\"")
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos]
                when {
                    c == '"' -> { pos++; return sb.toString() }
                    c == '\\' -> {
                        pos++
                        if (pos >= s.length) throw IllegalArgumentException("bad escape")
                        when (val e = s[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 >= s.length) throw IllegalArgumentException("bad unicode escape")
                                val hex = s.substring(pos + 1, pos + 5)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("unknown escape $e")
                        }
                        pos++
                    }
                    else -> { sb.append(c); pos++ }
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun parseNumber(): Any {
            val start = pos
            if (!atEnd() && s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) pos++
            if (pos == start) throw IllegalArgumentException("invalid number at $start")
            val text = s.substring(start, pos)
            return text.toLongOrNull() ?: text.toDouble()
        }
    }

    private fun write(v: Any?): String = when (v) {
        null -> "null"
        is String -> str(v)
        is Boolean -> if (v) "true" else "false"
        is Obj -> v.toString()
        is Arr -> v.toString()
        is Int -> v.toString()
        is Long -> v.toString()
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        else -> str(v.toString())
    }

    private fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> if (c < '\u0020') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
