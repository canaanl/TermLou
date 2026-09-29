package com.workspace.proot

/**
 * 无头浏览器的选择器解析（5.9.0，纯逻辑、可单测）。
 *
 * 支持两种写法：
 * ```
 * "input[name=q]"     原生 CSS 选择器（querySelector）
 * "text=登录"         按可见文字找元素（agent 最常用的写法）
 * ```
 * `text=` 后面跟着文字（先去首尾空格），匹配 `innerText`/`value`/`placeholder`/
 * `aria-label` 四者之一等于它；`text==` 开头则要求严格相等。这样 `text=登录` 能点中
 * `<button><span>登录</span></button>` 这种文字包在子标签里的情况。
 */
object WebSelector {

    sealed class Kind {
        data class Css(val value: String) : Kind()
        data class Text(val value: String, val exact: Boolean) : Kind()
    }

    /** 解析失败返回 null（由调用方转成 `{"ok":false}`）。 */
    fun parse(raw: String?): Kind? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.startsWith(TEXT_PREFIX)) {
            val rest = s.substring(TEXT_PREFIX.length).trim()
            if (rest.isEmpty()) return null
            // `text==xxx` 走严格相等；`text===` 是没写完的选择器，直接判非法
            val exact = rest.startsWith("=")
            val value = if (exact) rest.substring(1).trim() else rest
            if (value.isEmpty()) return null
            return Kind.Text(value, exact)
        }
        return Kind.Css(s)
    }

    /** 把 Kotlin 字符串安全地嵌进 JS 字面量（引号、反斜杠、换行、行分隔符都要转义）。 */
    fun jsString(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            val code = ch.code
            when {
                ch == '\\' -> sb.append("\\\\")
                ch == '"' -> sb.append("\\\"")
                ch == '\'' -> sb.append("\\'")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                // U+2028/U+2029 在 JS 字面量里算行终止符；其余控制字符统一 \uXXXX
                code < 0x20 || code == 0x2028 || code == 0x2029 ->
                    sb.append("\\u").append(String.format("%04x", code))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 生成"在页面里找元素"的 JS 表达式：找到返回元素，失败返回 null。 */
    fun pickJs(kind: Kind): String = when (kind) {
        is Kind.Css ->
            "(function(){try{return document.querySelector(${jsString(kind.value)})}catch(e){return null}})()"
        is Kind.Text -> buildString {
            append("(function(){var want=").append(jsString(kind.value)).append(";")
            append("var exact=").append(if (kind.exact) "true" else "false").append(";")
            append("var norm=function(s){return (s==null?'':String(s)).replace(/\\s+/g,' ').trim()};")
            append("var nodes=Array.prototype.slice.call(document.querySelectorAll(")
            append("'a,button,input,textarea,select,label,summary,[role=button],[onclick],[tabindex]'))")
            append(");var i,t,v;")
            append("for(i=0;i<nodes.length;i++){t=nodes[i];")
            append("v=norm(t.innerText)||norm(t.value)||norm(t.placeholder)||")
            append("norm(t.getAttribute?t.getAttribute('aria-label'):'');")
            append("if(v&&(exact?v===want:v.indexOf(want)>=0))return t}")
            append("return null})()")
        }
    }

    private const val TEXT_PREFIX = "text="
}
