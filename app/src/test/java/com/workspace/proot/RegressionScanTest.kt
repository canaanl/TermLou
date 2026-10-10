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

    /** 文件在不在（用来锁"整个删掉的文件不许被重新建出来"）。 */
    private fun fileExists(name: String): Boolean {
        var d: File? = File("").absoluteFile
        var hops = 0
        while (d != null && hops < 6) {
            if (File(d, "app/src/main/java/com/workspace/proot/$name").isFile) return true
            d = d.parentFile
            hops++
        }
        return false
    }

    /**
     * 只看**真正在跑的代码**：`//` 行注释与 KDoc 整块都去掉。
     *
     * ## 为什么要有这么一把而不是直接用 [code]
     *
     * 删掉的功能要在原地留注释讲"为什么删"，那段注释里**必然出现被删的 API 名**
     * （比如 `LAYER_TYPE_SOFTWARE`、`wv.draw(`）。用 [code] 扫，这些警告会被当成
     * "代码还在" —— 于是这条锁就变成了"不许提这个词"，谁也没法在那里写文档。
     *
     * [code] 只去掉 KDoc 是有历史的（5.9.9 那批探针锁靠它读代码行），
     * 所以不改它的行为，**另起一把更严的**给 5.9.37 这组用。
     */
    private fun codeOnly(name: String): String {
        var inBlock = false
        return source(name).lines().mapNotNull { line ->
            val t = line.trimStart()
            if (inBlock) {
                if (t.startsWith("*/")) inBlock = false
                return@mapNotNull null
            }
            when {
                t.startsWith("/*") -> {
                    if (!t.contains("*/")) inBlock = true
                    null
                }
                t.startsWith("//") -> null
                else -> t
            }
        }.joinToString("\n")
    }

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

