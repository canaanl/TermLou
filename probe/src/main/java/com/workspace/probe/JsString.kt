package com.workspace.probe

/**
 * `evaluateJavascript` 回来的值的解码（纯逻辑，可单测）。
 *
 * ## 为什么非做不可
 *
 * 页面里 `return JSON.stringify(obj)`，Android 那边收到的是**这个字符串本身**的 JSON 编码：
 * 外层多一对引号，里面的 `"` 全被转义成 `\"`。直接丢给 `JSONObject(raw)` 一定抛异常 ——
 * 报告会整片空白，而且不报错，很难看出是这一步坏了。
 *
 * TermLou 那边对应的是 `WebProtocol.decodeEvalResult`；probe 是独立模块，不依赖它，
 * 所以这里自己一份，并配测试盯住。
 */
object JsString {

    /** 解出页面真正返回的那个字符串；不是合法 JSON 字符串时返回 null。 */
    fun decode(raw: String?): String? {
        if (raw == null) return null
        val s = raw.trim()
        if (s.length < 2 || s[0] != '"' || s[s.length - 1] != '"') {
            // 页面返回的可能本来就不是字符串（比如 undefined / 数字）
            return s.ifEmpty { null }
        }
        val body = s.substring(1, s.length - 1)
        val sb = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\') { sb.append(c); i++; continue }
            if (i + 1 >= body.length) return null          // 末尾一个孤零零的转义符
            when (val n = body[i + 1]) {
                '"' -> { sb.append('"'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                '/' -> { sb.append('/'); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                'u' -> {
                    if (i + 5 >= body.length) return null
                    val hex = body.substring(i + 2, i + 6)
                    val code = hex.toIntOrNull(16) ?: return null
                    sb.append(code.toChar())
                    i += 6
                }
                else -> return null                         // 未知转义 = 不是 JSON 字符串
            }
        }
        return sb.toString()
    }

    /** [decode] + 解析成对象，任一步失败返回 null。 */
    fun decodeJson(raw: String?): org.json.JSONObject? {
        val text = decode(raw) ?: return null
        return runCatching { org.json.JSONObject(text) }.getOrNull()
    }
}
