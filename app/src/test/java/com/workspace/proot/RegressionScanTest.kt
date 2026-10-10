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
    fun `视口就是窗口不许有第二个数`() {
        // 以前有两个数：412dp 的视口 + 屏宽1/3 的窗口，中间靠缩放因子联系。
        // 两个数就意味着两者可能对不上 —— 而"对不上"正是整页截图拍不出来的土壤。
        // 全工程只该剩 WebFloatWindow.windowSize 一个数。
        val proto = source("WebProtocol.kt")
        assertFalse(
            "视口常数必须删掉：它和窗口是两个数，中间那层缩放正是坏图的来源：\n$proto",
            proto.contains("VIEWPORT_W_DP")
        )
        val win = source("WebFloatWindow.kt")
        assertFalse(
            "窗口几何里不许再有缩放因子：\n$win",
            win.contains("fun scaleFactors(")
        )
        val svc = source("WebAutomationService.kt")
        assertTrue(
            "视口必须就是窗口尺寸：\n$svc",
            svc.contains("WebFloatWindow.windowSize(dm.widthPixels, dm.heightPixels)")
        )
    }

    @Test
    fun `全工程不许再有任何显示缩放`() {
        // 这是 5.9.34 的根：安卓是照着**屏幕上实际大小**决定网页要画多少的，
        // 缩放一压它就只画那一小块，其余部分**从来没被画过**（不是画了看不见）。
        // 真机 5.9.31 第 3 屏、5.9.32 第 2 屏、5.9.33 第 2 屏，全是它。
        //
        // ⚠ 查的是**赋值语句**，不是那个词出现在哪：注释里可以写"以前缩放挂在…"
        val files = listOf("WebFloatWindowHost.kt", "WebAutomationService.kt", "WebFloatWindow.kt")
        for (f in files) {
            val c = code(f)
            for (m in Regex("""\.(scaleX|scaleY)\s*=[^=]""").findAll(c)) {
                throw AssertionError(
                    "$f 里出现了显示缩放赋值 `${m.value}` —— 那会让安卓只画屏幕上那一小块：" +
                        "\n${c.substring(maxOf(0, m.range.first - 200), m.range.last + 100)}"
                )
            }
            assertFalse(
                "$f 里不许再有缩放容器那个类：\n$c",
                c.contains("private class Scale" + "FrameLayout")
            )
        }
    }

    @Test
    fun `拍摄只有一条路`() {
        // 两条路的坏处不是"多写了一份代码"，而是**一条不行时另一条会静默顶替**，
        // 交出看起来正常、其实错的图。5.9.33 那条"取整页图再切块"切出来的是
        // 1 像素高的白条（capturePicture 给的不是整页，是当前那一屏）。
        val c = code("WebAutomationService.kt")
        val cap = c.substringAfter("private fun captureScreen(")
            .substringBefore("private fun probeScreen(")
        assertTrue("没抓到 captureScreen 的函数体", cap.length > 200)
        assertTrue(
            "只有把视图原尺寸画下来这一条：\n$cap",
            cap.contains("wv.draw(canvas)")
        )
        assertFalse(
            "不许再取整页图（它给的不是整页）：\n$cap",
            cap.contains("capturePicture")
        )
        assertFalse(
            "不许缩放或平移 —— 原尺寸画下来就是 1:1：\n$cap",
            cap.contains("canvas.scale(") || cap.contains("canvas.translate(")
        )
    }

    @Test
    fun `判据不许用截图`() {
        // ⚠ **5.9.34 最重要的一条。**
        //
        // 5.9.33 的 awaitScreenStable 是"拍一张 → 白吗 → 再拍一张 → 一样吗"：
        // 判"画面画完没有"用的就是"截图"这个动作。判据和被测对象是同一件事，
        // 于是截图这条路一瞎，循环只剩一个结局 —— 一直白、报一句 nothing rendered。
        // 真机三个版本都卡在同一句上，而它什么都没说清。
        //
        // 现在判空与判稳都必须走**探针**（缩到 48 见方的小图），
        // 与整屏拍摄是两个调用、两个函数。
        val c = code("WebAutomationService.kt")
        val await = c.substringAfter("private fun awaitScreenStable(")
            .substringBefore("private fun awaitFrameCommit(")
        assertTrue("没抓到 awaitScreenStable 的函数体", await.length > 400)
        assertTrue(
            "判据必须用探针：\n$await",
            await.contains("probeScreen(wv, vw, vh)")
        )
        assertTrue(
            "判空也必须用探针（不是整屏那张）：\n$await",
            Regex("""looksBlank\(probe\.width""").containsMatchIn(await)
        )
        assertTrue(
            "指纹必须取自探针：\n$await",
            Regex("""fingerprint\(probe\.width""").containsMatchIn(await)
        )
        assertTrue(
            "连续两次指纹相同才算画完：\n$await",
            await.contains("if (print == lastPrint)")
        )
        assertTrue(
            "探针与整屏拍摄必须是两个函数，不许合成一个：\n$await",
            c.contains("private fun probeScreen(")
        )
    }

    @Test
    fun `失败必须说清卡在第几步`() {
        // 真机 5.9.31/5.9.32/5.9.33 连续三版都报同一句 nothing rendered，
        // 害我只能靠反推才猜出"整页图其实只有一屏高"。三步失败必须分开说。
        val c = code("WebAutomationService.kt")
        val walk = c.substringAfter("private fun walkScreens(")
            .substringBefore("private class Screens(")
        assertTrue("没抓到 walkScreens 的函数体", walk.length > 600)
        for (step in listOf(1, 2, 3)) {
            assertTrue(
                "第 $step 步的失败必须报出来：\n$walk",
                walk.contains("ShotRunner.failure($step,")
            )
        }
        assertFalse(
            "不许再报那句糊在一起的话：\n$walk",
            walk.contains("nothing rendered")
        )
    }

    @Test
    fun `拍一张写一张不许把位图攒在内存里`() {
        // 旧做法：所有屏的位图攒在一个 List 里，最后一起写盘。
        // 视口 480×1056 时每屏 2 MB，一篇长文章十几屏就是三十几 MB —— 会撑爆。
        val c = code("WebAutomationService.kt")
        // ⚠ 必须用 source() 而不是 code()：code() 剥掉 KDoc，
        // Screens 的字段全是 KDoc 注释，剥完只剩 170 字符 —— 那会把判据卡在边界上。
        val screens = source("WebAutomationService.kt")
            .substringAfter("private class Screens(")
            .substringBefore("private class ScreenReady(")
        assertTrue("没抓到 Screens 的类体", screens.length > 400)
        assertFalse(
            "过程记录里不许再持有位图：\n$screens",
            screens.contains("List<Bitmap>")
        )
        assertTrue(
            "落盘必须走 ShotWriter（拍一张写一张）：\n$c",
            c.contains("WebArtifacts.ShotWriter(this)")
        )
        assertTrue(
            "写完必须立刻回收位图：\n$c",
            c.contains("writer.write(screenNo, shot)") && c.contains("shot.recycle()")
        )
    }

    @Test
    fun `不许预先算好段数`() {
        // 真机数据：两次 shot 的 page_height 是 6621 → 7443 —— 页面在拍摄途中还在长高。
        // 按开拍前那个数算好"拍几屏、每屏滚到哪"，那份计划**从一开始就是过期的**。
        val svc = source("WebAutomationService.kt")
        val walk = svc.substringAfter("private fun walkScreens(")
            .substringBefore("private class Screens(")
        assertTrue("没抓到 walkScreens 的函数体", walk.length > 600)
        assertFalse(
            "编排里不许再按 segments 循环 —— 屏数必须是走出来的：\n$walk",
            walk.contains("segments")
        )
        assertTrue(
            "必须用 while 走到实测的底：\n$walk",
            walk.contains("while (true)")
        )
        assertTrue(
            "到底了必须靠 nextScreenTop 返回 null：\n$walk",
            Regex("""nextScreenTop\([\s\S]{0,120}?\?: break""").containsMatchIn(walk)
        )
    }

    @Test
    fun `maxScroll必须每屏重读不许缓存`() {
        // 这是"过期计划"的直接解药：页面长了多少，下一屏就自动读到多少。
        val svc = source("WebAutomationService.kt")
        val walk = svc.substringAfter("private fun walkScreens(")
            .substringBefore("private class Screens(")
        val readAt = walk.indexOf("readMetrics(wv, cancelled)")
        assertTrue("必须每屏读一次页面度量", readAt >= 0)
        assertTrue(
            "读度量必须在循环体内（在 while 之后）：\n$walk",
            readAt > walk.indexOf("while (true)")
        )
        assertTrue(
            "下一屏落点必须用刚读到的 maxScroll：\n$walk",
            walk.contains("m.maxScrollCss * density")
        )
    }

    @Test
    fun `开拍前必须等页面静止且只用公开API`() {
        // ⚠ `WebChromeClient.onLoadingFinished` 与 `View.postVisualStateCallback`
        // 都是 `@hide` —— `javap` 查过 android-34 的公开 SDK 里**根本没有**。
        // 我按记忆写了，编译器当场打回来。公开可用的是 `WebViewClient.onPageFinished`
        // 与 document.readyState = complete。
        val svc = code("WebAutomationService.kt")
        assertFalse(
            "不许 override 不存在的 onLoadingFinished（编译就过不了）：\n$svc",
            svc.contains("override fun onLoadingFinished")
        )
        assertFalse(
            "不许用 @hide 的 postVisualStateCallback：\n$svc",
            svc.contains("postVisualStateCallback")
        )
        assertTrue(
            "必须用 onPageFinished 置位（公开 API）：\n$svc",
            svc.contains("pageLoadFinished = true")
        )
        assertTrue(
            "开拍前必须先等页面静止：\n$svc",
            svc.contains("awaitPageSettled(wv, cancelled)")
        )
        assertTrue(
            "静止判据要用 readyState（JS 侧同一件事）：\n$svc",
            svc.contains("ShotRunner.READY_STATE_JS")
        )
    }

    @Test
    fun `page_height必须报走完之后实测的值`() {
        // ⚠ 光查"出现过 measuredPageHeight"是**空跑**：绕开它、直接用开拍前那个数，
        // 那个字样仍然在函数体里，锁照样绿。所以要查**它真的被用上了**。
        val svc = code("WebAutomationService.kt")
        val fin = svc.substringAfter("private fun finishShot(")
            .substringBefore("截不出内容时的说法")
        assertTrue("没抓到 finishShot 的函数体", fin.length > 400)
        assertTrue(
            "必须先把实测值取出来：\n$fin",
            Regex("""val pageHeightPx = if \(walk\.measuredPageHeight > 0\)""").containsMatchIn(fin)
        )
        assertTrue(
            "page_height 必须真的用上那个实测值 —— 绕开它就等于还在报开拍前的旧数：\n$fin",
            Regex(""""page_height" to \(if \(isLong\) pageHeightPx""").containsMatchIn(fin)
        )
    }

    @Test
    fun `没渲染完的那几屏必须在note里说清`() {
        // 降级收下是允许的，但**必须说** —— 静默糊过去就是 5.9.9 的老错误。
        val fin = code("WebAutomationService.kt")
            .substringAfter("private fun finishShot(")
            .substringBefore("截不出内容时的说法")
        assertTrue("没抓到 finishShot 的函数体", fin.length > 400)
        assertTrue(
            "必须报出有几屏还在加载：\n$fin",
            fin.contains("were still loading")
        )
        assertTrue(
            "开拍时页面就没加载完也必须说：\n$fin",
            fin.contains("had not finished loading")
        )
    }

