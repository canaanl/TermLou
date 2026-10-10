package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **注入页面的每一段脚本，都必须过这一关**（5.9.38 新增）。
 *
 * ## 为什么必须有它
 *
 * 5.9.37 那两个真机 bug，字符串断言一条都没抓到：
 *
 * | bug | 形状 | 为什么旧锁看不见 |
 * |---|---|---|
 * | `extract` 全挂 | `idf()` 里多一个 `}` | 旧锁只查"源码里出现过某字样"；括号配平没人查 |
 * | `type` 回车恒挂 | 多一个 `try{`（括号**总数**配平） | 就算数括号也数不出来 —— 它配不上 `catch` |
 *
 * 而且这已经是**第五次**同一类事故（5.9.35 帧回调锁、5.9.36 软件渲染锁、
 * 5.9.37 的 extract 与回车、以及 `WebSessionPolicyTest` 里那几条自说自话的假锁）。
 * 共同点都是：**锁只验了字样，没验"它能不能真的跑起来"。**
 *
 * ## 这一关查什么（每一段成品都要过）
 *
 * 1. **括号配平**：`(` `)`、`{` `}`、`[` `]` 三种都数，且**跳过字符串 / 正则 / 注释里的内容**
 *    （不跳过的"数括号"是假检查：字符串里一个 `{` 就能骗过它）
 * 2. **每个 `try` 必须配得上同级的 `catch` 或 `finally`** —— 回车那个 bug 只有这条抓得到
 * 3. **单/双引号字符串里不许有裸换行** —— 5.9.9 那类（`evaluateJavascript` 吞成 null）
 * 4. **引用 `WEB_ERR` 就必须先声明它** —— 5.9.38 那个
 *    `WEBERR:ReferenceError: WEB_ERR is not defined`（工具定义在 IIFE 外面）
 *
 * ## 为什么不是"真跑一遍 JS"
 *
 * 构建用 JDK 17，**没有内置 JS 引擎**（Nashorn 在 JDK 15 就删了；见 [WebJsSyntaxTest]
 * 里那段说明）。而这两类错误**不需要引擎**就能判定：它们是结构问题，不是语义问题。
 *
 * 真引擎那一关我**每次打包前在这台电脑上用 node 手动跑一遍**（不是构建依赖）——
 * 那是最后一道，这个检查器是"每次跑测试都会自动过"的那一道。
 *
 * ## 刻意不查的
 *
 * 不查语义（变量有没有定义过、函数调得对不对）—— 那是真引擎的活。
 * 查自己**能做到的**，并且**只查这一类**：让"又出现一处括号错"这件事再也进不了包。
 */
class WebJsStructureTest {

    // ================= 检查器本身 =================

    /**
     * 结构检查器（纯字符串扫描，**不是** JS 解析器）。
     *
     * 返回空列表 = 通过；否则每一条是一句人话。
     */
    private object JsCheck {

        fun check(js: String): List<String> {
            val problems = mutableListOf<String>()
            checkBracketsAndTry(js, problems)
            checkWebErrDeclaredFirst(js, problems)
            return problems
        }

        /** 扫描时"当前在什么里面"。 */
        private enum class Mode { CODE, SINGLE, DOUBLE, TEMPLATE, LINE_COMMENT, BLOCK_COMMENT, REGEX }

