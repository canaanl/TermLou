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
     *   if(e===null)return '__TERMLOU_NOT_FOUND__';   // 漏网之鱼：e 是 null → 哨兵
     *   if(typeof e!=='object')return String(e);       // 真·字符串哨兵，原样带回
     *   …对 e 做事，返回一个字符串摘要…})()
     * ```
     *
     * ⚠ **绝不能写 `String(e)` 来处理 null**（5.9.4）：`String(null)` 得到字符串
     * `"null"`，和真实内容无法区分，于是对不存在的元素也报 `ok:true`。
     * 5.9.4 因此在两处堵：这里让 [pickJs] 自己兑现"找不到返回 [NOT_FOUND]"的契约，
     * [WebOpScripts.wrap] 再兜一层 null。
     *
     * 失败时**不用 null 作哨兵**是刻意的：`evaluateJavascript` 会把 JS 抛出的异常吞掉、
     * 回调给 `null`，那样"选择器语法写错"和"元素真的不在"两件事长得完全一样，
     * 全被报成 not found，排查时看不出真因（5.9.0/5.9.1 就是这么被掩盖的）。
     *
     * @param clickable **点人看得见的东西**（5.9.38，`click` 专用）。
     *   `value` 只在提交类按钮上算数（那是它显示出来的文字）；**输入框里现有的字不算**。
     *
     *   ## 为什么必须分开
     *
     *   真人点的是"那行标题"，而 `text=` 原来会把**输入框里现有的字**也当匹配依据
     *   （`norm(t.value)`），而搜索框在页面**最前面**、里面装的正是搜索词 ——
     *   于是 `click {"selector":"text=某词"}` 会点到**顶部搜索框**：点一个输入框
     *   什么都不会发生，而 `click` 只看"JS 没抛异常"就报 `ok:true`。
     *   真机现象就是"点了没反应、但 ok:true、文字也对得上，换一个词就好了"。
     *
     *   `type` / `text` **保留**按 value 匹配（对它们是对的：就是要找"里面已经有字的框"）。
     */
    fun pickJs(kind: Kind, clickable: Boolean = false): String = when (kind) {
        is Kind.Css -> buildString {
            // 查不到时必须回哨兵，不能回 JS 的 null：null 会被上层 `String(null)` 成
            // 字符串 "null"，和真实内容分不开（5.9.4 假成功的根源，就在这里）。
            //
            // ⚠ CSS 模式下 [clickable] **不生效**：选择器是 agent 指名道姓写的
            // （`#sb_form_q`、`input[name=q]`），点它就是要照做 —— 我们只对
            // 自己那套模糊的 `text=` 匹配负责。
            append("(function(){try{var e=document.querySelector(").append(jsLiteral(kind.value)).append(");")
            append("return e?e:").append(jsLiteral(NOT_FOUND)).append("}")
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
            if (clickable) {
                // "人看得见的名字"：value 只在提交类按钮上算
                append("var fallback=null;")
            }
            append("for(i=0;i<nodes.length;i++){t=nodes[i];")
            if (clickable) {
                append("v=norm(t.innerText);")
                append("if(!v&&t.tagName==='INPUT'){var ty=String(t.type||'').toLowerCase();")
                append("if(ty==='submit'||ty==='button'||ty==='image'||ty==='reset')v=norm(t.value)}")
                append("if(!v)v=norm(t.getAttribute?t.getAttribute('aria-label'):'');")
                append("if(v&&(exact?v===want:v.indexOf(want)>=0))return t;")
                // 文字确实在，但落在不能点的东西上（典型：搜索框里的搜索词）
                // → 单独一个哨兵，让上层说的是"找到但不能点"而不是"找不到"
                append("if(!fallback){")
                append("var a=norm(t.innerText)||norm(t.value)||norm(t.placeholder)||")
                append("norm(t.getAttribute?t.getAttribute('aria-label'):'');")
                append("if(a&&(exact?a===want:a.indexOf(want)>=0))fallback=t}")
            } else {
                append("v=norm(t.innerText)||norm(t.value)||norm(t.placeholder)||")
                append("norm(t.getAttribute?t.getAttribute('aria-label'):'');")
                append("if(v&&(exact?v===want:v.indexOf(want)>=0))return t")
            }
            append("}")
            // ⚠ 这里必须 jsLiteral 包裹：裸标识符在 JS 里是 ReferenceError（5.9.2 修的正是这类）
            //
            // ⚠ 三元表达式的两个分支都只能是**表达式**。5.9.38 初版写成
            // `return fallback?"X":` + `return "Y"` —— 拼出来是
            // `return fallback?"X":return "Y"`，冒号后面接了 `return`，**语法错**。
            // 结构检查器（括号/try 配对）抓不到这一类 —— 它由 WebJsRealEngineTest
            // 交给真 JS 引擎抓（这台机器上有 node 就自动跑）。
            append("return ")
            if (clickable) {
                append("fallback?").append(jsLiteral(NOT_CLICKABLE)).append(":")
            }
            append(jsLiteral(NOT_FOUND)).append("})()")
        }
    }

    /** 没找到的哨兵值。上层见到它就报 `not found: <selector>`。 */
    const val NOT_FOUND = "__TERMLOU_NOT_FOUND__"

    /**
     * **找到了、但那个东西点不动**（5.9.38）。典型场景：`text=<搜索词>` 命中的是
     * 顶部搜索框 —— 输入框里装的就是搜索词，而点它什么都不会发生。
     *
     * 与 [NOT_FOUND] 分开是刻意的：这两种情况的处置完全不同 ——
     * "找不到"要换选择器，"找到但不能点"要换一个**像标题/按钮**的文字。
     * 5.9.4 / 5.9.5 反复吃过"不同失败塌成一句"的亏。
     */
    const val NOT_CLICKABLE = "__TERMLOU_NOT_CLICKABLE__"

    /** JS 侧错误哨兵的前缀，后面跟错误信息。上层见到它就报 `bad selector: ...`。 */
    const val JS_ERROR_PREFIX = "__TERMLOU_JS_ERROR__:"

    /** 在 JS 里拼出"把错误信息转成哨兵字符串"的表达式（JS 里的 catch 参数名叫 e）。 */
    private fun jsErrExpr(jsExpression: String): String = "'" + JS_ERROR_PREFIX + " ' + ($jsExpression)"

    /** 判断一个 eval 结果是不是"元素没找到"的哨兵。 */
    fun isNotFound(value: String?): Boolean = value == NOT_FOUND

    /** 判断一个 eval 结果是不是"找到了但点不动"的哨兵（只有 `click` 会拿到它）。 */
    fun isNotClickable(value: String?): Boolean = value == NOT_CLICKABLE

    /**
     * 页面侧回来的值是不是"没找到"——除了 [NOT_FOUND] 哨兵，**字面量 `"null"` 也算**。
     *
     * 5.9.4 加的第二道保险。此前 CSS 选择器查不到元素时 `querySelector` 返回 JS 的
     * `null`，模板里一句 `String(e)` 把它变成字符串 `"null"`，和真实内容分不开，
     * 于是 text/click/type/select 对不存在的元素全报 `ok:true`
     * （真机实测 `#ghost` 返回 `label:"null"`）。
     *
     * 页面正文恰好等于这四个字母的情况极罕见，宁可误判成 not found，
     * 也不能把"什么都没发生"报成成功。
     */
    fun isMissing(value: String?): Boolean = isNotFound(value) || value == "null"

    /** 取出 JS 侧报回来的错误信息；不是错误则返回 null。 */
    fun jsErrorOf(value: String?): String? =
        if (value != null && value.startsWith(JS_ERROR_PREFIX)) {
            value.removePrefix(JS_ERROR_PREFIX).trim().ifEmpty { "syntax error" }
        } else {
            null
        }

    private const val TEXT_PREFIX = "text="
}
