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
 *  2. 脚本拼装抽成纯函数放这里，`WebOpScriptsTest` 对**最终成品**逐条断言；
 *  3. **5.9.38 起，成品必须过 [WebJsStructure] 的结构检查**（括号配平 +
 *     每个 `try` 配得上 `catch`）—— 因为 5.9.37 的 `extract` 与 `type+回车`
 *     各有一处括号/try 配对错误，字符串断言一条都没抓到，真机上全挂。
 *
 * ## 5.9.38 修的两个真 bug
 *
 * - `type(enter=true)` 里多包了一层 `try{…}`：括号总数是配平的（所以"数括号"
 *   的检查看不见它），但那个 `try` **没有 `catch` 配它** → 整段脚本语法错 →
 *   `type` 带回车**恒定失败**。
 * - 填字小工具原来定义在 IIFE **外面**，却引用 IIFE 里面的 `WEB_ERR` →
 *   往 `<div>` 里填字时它自己的报错分支抛 `ReferenceError`，agent 看到的是
 *   `WEB_ERR is not defined` 而不是"这不是个输入框"。现在整块都在 IIFE 里。
 */
internal object WebOpScripts {

    /** 页面侧"出错了"的哨兵前缀（JS 里抛异常回不来，只能把信息编码成字符串）。 */
    const val WEB_ERR_PREFIX = "WEBERR:"

    /**
     * 取元素 + 执行的统一外壳。
     *
     * 形状（**结尾的 `})()` 属于模板，不属于调用方**）：
     * ```
     * (function(){var WEB_ERR='WEBERR:';
     *   try{
     *     var e = <pick>;                        // 元素，或哨兵字符串，或 null
     *     if(e===null)return '__TERMLOU_NOT_FOUND__';
     *     if(typeof e!=='object')return String(e);
     *     <body>                                 // 调用方只写这一段
     *   }catch(err){return WEB_ERR+(err&&err.name||'Error')+': '+(err&&err.message||'')}
     * })()
     * ```
     *
     * 三类结果分开：正常值 / `NOT_FOUND`（元素不在）/ `WEBERR:…`（JS 抛错）。
     * 语法错误则由 WebView 吞成 `null`，上层据此报 `SCRIPT_FAILED`。
     *
     * ⚠ **`WEB_ERR` 只在 IIFE 里面有**（`var` 是函数作用域）。任何引用它的代码
     * 都必须写在 `body` 里，不能放外面 —— 5.9.38 那个 `ReferenceError` 就是这么来的。
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

    /**
     * 填字小工具（**必须放在 `body` 里**，靠 `WEB_ERR` 报错）。
     *
     * 返回 `'ok'` = 填成功了；返回 `''`（假值）= 这个元素不是能填字的东西，
     * 由调用方补上"是什么标签"再报出去。
     *
     * ⚠ 5.9.38 修：原来它返回的是 `WEB_ERR+'not a text field…'` —— 而那是**真值**，
     * 调用方的 `if(!WEB_FILL(…))return null` 永远不成立，于是"往 div 里填字"
     * 会被当成填成功了。现在失败回空串，判断真的生效。
     */
    private const val FILL_HELPER =
        "var WEB_FILL=function(e,v,clear){" +
            "var tag=String(e.tagName).toUpperCase();" +
            "if(tag==='INPUT'||tag==='TEXTAREA'){" +
            // 原生 setter：React/Vue 监听的是 input 事件上 setter 留下的痕迹
            "try{e.scrollIntoView({block:'center'})}catch(_){}" +
            "try{e.focus()}catch(_){}" +
            "var proto=tag==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
            "var d=Object.getOwnPropertyDescriptor(proto,'value');" +
            "if(clear){if(d&&d.set){d.set.call(e,'')}else{e.value=''}}" +
            "if(d&&d.set){d.set.call(e,v)}else{e.value=v}" +
            "try{e.dispatchEvent(new Event('input',{bubbles:true}))}catch(_){}" +
            "try{e.dispatchEvent(new Event('change',{bubbles:true}))}catch(_){}" +
            "return 'ok'}" +
            "if(e.isContentEditable){" +
            "try{e.scrollIntoView({block:'center'})}catch(_){}" +
            "try{e.focus()}catch(_){}" +
            "if(clear){try{e.textContent=''}catch(_){}}" +
            "try{e.textContent=(e.textContent||'')+v}catch(_){}" +
            "try{e.dispatchEvent(new Event('input',{bubbles:true}))}catch(_){}" +
            "return 'ok'}" +
            "return ''};"

    /** 取可见文字；[selector] 为空表示整页正文。 */
    fun text(pick: String?): String = if (pick == null) PAGE_TEXT_JS else {
        wrap(pick, "return e.innerText?e.innerText:(e.value!=null?e.value:'');")
    }