        private fun checkBracketsAndTry(js: String, out: MutableList<String>) {
            // 括号栈：记录类型、位置，以及"这个 { 是 try 的块吗"
            data class Open(val ch: Char, val at: Int, val isTryBlock: Boolean)

            val stack = ArrayDeque<Open>()
            var mode = Mode.CODE
            var at = 0
            // try 关键字刚读到、还没等到它的 '{'
            var pendingTryAt = -1
            // 刚关闭一个 try 块，下面必须紧跟 catch/finally
            var needCatchAfter = -1

            fun prevSignificant(i: Int): Char {
                var j = i - 1
                while (j >= 0 && js[j].isWhitespace()) j--
                return if (j < 0) ' ' else js[j]
            }

            fun atWord(i: Int): String {
                var end = i
                while (end < js.length && (js[end].isLetterOrDigit() || js[end] == '_' || js[end] == '$')) end++
                return js.substring(i, end)
            }

            while (at < js.length) {
                val c = js[at]
                when (mode) {
                    Mode.LINE_COMMENT -> {
                        if (c == '\n') mode = Mode.CODE
                        at++
                    }
                    Mode.BLOCK_COMMENT -> {
                        if (c == '*' && at + 1 < js.length && js[at + 1] == '/') {
                            mode = Mode.CODE
                            at += 2
                        } else at++
                    }
                    Mode.SINGLE, Mode.DOUBLE, Mode.TEMPLATE -> {
                        val quote = when (mode) {
                            Mode.SINGLE -> '\''
                            Mode.DOUBLE -> '"'
                            else -> '`'
                        }
                        if (c == '\\') {                    // 转义：跳过下一个字符
                            at += 2
                            continue
                        }
                        if (c == quote) {
                            mode = Mode.CODE
                            at++
                            continue
                        }
                        // ⚠ 单/双引号里不许有裸换行（模板字符串里允许，那是它的本事）
                        if ((c == '\n' || c == '\r') && mode != Mode.TEMPLATE) {
                            out += "第 $at 个字符：字符串字面量里有裸换行 —— " +
                                "evaluateJavascript 会把整段脚本吞成 null（5.9.9 的坑）"
                            mode = Mode.CODE
                            at++
                            continue
                        }
                        at++
                    }
                    Mode.REGEX -> {
                        if (c == '\\') { at += 2; continue }
                        if (c == '/') mode = Mode.CODE
                        at++
                    }
                    Mode.CODE -> {
                        // 注释
                        if (c == '/' && at + 1 < js.length && js[at + 1] == '/') {
                            mode = Mode.LINE_COMMENT; at += 2; continue
                        }
                        if (c == '/' && at + 1 < js.length && js[at + 1] == '*') {
                            mode = Mode.BLOCK_COMMENT; at += 2; continue
                        }
                        // 字符串
                        if (c == '\'') { mode = Mode.SINGLE; at++; continue }
                        if (c == '"') { mode = Mode.DOUBLE; at++; continue }
                        if (c == '`') { mode = Mode.TEMPLATE; at++; continue }
                        // 正则字面量：只在"表达式位置"才可能是正则。
                        // 我们的脚本里正则只出现在 .replace(…) 里（前一个有效字符是 `(`）。
                        if (c == '/') {
                            val p = prevSignificant(at)
                            if (p == '(' || p == ',' || p == '=' || p == ':' ||
                                p == '[' || p == '!' || p == '&' || p == '|' ||
                                p == '?' || p == '{' || p == ';' || p == ' '
                            ) {
                                mode = Mode.REGEX
                                at++
                                continue
                            }
                        }
                        // try 关键字
                        if (c == 't' && atWord(at) == "try") {
                            pendingTryAt = at
                            at += 3
                            continue
                        }
                        // catch / finally：把"必须紧跟"的账销掉
                        if ((c == 'c' && atWord(at) == "catch") ||
                            (c == 'f' && atWord(at) == "finally")
                        ) {
                            if (needCatchAfter >= 0) needCatchAfter = -1
                            // catch 后面必须有 '('
                            at += if (js[at] == 'c') 5 else 7
                            continue
                        }
                        // ⚠ 要有"必须紧跟 catch"的账没销，却遇到了别的有效 token。
                        //
                        // 这里**必须连 `}` 一起管**：5.9.37 那个 bug 的形状正是
                        // `try{…}}catch(` —— try 块先关、紧接着又关了一层，
                        // 于是那个 catch 挂在外面（`Missing catch or finally after try`）。
                        // 5.9.38 初版这里放过了 `}`，结果自检用例没被抓住 —— 顺手修掉。
                        if (needCatchAfter >= 0 && !c.isWhitespace()) {
                            out += "第 $needCatchAfter 个字符：这里的 try 块后面不是 catch/finally " +
                                "（下一个有效字符是 `$c`）—— 整段脚本会报 " +
                                "\"Missing catch or finally after try\"（5.9.37 回车的 bug）"
                            needCatchAfter = -1
                        }
                        // 括号
                        if (c == '(' || c == '{' || c == '[') {
                            val isTryBlock = (c == '{' && pendingTryAt >= 0)
                            if (c == '{' && pendingTryAt >= 0) pendingTryAt = -1
                            if (c == '(') {
                                // try (…) 这种写法不合法；本检查不需要处理
                            }
                            stack.addLast(Open(c, at, isTryBlock))
                            at++
                            continue
                        }
                        if (c == ')' || c == '}' || c == ']') {
                            val want = when (c) {
                                ')' -> '('
                                '}' -> '{'
                                else -> '['
                            }
                            val top = stack.removeLastOrNull()
                            if (top == null) {
                                out += "第 $at 个字符：多出来的 `$c` —— 括号不配平"
                            } else if (top.ch != want) {
                                out += "第 $at 个字符：`$c` 对不上第 ${top.at} 个字符的 `${top.ch}` —— 括号不配平"
                            } else if (top.isTryBlock) {
                                // 这个 try 块刚关，接下来必须看到 catch/finally
                                needCatchAfter = top.at
                            }
                            at++
                            continue
                        }
                        at++
                    }
                }
            }
            if (stack.isNotEmpty()) {
                val top = stack.last()
                out += "第 ${top.at} 个字符的 `${top.ch}` 没有闭合 —— 括号不配平"
            }
            if (needCatchAfter >= 0) {
                out += "第 $needCatchAfter 个字符：try 块结束在脚本末尾，没有 catch/finally"
            }
        }

