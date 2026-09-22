package com.workspace.proot

import java.io.InputStream
import java.util.Locale

/**
 * 拼音表：assets/pinyin.txt 的内存镜像，行格式「字 拼音1 拼音2 …」（无声调小写）。
 * 数据源 Unicode Unihan（kMandarin / kHanyuPinyin / kHanyuPinlu 求并集，多音字全收）。
 * 纯 JVM：Android 侧喂 asset 流，测试侧直接 install 小表。
 * 未就绪时拼音层自动跳过，字面/容错层不受影响。
 */
object PinyinDict {

    const val ASSET = "pinyin.txt"

    @Volatile
    private var table: Map<Char, Array<String>> = emptyMap()

    @Volatile
    var loaded: Boolean = false
        private set

    @Volatile
    private var started = false

    @Volatile
    private var loader: (() -> InputStream?)? = null

    val readings: Map<Char, Array<String>> get() = table

    /** 替换整表（测试注入 / 资源加载完成）。 */
    @Synchronized
    fun install(map: Map<Char, List<String>>) {
        table = map.mapValues { it.value.toTypedArray() }
        loaded = true
    }

    /** 测试专用：卸载整表，让 ensureLoaded 可以重新解析。 */
    @Synchronized
    fun uninstall() {
        table = emptyMap()
        loaded = false
        started = false
    }

    /** 后台线程加载一次；失败复位 started，下次进页面重试。加载器顺带留档，供写路径同步物化用。 */
    fun loadAsync(open: () -> InputStream?) {
        loader = open
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        Thread {
            ensureLoaded(open)
            if (!loaded) synchronized(this) { started = false }
        }.apply {
            isDaemon = true
            name = "pinyin-dict"
            start()
        }
    }

    /** 同步确保就绪（写路径物化拼音前调用）：已注册加载器才解析，未注册/已就绪则立即返回。 */
    fun ensureLoaded() {
        loader?.let { ensureLoaded(it) }
    }

