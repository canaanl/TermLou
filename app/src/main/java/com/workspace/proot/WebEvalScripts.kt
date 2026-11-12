package com.workspace.proot

import org.json.JSONObject

/**
 * `eval` / `waitfor` / `fetch` 的页面脚本包装层（5.13.0）。
 *
 * ## 解决的三个问题
 *
 * 1. **`evaluateJavascript` 把 JS 异常吞成 `null`** —— 与"页面算出来就是 null"
 *    完全不可分。包装器永远返回一段 JSON，运行时错误带 name/message/stack。
 *    语法错误仍是 `null`（引擎根本不执行），据此换一种包装形式重试。
 * 2. **不 await Promise** —— Promise 会被序列化成 `{}`。包装器检测 thenable，
 *    把结果写进 `window.__tl_eval[token]`，Kotlin 侧轮询取回。
 * 3. **类型信息丢失** —— 包装器带回类型标记，`raw:true` 时还原成原生 JSON。
 *
 * ## 为什么用全局槽位轮询，而不是 `addJavascriptInterface`
 *
 * JS 桥会让**页面自身**也能调用宿主侧对象，等于给任意网页开一个入口；
 * 而且 `eval` / `new Function` 会被页面 CSP 的 `unsafe-eval` 拦掉。
 * 槽位轮询只依赖 `evaluateJavascript` 本身，没有这两类副作用。
 */
@Suppress("MagicNumber", "TooManyFunctions", "LongParameterList", "ReturnCount")
internal object WebEvalScripts {

    /** 包装器同步返回的"还在跑"标记。 */
    const val PENDING = "__TERMLOU_PENDING__"

    /** 轮询时槽位消失（页面换了 / 被清掉）。 */
    const val GONE = "__TERMLOU_GONE__"

    /** 把任意 JS 值转成带类型标记的 JSON；`JSON.stringify` 失败时退回字符串。 */
    private const val PACK =
        "function __tlPack(v){" +
            "if(v===null)return {t:'null'};" +
            "var ty=typeof v;" +
            "if(ty==='undefined')return {t:'undefined'};" +
            "if(ty==='string')return {t:'string',s:v};" +
            "if(ty==='number')return {t:'number',s:String(v),n:(isFinite(v)?v:null)};" +
            "if(ty==='boolean')return {t:'boolean',s:String(v),b:v};" +
            "if(ty==='object'){" +
            "try{var j=JSON.stringify(v);" +
            "if(typeof j==='string'){" +
            "var arr=(Object.prototype.toString.call(v)==='[object Array]');" +
            "return {t:(arr?'array':'object'),s:j,j:j}}}" +
            "catch(e){}" +
            "return {t:'object',s:String(v)}}" +
            "return {t:'other',s:String(v)}}"

    /** 运行时异常 → 结构化 JSON。 */
    private const val ERR =
        "function __tlErr(e){" +
            "return {ok:false,name:String(e&&e.name||'Error')," +
            "message:String(e&&e.message||e),stack:String(e&&e.stack||'')}}"

    /** 成功信封：`{ok:true,t,s,n,b,j}`。 */
    private fun packOk(expr: String): String =
        "JSON.stringify(Object.assign({ok:true},__tlPack(" + expr + ")))"

    /** 轮询脚本：返回 PENDING / GONE / 信封 JSON。 */
    fun poll(token: String): String =
        "(function(){var M=window.__tl_eval||{};var S=M['$token'];if(!S)return '$GONE';" +
            "if(!S.done)return '$PENDING';var r=S.e;try{delete M['$token']}catch(e){}" +
            "return (typeof r==='string')?r:''})()"

    /** 同步表达式：`(js)`。 */
    fun syncExpression(js: String, token: String): String = buildString {
        append("(function(){").append(PACK).append(ERR)
        append("try{var v=(").append(js).append(");")
        append("if(v&&typeof v.then==='function'){")
        append(startSlot("v", token))
        append("return '").append(PENDING).append("';}")
        append("return ").append(packOk("v")).append(";")
        append("}catch(e){return JSON.stringify(__tlErr(e))}})()")
    }

    /** 同步语句体：`(function(){ js })()`；函数体里可以写 `return`。 */
    fun syncStatement(js: String, token: String): String =
        syncExpression("(function(){" + js + "\n})()", token)

    /**
     * 异步包装：`expr` 必须求值成 Promise。
     * 包装器同步返回 [PENDING]，结果由 [poll] 取回。
     */
    fun asyncScript(expr: String, token: String): String = buildString {
        append("(function(){")
        append("var M=window.__tl_eval||(window.__tl_eval={});")
        append("var S=M['").append(token).append("']={done:false,e:''};")
        append("try{Promise.resolve(").append(expr).append(").then(")
        append("function(v){try{S.e=").append(packOk("v")).append("}catch(e){S.e=JSON.stringify(__tlErr(e))}S.done=true},")
        append("function(e){try{S.e=JSON.stringify(__tlErr(e))}catch(_){S.e='{\"ok\":false}'}S.done=true});")
        append("}catch(e){try{S.e=JSON.stringify(__tlErr(e))}catch(_){S.e='{\"ok\":false}'}S.done=true}")
        append("return '").append(PENDING).append("';})()")
    }

    /** 异步表达式：允许顶层 `await`。 */
    fun asyncExpression(js: String): String = "(async function(){return (" + js + ");})()"

    /** 异步语句体：允许顶层 `await`，也允许 `return`。 */
    fun asyncStatement(js: String): String = "(async function(){" + js + "\n})()"