        /**
         * `WEB_ERR` 的**第一次出现**必须是声明（`var/let/const WEB_ERR`）。
         *
         * 5.9.38 那个 bug 就是：填字小工具定义在 IIFE **外面**，却引用 IIFE 里的
         * `WEB_ERR` —— 往 `<div>` 里填字时报的是
         * `WEBERR:ReferenceError: WEB_ERR is not defined`，而不是"这不是个输入框"。
         */
        private fun checkWebErrDeclaredFirst(js: String, out: MutableList<String>) {
            val first = js.indexOf("WEB_ERR")
            if (first < 0) return
            val before = js.substring(maxOf(0, first - 6), first)
            val declared = before.endsWith("var ") || before.endsWith("let ") || before.endsWith("const ")
            if (!declared) {
                out += "第 $first 个字符：先用后声明 WEB_ERR —— " +
                    "定义在外面的代码访问不到 IIFE 里的变量（5.9.38 的 ReferenceError）"
            }
        }
    }

    // ================= 被测对象：**全部**页面脚本 =================

    /**
     * 一段不落。**新增任何页面脚本，都要加到这里** ——
     * 这张表本身也有一条锁（[每一段页面脚本都在检查清单里]）。
     */
    private fun everyPageScript(): Map<String, String> = linkedMapOf(
        "extract(默认)" to WebExtract.pageJs(WebExtract.DEFAULT_LIMIT, WebExtract.DEFAULT_TEXT_CHARS),
        "extract(limit=0,text_chars=0)" to WebExtract.pageJs(0, 0),
        "extract(封顶)" to WebExtract.pageJs(WebExtract.MAX_LIMIT, WebExtract.MAX_TEXT_CHARS),

        "text(整页正文)" to WebOpScripts.text(null),
        "text(CSS)" to WebOpScripts.text(WebSelector.pickJs(WebSelector.Kind.Css("h1"))),
        "text(可见文字)" to WebOpScripts.text(WebSelector.pickJs(WebSelector.Kind.Text("登录", exact = false))),
        "text(可见文字,严格)" to WebOpScripts.text(WebSelector.pickJs(WebSelector.Kind.Text("登录", exact = true))),

        "click(CSS)" to WebOpScripts.click(WebSelector.pickJs(WebSelector.Kind.Css("a"), clickable = true)),
        "click(可见文字)" to WebOpScripts.click(
            WebSelector.pickJs(WebSelector.Kind.Text("某条标题", exact = false), clickable = true)
        ),

        "type(填到焦点)" to WebOpScripts.type(null, "关键词", clear = true),
        "type(CSS,不清空)" to WebOpScripts.type(WebSelector.pickJs(WebSelector.Kind.Css("#kw")), "x", clear = false),
        "type(带回车)" to WebOpScripts.type(
            WebSelector.pickJs(WebSelector.Kind.Css("#kw")), "关键词", clear = true, enter = true
        ),
        // 引号/换行/反斜杠都塞一遍 —— 转义错了会直接破坏字符串边界
        "type(带引号与换行)" to WebOpScripts.type(
            WebSelector.pickJs(WebSelector.Kind.Css("#kw")), "a\"b'c\\d\ne", clear = true, enter = true
        ),

        "select(可见文字)" to WebOpScripts.select(
            WebSelector.pickJs(WebSelector.Kind.Text("北京", exact = false)), "北京"
        ),

        "probe(落地页)" to WebOpScripts.PROBE_JS,
        "diag(页面探针)" to WebOpScripts.DIAG_JS,
        "html(整页源码)" to WebOpScripts.OUTER_HTML_JS
    )