// ---------- 5.9.31/5.9.34：结构不变义（正向设计，不是补丁） ----------

    @Test
    fun `I1_任何文件都不许改WebView的scale`() {
        // 5.9.27–5.9.30 把显示缩放挂在 **WebView 自己**身上，于是：
        // 1. 截图绕不开它 → 只能"临时归 1、画完 finally 还原"（事后补救）；
        // 2. 每屏来回切两次视图变换去搅合成器
        //    → 真机症状：整页截图前两屏正常，**第 3 屏起画面不跟随滚动**。
        //
        // 5.9.34 干脆**把缩放整个删掉**：视口就是窗口。
        // 这条锁还在：WebView 的 scale 是常量 1，任何人都不许去动。
        for (f in listOf("WebAutomationService.kt", "WebFloatWindowHost.kt")) {
            val c = code(f)
            assertFalse(
                "$f 里不许改 WebView 的 scale —— 缩放会让安卓只画屏幕上那一小块：\n$c",
                c.contains("wv.scaleX =") || c.contains("wv.scaleY =")
            )
        }
    }

    @Test
    fun `I1_WebView直接挂在窗口根下尺寸就是窗口尺寸`() {
        val c = code("WebFloatWindowHost.kt")
        assertTrue(
            "WebView 必须直接 addView 到 host（中间不许再有别的层）：\n$c",
            c.contains("host.addView(wv)")
        )
        assertFalse(
            "attachWebView 里不许再设 pivot 或缩放：\n$c",
            c.contains("pivotX =") || c.contains("pivotY =")
        )
    }

    @Test
    fun `I2_每屏必须等渲染跟上不许只剩固定sleep`() {
        // 固定 700ms 是拍出来的数字，对"页面有多重"一无所知 —— 第 3 屏就追不上了。
        // 现在必须**等到画面真的稳住**为止：等帧提交 + 探针指纹校验，两者都要。
        val svc = code("WebAutomationService.kt")
        assertFalse(
            "固定等待必须删掉（SHOT_SEGMENT_WAIT_MS 已作废）：\n$svc",
            svc.contains("SHOT_SEGMENT_WAIT_MS")
        )
        assertTrue(
            "必须等帧提交：\n$svc",
            // ⚠ 必须带 `observer.` 前缀：`unregisterFrameCommitCallback` 里
            // 也含 `registerFrameCommitCallback` 这段子串，只查方法名会漏。
            svc.contains("observer.registerFrameCommitCallback(")
        )
        assertTrue(
            "必须有上限，不许死等：\n$svc",
            svc.contains("SHOT_FRAME_BUDGET_MS")
        )
        // 必须放在循环里 —— 等一次不算"等到"
        val body = svc.substringAfter("private fun awaitScreenStable(")
            .substringBefore("private fun awaitFrameCommit(")
        assertTrue("没抓到 awaitScreenStable 的函数体", body.length > 400)
        assertTrue(
            "必须在循环里轮询到画面稳为止：\n$body",
            body.contains("while (true)") && body.contains("fingerprint")
        )
    }

    @Test
    fun `I2_帧回调必须用同一个lambda注销`() {
        // 新建一个 lambda 去注销是注销不掉的 —— 每次截图往 ViewTreeObserver 上
        // 多挂一个回调，越用越多，最后回调列表被系统拒绝添加。
        val c = code("WebAutomationService.kt")
        assertTrue(
            "必须把 listener 存成变量再传：\n$c",
            c.contains("val listener: Runnable = Runnable {")
        )
        assertTrue(
            "注册与注销必须用同一个变量：\n$c",
            c.contains("observer.registerFrameCommitCallback(listener)") &&
                c.contains("observer.unregisterFrameCommitCallback(listener)")
        )
    }

    @Test
    fun `I3_失败时报屏号且保留已拍好的屏`() {
        // 5.9.9 的教训：半张图报 ok:true 比修不好更糟 —— 所以必须仍是 ok:false。
        // 但把真的拍到的那几张扔掉同样是浪费。
        val svc = code("WebAutomationService.kt")
        assertTrue(
            "部分成功必须走 errJsonWith（ok:false + 带上已有文件）：\n$svc",
            svc.contains("WebProtocol.errJsonWith(it, body)")
        )
        val proto = code("WebProtocol.kt")
        assertTrue(
            "errJsonWith 必须标 partial：\n$proto",
            proto.contains("o.put(\"partial\", true)")
        )
        assertTrue(
            "errJsonWith 必须仍然 ok:false —— 绝不能改成成功：\n$proto",
            proto.contains("o.put(\"ok\", false)")
        )
    }

