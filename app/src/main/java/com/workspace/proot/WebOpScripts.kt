package com.workspace.proot

/**
 * 走选择器的 op 所用的**页面脚本模板**（5.9.3）。
 *
 * ## 为什么单独抽出来
 *
 * 5.9.0 / 5.9.1：`click` / `text` / `type` / `select` **全部恒定失败**，
 * 因为拼出来的 JS 少了引号（`querySelector(a)` 而不是 `querySelector("a")`）。
 * 5.9.2：引号修好了，却**又引入语法错误** —— `PICK_TEMPLATE` 写成
 * `"(function(){var e=%PICK%;"`，**结尾漏了 `})()`**，整段脚本花括号不配平，
 * `evaluateJavascript` 把语法错误吞成 `null`，于是又全部失败，只是报错从
 * "not found" 变成 "returned null"。
 *
 * 两次都是同一个教训：**测试盯住了零件（`pickJs` 的产物），没盯住成品（调用方拼完的脚本）**。
 * 所以现在：
 *  1. 模板自己带尾部闭合 —— 调用方**不可能忘记**补 `})()`；
 *  2. 脚本拼装抽成纯函数放这里，`WebOpScriptsTest` 对**最终成品**逐条断言。
 */
internal object WebOpScripts {

    /** 页面侧"出错了"的哨兵前缀（JS 里抛异常回不来，只能把信息编码成字符串）。 */
    const val WEB_ERR_PREFIX = "WEBERR:"

    /** JS 侧错误哨兵：`WEBERR:<name>: <message>`。 */
    const val JS_ERROR_PREFIX = "__TERMLOU_JS_ERROR__:"

    /** 元素没找到的哨兵。 */
    const val NOT_FOUND = WebSelector.NOT_FOUND

    /**
     * 取元素 + 执行的统一外壳。
     *
     * 形状（**结尾的 `})()` 属于模板，不属于调用方**）：
     * ```
     * (function(){var WEB_ERR='WEBERR:';
     *   try{
     *     var e = <pick>;                        // 元素，或哨兵字符串，或 null
        // 5.9.4: a null pick maps to the not-found sentinel, never to String(e).
        // A CSS selector that matches nothing makes querySelector return JS null,
        // and String(null) is the *string* null - indistinguishable from real
        // content, so every selector op reported ok:true for missing elements.
        append("if(e===null)return ").append(jsNotFound()).append(";")
        append("if(typeof e!=='object')return String(e);")
     *     <body>                                  // 调用方只写这一段
     *   }catch(err){return WEB_ERR+(err&&err.name||'Error')+': '+(err&&err.message||'')}
     * })()
     * ```
     *
     * 三类结果分开：正常值 / `NOT_FOUND`（元素不在）/ `WEBERR:…`（JS 抛错）。
     * 语法错误则由 WebView 吞成 `null`，上层据此报 `bad script`。
     */
    fun wrap(pick: String, body: String): String = buildString {
        append("(function(){var WEB_ERR='").append(WEB_ERR_PREFIX).append("';")
        append("try{var e=").append(pick).append(";")
        // 5.9.4: a null pick maps to the not-found sentinel, never to String(e).
        // A CSS selector that matches nothing makes querySelector return JS null,
        // and String(null) is the *string* null - indistinguishable from real
        // content, so every selector op reported ok:true for missing elements.
        append("if(e===null)return ").append(jsNotFound()).append(";")
        append("if(typeof e!=='object')return String(e);")
        append(body)
        append("}catch(err){return WEB_ERR+(err&&err.name||'Error')+': '+(err&&err.message||'')}")
        append("})()")
    }

    /** Page-side literal for "not found". */
    private fun jsNotFound(): String = "'" + WebSelector.NOT_FOUND + "'"