    // ================= 锁 =================

    @Test
    fun `每一段页面脚本都过结构检查`() {
        val failed = mutableListOf<String>()
        for ((name, js) in everyPageScript()) {
            for (p in JsCheck.check(js)) failed += "[$name] $p"
        }
        assertEquals(
            "页面脚本有结构错误（这类错误在真机上表现为\"整段脚本不跑\"，\n" +
                "而 evaluateJavascript 会把它吞成 null，非常难查）：\n" +
                failed.joinToString("\n"),
            emptyList<String>(),
            failed
        )
    }

    @Test
    fun `每一段页面脚本都不是空的`() {
        // 防"名字在表里、内容是空串"——那种情况检查器会一路通过
        for ((name, js) in everyPageScript()) {
            assertTrue("$name 是空的", js.length > 20)
        }
    }

    @Test
    fun `每一段页面脚本都在检查清单里`() {
        // 这个测试盯的是"清单本身有没有漏"。
        //
        // 检查器再严，也拦不住"新写了一段脚本、却没加进 [everyPageScript]"——
        // 而那种漏是**静默**的：新脚本照样上真机，坏了一样没人知道。
        // 所以这里去源码里找**所有交给 evalInPage 的脚本**，逐个核对它在不在清单里。
        //
        // （opEval 是 agent 自己写的 JS，不是我们产的，跳过。）
        val svc = source("WebAutomationService.kt")
        val produced = secondArgumentsOf(svc, "evalInPage").map { it.replace(Regex("\\s+"), " ") }
        assertTrue("一个 evalInPage 都没扫到 —— 这个检查本身坏了", produced.size >= 8)
        val known = listOf(
            "WebExtract.pageJs(",
            "WebOpScripts.text(",
            "WebOpScripts.click(",
            "WebOpScripts.type(",
            "WebOpScripts.select(",
            "WebOpScripts.PROBE_JS",
            "WebOpScripts.DIAG_JS",
            "WebOpScripts.OUTER_HTML_JS",
            "js"        // opEval：agent 自己写的，我们不检查
        )
        val unknown = produced.filter { expr -> known.none { expr.startsWith(it) } }
        assertEquals(
            "有页面脚本没有登记进 [everyPageScript]（要么加进去，要么把名字补进 known）：\n" +
                unknown.joinToString("\n"),
            emptyList<String>(),
            unknown
        )
    }

    /**
     * 取出源码里 `name(` 调用点的**第二个实参**（按括号配平切，不是按逗号切 ——
     * 实参里自己带逗号，比如 `pageJs(limit, textChars)`）。
     */
    private fun secondArgumentsOf(src: String, name: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val i = src.indexOf("$name(", from)
            if (i < 0) return out
            // 找到这个调用的右括号
            var depth = 0
            var j = i + name.length
            val open = j
            while (j < src.length) {
                when (src[j]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                j++
            }
            val inside = src.substring(open + 1, j)
            // 按顶层逗号切
            val parts = mutableListOf<String>()
            var d = 0
            var start = 0
            var k = 0
            while (k < inside.length) {
                when (inside[k]) {
                    '(', '[' -> d++
                    ')', ']' -> d--
                    ',' -> if (d == 0) {
                        parts += inside.substring(start, k)
                        start = k + 1
                    }
                }
                k++
            }
            parts += inside.substring(start)
            if (parts.size >= 2) out += parts[1].trim()
            from = j
        }
    }

