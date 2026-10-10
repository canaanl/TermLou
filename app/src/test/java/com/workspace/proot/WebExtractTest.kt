package com.workspace.proot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `extract` 的**真跑值**测试（5.9.37）。
 *
 * ## 为什么这组不是扫源码
 *
 * 因为「扫源码的锁只能验字样，验不了它有没有用」—— 5.9.35 和 5.9.36 都被这条咬过。
 * 这里的断言是把真结果丢进 [WebExtract.parse]，看它拆出来对不对。
 * 只有一件事扫源码才够：**页面侧脚本到底问没问那些元素**（在 `RegressionScanTest`）。
 */
class WebExtractTest {

    // ---------- 参数夹取 ----------

    @Test
    fun `默认就是约定好的值`() {
        assertEquals(300, WebExtract.DEFAULT_LIMIT)
        assertEquals(2000, WebExtract.MAX_LIMIT)
        assertEquals(8_000, WebExtract.DEFAULT_TEXT_CHARS)
        assertEquals(50_000, WebExtract.MAX_TEXT_CHARS)
    }

    @Test
    fun `limit为零是合法用法不是错误`() {
        // "我只想找搜索框，别给我链接" —— 最省的用法，必须能用
        assertEquals(0, WebExtract.clampLimit(0))
    }

    @Test
    fun `范围内的limit原样通过`() {
        assertEquals(1, WebExtract.clampLimit(1))
        assertEquals(300, WebExtract.clampLimit(300))
        assertEquals(2000, WebExtract.clampLimit(2000))
    }

    @Test
    fun `超上限的limit夹到封顶并如实标记`() {
        // 夹住而不是报错：报错要让 agent 重来一轮，多一轮往返。
        // 但**必须标 limit_capped**，让它知道自己要 5000 没要到。
        assertEquals(WebExtract.MAX_LIMIT, WebExtract.clampLimit(5000))
        assertEquals(WebExtract.MAX_LIMIT, WebExtract.clampLimit(Int.MAX_VALUE))
        assertTrue(WebExtract.limitCapped(5000))
        assertTrue(WebExtract.limitCapped(Int.MAX_VALUE))
    }

    @Test
    fun `没超上限不算被夹`() {
        assertFalse(WebExtract.limitCapped(WebExtract.MAX_LIMIT))
        assertFalse(WebExtract.limitCapped(300))
        assertFalse(WebExtract.limitCapped(0))
    }

    @Test
    fun `负数直接报错不悄悄当成零`() {
        // 静默把 -5 当 0 是这个项目反复吃亏的那类错（悄悄兜底）
        for (bad in listOf(-1, -300)) {
            val e = runCatching { WebExtract.clampLimit(bad) }.exceptionOrNull()
            assertTrue("limit=$bad 应该报错", e is IllegalArgumentException)
            assertTrue("报错要说清为什么：${e?.message}", e?.message?.contains(">=") == true)
        }
    }

    @Test
    fun `text_chars的夹取规则与limit一致`() {
        assertEquals(0, WebExtract.clampTextChars(0))
        assertEquals(20_000, WebExtract.clampTextChars(20_000))
        assertEquals(WebExtract.MAX_TEXT_CHARS, WebExtract.clampTextChars(999_999))
        assertTrue(WebExtract.textCharsCapped(999_999))
        assertFalse(WebExtract.textCharsCapped(8_000))
        assertTrue(
            runCatching { WebExtract.clampTextChars(-1) }.exceptionOrNull() is IllegalArgumentException
        )
    }

    // ---------- 页面脚本：必须真的去问那些元素 ----------

    @Test
    fun `脚本里的上限数字是脚本自己带的`() {
        // 参数是**编进脚本**的（evaluateJavascript 只能传字符串）。
        // 这里锁住它真的带上了，且页面侧没有第二份数字可以作祟。
        val js = WebExtract.pageJs(limit = 42, textChars = 7)
        assertTrue("链接上限必须编进脚本：$js", js.contains("var L=42,T=7"))
    }

    @Test
    fun `脚本要问的每一类元素都在`() {
        val js = WebExtract.pageJs(300, 8_000)
        for (must in listOf(
            "input,textarea,select",     // 能填的
            "button,input[type=submit]", // 能点的
            "a[href]",                   // 链接
            "document.title",            // 标题
            "meta[name=description]",    // 简介
            "script,style,noscript,template,svg"  // 正文里要剔掉的垃圾
        )) {
            assertTrue("脚本没问 $must：$js", js.contains(must))
        }
    }

