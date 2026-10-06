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

    // ---------- 5.9.20：双击切换输入法 ----------

    @Test
    fun `输入法必须挂在双击上不许挂回单击`() {
        // 5.9.19 及以前：单击切换输入法（开→收，关→弹）。用户要求改成双击。
        //
        // 能干净地改，是因为 termux 的 `onSingleTapUp` 其实是
        // `GestureDetector.onSingleTapConfirmed` —— 双击时它根本不触发，
        // 而它自己的 `onDoubleTap` 返回 false 什么都没做，正好是空位。
        val c = code("TerminalController.kt")
        assertTrue("必须有 GestureDetector 接双击", c.contains("GestureDetector("))
        assertTrue(
            "必须有 onDoubleTap 覆写",
            c.contains("override fun onDoubleTap(e: MotionEvent): Boolean")
        )
        // ⚠ 必须锁**双击体内部**：只查 "onDoubleTap(...)" 出现过是不够的 ——
        //    注入"把 onDoubleTap 改成 return false"或"把里面的 toggleIme() 删掉"时
        //    它照样 ALL_GREEN，而功能已经废了。
        val dbl = c.substringAfter("override fun onDoubleTap(e: MotionEvent): Boolean")
            .substringBefore("\n                    }")
        assertTrue("双击必须真的执行弹/收：$dbl", dbl.contains("toggleIme()"))
        assertTrue("双击必须吃掉事件以免继续判定：$dbl", dbl.contains("return true"))
        assertTrue(
            "单击必须什么都不做",
            Regex("override fun onSingleTapUp\\(e: MotionEvent\\?\\) = Unit").containsMatchIn(c)
        )
        // ⚠ 不许把 showSoftInput / hideSoftInputFromWindow 塞回 onSingleTapUp
        val singleTap = c.substringAfter("override fun onSingleTapUp(e: MotionEvent?) = Unit")
            .substringBefore("\n    }")
        assertFalse(
            "单击里不许再弹/收输入法：$singleTap",
            singleTap.contains("showSoftInput") || singleTap.contains("hideSoftInputFromWindow")
        )
    }

    @Test
    fun `双击检测不许消费事件`() {
        // OnTouchListener 返回 false 才不会吃掉 termux 的手势：
        // 长按选字、双指缩放、滑动切笔记都得照常。
        val c = code("TerminalController.kt")
        assertTrue("必须把事件喂给检测器", c.contains("tapDetector.onTouchEvent(event)"))
        val listener = c.substringAfter("setOnTouchListener { _, event ->").take(700)
        assertTrue(
            "OnTouchListener 必须返回 false（不消费）：$listener",
            Regex("false\\s*(//[^\\n]*)?\\n\\s*\\}").containsMatchIn(listener)
        )
        assertTrue("滑动切笔记必须保留", c.contains("activity.showNotesView()"))
        assertTrue("双指缩放不该被当成双击", c.contains("setIsLongpressEnabled(false)"))
    }

    @Test
    fun `切tab的聚焦与收键盘不许被这次改动带坏`() {
        val m = code("MainActivity.kt")
        assertTrue("切到终端 tab 仍要 requestFocus", m.contains("terminalController.terminalView.requestFocus()"))
        assertTrue("切走 tab 仍要 hideIme", m.contains("hideIme()"))
    }