    private val FILL_HELPER = buildString {
        append("function WEB_FILL(e,v,clear){")
        append("var tag=String(e.tagName).toUpperCase();")
        append("if(tag==='INPUT'||tag==='TEXTAREA'){")
        // 原生 setter：React/Vue 监听的是 input 事件上 setter 留下的痕迹
        append("try{e.scrollIntoView({block:'center'})}catch(_){}")
        append("try{e.focus()}catch(_){}")
        append("var proto=tag==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;")
        append("var d=Object.getOwnPropertyDescriptor(proto,'value');")
        append("if(clear){if(d&&d.set){d.set.call(e,'')}else{e.value=''}}")
        append("if(d&&d.set){d.set.call(e,v)}else{e.value=v}")
        append("try{e.dispatchEvent(new Event('input',{bubbles:true}))}catch(_){}")
        append("try{e.dispatchEvent(new Event('change',{bubbles:true}))}catch(_){}")
        append("return true}")
        append("if(e.isContentEditable){try{e.focus()}catch(_){}")
        append("if(clear){try{e.textContent=''}catch(_){}}")
        append("try{e.textContent=(e.textContent||'')+v}catch(_){}")
        append("try{e.dispatchEvent(new Event('input',{bubbles:true}))}catch(_){}")
        // 闭合 isContentEditable 分支（少这一个 } 会让整个脚本语法错误，由 WebOpScriptsTest 逮到）
        append("return true}")
        append("return WEB_ERR+'not a text field (got <'+String(e.tagName).toLowerCase()+'>)'}")
        append(";")
    }

    /** 取可见文字；[selector] 为空表示整页正文。 */
    fun text(pick: String?): String = if (pick == null) {
        "(document.body ? document.body.innerText : '')"
    } else {
        wrap(pick, "return e.innerText?e.innerText:(e.value!=null?e.value:'');")
    }

    /** 真实点击：先滚进可视区，再 `click()`（不是改状态）。 */
    fun click(pick: String): String = wrap(
        pick,
        "try{e.scrollIntoView({block:'center',inline:'center'})}catch(_){}" +
            "try{e.click()}catch(err){return WEB_ERR+String(err&&err.message)}" +
            "return e.innerText?e.innerText:(e.value!=null?e.value:'ok');"
    )

    /** 填输入框。[pick] 为空表示填到当前焦点元素上。 */
    fun type(pick: String?, value: String, clear: Boolean): String {
        val literal = WebSelector.jsLiteral(value)     // ← 必须带引号
        val source = pick ?: "(document.activeElement)"
        return FILL_HELPER + wrap(
            source,
            "try{e.focus()}catch(_){}" +
                "return WEB_FILL(e," + literal + "," + clear + ")?'ok':null;"
        )
    }

    /** 选 `<select>` 的一项（按 value 或可见文字），并派发 change。 */
    fun select(pick: String, value: String): String {
        val literal = WebSelector.jsLiteral(value)     // ← 必须带引号
        return wrap(
            pick,
            "if(String(e.tagName).toUpperCase()!=='SELECT')" +
                "return WEB_ERR+'not a <select> (got <'+String(e.tagName).toLowerCase()+'>)';" +
                "var want=" + literal + ";var i,o,hit='';" +
                "for(i=0;i<e.options.length;i++){o=e.options[i];" +
                "if(o.value===want||String(o.text).trim()===want){hit=o.value;break}}" +
                "if(hit==='')return WEB_ERR+'no option matches '+want;" +
                "e.value=hit;" +
                "try{e.dispatchEvent(new Event('change',{bubbles:true}))}catch(_){}" +
                "return hit;"
        )
    }

    // ---------- 哨兵解析 ----------

    /** 值是不是"元素没找到"。 */
    fun isNotFound(value: String?): Boolean = WebSelector.isNotFound(value)

    /** 取出 JS 侧的错误信息；不是错误则返回 null。 */
    fun webErrorOf(value: String?): String? =
        if (value != null && value.startsWith(WEB_ERR_PREFIX)) {
            value.removePrefix(WEB_ERR_PREFIX).trim().ifEmpty { "unknown error" }
        } else {
            null
        }

    /** 取出 `pickJs` 内部报出的选择器语法错误（带 `__TERMLOU_JS_ERROR__` 前缀）。 */
    fun jsErrorOf(value: String?): String? = WebSelector.jsErrorOf(value)
}