    @Test
    fun `链接不列纯图标的那类`() {
        // 图标链接（导航、社交）在结果页能占几百条，全是噪声，而且 agent 没用
        val js = WebExtract.pageJs(300, 8_000)
        assertTrue("没文字的链接必须跳过：$js", js.contains("if(!tx)continue;"))
    }

    @Test
    fun `正文用innerText而不是innerHTML`() {
        // innerText 是**人眼看到的**文字：display:none 不出现、script 不出现。
        // agent 要看的是页面，不是源码。
        val js = WebExtract.pageJs(300, 8_000)
        assertTrue("正文必须用 innerText：$js", js.contains("c.innerText"))
        assertFalse("正文不许用 innerHTML：$js", js.contains("c.innerHTML"))
    }

    // ---------- 解析：真结果丢进来 ----------

    /** 一份真实的百度结果页形状（字段名与页面脚本一致）。 */
    private fun sampleJson(
        inputs: String = """[{"tag":"input","type":"text","id":"kw","name":"wd","hint":"百度一下"}]""",
        buttons: String = """[{"text":"百度一下","id":"su"}]""",
        links: String = """[{"text":"结果一","href":"https://a.example/1"}]""",
        linksTotal: Int = 1,
        truncated: Boolean = false,
        text: String = "页面正文"
    ) = """{"url":"https://www.baidu.com/s?wd=x","title":"x_百度搜索",
        |"description":"示例","text":"$text","inputs":$inputs,"buttons":$buttons,
        |"links":$links,"links_total":$linksTotal,"links_truncated":$truncated}"""
        .trimMargin()

    @Test
    fun `能拆出一份完整的页面`() {
        val p = WebExtract.parse(sampleJson())
        assertNotNull("这都解析不出来，extract 就是废的", p)
        p!!
        assertEquals("https://www.baidu.com/s?wd=x", p.url)
        assertEquals("x_百度搜索", p.title)
        assertEquals("示例", p.description)
        assertEquals("页面正文", p.text)
        assertEquals(1, p.inputs.size)
        // 搜索框的 id 必须原样带出去 —— agent 就是靠这个填词
        assertEquals("kw", p.inputs[0]["id"])
        assertEquals("wd", p.inputs[0]["name"])
        assertEquals("百度一下", p.inputs[0]["hint"])
        assertEquals("百度一下", p.buttons[0]["text"])
        assertEquals("结果一", p.links[0]["text"])
        assertEquals("https://a.example/1", p.links[0]["href"])
        assertEquals(1, p.linksTotal)
        assertFalse(p.linksTruncated)
    }

    @Test
    fun `链接被截断时总数照样要带出来`() {
        val links = (1..3).joinToString(",") { """{"text":"t$it","href":"https://e/$it"}""" }
        val p = WebExtract.parse(sampleJson(links = "[$links]", linksTotal = 1847, truncated = true))
        assertNotNull(p)
        p!!
        assertEquals("只给了 3 条", 3, p.links.size)
        // 总数与"截了"这两个字段是 agent 判断"还有没有东西"的唯一依据
        assertEquals(1847, p.linksTotal)
        assertTrue("截了必须说", p.linksTruncated)
    }

    @Test
    fun `没有链接的页面不会崩`() {
        val p = WebExtract.parse(
            sampleJson(inputs = "[]", buttons = "[]", links = "[]", linksTotal = 0)
        )
        assertNotNull(p)
        p!!
        assertEquals(0, p.inputs.size)
        assertEquals(0, p.links.size)
        assertEquals(0, p.linksTotal)
        assertFalse(p.linksTruncated)
    }

    @Test
    fun `只想要搜索框时也能拆出来`() {
        // {"op":"extract","limit":0,"text_chars":0} 的返回形状：inputs/buttons 在，links 空
        val p = WebExtract.parse(
            """{"url":"https://x","title":"t","description":"","text":"",
               |"inputs":[{"tag":"input","type":"text","id":"q"}],
               |"buttons":[],"links":[],"links_total":0,"links_truncated":false}""".trimMargin()
        )
        assertNotNull(p)
        assertEquals("q", p!!.inputs.single()["id"])
        assertEquals("", p.text)
    }