    /** 解析 pinyin.txt 并安装；流打不开或解析为空则静默降级（拼音层不可用）。 */
    @Synchronized
    fun ensureLoaded(open: () -> InputStream?) {
        if (loaded) return
        val input = runCatching { open() }.getOrNull() ?: return
        runCatching {
            val map = HashMap<Char, MutableList<String>>(40_000)
            input.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.forEachLine { line ->
                    if (line.isEmpty() || line[0] == '#') return@forEachLine
                    val sp = line.indexOf(' ')
                    if (sp <= 0) return@forEachLine
                    val list = map.getOrPut(line[0]) { ArrayList(2) }
                    for (tok in line.substring(sp + 1).split(' ')) {
                        if (tok.isNotEmpty() && !list.contains(tok)) list.add(tok)
                    }
                }
            }
            if (map.isNotEmpty()) install(map)
        }
    }

    // ---------- 写路径物化（存库时把拼音/首字母算好，查询期零计算零读盘） ----------

    /** 主读音拼音串：多音字取第一个读音，非汉字原样（小写）。未就绪返回空串。 */
    fun primaryPy(text: String): String = buildPy(text, secondary = false)

    /** 副读音拼音串：多音字取第二个读音（无则同主读音）。全文无多音字时留空——与主串同义，省空间。 */
    fun secondaryPy(text: String): String {
        if (!loaded) return ""
        val map = table
        if (text.none { (map[it]?.size ?: 0) > 1 }) return ""
        return buildPy(text, secondary = true)
    }

    /** 拼音首字母串（只取汉字，跳过其它字符）——与首字母层打分同源，保证召回一致。 */
    fun initials(text: String): String {
        if (!loaded) return ""
        val map = table
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch.code < 0x2E80) continue
            val r = map[ch] ?: continue
            if (r.isNotEmpty()) sb.append(r[0][0])
        }
        return sb.toString()
    }

    private fun buildPy(text: String, secondary: Boolean): String {
        if (!loaded) return ""
        val map = table
        val sb = StringBuilder(text.length + 8)
        for (ch in text) {
            val rs = map[ch]
            if (rs != null && rs.isNotEmpty()) {
                sb.append(if (secondary && rs.size > 1) rs[1] else rs[0])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString().lowercase(Locale.ROOT)
    }
}

/**
 * 笔记 / 待办 / 标签三处搜索共用的模糊匹配内核。纯 Kotlin 无 Android 依赖，JVM 直接可测。
 *
 * 分词：trim + 小写(Locale.ROOT) + 按空白切（多词）。
 * 单词分层（数字越小越优，-1 不命中）：
 *  0 标题子串 → 1 正文子串 → 2 容错（错别字）→ 3 拼音（全拼/同音字）→ 4 拼音首字母。
 * 容错规则：词含汉字才启用；≤5 字走等长窗口、只容替换（「苹菓→苹果」命中，
 * 但正文只剩一个「苹」不会乱命中）；6~16 字走滑窗编辑距离 ≤2（容多字/少字）。
 * 拼音层依赖 [PinyinDict]，未就绪时跳过。正文扫描封顶 [MAX_BODY_CHARS]，单次开销可控。
 */
object NoteMatcher {

    const val MISS = -1
    const val LAYER_NAME = 0
    const val LAYER_BODY = 1
    const val LAYER_FUZZY = 2
    const val LAYER_PINYIN = 3
    const val LAYER_INITIALS = 4

    /** 正文参与容错/拼音扫描的上限（字符数），避免长文拖慢击键。 */
    const val MAX_BODY_CHARS = 4000

    /** 搜索击键防抖：笔记列表 / 独立笔记页共用节奏。 */
    const val SEARCH_DEBOUNCE_MS = 200L

    /** 容错层启用的词长窗口（召回归约也用同一窗口）。 */
    const val MIN_FUZZY_TERM = 2
    const val MAX_FUZZY_TERM = 16

    /** 汉字判定（与容错/拼音层同口径）。 */
    fun isHan(c: Char): Boolean = c.code >= 0x2E80

    /** 分词：去首尾空白、小写、按空白切、去重保序。空串/全空白 → 空列表（调用方视为不过滤）。 */
    fun terms(raw: String): List<String> =
        raw.trim().lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * 单词对笔记全字段打分：返回最优层，不命中返回 [MISS]。
     * 字段分两档——**标题层（0）＝标题＋标签**，**正文层（1）＝正文＋所属待办文本**；
     * 容错（2）/拼音（3）/首字母（4）对四个字段一律平等启用。
     * 标签页复用本函数做 OR（tags/todos 不传即可）。
     */
    fun termScore(
        term: String,
        name: String,
        body: String,
        tags: String = "",
        todos: String = ""
    ): Int {
        if (term.isEmpty()) return MISS
        var best = MISS
        best = minLayer(best, scoreField(term, name, LAYER_NAME))
        best = minLayer(best, scoreField(term, tags, LAYER_NAME))
        best = minLayer(best, scoreField(term, body, LAYER_BODY))
        best = minLayer(best, scoreField(term, todos, LAYER_BODY))
        return best
    }

    /**
     * 笔记/待办打分：**所有词都要命中**（AND），跨字段互补——词1 出现在标题、
     * 词2 出现在标签/待办也算命中；取各词中最差的层，任一词不命中 → [MISS]。
     * 空词表返回 0（视作全命中，调用方一般自行跳过过滤）。
     */
    fun noteScore(
        name: String,
        body: String,
        terms: List<String>,
        tags: String = "",
        todos: String = ""
    ): Int {
        if (terms.isEmpty()) return LAYER_NAME
        var worst = LAYER_NAME
        for (t in terms) {
            val s = termScore(t, name, body, tags, todos)
            if (s < 0) return MISS
            if (s > worst) worst = s
        }
        return worst
    }

    /** 取两层中更优的（更小）；-1 表示不命中，忽略。 */
    private fun minLayer(cur: Int, v: Int): Int = when {
        v < 0 -> cur
        cur < 0 -> v
        v < cur -> v
        else -> cur
    }

    /** 单字段打分：字面子串返回 base 档位，否则 容错2 / 拼音3 / 首字母4，不中 MISS。 */
    private fun scoreField(term: String, field: String, base: Int): Int {
        if (field.isEmpty()) return MISS
        val text = field.lowercase(Locale.ROOT).take(MAX_BODY_CHARS)
        if (text.contains(term)) return base
        if (term.any { isHan(it) } && term.length in MIN_FUZZY_TERM..MAX_FUZZY_TERM && fuzzyContains(text, term)) {
            return LAYER_FUZZY
        }
        if (PinyinDict.loaded) {
            if (pinyinMatches(term, text)) return LAYER_PINYIN
            if (term.length >= 2 && term.all { it in 'a'..'z' } && initialsOf(text).contains(term)) {
                return LAYER_INITIALS
            }
        }
        return MISS
    }

    // ---------- 容错层 ----------

    private fun fuzzyContains(candidate: String, term: String): Boolean {
        if (term.isEmpty() || candidate.isEmpty()) return false
        return if (term.length <= 5) {
            // 等长窗口需要至少同样长的候选；短 DP 不需要
            candidate.length >= term.length && fuzzySubs(candidate, term)
        } else {
            fuzzyEdits(candidate, term)
        }
    }

    /** 2~5 字词：等长窗口、最多 1 处差异，且差异处必须**同音**（拼音表就绪时）。
     *  这是防噪声的关键——「苹菓→苹果」「超屎→超市」这类同音错别字命中，
     *  而搜「苹果」不会因为正文里有个「水果/果然」就乱蹦出来。表未就绪时退化为纯 1 字差。 */
    private fun fuzzySubs(candidate: String, term: String): Boolean {
        val n = term.length
        for (i in 0..candidate.length - n) {
            var bad = 0
            var ok = true
            for (j in 0 until n) {
                if (candidate[i + j] == term[j]) continue
                if (++bad > 1) {
                    ok = false
                    break
                }
                if (PinyinDict.loaded && !homophone(term[j], candidate[i + j])) {
                    ok = false
                    break
                }
            }
            if (ok) return true
        }
        return false
    }

    /** 两字读音集合有交集（多音字取全集；任一字查不到读音 → 不算同音，宁缺毋滥）。 */
    private fun homophone(a: Char, b: Char): Boolean {
        val ra = PinyinDict.readings[a] ?: return false
        val rb = PinyinDict.readings[b] ?: return false
        for (x in ra) for (y in rb) if (x == y) return true
        return false
    }

    /** 6~16 字词：任意子串编辑距离 ≤2（容多字/少字/错字）。
     *  f(i,0)=0 任意起点，答案取 min_i f(i,n) 任意终点。 */
    private fun fuzzyEdits(candidate: String, term: String): Boolean {
        val allowed = 2
        val m = candidate.length
        val n = term.length
        var prev = IntArray(n + 1) { it } // f(0,j)=j
        var cur = IntArray(n + 1)
        var bestEnd = Int.MAX_VALUE
        for (i in 1..m) {
            cur[0] = 0 // 任意起点免费对齐
            val cc = candidate[i - 1]
            for (j in 1..n) {
                val cost = if (term[j - 1] == cc) 0 else 1
                var v = prev[j] + 1
                val d = cur[j - 1] + 1
                if (d < v) v = d
                val dg = prev[j - 1] + cost
                if (dg < v) v = dg
                cur[j] = v
            }
            if (cur[n] < bestEnd) bestEnd = cur[n]
            if (bestEnd <= allowed) return true
            val t = prev
            prev = cur
            cur = t
        }
        return bestEnd <= allowed
    }

    // ---------- 拼音层 ----------

    /** 词含汉字 → 同音字对齐（图形对不上、读音相同即中）；纯拉丁 → 全拼 DP。 */
    private fun pinyinMatches(term: String, text: String): Boolean {
        if (text.isEmpty()) return false
        return if (term.any { isHan(it) }) homophoneContains(text, term) else latinPinyinContains(text, term)
    }

    /** 逐字对齐：查询每字与正文每字读音集合有交集即算同字（多音字取全集）。 */
    private fun homophoneContains(text: String, term: String): Boolean {
        val n = term.length
        if (text.length < n) return false
        val want = ArrayList<Set<String>>(n)
        for (ch in term) {
            val r = PinyinDict.readings[ch] ?: return false // 有字查不到读音就放弃该层
            want.add(r.toHashSet())
        }
        for (i in 0..text.length - n) {
            var ok = true
            for (k in 0 until n) {
                val cand = PinyinDict.readings[text[i + k]]
                if (cand == null || cand.none { want[k].contains(it) }) {
                    ok = false
                    break
                }
            }
            if (ok) return true
        }
        return false
    }

    /** 拉丁查询在正文拼音流上做「按音节贪心消费」：支持跨字边界（西安/xian 等歧义都能中）。
     *  状态 = 已消费前缀长度 0..len，用两张布尔数组轮转替代逐字新建 HashSet——
     *  状态机与原实现逐位等价，万条候选精排时省掉每字一次的集合分配。 */
    private fun latinPinyinContains(text: String, term: String): Boolean {
        val n = term.length
        var cur = BooleanArray(n + 1)
        var next = BooleanArray(n + 1)
        cur[0] = true // qi：term 已消费长度；0 = 从下一个字起头
        for (ch in text) {
            next.fill(false)
            val rs = PinyinDict.readings[ch]
            if (rs != null) {
                for (qi in 0..n) {
                    if (!cur[qi]) continue
                    for (r in rs) {
                        if (term.startsWith(r, qi)) {
                            val nq = qi + r.length
                            if (nq == n) return true
                            if (nq < n) next[nq] = true
                        }
                    }
                }
            }
            next[0] = true // 任意位置可重新起头
            val tmp = cur
            cur = next
            next = tmp
        }
        return false
    }

    /** 拼音首字母串（只取汉字，跳过其它字符）——与入库物化同源，打分与召回永远一致。 */
    private fun initialsOf(text: String): String = PinyinDict.initials(text)
}