// ---------- 5.9.30：整页截图一屏一张，不许再拼 ----------

    @Test
    fun `整页截图不许再拼成一张长图`() {
        // 拼接是我自己加的、用户没要求的。两样纯负担：
        // 一堆 src/dst 矩形换算（真机"首页缩小版+大片空白"就出在那儿），
        // 以及一张 1236×11440 的巨位图（56MB）。
        val svc = code("WebAutomationService.kt")
        assertFalse(
            "不许再往大位图上 drawBitmap 拼：\n$svc",
            svc.contains("canvas.drawBitmap(piece")
        )
        assertFalse(
            "不许再建整页那么大的位图：\n$svc",
            Regex("""Bitmap\.createBitmap\(outW, outH""").containsMatchIn(svc)
        )
        assertTrue(
            "每屏存一张，走 ShotWriter（5.9.34 拍一张写一张）：\n$svc",
            svc.contains("WebArtifacts.ShotWriter(this)")
        )
    }

    @Test
    fun `这屏和上一屏一样必须报错并报出是第几屏`() {
        // 真机 5.9.29：第二屏拍出来是第一屏的缩小版。两张都"有内容"，
        // 判空查不出来，于是一张首页的复制品被当成整页交出去了。
        // 现在靠**探针指纹**抓住，并且**报出第几屏**。
        val svc = source("WebAutomationService.kt")
        // 指纹比较在 awaitScreenStable 里（"等到画面稳定为止"那个循环）；
        // walkScreens 只负责滚动、调用、报屏号。所以判据分两处查。
        val loop = svc.substringAfter("private fun walkScreens(")
            .substringBefore("private class Screens(")
        assertTrue("没抓到 walkScreens 的函数体", loop.length > 600)
        val await = svc.substringAfter("private fun awaitScreenStable(")
            .substringBefore("private fun awaitFrameCommit(")
        assertTrue("没抓到 awaitScreenStable 的函数体", await.length > 400)
        assertTrue("必须比指纹：\n$await", await.contains("WebShotSampler.fingerprint(probe.width"))
        assertTrue(
            "walkScreens 必须调用 awaitScreenStable（等画面画完）：\n$loop",
            loop.contains("awaitScreenStable(wv, vw, vh)")
        )
        // 报错必须带**动态**屏号，不能写死数字 —— 写死的话 agent 不知道是哪一屏坏的
        val dollar = '$'
        val placeholder = "screen ${dollar}screenNo"
        assertTrue(
            "报错文案必须用动态屏号占位符：\n$loop",
            loop.contains(placeholder)
        )
        assertTrue(
            "屏号必须逐屏递增：\n$loop",
            loop.contains("screenNo++")
        )
    }

    @Test
    fun `shot返回必须带files和screens`() {
        // agent 靠 files 逐屏看整页；靠 screens 知道有几屏
        val body = code("WebAutomationService.kt")
            .substringAfter("private fun finishShot(")
            .substringBefore("截不出内容时的说法")
        assertTrue("没抓到 finishShot 的函数体", body.length > 400)
        assertTrue("必须返回 files：\n$body", body.contains("\"files\" to"))
        assertTrue("必须返回 screens：\n$body", body.contains("\"screens\" to"))
        // height 不再是整页高 —— 必须补 page_height，否则老 agent 会算错页面长度
        assertTrue("必须返回 page_height：\n$body", body.contains("\"page_height\" to"))
    }

    @Test
    fun `help里必须讲清整页是拆成多张`() {
        // agent 自发现全靠 help。字段变了不说，agent 就会当没变化。
        val h = WebProtocol.help(39080)
        assertTrue("help 必须提到 files：$h", h.contains("files/screens/file/width/height/"))
        assertTrue("help 必须说明按顺序看：$h", h.contains("从上到下"))
        assertTrue(
            "help 不许再说'自动截整页（长图）'—— 那已经不成立了：$h",
            !h.contains("自动截**整页**")
        )
    }