    // ---------- 解析失败必须能分辨，不能一律当空页 ----------

    @Test
    fun `页面没给出可用答案时返回null而不是空页面`() {
        // 5.9.5 的教训：探测失败被当成通过。所以这里必须能分出来。
        assertNull("空串", WebExtract.parse(""))
        assertNull("null", WebExtract.parse("null"))
        assertNull("没有回值", WebExtract.parse(null))
        assertNull("空空白", WebExtract.parse("   "))
        assertNull("不是 JSON", WebExtract.parse("<html>error page</html>"))
        assertNull("脚本自己报了错", WebExtract.parse("""{"error":"boom"}"""))
    }

    @Test
    fun `页面自己报的错不会混进结果里`() {
        // 页面脚本最外层 catch 住异常后回 {"error":...}；那必须让调用方看见失败
        val p = WebExtract.parse("""{"error":"boom","inputs":[{"id":"kw"}]}""")
        assertNull("报错的回值里那些字段一律不算数", p)
    }

    @Test
    fun `缺字段的JSON照样能拆出已知的那些`() {
        // 页面脚本可能因为改了字段名而少给一项；不该整个炸掉
        val p = WebExtract.parse("""{"url":"https://x","title":"t"}""")
        assertNotNull(p)
        p!!
        assertEquals("t", p.title)
        assertEquals(0, p.inputs.size)
        assertEquals(0, p.links.size)
        assertFalse("没给就是没截断，不许瞎报", p.linksTruncated)
    }

    @Test
    fun `数组里有坏元素时跳过它而不是整个失败`() {
        // 单个元素的形状不对（网站写了奇怪的东西）不该让整次 extract 失败
        val p = WebExtract.parse(
            """{"links":[{"text":"好","href":"https://a"}, 42, null, {"text":"也好"}]}"""
        )
        assertNotNull(p)
        assertEquals("坏元素要跳过，好的两个留下", 2, p!!.links.size)
        assertEquals("好", p.links[0]["text"])
        assertEquals("也好", p.links[1]["text"])
    }

    // ---------- 体积：这个功能必须是小东西 ----------

    @Test
    fun `脚本保持在几KB以内`() {
        // 用户明说不要注入大东西（Readability 几十 KB、XPath 几十 KB）。
        // 整个 extract 就靠这一段脚本，超了就说明我偷偷加了重量。
        val js = WebExtract.pageJs(WebExtract.MAX_LIMIT, WebExtract.MAX_TEXT_CHARS)
        assertTrue(
            "页面脚本 ${js.length} 字符，太大了（上限 8000）",
            js.length < 8_000
        )
    }

    @Test
    fun `脚本是自包含的一个表达式`() {
        // evaluateJavascript 收的是表达式；不能靠外部变量，也不能在末尾漏分号
        val js = WebExtract.pageJs(300, 8_000)
        assertTrue("必须是 IIFE 包裹：$js", js.startsWith("(function(") && js.endsWith("()"))
        assertFalse("不许留裸的 var/return 在最外层", js.trimStart().startsWith("var "))
    }

    @Test
    fun `脚本里不许出现会提前闭合的裸结束标签`() {
        // 拼字符串时最容易踩的坑：脚本正文里带着 </script> 之类，
        // 某些注入路径会在这里被截断。扫一遍确认没有。
        val js = WebExtract.pageJs(300, 8_000)
        assertFalse(js.contains("</"))
        assertFalse(js.contains("-->"))
    }

    @Test
    fun `抽出来的Page能被原样序列化回JSON`() {
        // 返回体是直接 put 进去的；这里确认拼出来的 JSON 形状合法
        val p = WebExtract.parse(sampleJson())!!
        val o = JSONObject()
        o.put("url", p.url)
        o.put("title", p.title)
        o.put("inputs", p.inputs)
        o.put("buttons", p.buttons)
        o.put("links", p.links)
        o.put("links_total", p.linksTotal)
        o.put("links_truncated", p.linksTruncated)
        val round = JSONObject(o.toString())
        assertEquals("kw", round.getJSONArray("inputs").getJSONObject(0).getString("id"))
        assertEquals(1, round.getInt("links_total"))
    }
}
