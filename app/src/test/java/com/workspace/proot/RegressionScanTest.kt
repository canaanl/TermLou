package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 5.9.9 修的那几个 bug 的锁。
 *
 * ## 这一版修的（全部有实据，不是猜的）
 *
 * 1. `probeUsability` 的探针 JS 里混进**真实换行** → 整段语法错 → `evaluateJavascript`
 *    回调 `null` → `usable` 对**每一个**页面都是 `Unknown`。Kotlin 里的 `'\n'`
 *    编译后就是真实 LF，被塞进了 JS 的单引号字符串字面量中间。
 *    本地 node 复现过：`SyntaxError: Invalid or unexpected token`，
 *    改双反斜杠（`"\\n"`）才通过。
 * 2. Kotlin 侧 `split('\n')` 也错：正文（`innerText` 截 400 字）几乎必然自带换行，
 *    `parts[2]` 拿到的是正文第二行而不是 protocol → **错误页检测失效**。
 *    1 和 2 一起由「改用 `JSON.stringify`」解决。
 * 3. 自检的 `PROBE_JS` 用 `querySelector('div')`，5.9.8 加了 `#box` 包裹层之后
 *    取到的是**满宽**的 div，`layoutConsistent` 拿满宽比半宽 → 恒 false。
 *    这是 5.9.8 自己引入的。
 * 4. `select` 用 `hit=''` 当"没找到"哨兵，而 `<option value="">` 命中时
 *    `hit=o.value` 也是 `''` → 命中了却报 no option matches，且空 value 永远选不中。
 * 5. `GET /help` 免令牌，**却把真令牌印在说明书里**。Android 上任何 app 访问
 *    127.0.0.1 都不需要权限 → 令牌形同虚设。
 * 6. 生产 WebView 没关 `allowFileAccess` / `allowContentAccess`（默认 true），
 *    配合 5 就是本地 app 读 app 私有目录的通路。
 *
 * 这些大多是"接线层"和"字符串"的问题，**单测很难直接碰到**，
 * 所以这里扫源码把形状钉住。扫源码的局限写在每条的 KDoc 里。
 */
class RegressionScanTest {

    private fun source(name: String): String {
        var d: File? = File("").absoluteFile
        var hops = 0
        while (d != null && hops < 6) {
            val f = File(d, "app/src/main/java/com/workspace/proot/$name")
            if (f.isFile) return f.readText()
            d = d.parentFile
            hops++
        }
        throw AssertionError("找不到 $name，这条检查等于没跑")
    }

    /** 只看代码行：KDoc 里正引着那两句旧文案，是在讲它们为什么不对。 */
    private fun code(name: String): String = source(name).lines()
        .map { it.trimStart().let { t -> if (t.startsWith("*") || t.startsWith("/*")) "" else it } }
        .joinToString("\n")

    // ---------- 1 + 2：探针脚本不许手拼分隔符 ----------

    @Test
    fun `页面探针不许把真实换行塞进JS字符串`() {
        val c = code("WebAutomationService.kt")
        assertFalse(
            "Kotlin 里的 '\\n' 编译成真实 LF，塞进 JS 单引号字面量就是语法错。" +
                "要分隔符请用 JSON.stringify —— 见 WebAutomationService.PROBE_JS 的 KDoc",
            c.contains("""+'\n'+""")
        )
        assertTrue("探针脚本应当改用 JSON.stringify", c.contains("JSON.stringify({t:t,b:b,p:p,h:h})"))
    }

    @Test
    fun `页面探针的Kotlin侧不许再split换行`() {
        val c = code("WebAutomationService.kt")
        assertFalse(
            "正文自带换行，split 会把 protocol/href 串成正文行，错误页检测失效",
            c.contains("parts[2]") || c.contains("probe answer is incomplete")
        )
    }

    @Test
    fun `全项目只有WebSelector那一处是对的换行写法`() {
        // `WebSelector.escape` 用 "\\\\n" 产出 JS 侧看得懂的转义 —— 那才是对的。
        // 留着它当正确范例，别的脚本抄它
        assertTrue(source("WebSelector.kt").contains("""append("\\n")"""))
    }

    // ---------- 3：自检取元素 ----------

    @Test
    fun `自检必须按id取那个半宽div而不是取第一个div`() {
        val js = WebHeadless.PROBE_JS
        assertTrue("必须用 getElementById('w')", js.contains("getElementById('w')"))
        assertFalse(
            "querySelector('div') 取到的是第一个 div —— 5.9.8 加了 #box 包裹层之后" +
                "就是满宽那个，layoutConsistent 拿满宽比半宽恒为 false",
            js.contains("querySelector('div')")
        )
    }

    @Test
    fun `探针页里那个半宽div真的有id`() {
        assertTrue("PROBE_HTML 里必须有 id=\"w\"", WebHeadless.PROBE_HTML.contains("id=\"w\""))
    }

    // ---------- 4：select 哨兵 ----------