    /** `waitfor` 的条件脚本：返回 `1` / `0` / `E:<msg>`。 */
    fun condition(js: String): String =
        "(function(){try{var v=(" + js + ");return v?1:0}" +
            "catch(e){return 'E:'+String(e&&e.message||e)}})()"

    /** `waitfor` 的 selector 条件：元素存在即满足；未找到继续等，不当作错误。 */
    fun conditionSelector(pick: String): String =
        "(function(){try{var e=(" + pick + ");" +
            "if(typeof e==='string'){" +
            "if(e==='" + WebSelector.NOT_FOUND + "')return 0;" +
            "return 'E:'+e}" +
            "return e?1:0}catch(err){return 'E:'+String(err&&err.message||err)}})()"

    /**
     * `fetch`：在页面里发请求（带页面 cookie），结果写进槽位。
     *
     * @param urlLiteral JS 字符串字面量（已转义）
     * @param methodLiteral 同上
     * @param headersLiteral JS 对象字面量，如 `{"a":"b"}`；空对象用 `{}`
     * @param bodyLiteral 请求体字面量；GET/HEAD 传 null 字面量
     */
    fun fetchScript(
        token: String,
        urlLiteral: String,
        methodLiteral: String,
        headersLiteral: String,
        bodyLiteral: String,
        maxChars: Int,
    ): String = buildString {
        append("(function(){")
        append("var M=window.__tl_eval||(window.__tl_eval={});")
        append("var S=M['").append(token).append("']={done:false,e:''};")
        append("(async function(){try{")
        append("var r=await fetch(").append(urlLiteral).append(",{")
        append("method:").append(methodLiteral).append(",")
        append("headers:").append(headersLiteral).append(",")
        append("credentials:'include',")
        append("body:").append(bodyLiteral)
        append("});")
        append("var t=await r.text();")
        append("var cut=t.length>").append(maxChars).append(";")
        append("if(cut)t=t.slice(0,").append(maxChars).append(");")
        append("S.e=JSON.stringify(Object.assign({ok:true},__tlPack({")
        append("status:r.status,navigated:r.redirected,ct:(r.headers.get('content-type')||''),")
        append("truncated:cut,body:t})));")
        append("}catch(e){try{S.e=JSON.stringify(__tlErr(e))}catch(_){S.e='{\"ok\":false}'}}")
        append("S.done=true})();")
        append("return '").append(PENDING).append("';})()")
    }

    /** 异步槽位：Promise 完成后写入结果。 */
    private fun startSlot(expr: String, token: String): String =
        "var M=window.__tl_eval||(window.__tl_eval={});" +
            "var S=M['$token']={done:false,e:''};" +
            "Promise.resolve(" + expr + ").then(" +
            "function(v){try{S.e=" + packOk("v") + "}catch(e){S.e=JSON.stringify(__tlErr(e))}S.done=true}," +
            "function(e){try{S.e=JSON.stringify(__tlErr(e))}catch(_){S.e='{\"ok\":false}'}S.done=true});"

    // ---------- 解析 ----------

    /** 包装器返回的信封。 */
    data class Envelope(
        val ok: Boolean,
        val type: String,
        val text: String?,
        val json: String?,
        val number: Double?,
        val boolean: Boolean?,
        val name: String?,
        val message: String?,
        val stack: String?,
    )

    fun parseEnvelope(raw: String?): Envelope? {
        if (raw.isNullOrBlank()) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!o.has("ok")) return null
        val ok = o.optBoolean("ok", false)
        return Envelope(
            ok = ok,
            type = o.optString("t", ""),
            text = if (o.has("s")) o.optString("s", "") else null,
            json = if (o.has("j")) o.optString("j", "") else null,
            number = if (o.has("n") && !o.isNull("n")) o.optDouble("n", Double.NaN) else null,
            boolean = if (o.has("b")) o.optBoolean("b", false) else null,
            name = if (o.has("name")) o.optString("name", "") else null,
            message = if (o.has("message")) o.optString("message", "") else null,
            stack = if (o.has("stack")) o.optString("stack", "") else null,
        )
    }

    /**
     * 默认契约：一切按字符串 / JSON 文本返回。
     * `42` → `"42"`、`true` → `"true"`、对象 → JSON 文本、`null`/`undefined` → JSON null。
     */
    fun textValue(env: Envelope): Any? = when (env.type) {
        "null", "undefined" -> null
        "object", "array" -> env.json ?: env.text
        else -> env.text
    }

    /** `raw:true`：还原成原生 JSON 值（数字/布尔/对象/数组）。 */
    @Suppress("CyclomaticComplexMethod")
    fun rawValue(env: Envelope): Any? = when (env.type) {
        "string" -> env.text ?: ""
        "number" -> {
            val n = env.number
            when {
                n == null -> env.text?.toLongOrNull() ?: env.text
                n == Math.floor(n) && !n.isInfinite() -> n.toLong()
                else -> n
            }
        }
        "boolean" -> env.boolean ?: false
        "null", "undefined" -> null
        "object", "array" -> env.json?.let { jsonOrNull(it, "json") } ?: env.text
        else -> env.text
    }

    /** 看起来是 JSON 就解析，否则返回 null。 */
    fun jsonOrNull(body: String, contentType: String): Any? {
        val t = body.trimStart()
        val looksJson = t.startsWith("{") || t.startsWith("[")
        if (!looksJson && !contentType.contains("json", ignoreCase = true)) return null
        return runCatching {
            if (t.startsWith("[")) org.json.JSONArray(body) else JSONObject(body)
        }.getOrNull()
    }
}