// ---------- 5.9.29：整页截图必须滚文档，不能滚视图 ----------

    @Test
    fun `整页截图不许用View的scrollTo`() {
        // 5.9.28 的根因：`wv.scrollTo(0, y)` 改的是**视图**的滚动偏移，
        // WebView 的**文档滚动**它根本不管 —— 文档压根没滚。
        // 于是第一段画的还是首屏（对的），后面每段都只是把同一个视图往上挪，
        // 露出来的是渲染内容之下的空白 → 真机上"只有第一屏，后面全白"。
        //
        // 佐证：`:probe` v8 扫过 11 种"踢一帧"的办法，**`View.scrollTo` 就在里面、
        // 22 次全灭** —— 这条路对 WebView 不通，当时已经验过了。
        val c = code("WebAutomationService.kt")
        assertFalse(
            "整页分段不许用 View.scrollTo（那是视图偏移，不是文档滚动）：\n$c",
            c.contains("wv.scrollTo(")
        )
        assertFalse(
            "也不许拿 wv.scrollY 当文档滚动位置：\n$c",
            Regex("""wv\.scrollY""").containsMatchIn(c)
        )
    }

    @Test
    fun `整页分段必须用JS滚文档并回读确认`() {
        // `window.scrollTo` 是个**请求**：页面可以 scroll-snap 改掉、可以 JS 拦掉、
        // 可以在平滑滚动动画里还没到位。只看"JS 有没有回值"不算数，
        // 必须**回读 window.pageYOffset** 才知道真到没到。
        val c = code("WebAutomationService.kt")
        assertTrue(
            "必须走 JS 滚动：\n$c",
            c.contains("ShotRunner.scrollToJs(")
        )
        assertTrue(
            "必须回读滚动位置：\n$c",
            c.contains("ShotRunner.SCROLL_Y_JS")
        )
        assertTrue(
            "必须拿回读值判到位（scrollLanded），不能只看 JS 回过值：\n$c",
            c.contains("ShotRunner.scrollLanded(")
        )
    }

    @Test
    fun `scrollDocumentTo里的每个return true都必须由scrollLanded把门`() {
        // 这条锁的是"回读校验"本身。
        // 5.9.29 第一版只锁了"文件里出现过 scrollLanded"——
        // 注入一行 `if (nowCss >= 0) return true`（回读到任意值就当成功）照样全绿，
        // 那是**空跑**：判据存在，但它不再把门。
        //
        // 精确判据：`scrollDocumentTo` 里**每一处 `return true` 所在行**
        // 都必须同时出现 `scrollLanded`。
        val c = code("WebAutomationService.kt")
        val body = c.substringAfter("private fun scrollDocumentTo(")
            .substringBefore("private fun readScrollCss(")
        assertTrue("没找到 scrollDocumentTo 的函数体", body.length > 300)

        val trueLines = body.lines().filter { it.contains("return true") }
        assertTrue(
            "scrollDocumentTo 里一处 return true 都没有 —— 判据写错了：\n$body",
            trueLines.isNotEmpty()
        )
        for (line in trueLines) {
            assertTrue(
                "这一行 return true 没有由 scrollLanded 把门 —— 页面滚不动也会被当成功：" +
                    "\n  ${line.trim()}",
                line.contains("scrollLanded")
            )
        }
    }

    @Test
    fun `每滚一段都必须等一帧而不是紧接着画`() {
        // `scrollTo` 只改值，内容是合成器**异步**画的。紧接着画拿到的还是上一段
        // —— 真机上就是一片底色。5.9.9 那次"下半截白"就是没等帧。
        val c = code("WebAutomationService.kt")
        assertTrue("必须等帧提交", c.contains("observer.registerFrameCommitCallback("))
        assertTrue(
            "等帧必须在截图循环里，且轮询到画面稳定为止",
            c.contains("private fun awaitScreenStable(") &&
                c.contains("private fun probeScreen(")
        )
    }

    @Test
    fun `某屏画不出来不许交半张图`() {
        // 5.9.9：那次半空白图是 ok:true + full_page:true 出去的 —— agent 会以为那就是整页。
        // 这比修不好更糟：修不好 agent 知道，能骗过去 agent 就信了。
        // 5.9.30 改成多屏之后更明显：**不能把已经拍好的那几屏照交**，那等于交一套残缺整页。
        val body = source("WebAutomationService.kt")
            .substringAfter("private fun walkScreens(")
            .substringBefore("private class Screens(")
        assertTrue("没找到 walkScreens 的函数体", body.length > 600)
        assertTrue(
            "某屏画不出来必须带屏号报错（不能悄悄跳过继续）：\n$body",
            body.contains("ShotRunner.failure(3, screenNo, yDevice)")
        )
        assertFalse(
            "空图那一屏不许再报那句糊在一起的话：\n$body",
            body.contains("nothing rendered (blank)")
        )
    }

    @Test
    fun `滚不到位不许交残缺的屏`() {
        // 页面劫持滚动、滚动中高度变了 —— 这时候拍到的屏是不该有的。
        // 5.9.30 改成多屏之后更明显：**不能把已经拍好的那几屏照交**，
        // 那等于交一套残缺整页，agent 会当成完整的看。
        val body = source("WebAutomationService.kt")
            .substringAfter("private fun walkScreens(")
            .substringBefore("private class Screens(")
        assertTrue("没找到 walkScreens 的函数体", body.length > 600)
        assertTrue(
            "滚不到位必须带屏号报错，不能接着往下拍：\n$body",
            body.contains("ShotRunner.failure(1, screenNo, yDevice)")
        )
        assertTrue(
            "文案要说清是页面不让滚（agent 才知道该换站还是该等）：\n$body",
            body.contains("ShotRunner.failure(") && code("ShotRunner.kt").contains("would not scroll")
        )
    }

}

