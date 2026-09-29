package com.workspace.proot

/**
 * 无头浏览器的选择器解析（5.9.0；5.9.2 修字面量与异常吞噬），纯逻辑、可单测。
 *
 * 支持两种写法：
 * ```
 * "input[name=q]"     原生 CSS 选择器（querySelector）
 * "text=登录"         按可见文字找元素（agent 最常用的写法）
 * ```
 * `text=` 后面跟着文字（先去首尾空格），匹配 `innerText`/`value`/`placeholder`/
 * `aria-label` 四者之一等于它；`text==` 开头则要求严格相等。
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

    /**
     * 转义字符串里的特殊字符，**不含两侧引号**。
     *
     * ⚠ 不要拿它直接拼进 JS —— 那会漏掉引号，生成 `querySelector(a)` 而不是
     * `querySelector("a")`，于是 JS 抛 `ReferenceError`、元素永远"找不到"。
     * 拼 JS 一律用 [jsLiteral]。
     */
    fun escape(value: String): String {
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

    /**
     * 完整的 JS 字符串字面量（**含两侧双引号**），可以直接拼进 JS 代码。
     *
     * 5.9.2：此前叫 `jsString` 但只做转义、不带引号，四处调用点全部漏了引号，
     * 导致所有走选择器的指令恒定失败（详见 [WebSelectorTest] 的锁）。
     */
    fun jsLiteral(value: String): String = "\"" + escape(value) + "\""

    /**
     * 生成"在页面里找元素"的 JS 表达式，**求值结果是元素本身**，找不到时是 [NOT_FOUND]，
     * JS 报错时是 `__TERMLOU_JS_ERROR__:…`。
     *
     * 调用方拿到的不是元素就是字符串，所以模板统一这么写：
     * ```
     * (function(){var e=<pickJs>;
     *   if(typeof e!=='object'||e===null)return String(e);   // 哨兵，原样带回
     *   …对 e 做事，返回一个字符串摘要…})()
     * ```
     *
     * 失败时**不用 null 作哨兵**是刻意的：`evaluateJavascript` 会把 JS 抛出的异常吞掉、
     * 回调给 `null`，那样"选择器语法写错"和"元素真的不在"两件事长得完全一样，
     * 全被报成 not found，排查时看不出真因（5.9.0/5.9.1 就是这么被掩盖的）。
     */
    fun pickJs(kind: Kind): String = when (kind) {
        is Kind.Css -> buildString {
            append("(function(){try{return document.querySelector(").append(jsLiteral(kind.value)).append(")}")
            // JS 里的 catch 参数名照旧叫 e，这里是 Kotlin 字符串，别让两边搞混
            append("catch(e){return ").append(jsErrExpr("e.name + ': ' + e.message")).append("}})()")
        }
        is Kind.Text -> buildString {
            append("(function(){var want=").append(jsLiteral(kind.value)).append(";")
            append("var exact=").append(if (kind.exact) "true" else "false").append(";")
            append("var norm=function(s){return (s==null?'':String(s)).replace(/\\s+/g,' ').trim()};")
            append("var nodes=Array.prototype.slice.call(document.querySelectorAll(")
            append("'a,button,input,textarea,select,label,summary,[role=button],[onclick],[tabindex]'))")
            // 这里只能接 ';var' —— 多写一个 ')' 会让整个 IIFE 语法错误，
            // 于是 text= 这条路径恒定失败（5.9.0/5.9.1 的第二个真 bug，由 WebJsSyntaxTest 逮到）
            append(";var i,t,v;")
            append("for(i=0;i<nodes.length;i++){t=nodes[i];")
            append("v=norm(t.innerText)||norm(t.value)||norm(t.placeholder)||")
            append("norm(t.getAttribute?t.getAttribute('aria-label'):'');")
            append("if(v&&(exact?v===want:v.indexOf(want)>=0))return t}")
            // ⚠ 这里必须 jsLiteral 包裹：裸标识符在 JS 里是 ReferenceError（5.9.2 修的正是这类）
            append("return ").append(jsLiteral(NOT_FOUND)).append("})()")
        }
    }

    /** 没找到的哨兵值。上层见到它就报 `not found: <selector>`。 */
    const val NOT_FOUND = "__TERMLOU_NOT_FOUND__"

    /** JS 侧错误哨兵的前缀，后面跟错误信息。上层见到它就报 `bad selector: ...`。 */
    const val JS_ERROR_PREFIX = "__TERMLOU_JS_ERROR__:"

    /** 在 JS 里拼出"把错误信息转成哨兵字符串"的表达式（JS 里的 catch 参数名叫 e）。 */
    private fun jsErrExpr(jsExpression: String): String = "'" + JS_ERROR_PREFIX + " ' + ($jsExpression)"

    /** 判断一个 eval 结果是不是"元素没找到"的哨兵。 */
    fun isNotFound(value: String?): Boolean = value == NOT_FOUND

    /** 取出 JS 侧报回来的错误信息；不是错误则返回 null。 */
    fun jsErrorOf(value: String?): String? =
        if (value != null && value.startsWith(JS_ERROR_PREFIX)) {
            value.removePrefix(JS_ERROR_PREFIX).trim().ifEmpty { "syntax error" }
        } else {
            null
        }

    private const val TEXT_PREFIX = "text="
}
