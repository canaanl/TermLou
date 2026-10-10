package com.workspace.proot

import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * **把每一段页面脚本交给真 JS 引擎跑一遍**（5.9.38 新增）。
 *
 * ## 为什么结构检查还不够
 *
 * [WebJsStructureTest] 查的是**结构类**错误（括号配平、`try` 配对、字符串里裸换行、
 * `WEB_ERR` 先用后声明）—— 它抓得到 5.9.37 那两处真机 bug，但**抓不到语义类语法错**。
 *
 * 这一条不是空想：5.9.38 我自己就写出来一处 —— `click` 的 clickable 分支拼成了
 * `return fallback?"X":return "Y"`（冒号后面接了 `return`），括号全配平、
 * 字符串也没问题，**只有真解析器才认得出来**。所以两层都要有：
 *
 * | 关卡 | 谁跑 | 抓什么 |
 * |---|---|---|
 * | 结构检查 | [WebJsStructureTest]，每次跑测试 | 括号 / try 配对 / 裸换行 / 变量作用域 |
 * | **真引擎** | **本测试**，机器上有 node 就跑 | **一切语法错**（结构检查漏的那一类） |
 *
 * ## 为什么不是"必须有 node"
 *
 * 构建用的是 JDK 17，**没有内置 JS 引擎**（Nashorn 在 JDK 15 就删了）。
 * 所以这里：**有 node 就真跑，没有就跳过并说清楚**（[Assume]）。
 * 跳过是诚实的 —— 不假装验过；而在这台开发机上它一定会跑。
 */
class WebJsRealEngineTest {

    /** 与 [WebJsStructureTest] 同一张清单（那边负责结构，这边负责真引擎）。 */
    private fun everyPageScript(): Map<String, String> = linkedMapOf(
        "extract-default" to WebExtract.pageJs(WebExtract.DEFAULT_LIMIT, WebExtract.DEFAULT_TEXT_CHARS),
        "extract-zero" to WebExtract.pageJs(0, 0),
        "extract-max" to WebExtract.pageJs(WebExtract.MAX_LIMIT, WebExtract.MAX_TEXT_CHARS),
        "text-page" to WebOpScripts.text(null),
        "text-css" to WebOpScripts.text(WebSelector.pickJs(WebSelector.Kind.Css("h1"))),
        "text-txt" to WebOpScripts.text(WebSelector.pickJs(WebSelector.Kind.Text("登录", false))),
        "click-css" to WebOpScripts.click(WebSelector.pickJs(WebSelector.Kind.Css("a"), clickable = true)),
        "click-txt" to WebOpScripts.click(
            WebSelector.pickJs(WebSelector.Kind.Text("标题", false), clickable = true)
        ),
        "click-txt-exact" to WebOpScripts.click(
            WebSelector.pickJs(WebSelector.Kind.Text("标题", true), clickable = true)
        ),
        "type-plain" to WebOpScripts.type(null, "关键词", clear = true),
        "type-css" to WebOpScripts.type(WebSelector.pickJs(WebSelector.Kind.Css("#kw")), "x", false),
        "type-enter" to WebOpScripts.type(
            WebSelector.pickJs(WebSelector.Kind.Css("#kw")), "关键词", true, enter = true
        ),
        // 引号 / 单引号 / 反斜杠 / 换行 / U+2028 都塞一遍：转义错了会直接撑破字符串边界
        "type-escapes" to WebOpScripts.type(
            WebSelector.pickJs(WebSelector.Kind.Css("#kw")), "a\"b'c\\d\ne\u2028f", true, enter = true
        ),
        "select" to WebOpScripts.select(
            WebSelector.pickJs(WebSelector.Kind.Text("北京", false)), "北京"
        ),
        "probe" to WebOpScripts.PROBE_JS,
        "diag" to WebOpScripts.DIAG_JS,
        "html" to WebOpScripts.OUTER_HTML_JS
    )