// ---------- 5.9.22：文件状态栏长名纵向整行滚动 ----------

    @Test
    fun `文件名不许直接塞进状态栏`() {
        // 之前三处都是 `statusText?.text = fullName`：超长名把栏撑成多行，
        // 下面所有元素被顶下去。现在必须走 StatusMarquee（栏高锁死 1 行 + 纵向滚动）。
        val c = code("FileListManager.kt")
        assertFalse(
            "文件名必须走 StatusMarquee.show，不许直接设文字：$c",
            c.contains("statusText?.text = fullName") || c.contains("statusText?.text = newDir.name")
        )
        assertTrue("三处展示都要走 StatusMarquee", c.contains("StatusMarquee.show("))
        assertFalse(
            "旧的恢复回调字段必须删掉（取消逻辑收进 StatusMarquee 里了）：$c",
            c.contains("statusTextRestoreRunnable")
        )
    }

    @Test
    fun `展示时长必须和原来一致`() {
        // 原来三处都是 1000ms。正常名字一毫秒不许加，只有走不完才延长（延长的逻辑在 showMs 里）。
        val c = code("FileListManager.kt")
        assertFalse("不许手写 postDelayed 1000", c.contains("postDelayed(r, 1000)"))
        assertTrue("必须用原来的基础时长", c.contains("StatusMarquee.BASE_MS"))
        assertEquals("基础时长就是 1000ms", 1000L, StatusMarquee.BASE_MS)
    }

    @Test
    fun `恢复前必须先停掉动画否则常态显示会被顶空`() {
        // 根因：animator 的结束帧和 finish 都排在 total 毫秒，先后没保证。
        // 结束帧若落在 finish 后面，会把 scrollY 写回最大值 —— 此时栏里已经是
        // 1 行的常态文字，常态文字被顶出可视区，栏空。
        // 静态路径没有 animator，所以短名永远没事，只坏滚动的情况。
        val c = code("StatusMarquee.kt")
        assertTrue(
            "finish 必须先 cancel animator 再恢复：$c",
            Regex("(?s)val finish = Runnable \\{[^}]*animator\\?\\.cancel\\(\\)").containsMatchIn(c)
        )
    }

    @Test
    fun `滚动目标必须是实测行顶不许再乘行高`() {
        // 5.9.22 用 `i × lineHeight` 算滚动位置，排版的零点几像素累计误差让行对不齐，
        // 上一行的降部（j 的脚）漏出来一截。改成 `layout.getLineTop(i)` 一行一取。
        // ⚠ 判据必须形状无关：只禁某几种写法（如 `i * lineH`），换个变量名
        // （`it * view.lineHeight`）就漏过去，白骗一次验证。
        val c = code("StatusMarquee.kt")
        assertTrue("必须用实测行顶", c.contains("layout.getLineTop("))
        assertFalse(
            "任何'行号乘行高'的写法都不许回来：$c",
            Regex("""\b\w+\s*\*\s*(view\.lineHeight|lineH|lineHeight)\b""").containsMatchIn(c)
        )
    }

    @Test
    fun `滚动不能太快`() {
        // 用户反馈 250/150 太快。5.9.23 起每行停 450ms、过渡 250ms。
        assertEquals("每行停留", 450L, StatusMarquee.HOLD_MS)
        assertEquals("行间过渡", 250L, StatusMarquee.STEP_MS)
    }

    @Test
    fun `状态栏外框只许建一次`() {
        // 5.9.24 黑屏根因：MainActivity 里留了两处 createStatusWrap() 调用。
        // 第一处建了 statusView 并装进外框 1；第二处跳过创建（已初始化）直接 addView ——
        // 同一个 child 有两个爹，当场抛 `child already has a parent`，onCreate 炸 →
        // TermLouApp 静默杀进程 → 黑屏 + 磁贴全死 + 无崩溃框。
        val m = code("MainActivity.kt")
        assertEquals(
            "createStatusWrap() 在 MainActivity 里必须只出现一次",
            1,
            Regex("createStatusWrap\\(\\)").findAll(m).count()
        )
        val s = code("StatusController.kt")
        assertTrue(
            "createStatusWrap 必须是幂等的（建过直接返回旧外框）：$s",
            s.contains("statusWrap?.let { return it }") && s.contains("also { statusWrap = it }")
        )
    }

    // ---------- 5.9.34：视口就是窗口，没有缩放；判据与动作分开 ----------

    @Test
    fun `视口是412dp缩放只发生在显示层`() {
        // 5.9.34 我删掉视口常数、理由是"缩放是第 2 屏失败的原因"。
        // **那个理由是错的** —— 5.9.34/5.9.35 都没有缩放，第 2 屏照样失败。
        // 缩放从来不是原因；删掉它的代价只有画质（360px 视口里字 19px，发虚）。
        //
        // 5.9.36 请回来：视口 412×892dp，缩放只发生在显示那一层，
        // agent 的版式与坐标一个像素都不变。
        val proto = source("WebProtocol.kt")
        assertTrue(
            "视口常数必须回来（用户要的是清楚，不是老人机小字）：\n$proto",
            proto.contains("const val VIEWPORT_W_DP = 412") &&
                proto.contains("const val VIEWPORT_H_DP = 892")
        )
        val win = source("WebFloatWindow.kt")
        assertTrue(
            "缩放因子必须回来：\n$win",
            win.contains("fun scaleFactors(")
        )
        val svc = source("WebAutomationService.kt")
        assertTrue(
            "视口必须由 412dp × density 算出来：\n$svc",
            svc.contains("WebProtocol.VIEWPORT_W_DP * d")
        )
    }

    @Test
    fun `显示缩放必须挂在容器上不许挂WebView`() {
        // 这条 5.9.31 就立对了，至今有效：缩放挂 WebView 自己身上会让截图
        // 必须"临时归 1、画完 finally 还原"，每屏来回切两次视图变换搅乱合成器。
        //
        // 5.9.34 曾把整个缩放删掉（理由是错的），5.9.36 请回来 —— 但**位置不能变**。
        val c = code("WebFloatWindowHost.kt")
        assertTrue(
            "必须有独立的缩放容器：\n$c",
            c.contains("private class Scale" + "FrameLayout")
        )
        assertTrue(
            "缩放必须加在容器上：\n$c",
            c.contains("layer.scaleX = sx") && c.contains("layer.scaleY = sy")
        )
        assertTrue(
            "容器必须夹在窗口与 WebView 之间：\n$c",
            c.contains("host.addView(scaled,") && c.contains("layer.addView(wv)")
        )
        assertFalse(
            "WebView 自己的 scaleX/scaleY 是常量 1，任何人都不许去动：\n$c",
            Regex("""wv\.scale[XY]\s*=""").containsMatchIn(c)
        )
    }

