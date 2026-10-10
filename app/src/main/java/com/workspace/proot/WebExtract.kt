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
 * 所以它不需要"理解"什么是搜索框 —— 网站自己写了 `id="kw"`，抄下来告诉 agent，
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
 *
 * ## ⚠ 5.9.38：脚本从"几十段碎片拼"改成**一整块**
 *
 * 5.9.37 的写法是几十个 `append("…")` 首尾相接。`idf()` 里因此多出一个 `}`，
 * 而**括号总数配平、只有 `try` 配不上 `catch`**，字符串层面的检查全都没抓到 ——
 * 那一个字符让 `extract` 在真机上**全挂**（整段脚本编译不过，
 * `evaluateJavascript` 把语法错误吞成 `null`，最后报成"页面没给出可用答案"）。
 *
 * 现在脚本是一整块（Kotlin 原始字符串），括号在一处、看得见、能缩进，
 * 三个数字用 `$` 插值。**不要再拆回碎片拼。**
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
     * @throws IllegalArgumentException 参数不是个数（负数）
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
     * 页面侧脚本。**一整块，不要拆**（见类注释里 5.9.38 那段）。
     *
     * ## 它做四件事
     *
     * 1. 抄所有能填的地方（`input` / `textarea` / `select`）：标签、类型、name、id、提示语
     * 2. 抄所有能点的地方（`button` 和 `input[type=submit|button]`）：按钮上的字、id
     * 3. 抄所有链接：文字 + 完整网址（`a.href` 由浏览器自动转绝对地址）
     * 4. 抄标题、简介、可见正文
     *
     * ## 正文为什么直接取活页面的 `innerText`（5.9.38 改）
     *
     * 5.9.37 是"把 body **复制**一份、在复制品上删掉 script/style、再取文字"。
     * 那一步**有害无益**：
     *
     * - `innerText` 是"人眼看到的字"，**需要排版才可能算出来**；
     *   而复制品不在文档里、没有排版 → 它退化成"所有文字都算"，
     *   `display:none` 藏起来的字**也会进来**。
     * - 而真 `innerText` **本来就不含** `<script>` / `<style>`（它们不参与渲染，
     *   默认就是 `display:none`），所以"删 script/style"这一步是多余的。
     *
     * 现在直接取活页面：更简单，也真的只给看得见的字。
     *
     * ## 为什么链接要滤掉没文字的
     *
     * 图标链接（导航、社交按钮）在结果页能占几百条，全是噪声；
     * 而且它们没有文字，agent 看到也用不上。
     *
     * @param limit 链接条数上限（0 = 不列链接）
     * @param textChars 正文字数上限（0 = 不取正文）
     */
    fun pageJs(limit: Int, textChars: Int): String = """
        (function(){
          try{
            var L=$limit, T=$textChars, TC=$LINK_TEXT_CHARS;
            var r={}, i, e, o, f, b, a;

            function txt(el){
              try{
                var s=(el.innerText||el.textContent||'').replace(/\s+/g,' ').trim();
                return s.length>TC?s.slice(0,TC):s;
              }catch(_){
                return '';
              }
            }

            function idf(el){
              try{
                var s=el.id||el.getAttribute('name');
                if(s)return s;
                if(el.className&&typeof el.className==='string')return String(el.className).split(' ')[0];
                return '';
              }catch(_){
                return '';
              }
            }

            /* ---- 能填的地方：永不截断（搜索框就在这里，且总在页面顶部） ---- */
            r.inputs=[];
            f=document.querySelectorAll('input,textarea,select');
            for(i=0;i<f.length;i++){
              e=f[i];
              var tag=String(e.tagName).toLowerCase();
              o={tag:tag,type:(e.type||'')};
              var id=idf(e);
              if(id)o.id=id;
              var nm=e.getAttribute('name');
              if(nm)o.name=nm;
              var ph=e.getAttribute('placeholder')||e.getAttribute('aria-label');
              if(ph)o.hint=txt({innerText:ph});
              var vt=txt(e);
              if(vt&&tag!=='input')o.value=vt;
              r.inputs.push(o);
            }

            /* ---- 能点的按钮：永不截断 ---- */
            r.buttons=[];
            b=document.querySelectorAll('button,input[type=submit],input[type=button]');
            for(i=0;i<b.length;i++){
              e=b[i];
              var tx=txt(e)||e.value||'';
              o={text:tx||'(no text)'};
              var bid=idf(e);
              if(bid)o.id=bid;
              var ty=e.getAttribute('type');
              if(ty)o.type=ty;
              r.buttons.push(o);
            }

            /* ---- 链接：只列有文字的，href 由浏览器转绝对地址 ---- */
            r.links=[];
            var tot=0;
            if(L>0){
              a=document.querySelectorAll('a[href]');
              for(i=0;i<a.length;i++){
                e=a[i];
                var h=e.href;
                if(!h||h.charAt(0)==='j')continue;
                var ltx=txt(e);
                if(!ltx)continue;
                tot++;
                if(r.links.length<L)r.links.push({text:ltx,href:h});
              }
            }
            r.links_total=tot;
            r.links_truncated=tot>L;

            /* ---- 标题、简介、正文 ---- */
            r.title=(document.title||'');
            r.url=(location.href||'');
            var md=document.querySelector('meta[name=description],meta[property="og:description"]');
            r.description=md?(md.getAttribute('content')||''):'';
            r.text='';
            if(T>0){
              try{
                var body=document.body;
                var s=body?(body.innerText||''):'';
                s=String(s).replace(/\s+/g,' ').trim();
                r.text=s.length>T?s.slice(0,T):s;
              }catch(_){
              }
            }

            return JSON.stringify(r);
          }catch(e){
            return JSON.stringify({error:String(e)});
          }
        })()
    """.trimIndent()

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

    private fun JSONArray?.mapObjects(): List<Map<String, String>> {
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