    @Test
    fun `每一段页面脚本都能被真的 JS 引擎解析`() {
        val node = findNode()
        Assume.assumeTrue(
            "这台机器上没有 node —— 真引擎这一关跳过了。" +
                "结构检查（WebJsStructureTest）仍然在跑；打包前请在有 JS 引擎的机器上过一遍。",
            node != null
        )
        val checker = File(System.getProperty("java.io.tmpdir"), "termlou-js-realcheck.js")
        checker.writeText(CHECKER_JS)
        val scripts = File(System.getProperty("java.io.tmpdir"), "termlou-js-scripts.txt")
        scripts.writeText(
            everyPageScript().entries.joinToString("\n") { (name, js) ->
                "===== " + name + "\n" + js + "\n"
            }
        )
        val out = run(node!!, checker.absolutePath, scripts.absolutePath)
        assertTrue(
            "真 JS 引擎认为有脚本编译不过（真机上表现为\"整段脚本不跑\"）：\n$out",
            out.contains("ALL_OK")
        )
    }

    @Test
    fun `自检_真引擎这一关抓得住语义类语法错`() {
        // 5.9.38 我自己写出来的那种错：括号全配平，只有真解析器认得出来
        val node = findNode()
        Assume.assumeTrue("这台机器上没有 node", node != null)
        val broken = File(System.getProperty("java.io.tmpdir"), "termlou-js-broken.js")
        broken.writeText(
            "const fs=require('fs');const x=fs.readFileSync(process.argv[2],'utf8');" +
                "try{new Function(x);console.log('ALL_OK')}catch(e){console.log('BROKEN '+e.message)}"
        )
        val target = File(System.getProperty("java.io.tmpdir"), "termlou-js-broken-target.js")
        target.writeText("(function(){return 1?\"a\":return \"b\"})()")
        val out = run(node!!, broken.absolutePath, target.absolutePath)
        assertTrue("这种错必须被抓到，实际输出：$out", out.contains("BROKEN"))
    }

    private fun run(node: String, vararg args: String): String {
        val cmd = mutableListOf(node)
        cmd += args
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return text
    }

    /** 找 node（Windows 上是 node.exe）。**找不到就是找不到**，不猜别的引擎。 */
    private fun findNode(): String? {
        val candidates = listOf(
            "node", "node.exe",
            "C:\\Program Files\\nodejs\\node.exe",
            "/usr/local/bin/node", "/usr/bin/node"
        )
        for (c in candidates) {
            val r = runCatching {
                val p = ProcessBuilder(c, "--version").redirectErrorStream(true).start()
                val ok = p.waitFor() == 0
                p.inputStream.close()
                ok
            }.getOrDefault(false)
            if (r) return c
        }
        return null
    }

    companion object {
        /**
         * 真引擎检查器（node 侧）。读 `===== 名字` 分块，逐块 `new Function(...)`。
         *
         * 只 `new Function(code)` 就够 —— 那正是 WebView 拿到我们的脚本时做的第一件事
         * （编译）。编译不过就等于真机上的"整段脚本不跑"。
         */
        private val CHECKER_JS = """
            const fs = require('fs');
            const txt = fs.readFileSync(process.argv[2], 'utf8');
            const blocks = [];
            let cur = null;
            for (const line of txt.split('\n')) {
              if (line.startsWith('===== ')) {
                if (cur) blocks.push(cur);
                cur = { name: line.slice(6).trim(), lines: [] };
              } else if (cur) cur.lines.push(line);
            }
            if (cur) blocks.push(cur);
            let bad = 0;
            for (const b of blocks) {
              const js = b.lines.join('\n').replace(/\n+$/, '');
              try {
                new Function(js);
                console.log('OK    ' + b.name);
              } catch (e) {
                bad++;
                console.log('BROKEN ' + b.name + ' -> ' + e.message);
              }
            }
            console.log(bad === 0 ? 'ALL_OK (' + blocks.length + ' scripts)' : 'FAILED ' + bad + '/' + blocks.length);
        """.trimIndent()
    }
}
