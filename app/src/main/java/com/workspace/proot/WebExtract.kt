package com.workspace.proot

import org.json.JSONArray
import org.json.JSONObject

/**
 * `extract` 的**纯逻辑**：注入页面的脚本、参数夹取、返回值的解析（5.9.37 新增）。
 *
 * ## 它解决什么
 *
 * agent 打开一个网页之后是"瞎的" —— 它不知道搜索框的选择器叫什么、不知道结果
 * 链接在哪。现有指令（`click` / `type` / `text`）全都以"agent 知道元素叫什么"
 * 为前提，所以整条搜索流程卡在第一步。
 *
 * 两条老路都不行：
 * - `text`（整页正文）：几千字，搜索框淹没在里面
 * - `html`（整页源码）：几十万字符，直接把 agent 的上下文撑爆
 *
 * `extract` 就是"**快速看清这个页面上有什么**"：只提取，不做正文算法。
 *
 * ## 它不猜，靠网站自己写明的标签
 *
 * 每个元素都是网站自己的代码写上去的，标签和属性明明白白：
 *
 * ```
 * <input id="kw" name="wd" placeholder="百度一下">   → 框
 * <button id="su">百度一下</button>                  → 按钮
 * <a href="https://…">某条结果标题</a>                → 链接
 * ```
 *
 * 所以它不需要"理解"什么���搜索框 —— 网站自己写了 `id="kw"`，抄下来告诉 agent，
 * agent 就知道往那儿填。
 *
 * ## 体积
 *
 * 几十行脚本，约 2～3 KB。对比：Readability 正文提取几十 KB、XPath 引擎几十 KB。
 *
 * ## 为什么不截断输入框和按钮
 *
 * agent 滚得再久，搜索框永远在页面**顶部**，而它在 [Page] 的 `inputs` /
 * `buttons` 里 —— 那两个数组**永不截断**。只有 `links` 会给上限。
 *
 * 无尽滚动每滚一下往页面塞一批内容，所以链接可能上千条；那才需要上限，
 * 而且截了要**明说**（`links_truncated` / `links_total`），不瞒着。
 */
object WebExtract {

    /** 链接条数默认值。 */
    const val DEFAULT_LIMIT = 300

    /** 链接条数上限：agent 传更大的会被夹到这里，并回 `limit_capped: true`。 */
    const val MAX_LIMIT = 2000

    /** 正文字数默认值。 */
    const val DEFAULT_TEXT_CHARS = 8_000

    /** 正文字数上限。 */
    const val MAX_TEXT_CHARS = 50_000

    /** 单条链接文字的截断长度 —— 一条链接的标题不会有 100 字那么长。 */
    const val LINK_TEXT_CHARS = 100

    /**
     * 夹 `limit`：0 是**合法值**（"我只想找搜索框，别给我链接"），
     * 负数与超上限都要如实告诉调用方。
     *
     * @return `夹好的值`
     * @throws IllegalArgumentException 参数不是个数（负数、缺值）
     */
    fun clampLimit(raw: Int): Int = when {
        raw < 0 -> throw IllegalArgumentException("limit must be >= 0 (got $raw)")
        raw > MAX_LIMIT -> MAX_LIMIT
        else -> raw
    }

    /** `limit` 是否被夹过（要被调用方报成 `limit_capped`）。 */
    fun limitCapped(raw: Int): Boolean = raw > MAX_LIMIT

    /** 夹 `text_chars`，规则同 [clampLimit]。 */
    fun clampTextChars(raw: Int): Int = when {
        raw < 0 -> throw IllegalArgumentException("text_chars must be >= 0 (got $raw)")
        raw > MAX_TEXT_CHARS -> MAX_TEXT_CHARS
        else -> raw
    }

    fun textCharsCapped(raw: Int): Boolean = raw > MAX_TEXT_CHARS