    /**
     * 真实点击：先滚进可视区，再 `click()`（不是改状态）。
     *
     * ## 5.9.38：如实报告点了什么
     *
     * 原来只回一个"元素上的文字"，于是**点了但什么都没发生**和**点成功了**
     * 长得一模一样 —— agent 只能靠猜（真机现象：`ok:true`、文字也对上了，
     * 但页面根本没跳）。现在把**被点元素的标签与去向**一起带回去：
     *
     * - `e.href` 有值（是个链接）→ 报出地址，agent 一眼看出要去哪
     * - `e.target` 是 `_blank` → 报出来（安卓默认会**丢掉**这种新窗口请求，
     *   见 [WebAutomationService] 里 `onCreateWindow` 的说明）
     *
     * 返回的是 **JSON**（不是自己拼分隔符）：链接地址和标题里什么字符都可能有，
     * 用 `|` 之类的分隔符拼，迟早会被内容切错。结构与 [parseClicked] 成对。
     */
    fun click(pick: String): String = wrap(
        pick,
        "try{e.scrollIntoView({block:'center',inline:'center'})}catch(_){}" +
            "try{e.click()}catch(err){return WEB_ERR+String(err&&err.message)}" +
            "var txt=e.innerText?e.innerText:(e.value!=null?e.value:'');" +
            "txt=String(txt).replace(/\\s+/g,' ').trim().slice(0,120);" +
            "var href=(typeof e.href==='string')?e.href:'';" +
            "var target=(typeof e.target==='string')?e.target:'';" +
            "return JSON.stringify({" +
            "tag:String(e.tagName).toLowerCase(),href:href,target:target,text:txt});"
    )

    /** `click` 报回来的"点了什么"。 */
    data class Clicked(
        /** 被点元素的标签名（小写）。 */
        val tag: String,
        /** 是链接时它的绝对地址；不是链接就是空串。 */
        val href: String,
        /** `target` 属性原样（`_blank` 意味着安卓默认会把这次导航丢掉）。 */
        val target: String,
        /** 元素上的可见文字，最多 120 字。 */
        val text: String
    ) {
        /** 点的是不是个能导航的东西。 */
        val isLink: Boolean get() = href.isNotEmpty()

        /** 这条链接要求开新窗口吗。 */
        val wantsNewWindow: Boolean get() = target == "_blank"
    }

    /**
     * 拆 [click] 的返回值。不是那个形状（哨兵、页面返回了别的东西）就返回 null，
     * 调用方按老路处理 —— **不许把拆不出来的东西编成"点了什么"**。
     */
    fun parseClicked(value: String?): Clicked? {
        val v = value ?: return null
        if (!v.startsWith("{")) return null
        val o = runCatching { org.json.JSONObject(v) }.getOrNull() ?: return null
        return Clicked(
            tag = o.optString("tag", ""),
            href = o.optString("href", ""),
            target = o.optString("target", ""),
            text = o.optString("text", "")
        )
    }

    /**
     * 填输入框。[pick] 为空表示填到当前焦点元素上。
     *
     * @param enter **填完按回车**（5.9.37）。真人搜索是打完字按回车，不是去点那个
     *   又小又难找的提交按钮 —— 而且很多搜索框的按钮选择器根本猜不出来。
     *   默认 `false`：不改变既有行为，agent 想要回车才显式传。
     */
    fun type(pick: String?, value: String, clear: Boolean, enter: Boolean = false): String {
        val literal = WebSelector.jsLiteral(value)     // ← 必须带引号
        val source = pick ?: "(document.activeElement)"
        // 回车：派发真实按键事件（网站自己监听的就是它），再加一道"提交最近的表单"。
        // ⚠ 5.9.38：这里**不许**再套一层 `try{…}` —— 每个小步骤各自有 try/catch 就够了，
        // 多包一层会让它配不上 catch，整段脚本语法错（5.9.37 的 bug）。
        val enterJs = if (!enter) "" else
            "var ev=function(k){try{e.dispatchEvent(new KeyboardEvent(k," +
                "{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}))}catch(_){}};" +
                "ev('keydown');ev('keypress');ev('keyup');" +
                "try{" +
                "if(e.form){" +
                "if(typeof e.form.requestSubmit==='function')e.form.requestSubmit();" +
                "else e.form.submit();" +
                "}" +
                "}catch(_){}"
        return wrap(
            source,
            FILL_HELPER +
                "var filled=WEB_FILL(e," + literal + "," + clear + ");" +
                "if(!filled)return WEB_ERR+'not a text field (got <'+String(e.tagName).toLowerCase()+'>)';" +
                enterJs +
                "return 'ok';"
        )
    }