// ---------- 5.9.21：英文按钮全大写 + 介绍页加双击切输入法 ----------

    /** 读 res/values(-en)/xxx.xml 的原始文本（扫源码锁的写法，不走 Android 资源系统）。 */
    private fun resXml(relative: String): String {
        var d: File? = File("").absoluteFile
        var hops = 0
        while (d != null && hops < 6) {
            val f = File(d, "app/src/main/res/$relative")
            if (f.isFile) return f.readText()
            d = d.parentFile
            hops++
        }
        throw AssertionError("找不到 $relative，这条检查等于没跑")
    }

    /** 从 strings.xml 里取某个键的原始值（`&amp;` 这类转义要先解开再判）。 */
    private fun stringValue(xml: String, key: String): String {
        val m = Regex("<string name=\"$key\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(xml) ?: throw AssertionError("strings.xml 里没有 $key")
        return m.groupValues[1]
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("\\'", "'").replace("\\n", "\n")
    }

    @Test
    fun `英文按钮串必须全大写`() {
        // 5.9.20 及以前：走 ButtonStyle / SegmentStyle 的按钮是 `isAllCaps = false`
        // （按串原样显示），而散在各处的 Button 是 `isAllCaps = true`（强制大写）——
        // 于是英文界面下 `Todos` 和 `START CAPTURE` 放一屏，一个首字母大写一个全大写。
        // 定的是全大写：只改串，不碰代码（中文串无大小写，不用动）。
        val en = resXml("values-en/strings.xml")
        val buttonKeys = listOf(
            "notes_todo_tab_btn", "notes_tags_tab_btn", "notes_cal_tab_btn",
            "notes_insert_todo", "notes_insert_tag",
            "palette_save",
            "web_btn_start", "web_btn_stop", "web_btn_clear"
        )
        for (key in buttonKeys) {
            val v = stringValue(en, key)
            assertFalse(
                "$key = [$v] 里还有小写字母，英文按钮就不统一了",
                v.any { it.isLowerCase() }
            )
        }
    }

    @Test
    fun `标题栏不许跟着按钮变大写`() {
        // `notes_todo_tab`（Todos）既是分段按钮又是标题栏/分区标签。
        // 修法是拆键：按钮用 `_btn` 键，标题继续用原键 —— 首字母大写的标题是对的，不许动。
        for (name in listOf("NotesController.kt", "NotesStandaloneActivity.kt")) {
            val c = code(name)
            assertFalse(
                "$name 的分段按钮还在用标题键（会显示首字母大写）：$c",
                Regex("segButton\\([^)]*R\\.string\\.notes_(todo|tags|cal)_tab\\)").containsMatchIn(c)
            )
            assertTrue("$name 必须用 _btn 键", c.contains("notes_todo_tab_btn"))
        }
        // 标题侧一个不许动
        assertTrue(
            "标题栏必须继续用原键",
            code("NotesTodoActivity.kt").contains("R.string.notes_todo_tab)") &&
                code("NotesTagsActivity.kt").contains("R.string.notes_tags_tab)") &&
                code("NotesCalendarActivity.kt").contains("R.string.notes_cal_tab)")
        )
        // 中英键必须对齐：英文有 _btn 键，中文默认串里也必须有（否则中文环境取不到会崩）
        val zh = resXml("values/strings.xml")
        for (key in listOf("notes_todo_tab_btn", "notes_tags_tab_btn", "notes_cal_tab_btn")) {
            stringValue(zh, key)
        }
    }

    @Test
    fun `终端介绍页必须有双击切输入法这一节`() {
        // 5.9.20 把单击切换输入法改成双击，介绍页要同步（中英各 +1 节，子弹格式与现有节一致）。
        assertTrue(
            "必须挂进终端页",
            code("TabIntroContent.kt").contains("R.string.intro_terminal_ime_title")
        )
        for ((file, title, bullet) in listOf(
            Triple(
                "values/tabintro_strings.xml",
                "输入法的弹出与收起",
                "▸ 双击终端 —— 输入法开着就收起，收着就弹出"
            ),
            Triple(
                "values-en/tabintro_strings.xml",
                "Showing and hiding the keyboard",
                "▸ Double-tap the terminal — hides the keyboard if shown, shows it if hidden"
            )
        )) {
            val xml = resXml(file)
            assertTrue("$file 缺 title", stringValue(xml, "intro_terminal_ime_title") == title)
            val body = stringValue(xml, "intro_terminal_ime_body")
            assertTrue("$file 正文必须是一条子弹：[$body]", body == bullet)
        }
    }

// ---------- 5.9.18：终端缝隙里的 opencode 活动进度条 ----------

    @Test
    fun `进度条绝不许碰终端的布局`() {
        // 硬约束：它只是叠在最底层的一层。改 terminalView 的尺寸/位置/padding
        // 就会改 PTY rows/columns，TUI 会重排 —— 用户明确要求不做任何重新排版。
        val r = code("TerminalActivityBar.kt")
        assertFalse("绝不许位移终端", r.contains("translationY") || r.contains("translationX"))
        assertFalse("绝不许改终端 layoutParams", r.contains("layoutParams =") && r.contains("terminalView"))
        assertFalse("绝不许调 setPadding", r.contains("setPadding"))
        assertFalse("绝不许 resize/measure 终端", r.contains("requestLayout") || r.contains("measure("))
        val c = code("TerminalController.kt")
        assertFalse("不许调 terminalView.setPadding", c.contains("terminalView.setPadding"))
        assertFalse("不许改终端 layoutParams", c.contains("terminalView.layoutParams ="))
        assertFalse("不许改终端高度", c.contains("terminalView.layoutParams.height"))
        assertFalse("不许直接写 session.updateSize", c.contains("session.updateSize("))
    }

    @Test
    fun `三层顺序必须是进度条-终端背景-文字`() {
        // 中间那层是全部关键：没有它，底层会从文字后面整个透出来（TerminalView
        // 自己一像素背景都不画），那就变成"整片变色"而不是"填缝"。
        val c = code("TerminalController.kt")
        val order = listOf(
            c.indexOf("addView(activityBar)"),
            c.indexOf("addView(termBg)"),
            c.indexOf("addView(terminalView)")
        )
        assertTrue("三层都得存在：$order", order.all { it >= 0 })
        assertTrue(
            "顺序必须是 底层进度条 → 中层终端背景 → 顶层文字，实际 $order",
            order[0] < order[1] && order[1] < order[2]
        )
        assertTrue("中层必须不透明，否则底层从文字后面透出来", c.contains("setBackgroundColor(scope.cSurface)"))
        assertTrue(
            "中层底边必须用 termux 自己的方法，不许猜 ceil(ascent)",
            c.contains("terminalView.getPointY(rows)")
        )
    }

    @Test
    fun `进度条静默时必须清空成背景色而不是冻结`() {
        // 用户原话："在没有输出的时候这个静态画面是什么？应该就是背景色吧，
        // 只有当终端有输出的时候才变成动画"
        val r = code("TerminalActivityBar.kt")
        assertTrue(
            "起动时必须从周期开头重新开始（5.9.19 起是时间轴 elapsedMs，不是帧号）：$r",
            r.contains("elapsedMs = 0f")
        )
        // 静止时一个像素都不画 → 底下透出根容器的 cSurface
        assertFalse("不许设自己的背景色", r.contains("setBackgroundColor"))
        // ⚠ 必须锁**精确条件**：只查 "if (!running" 出现过是不够的 ——
        //    注入"把 `!running` 从 onDraw 判据里删掉"时它照样 ALL_GREEN，静默就变成
        //    冻结在最后一帧而不是清空成背景色。
        assertTrue(
            "onDraw 必须在没在动时直接返回（静默 = 什么都不画）：$r",
            r.contains("if (!running || cells <= 1 || cellWidth <= 0f) return")
        )
        // 清空逻辑收敛到一个具名方法：空闲和停机共用，不许只改一处漏一处
        assertTrue("必须有统一的清空方法", r.contains("private fun clearToBackground()"))
        val clear = r.substringAfter("private fun clearToBackground()").take(200)
        assertTrue(
            "清空方法必须复位时间轴：$clear",
            clear.contains("elapsedMs = 0f")
        )
        assertTrue(
            "清空方法必须 invalidate（不重画的话最后一帧会留在屏幕上）：$clear",
            clear.contains("invalidate()")
        )
        // 空闲分支和 stop() 都要走它
        assertTrue(
            "空闲分支必须调 clearToBackground",
            Regex("stopScheduled = false\\s+running = false\\s+removeCallbacks\\(frameTask\\)\\s+clearToBackground\\(\\)")
                .containsMatchIn(r)
        )
        assertTrue(
            "stop() 必须调 clearToBackground",
            Regex("startPosted\\.set\\(false\\)\\s+clearToBackground\\(\\)").containsMatchIn(r)
        )
    }

    @Test
    fun `进度条只由PTY输出驱动`() {
        // 需求：只看 PTY 有没有输出，不判断跑的程序、不解析 TUI、不往 PTY 写
        val c = code("TerminalController.kt")
        assertTrue(
            "onTextChanged（PTY 输出回调）必须报活动",
            c.contains("pokeActivityBar()")
        )
        val r = code("TerminalActivityBar.kt")
        assertFalse("不许往 PTY 写数据", r.contains("mTermSession.write("))
        assertFalse("不许读终端内容判断状态", r.contains("getTranscriptText"))
        assertFalse(
            "不许 resize 终端",
            r.contains("updateSize") || r.contains("setPtyWindowSize")
        )
    }

    @Test
    fun `线条必须用可见变体而不是primary`() {
        // 用 primary 的话，背景接近主题绿时它等于隐形（5.9.11 就是这么写的，看不见）
        val c = code("TerminalController.kt")
        assertTrue("必须用 cPrimaryVisible", c.contains("scope.cPrimaryVisible"))
        assertFalse(
            "不许把 cPrimary 当线条色",
            Regex("setInkColor\\([^)]*scope\\.cPrimary\\)").containsMatchIn(c)
        )
    }

    @Test
    fun `每格必须等宽等高`() {
        // 用户原话："所有的线条是都等宽等高的"。
        // 5.9.18 激活格满高、轨道点 34% 高居中，一眼两种高度。现在只分明暗、不分高矮。
        val r = code("TerminalActivityBar.kt")
        assertFalse(
            "不许有按比例缩小的格子（那就是两种高度）：$r",
            r.contains("TRACK_HEIGHT_RATIO") || r.contains("bandH * ") || r.contains("dotH")
        )
        assertTrue(
            "每格都必须用同一个 top..bottom 区间画（等高）",
            r.contains("val top = stripTop.toFloat()") &&
                r.contains("val bottom = height.toFloat()") &&
                Regex("drawRect\\([^)]*\\btop\\b,[^)]*\\bbottom\\b").containsMatchIn(r)
        )
        assertTrue(
            "每格宽度都必须等于一个字符格（等宽）",
            Regex("drawRect\\(cellLeft\\(i\\), top, cellLeft\\(i\\) \\+ cellWidth, bottom").containsMatchIn(r)
        )
        assertFalse("不许画弧线", r.contains("quadTo") || r.contains("drawArc"))
        assertFalse("不许留左右边距（那会让格子不等宽）", r.contains("BLOCK_INSET_RATIO"))
    }

    @Test
    fun `必须先铺满轨道底色否则拖尾外会空掉`() {
        // 拖尾只覆盖 18 格，而轨道有 73 格 —— 剩下 55 格的 alpha 算出来是 0。
        // 不先铺一层暗色底的话那些格子是**全透明**的，缝隙会一段一段空掉，
        // 看上去又变成"高度不一致"。这条 5.9.19 差点漏掉（注入"去掉铺底"时 ALL_GREEN）。
        val r = code("TerminalActivityBar.kt")
        assertTrue(
            "必须整条轨道铺一层暗色底：$r",
            r.contains("canvas.drawRect(0f, top, cells * cellWidth, bottom, fillPaint)")
        )
        assertTrue("铺底色必须用 trackAlpha", r.contains("ActivityTrail.trackAlpha(elapsedMs)"))
        assertTrue(
            "alpha≈0 的格子必须跳过而不是画透明（底色已经铺好了）",
            r.contains("if (alpha <= 0.004f) continue")
        )
    }

    @Test
    fun `单焦点不许退回单元平铺`() {
        // 用户原话："很多一起移动" → 要"只有一个凸显的焦点"。
        // 5.9.18 是 8 格一个单元平铺满宽 → 9 个亮头。现在轨道 = 全宽，只有 1 个焦点。
        val r = code("TerminalActivityBar.kt")
        assertFalse("不许把单元重复画（会出现多个焦点）：$r", r.contains("% OpencodeRider.WIDTH"))
        assertTrue(
            "焦点位置必须来自 ActivityTrail.focusCell（全宽轨道）",
            r.contains("ActivityTrail.focusCell(cells, elapsedMs)")
        )
        assertTrue(
            "明暗必须按**连续距离**算，焦点位置才是浮点、不跳格",
            r.contains("ActivityTrail.signedDistance(focus,") &&
                r.contains("i.toFloat()")
        )
        assertTrue("不许用格号取模（那是原版 8 格小单元的做法）", !r.contains("trailIndex"))
        val t = code("ActivityTrail.kt")
        assertFalse("旧的开码器不许留着", t.contains("OpencodeRider") || t.contains("trailIndex"))
    }

    @Test
    fun `不存在的东西不许回来`() {
        // 5.9.11–5.9.15 那五版的方向全是错的：弯弧线、单向传送带、猜 ceil(ascent)、
        // 私自把终端上移。这些都删干净了，不许复活。
        val repo = listOf("TerminalActivityBar.kt", "ActivityTrail.kt", "TerminalController.kt")
        for (name in repo) {
            val c = code(name)
            assertFalse("$name 不许画弧", c.contains("quadTo") || c.contains("drawArc"))
            assertFalse("$name 不许上移终端", c.contains("terminalView.translationY"))
            assertFalse("$name 不许复活单向传送带", c.contains("DashPathEffect") || c.contains("phaseAfter"))
            assertFalse("$name 不许再减保险像素", c.contains("- 1L"))
        }
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