    /**
     * 页面侧脚本。
     *
     * ## 它做四件事
     *
     * 1. 抄所有能填的地方（`input` / `textarea` / `select`）：标签、类型、name、id、提示语
     * 2. 抄所有能点的地方（`button` 和 `input[type=submit|button]`）：按钮上的字、id
     * 3. 抄所有链接：文字 + 完整网址（`a.href` 由浏览器自动转绝对地址）
     * 4. 抄标题、简介、可见正文
     *
     * ## 为什么不用 innerHTML 取文字
     *
     * `innerText` 是**人眼看到的**文字：`display:none` 的不出现、`<br>` 变成换行、
     * `<script>` / `<style>` 的内容不出现。这正是我们要的 —— agent 要看的是页面，
     * 不是源码。
     *
     * ## 为什么链接要滤掉没文字的
     *
     * 图标链接（导航、社交按钮）在结果页能占几百条，全是噪声；
     * 而且它们没有文字，agent 看到也用不上。
     *
     * @param limit 链接条数上限（0 = 不列链接）
     * @param textChars 正文字数上限（0 = 不取正文）
     */
    fun pageJs(limit: Int, textChars: Int): String = buildString {
        append("(function(){try{")
        append("var L=").append(limit).append(",T=").append(textChars).append(",TC=")
        append(LINK_TEXT_CHARS).append(";")
        // 一段纯文本：去掉 script/style，innerText 只给人看的
        append("function txt(e){try{var s=(e.innerText||e.textContent||'')")
        append(".replace(/\\s+/g,' ').trim();return s.length>TC?s.slice(0,TC):s}catch(_){return ''}}")
        // 元素标识：优先 id（最稳），其次 name，其次 class —— agent 拿去当选择器
        append("function idf(e){try{return e.id||e.getAttribute('name')||")
        append("(e.className&&typeof e.className==='string'?String(e.className).split(' ')[0]:'')||''}")
        append("}catch(_){return ''}}")
        append("var r={},i,e;")
        // ---- 能填的地方：永不截断（搜索框就在这里，且总在页面顶部）----
        append("r.inputs=[];")
        append("var f=document.querySelectorAll('input,textarea,select');")
        append("for(i=0;i<f.length;i++){e=f[i];var t=String(e.tagName).toLowerCase();")
        append("var o={tag:t,type:(e.type||'')};var id=idf(e);if(id)o.id=id;")
        append("var nm=e.getAttribute('name');if(nm)o.name=nm;")
        append("var ph=e.getAttribute('placeholder')||e.getAttribute('aria-label');if(ph)o.hint=txt({innerText:ph});")
        append("var vt=txt(e);if(vt&&t!=='input')o.value=vt;r.inputs.push(o)}")
        // ---- 能点的按钮：永不截断 ----
        append("r.buttons=[];")
        append("var b=document.querySelectorAll('button,input[type=submit],input[type=button]');")
        append("for(i=0;i<b.length;i++){e=b[i];var tx=txt(e)||e.value||'';")
        append("var o={text:tx||'(无文字)'};var id=idf(e);if(id)o.id=id;")
        append("var ty=e.getAttribute('type');if(ty)o.type=ty;r.buttons.push(o)}")
        // ---- 链接：只列有文字的，href 由浏览器转绝对地址 ----
        append("r.links=[];var tot=0;")
        append("if(L>0){var a=document.querySelectorAll('a[href]');")
        append("for(i=0;i<a.length;i++){e=a[i];var h=e.href;")
        append("if(!h||h.charAt(0)==='j')continue;")
        append("var tx=txt(e);if(!tx)continue;")
        append("tot++;")
        append("if(r.links.length<L)r.links.push({text:tx,href:h})}}")
        append("r.links_total=tot;r.links_truncated=tot>L;")
        // ---- 标题、简介、正文 ----
        append("r.title=(document.title||'');")
        append("r.url=(location.href||'');")
        append("var md=document.querySelector('meta[name=description],meta[property=\"og:description\"]');")
        append("r.description=md?(md.getAttribute('content')||''):'';")
        append("r.text='';if(T>0){try{")
        append("var body=document.body;var c=body?body.cloneNode(true):null;")
        append("if(c){var junk=c.querySelectorAll('script,style,noscript,template,svg');")
        append("for(i=0;i<junk.length;i++){try{junk[i].remove()}catch(_){}}")
        append("var s=c.innerText||'';s=s.replace(/\\s+/g,' ').trim();")
        append("r.text=s.length>T?s.slice(0,T):s}}catch(_){}}")
        append("return JSON.stringify(r)}catch(e){return JSON.stringify({error:String(e)})}})()")
    }

    /**
     * 解析脚本回值。**解析不出来必须返回 null** —— 调用方要能把
     * "页面没给出答案"和"页面是空的"分开（5.9.5 的教训：探测失败被当成通过）。
     */
    fun parse(raw: String?): Page? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s == "null") return null
        val o = runCatching { JSONObject(s) }.getOrNull() ?: return null
        if (o.has("error")) return null
        return Page(
            url = o.optString("url", ""),
            title = o.optString("title", ""),
            description = o.optString("description", ""),
            text = o.optString("text", ""),
            inputs = o.optJSONArray("inputs").mapObjects(),
            buttons = o.optJSONArray("buttons").mapObjects(),
            links = o.optJSONArray("links").mapObjects(),
            linksTotal = o.optInt("links_total", 0),
            linksTruncated = o.optBoolean("links_truncated", false)
        )
    }

    private fun org.json.JSONArray?.mapObjects(): List<Map<String, String>> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            optJSONObject(i)?.let { obj ->
                obj.keys().asSequence().associateWith { obj.optString(it, "") }
            }
        }
    }

    /** extract 的结果。字段都是**结构化**的，省得 agent 每次现写 JS 去猜。 */
    data class Page(
        val url: String,
        val title: String,
        val description: String,
        val text: String,
        val inputs: List<Map<String, String>>,
        val buttons: List<Map<String, String>>,
        val links: List<Map<String, String>>,
        /** 页面里一共多少条带文字的链接（不管截没截）。 */
        val linksTotal: Int,
        /** 链接被 `limit` 截断了吗。**截了必须让 agent 知道**，否则它会以为那就是全部。 */
        val linksTruncated: Boolean
    )
}