    @Test
    fun `select的没找到哨兵必须是null不是空串`() {
        val js = WebOpScripts.select("e", "x")
        assertTrue("哨兵必须是 null", js.contains("hit=null"))
        assertFalse(
            "空串既是命中值又是\"没找到\"哨兵 → <option value=\"\"> 命中了却报 no option matches，" +
                "而且空 value 的选项永远选不中",
            js.contains("hit=''")
        )
        assertTrue("判定必须与 null 比", js.contains("hit===null"))
    }

    // ---------- 5 + 6：鉴权与文件访问 ----------

    @Test
    fun `help不再印令牌`() {
        val help = WebProtocol.help(39080)
        // 真令牌的形状：一段不含 $ 的十六进制。说明书里只该有 $TOKEN 这种占位符
        assertFalse("绝不许印真令牌", help.contains("X-Token: deadbeef"))
        for (line in help.lines()) {
            if (!line.contains("X-Token")) continue
            assertTrue(
                "X-Token 后面只能是占位符：$line",
                line.contains("\$TOKEN") || line.contains("<令牌>")
            )
        }
    }

    @Test
    fun `生产WebView显式关掉文件与内容访问`() {
        val c = code("WebAutomationService.kt")
        assertTrue("allowFileAccess 必须显式关（默认 true）", c.contains("allowFileAccess = false"))
        assertTrue("allowContentAccess 必须显式关（默认 true）", c.contains("allowContentAccess = false"))
    }

    @Test
    fun `存活探测的流必须是参数不能是Service字段`() {
        // 并发隐患：probeInput 曾是 Service 级共享字段，后连上的盖掉先连上的，
        // A 的探测读 B 的流。现在字段必须不存在，流只能当参数传
        val c = code("WebAutomationService.kt")
        assertFalse(
            "probeInput 字段不许回来 —— 回来就等于把并发 bug 带回来",
            c.contains("probeInput")
        )
        assertTrue("route 必须接住流参数", c.contains("input: PushbackInputStream"))
        assertTrue("探测必须用传进来的流", c.contains("isClientGone(client, input)"))
    }

    // ---------- 7：UiEval 不得混淆 ----------

    @Test
    fun `发起求值抛错不许塌成Ok`() {
        val c = code("UiEval.kt")
        assertTrue("必须有 Result.Failed", c.contains("data class Failed"))
        assertTrue("onFailure 里必须记下异常", c.contains("thrown = it"))
    }

    @Test
    fun `求值失败与页面返回null是两种结果`() {
        // 用**运行时**的类型判，不去数源码里的类声明条数 ——
        // 数条数那种写法会被任何一处无关的格式改动误伤
        val outcomes = listOf(
            WebProtocol.EvalOutcome.Timeout(1),
            WebProtocol.EvalOutcome.Value("x"),
            WebProtocol.EvalOutcome.Value(null),
            WebProtocol.EvalOutcome.Cancelled,
            WebProtocol.EvalOutcome.NotPosted,
            WebProtocol.EvalOutcome.Failed("webview destroyed")
        )
        // 六种互不相同的类型/取值，各占一个 —— 少一种就说明又混了
        val distinct = outcomes.map { it::class to it.toString() }.toSet()
        assertEquals("求值结果被混了：$distinct", outcomes.size, distinct.size)
        val uiResults = listOf(
            UiEval.Result.Ok("x"),
            UiEval.Result.Ok(null),
            UiEval.Result.TimedOut(1),
            UiEval.Result.Cancelled,
            UiEval.Result.NotPosted,
            UiEval.Result.Failed(RuntimeException("webview destroyed"))
        )
        val distinctUi = uiResults.map { it::class to it.toString() }.toSet()
        assertEquals("UiEval 结果被混了：$distinctUi", uiResults.size, distinctUi.size)
    }

    // ---------- 8：整页截图必须等首帧 + 验墨（5.9.9"下半截白"） ----------

    @Test
    fun `整页分支必须等首帧而不是紧接着画`() {
        // 真机：撑高之后紧接着画，下半截是白的。自检那边等 1400ms 就好，
        // 这里首等 900ms + 最多 3 次 300ms —— 但"等"这件事不能丢
        val c = code("WebAutomationService.kt")
        assertTrue("必须等首帧", c.contains("FULL_FIRST_FRAME_MS"))
        assertTrue("必须有有限次重试", c.contains("FULL_RETRIES"))
    }

    @Test
    fun `整页分支必须验下三分之一而不是只验整张有内容`() {
        // `hasContent` 整张判空分不清"底色铺满"和"有内容"（底色本身非白）。
        // 下半截白的那次，整张是有内容的（上半截），照样 ok:true 出去了
        val c = code("WebAutomationService.kt")
        assertTrue("必须验下三分之一", c.contains("lowerThirdRendered"))
    }

    @Test
    fun `四次之后还白必须明说而不是按成功返回`() {
        // 那次半空白图是 ok:true + full_page:true 出去的 —— agent 会以为那就是整页。
        // 这比修不好更糟：修不好 agent 知道，能骗过去 agent 就信了
        val c = code("WebAutomationService.kt")
        assertTrue("必须有下半截没渲染的报错", c.contains("SHOT_HALF_BLANK"))
        assertTrue(
            "报错必须说清是下半部分：",
            c.contains("lower part of the long page not rendered")
        )
    }

}
