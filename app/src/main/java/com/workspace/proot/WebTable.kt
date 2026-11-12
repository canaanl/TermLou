package com.workspace.proot

import org.json.JSONArray
import org.json.JSONObject

/**
 * 表格结构化提取（5.13.0）。
 *
 * `extract` 是"看清页面有什么"的通用视图，面向文章页；数据表格（车次、榜单、
 * 股票、招聘）的**行列结构**会被它压成一整块 `text`，等于逼 agent 回去手写解析。
 * 这里补一个 `table` op：按 `headers` + `rows` 输出，并把单元格上承载语义的
 * `title` / `aria-label` / `data-*` 原样带出（12306 的余票与票价就在这些属性里）。
 *
 * 纯字符串拼装，页面脚本部分由 [WebJsStructureTest] 做结构检查。
 */
object WebTable {

    const val DEFAULT_MAX_ROWS = 200
    const val MAX_ROWS = 2000

    /**
     * 页面脚本：从 [pick] 指向的元素（或其中第一个 `<table>`）提取表格。
     *
     * 返回值是 JSON 文本：
     * ```
     * {"ok":true,"headers":[...],"rows":[{"cells":[...],"attrs":[{...}]}],
     *  "rowCount":N,"truncated":bool}
     * ```
     * 失败返回 `{"ok":false,"error":"..."}`。
     */
    fun pageJs(pick: String, maxRows: Int): String = buildString {
        append("(function(){")
        append("try{")
        append("var e=").append(pick).append(";")
        append("if(e===null)return JSON.stringify({ok:false,error:'not found'});")
        append("var t=(String(e.tagName).toUpperCase()==='TABLE')?e:e.querySelector('table');")
        append("if(!t)return JSON.stringify({ok:false,error:'no <table> inside the element'});")
        append("var headSel=t.querySelectorAll('thead th');")
        append("var headers=[];var i;")
        append("for(i=0;i<headSel.length;i++)headers.push(cellText(headSel[i]));")
        append("if(headers.length===0){")
        append("var first=t.querySelector('tr');")
        append("if(first){var ths=first.querySelectorAll('th');")
        append("for(i=0;i<ths.length;i++)headers.push(cellText(ths[i]))}}")
        append("var trs=t.querySelectorAll('tbody tr');")
        append("if(trs.length===0)trs=t.querySelectorAll('tr');")
        append("var rows=[];var truncated=false;")
        append("for(i=0;i<trs.length;i++){")
        append("if(rows.length>=").append(maxRows).append("){truncated=true;break}")
        append("var tr=trs[i];var tds=tr.querySelectorAll('td');")
        append("if(tds.length===0)continue;")
        append("var cells=[];var attrs=[];var k;")
        append("for(k=0;k<tds.length;k++){")
        append("var td=tds[k];")
        append("cells.push(cellText(td));")
        append("var a={};")
        append("if(td.getAttribute('title'))a.title=td.getAttribute('title');")
        append("if(td.getAttribute('aria-label'))a.ariaLabel=td.getAttribute('aria-label');")
        append("try{")
        append("var ds=td.attributes;var n;")
        append("for(n=0;n<ds.length;n++){")
        append("var nm=ds[n].name;")
        append("if(nm&&nm.indexOf('data-')===0){")
        append("a[nm.slice(5)]=ds[n].value}}")
        append("}catch(_){}")
        append("if(td.colSpan&&td.colSpan>1)a.colSpan=td.colSpan;")
        append("if(td.rowSpan&&td.rowSpan>1)a.rowSpan=td.rowSpan;")
        append("attrs.push(a)}")
        append("rows.push({cells:cells,attrs:attrs})}")
        append("return JSON.stringify({ok:true,headers:headers,rows:rows,")
        append("rowCount:rows.length,truncated:truncated})")
        append("}catch(err){")
        append("return JSON.stringify({ok:false,error:String(err&&err.message||err)})")
        append("}")
        append("function cellText(v){")
        append("var s=v.innerText!=null?v.innerText:(v.textContent||'');")
        append("return String(s).replace(/\\s+/g,' ').trim()}")
        append("})()")
    }

    /** 解析页面返回的 JSON；形状不对返回 null。 */
    @Suppress("ReturnCount")
    fun parse(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return if (o.optBoolean("ok", false)) o else null
    }

    /** 上游响应体：`headers` / `rows` 作为原生 JSON 数组返回。 */
    fun response(o: JSONObject): String {
        val headers: JSONArray = o.optJSONArray("headers") ?: JSONArray()
        val rows: JSONArray = o.optJSONArray("rows") ?: JSONArray()
        return WebProtocol.okJson(
            "headers" to headers,
            "rows" to rows,
            "rowCount" to o.optInt("rowCount", rows.length()),
            "truncated" to o.optBoolean("truncated", false),
        )
    }
}