    // ================= 自检：检查器真的会红 =================

    @Test
    fun `自检_多一个花括号会被抓到`() {
        // 5.9.37 extract 的真实形状：`||''}}catch(` ——
        // try 块先关、紧接着又关了一层，于是那个 catch 挂到了外面
        val broken = "(function(){try{function idf(){try{return 1}}catch(e){return 2}}return 3})()"
        val problems = JsCheck.check(broken)
        assertTrue("多一个 } 必须被抓到：$problems", problems.isNotEmpty())
    }

    @Test
    fun `自检_try缺catch会被抓到`() {
        // 5.9.37 回车的真实形状：括号总数配平，但那个 try 没有 catch
        val broken = "(function(){var a=1;try{a=2;try{a=3}catch(e){}}return a})()"
        val problems = JsCheck.check(broken)
        assertTrue("try 缺 catch 必须被抓到：$problems", problems.isNotEmpty())
    }

    @Test
    fun `自检_字符串里的括号不该被当真`() {
        // 这是"数括号"式假检查最容易漏的地方：字符串里的 { 不能算进配平
        val ok = "(function(){var s='{';return s})()"
        assertEquals("字符串里的 { 不算括号：${JsCheck.check(ok)}", emptyList<String>(), JsCheck.check(ok))
    }

    @Test
    fun `自检_字符串里的裸换行会被抓到`() {
        val broken = "(function(){var s='a\nb';return s})()"
        assertTrue("裸换行必须被抓到", JsCheck.check(broken).isNotEmpty())
    }

    @Test
    fun `自检_正则里的括号不该被当真`() {
        val ok = "(function(){return 'x'.replace(/\\s+/g,' ')})()"
        assertEquals(emptyList<String>(), JsCheck.check(ok))
    }

    @Test
    fun `自检_先用后声明WEB_ERR会被抓到`() {
        val broken = "function h(){return WEB_ERR+'x'};" +
            "(function(){var WEB_ERR='WEBERR:';try{return h()}catch(e){return String(e)}})()"
        assertTrue("先用后声明必须被抓到", JsCheck.check(broken).isNotEmpty())
    }

    @Test
    fun `自检_模板字符串里的换行是合法的`() {
        val ok = "(function(){var s=`a\nb`;return s})()"
        assertEquals(emptyList<String>(), JsCheck.check(ok))
    }

    @Test
    fun `自检_注释里的括号不该被当真`() {
        val ok = "(function(){/* 这里写着 { 与 ( */return 1})()"
        assertEquals(emptyList<String>(), JsCheck.check(ok))
    }

    // ================= 与旧锁的关系 =================

    @Test
    fun `这两段曾经坏过的脚本现在的形状是对的`() {
        // 直接盯着 5.9.37 两处 bug 的形状，任何一处复发都必须红
        val extract = WebExtract.pageJs(300, 8_000)
        assertTrue(
            "extract 的 idf() 不许再出现多一个 } 的形状：$extract",
            !extract.contains("||''}}catch(")
        )
        val enter = WebOpScripts.type("#kw", "x", true, enter = true)
        assertTrue("带回车的脚本里必须有 requestSubmit", enter.contains("requestSubmit"))
        assertTrue("带回车的脚本里必须有 KeyboardEvent", enter.contains("KeyboardEvent"))
        assertEquals("带回车的脚本必须过结构检查", emptyList<String>(), JsCheck.check(enter))
    }

    /** 从测试的工作目录往上找 `app/src/main/...` —— 单测的工作目录不是仓库根。 */
    private fun source(name: String): String {
        var d: java.io.File? = java.io.File("").absoluteFile
        var hops = 0
        while (d != null && hops < 6) {
            val f = java.io.File(d, "app/src/main/java/com/workspace/proot/$name")
            if (f.isFile) return f.readText()
            d = d.parentFile
            hops++
        }
        throw AssertionError("找不到 $name，这条检查等于没跑")
    }
}