// ---------- 5.9.31/5.9.34：结构不变义（正向设计，不是补丁） ----------

    @Test
    fun `I1_任何文件都不许改WebView的scale`() {
        // 5.9.27–5.9.30 把显示缩放挂在 **WebView 自己**身上，于是：
        // 1. 截图绕不开它 → 只能"临时归 1、画完 finally 还原"（事后补救）；
        // 2. 每屏来回切两次视图变换去搅合成器
        //    → 真机症状：整页截图前两屏正常，**第 3 屏起画面不跟随滚动**。
        // 5.9.34 曾把缩放整个删掉，5.9.36 请回来（理由见「视口是412dp缩放只发生在显示层」）。
        // 这条锁不变：WebView 的 scale 是常量 1，任何人都不许去动。
        for (f in listOf("WebAutomationService.kt", "WebFloatWindowHost.kt")) {
            val c = code(f)
            assertFalse(
                "$f 里不许改 WebView 的 scale —— 截图 1:1 结构上就成立，别去破坏它：\n$c",
                c.contains("wv.scaleX =") || c.contains("wv.scaleY =")
            )
        }
    }

    @Test
    fun `I1_视口是412dp不是一个跟着窗口走的数`() {
        // 视口必须锁死 412×892dp，靠缩放塞进小窗 —— 这样 agent 的版式与坐标
        // 一个像素都不变，且小窗里的字是 48px 而不是 19px。
        val c = code("WebAutomationService.kt")
        assertTrue(
            "视口尺寸必须由 VIEWPORT_W_DP/H_DP × density 算：\n$c",
            c.contains("WebProtocol.VIEWPORT_W_DP * d") &&
                c.contains("WebProtocol.VIEWPORT_H_DP * d")
        )
        // ⚠ 5.9.36 我在这里锁了"WebView 必须换软件渲染层"，
        // 理由是"硬件加速时 Chromium 只把已光栅化的区域交给 wv.draw(canvas)"。
        // **那条锁本身通过了，而它是错的** —— 又一次"扫源码的锁给坏设计盖章"。
        //
        // 真机结果：软件图层要自己分配一张视口大小的位图（1236×2676 的 ARGB_8888
        // = 13.2 MB），分配失败时系统直接杀服务 → teardown → 悬浮窗消失。
        // 症状是"小窗没了 + 第 2 屏照样空白"，一个问题都没解决，代价是两个都坏。
        //
        // 5.9.37 把整页截图功能整个删掉，`wv.draw(canvas)` 这条调用点**不存在了**，
        // 硬件加速照原样保留（小窗正常、不卡）。这条锁现在反过来锁：
        // **不许再有人为了截图去动渲染层类型。**
        assertFalse(
            "不许再为了截图把 WebView 换成软件渲染层 —— 真机上它会杀掉服务、悬浮窗直接消失：\n$c",
            codeOnly("WebAutomationService.kt").contains("LAYER_TYPE_SOFTWARE")
        )
        // ⚠ 只查 viewportSizePx 的函数体：attachFloatWindow 里**也要**算窗口尺寸
        // （那是小窗自己的宽高，不是视口），全文件搜会误伤。
        val vp = c.substringAfter("private fun viewportSizePx(")
            .substringBefore("private fun viewWidthPx(")
        assertTrue("没抓到 viewportSizePx 的函数体", vp.length > 80)
        assertFalse(
            "viewportSizePx 里不许拿窗口尺寸当视口 —— 那会让字缩到 19px：\n$vp",
            vp.contains("WebFloatWindow.windowSize(")
        )
    }

    /**
     * ⚠ **5.9.35 退役的锁，留着当教训**（5.9.37 整理）
     *
     * 原来那条 `I2_每屏必须等渲染跟上` 断言的是"源码里出现过
     * `observer.registerFrameCommitCallback(`"。它**通过了** —— 而那行代码被包在
     * 一个把主线程锁死的循环里，回调永远等不到。
     * 这条锁不是在保护我，是在**给一个坏设计盖章**；我拿它当证据说
     * "帧提交机制已就位"，实际是反的。
     *
     * 5.9.36 我又犯了一次同样的错（锁"必须换软件渲染层"，锁通过了，真机上服务被杀、
     * 悬浮窗消失，见 [I1_视口是412dp不是一个跟着窗口走的数]）。
     *
     * **教训：扫源码的锁只能验字样，验不了"它有没有用"。**
     * 所以下面 5.9.37 那组锁刻意避开"某行代码在不在"，改成锁**取舍**：
     * 删掉的东西不许复活、新加的东西必须真的接上。
     */

    // ---------- 5.9.37：删截图 + 换 extract + type 回车 ----------

    @Test
    fun `截图功能必须真的删干净`() {
        // 十条版本（5.9.27–5.9.36）全卡在"第 2 屏空白"，取像素的那一行是 `wv.draw(canvas)`。
        // 5.9.37 决定不让 agent 看截图了，于是这条路**整个拿掉** ——
        // 如果哪天它悄悄回来，agent 又会拿到一片空白，而且没人知道为什么。
        for (f in listOf(
            "WebAutomationService.kt", "WebProtocol.kt", "WebArtifacts.kt",
            "WebOpScripts.kt", "WebFloatWindow.kt", "WebFloatWindowHost.kt",
            "WebExtract.kt", "WebSelector.kt"
        )) {
            val c = codeOnly(f)
            assertFalse(
                "$f 里还有截图的残留：\n$c",
                Regex("""\bopShot\b|\bcaptureScreen\b|\bprobeScreen\b|\bawaitScreenStable\b""" +
                    """|\bwalkScreens\b|\bfinishShot\b|\bShotRunner\b|\bWebShotSampler\b""" +
                    """|\bShotWriter\b|\bshotFileName\b|\bhighestShotSeq\b|\boldestToDelete\b""" +
                    """|\bMAX_SHOTS\b|\bregisterFrameCommitCallback\b|\bwv\.draw\(""").containsMatchIn(c)
            )
        }
        assertFalse(
            "ShotRunner.kt 与 WebShotSampler.kt 必须整个删掉，不是掏空",
            fileExists("ShotRunner.kt") || fileExists("WebShotSampler.kt")
        )
    }

    @Test
    fun `help不许再教截图也不许漏掉extract`() {
        val h = WebProtocol.help(39080)
        assertFalse("help 不许再提 shot 指令：\n$h", h.contains("\"op\":\"shot\""))
        assertFalse("help 不许再提 shots 目录", h.contains("shots/"))
        assertFalse("help 不许再教一屏一张/拼接那套", h.contains("shot-0007"))
        // extract 的字段与两个上限必须写进去 —— agent 是照着 help 用的
        for (must in listOf(
            "\"op\":\"extract\"", "inputs", "buttons", "links_truncated", "links_total",
            "\"limit\"", "\"text_chars\"", "enter\":true"
        )) {
            assertTrue("help 漏了 $must：\n$h", h.contains(must))
        }
        // 默认值与封顶必须和 WebExtract 里的常量一致，不能各写一份
        assertTrue(
            "help 里的链接默认值必须等于 WebExtract.DEFAULT_LIMIT=${WebExtract.DEFAULT_LIMIT}",
            h.contains("默认 ${WebExtract.DEFAULT_LIMIT}")
        )
        assertTrue(
            "help 里的正文字数默认值必须等于 WebExtract.DEFAULT_TEXT_CHARS=${WebExtract.DEFAULT_TEXT_CHARS}",
            h.contains("默认 ${WebExtract.DEFAULT_TEXT_CHARS}")
        )
    }

    @Test
    fun `extract必须真的接在handle上且参数能夹`() {
        val c = code("WebAutomationService.kt")
        assertTrue(
            "handle 必须分发 extract：\n$c",
            c.contains("\"extract\" -> opExtract(")
        )
        assertTrue(
            "extract 必须走 evalInPage 拿页面脚本回值：\n$c",
            c.contains("WebExtract.pageJs(") && c.contains("WebExtract.parse(")
        )
        // 参数是从请求里读的、夹过的，不是写死的 —— 否则 help 里说的 limit 是假的
        assertTrue(
            "limit 必须从请求里读再夹：\n$c",
            c.contains("bodyIntOrNull(request, \"limit\"") && c.contains("WebExtract.clampLimit(")
        )
        assertTrue(
            "text_chars 必须从请求里读再夹：\n$c",
            c.contains("bodyIntOrNull(request, \"text_chars\"") && c.contains("WebExtract.clampTextChars(")
        )
    }

    @Test
    fun `extract的输入框和按钮不许设上限`() {
        // 用户明说怕"加了上限会漏掉搜索框"。搜索框在 inputs/buttons 里，
        // 而且总在页面顶部 —— 所以这两张表**一个都不许截**，
        // 只有 links 能给上限（并且要明说截了）。
        val js = WebExtract.pageJs(limit = 7, textChars = 11)
        val inputsAt = js.indexOf("querySelectorAll('input,textarea,select')")
        val buttonsAt = js.indexOf("querySelectorAll('button,input[type=submit]")
        assertTrue("没抓到输入框的扫描：$js", inputsAt > 0)
        assertTrue("没抓到按钮的扫描：$js", buttonsAt > 0)
        // 两段循环里都不许出现 L（链接上限）作为停止条件
        val inputLoop = js.substring(inputsAt, buttonsAt)
        assertFalse("输入框不许受 limit 限制：$inputLoop", inputLoop.contains("i<L"))
        val linkStart = buttonsAt
        val linkLoop = js.substring(linkStart, js.indexOf("r.title="))
        assertFalse("按钮不许受 limit 限制：$linkLoop", linkLoop.contains("i<L"))
        // 但链接**必须**受 limit 限制，而且要报总数与是否截断
        assertTrue("链接必须受 limit 限制：$js", linkLoop.contains("r.links.length<L"))
        assertTrue("链接总数必须一起报：$js", js.contains("r.links_total=tot"))
        assertTrue("截了必须明说：$js", js.contains("r.links_truncated=tot>L"))
    }

    @Test
    fun `type的回车必须真的派发按键且默认不按`() {
        val noEnter = WebOpScripts.type("#kw", "hi", true)
        assertFalse("默认不许按回车：$noEnter", noEnter.contains("KeyboardEvent"))
        val withEnter = WebOpScripts.type("#kw", "hi", true, enter = true)
        assertTrue("enter:true 必须派发 Enter 按键：$withEnter", withEnter.contains("KeyboardEvent"))
        assertTrue("keyCode 必须是 13：$withEnter", withEnter.contains("keyCode:13"))
        // 派发不了要退回提交表单 —— 网站常常只认 keypress，不认 requestSubmit
        assertTrue(
            "Enter 派发不了必须退回提交表单，不能什么都不做：$withEnter",
            withEnter.contains("requestSubmit") || withEnter.contains("form.submit()")
        )
        val c = code("WebAutomationService.kt")
        assertTrue(
            "opType 必须把 enter 读出来：\n$c",
            c.contains("bodyOptBoolean(request, \"enter\"")
        )
    }
}