    /**
     * 选 `<select>` 的一项（按 value 或可见文字），并派发 change。
     *
     * ⚠ **"没找到"的哨兵必须是 `null`，不能是空串**（5.9.9 修）：
     * `<option value="">请选择</option>` 这种（按**文字**匹配确实命中）
     * 会让 `hit=o.value` 赋成 `''` —— 而空串同时也是"没找到"的哨兵，
     * 于是**命中了却报 "no option matches"，而且空 value 的选项永远选不中**。
     */
    fun select(pick: String, value: String): String {
        val literal = WebSelector.jsLiteral(value)     // ← 必须带引号
        return wrap(
            pick,
            "if(String(e.tagName).toUpperCase()!=='SELECT')" +
                "return WEB_ERR+'not a <select> (got <'+String(e.tagName).toLowerCase()+'>)';" +
                "var want=" + literal + ";var i,o,hit=null;" +
                "for(i=0;i<e.options.length;i++){o=e.options[i];" +
                "if(o.value===want||String(o.text).trim()===want){hit=o.value;break}}" +
                "if(hit===null)return WEB_ERR+'no option matches '+want;" +
                "e.value=hit;" +
                "try{e.dispatchEvent(new Event('change',{bubbles:true}))}catch(_){}" +
                "return hit;"
        )
    }

    // ---------- 不带选择器的页面脚本 ----------

    /**
     * 整页正文（`text` 不给选择器时）。
     *
     * ⚠ 与 `extract` 的正文**同源**：都是活页面的 `innerText`，所以两边看到的字一致。
     */
    const val PAGE_TEXT_JS = "(document.body ? document.body.innerText : '')"

    /**
     * 落地页探针：一次 eval 拿回 标题 / 正文片段 / 协议 / 当前地址。
     *
     * ## 必须 JSON.stringify，不能手拼分隔符（5.9.9 修的严重 bug）
     *
     * 此前是 `return t+'\n'+b+'\n'+protocol+'\n'+href`：
     *
     *  - Kotlin 里的 `'\n'` 编译后是**真实的 LF 字符**，被塞进 JS 的**单引号字符串
     *    字面量**中间。JS 不允许字面量里裸换行 → **整段语法错** →
     *    `evaluateJavascript` 回调 `null`。
     *  - 本地 node 复现过：`SyntaxError: Invalid or unexpected token`，
     *    双反斜杠（`"\\n"`）才通过。
     *  - 真机表现：`usable` **对每一个页面**都是 `Unknown("probe returned no value")`，
     *    `open`/`wait`/`click`/`back`/`reload` 五处都瞎。
     *  - 而且 Kotlin 侧 `split('\n')` 也错：正文（`innerText` 截 400 字）几乎必然
     *    自带换行，`parts[2]` 拿到的是正文第二行而不是 protocol，
     *    **错误页检测因此失效**。
     *
     * 改用 `JSON.stringify` 一次干掉两处。全项目只有这一处踩了这个坑 ——
     * `WebSelector.escape` 写的是 `"\\n"`，那才是对的。
     */
    const val PROBE_JS = "(function(){var t='';try{t=document.title||''}catch(e){};" +
        "var b='';try{b=(document.body?document.body.innerText:'').slice(0,400)}catch(e){};" +
        "var p='',h='';try{p=location.protocol||'';h=location.href||''}catch(e){};" +
        "return JSON.stringify({t:t,b:b,p:p,h:h})})()"

    /**
     * `diag` 的页面探针。
     *
     * 5.9.38 从 `opDiag` 的函数体里搬出来（那时它是就地拼的临时字符串）——
     * 搬出来才过得了 [WebJsStructureTest] 的检查，不然它是一段没人真正验过的脚本。
     */
    const val DIAG_JS = "(function(){var r={};" +
        "try{r.protocol=location.protocol}catch(e){r.protocol='?'}" +
        "try{r.href=location.href}catch(e){r.href='?'}" +
        "try{r.title=document.title}catch(e){r.title='?'}" +
        "try{r.readyState=document.readyState}catch(e){r.readyState='?'}" +
        "try{r.docW=document.documentElement.scrollWidth}catch(e){r.docW=-1}" +
        "try{r.docH=document.documentElement.scrollHeight}catch(e){r.docH=-1}" +
        "try{r.bodyLen=(document.body?document.body.innerHTML.length:-1)}catch(e){r.bodyLen=-1}" +
        "try{r.visState=document.visibilityState}catch(e){r.visState='?'}" +
        "try{r.visCss=(document.body?getComputedStyle(document.body).visibility:'-')}catch(e){r.visCss='?'}" +
        "try{r.imgs=document.images.length;r.doneImgs=0;" +
        "for(var i=0;i<document.images.length;i++){if(document.images[i].complete)r.doneImgs++}}catch(e){}" +
        "return JSON.stringify(r)})()"

    /**
     * 取回整页源码（`html`）。
     */
    const val OUTER_HTML_JS = "(document.documentElement||{}).outerHTML||''"

    // ---------- 哨兵解析 ----------

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
