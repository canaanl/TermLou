package com.workspace.proot

/**
 * 笔记搜索聚合展示的数据层（5.12.5）。
 *
 * 只做一件事：把“搜索命中的笔记”翻译成聚合页可直接渲染的条目列表。
 *
 * 规则（用户定义）：
 *  - **只有正文与待办命中算内容匹配**；笔记名、标签筛选都不算；
 *  - 待办命中 → 展示该条待办的**完整文本**；
 *  - 正文命中 → 展示命中处附近的一段片段；
 *  - 不展示笔记名（`note` 只用于点击跳转）。
 *
 * 纯 Kotlin，无 Android 依赖，JVM 可直接单测。
 */
@Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
object NoteDigest {
    /** 片段命中点前后各保留的字符数。 */
    private const val CONTEXT_CHARS = 40

    /** 单篇笔记正文最多产出的片段数，避免长文撑爆聚合页。 */
    private const val MAX_BODY_SNIPPETS = 3

    /** 正文为空时的兜底展示长度。 */
    private const val EMPTY_BODY_FALLBACK = 80

    enum class Kind { TODO, BODY }

    /**
     * 聚合页的一条内容。
     *
     * @param note 所属笔记名，仅用于点击跳转，不参与展示。
     */
    data class Item(
        val note: String,
        val kind: Kind,
        val text: String,
    )

    /** 一篇笔记的正文与待办；由调用方从 [NotesStore] 取好传入，保持本对象无 I/O。 */
    data class Source(
        val note: String,
        val body: String,
        val todos: List<String>,
    )

    /**
     * 构建聚合条目。
     *
     * @param sources 已经过标签筛选与搜索命中的笔记集合（调用方保证）。
     * @param terms 文字查询词；**为空时返回空列表**（纯标签查询不算内容匹配）。
     */
    fun build(sources: List<Source>, terms: List<String>): List<Item> {
        val cleaned = terms.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) return emptyList()
        val out = ArrayList<Item>()
        for (src in sources) {
            // 多词 AND：整篇（正文 + 待办合起来）必须命中全部词，否则不进聚合页。
            if (!matchesContent(src, cleaned)) continue
            out += todoItems(src, cleaned)
            out += bodyItems(src, cleaned)
        }
        return out
    }

    /** 是否至少有一条内容命中；决定聚合 FAB 的灰/亮。 */
    fun hasContentMatch(sources: List<Source>, terms: List<String>): Boolean {
        val cleaned = terms.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) return false
        return sources.any { matchesContent(it, cleaned) }
    }

    /**
     * 单篇笔记是否满足内容匹配：**每个词都要在正文或待办里命中**（AND）。
     *
     * 走 [NoteMatcher.termScore] 但明确把 name/tags 传空，
     * 从根上排除“只命中笔记名/标签”的情况。
     */
    fun matchesContent(source: Source, terms: List<String>): Boolean {
        if (terms.isEmpty()) return false
        val todos = source.todos.joinToString(" ")
        for (term in terms) {
            val score = NoteMatcher.termScore(
                term = term,
                name = "",
                body = source.body,
                tags = "",
                todos = todos,
            )
            if (score < 0) return false
        }
        return true
    }

    // ---------- 待办 ----------

    /** 命中的待办：整条完整文本输出，不做截断。 */
    private fun todoItems(src: Source, terms: List<String>): List<Item> =
        src.todos.filter { todo -> todo.isNotBlank() && contentHits(todo, "", terms) }
            .map { Item(src.note, Kind.TODO, it) }

    // ---------- 正文 ----------

    /**
     * 命中的正文片段。
     *
     * 优先围绕**第一个字面命中**取上下文；拼音/模糊命中在正文里没有字面词，
     * 此时退化为正文开头一段，保证“匹配上的条目一个不漏”。
     */
    private fun bodyItems(src: Source, terms: List<String>): List<Item> {
        val body = src.body
        if (body.isBlank()) return emptyList()
        // 正文自身必须命中全部词；只命中待办时不能凭空产出正文片段。
        if (!contentHits(body, "", terms)) return emptyList()
        val hits = literalHits(body, terms)
        if (hits.isEmpty()) {
            val head = body.trim().take(EMPTY_BODY_FALLBACK)
            return listOf(Item(src.note, Kind.BODY, head))
        }
        val snippets = ArrayList<Item>()
        var lastEnd = -1
        for (hit in hits) {
            // 同一段上下文里的命中合并，避免碎片化。
            if (snippets.isNotEmpty() && hit.first <= lastEnd + CONTEXT_CHARS) {
                lastEnd = maxOf(lastEnd, hit.last)
                continue
            }
            val start = (hit.first - CONTEXT_CHARS).coerceAtLeast(0)
            val end = (hit.last + 1 + CONTEXT_CHARS).coerceAtMost(body.length)
            snippets += Item(src.note, Kind.BODY, body.substring(start, end).trim())
            lastEnd = hit.last
            if (snippets.size >= MAX_BODY_SNIPPETS) break
        }
        return snippets
    }

    /** 正文里所有字面命中的区间（小写匹配，保持原文字符位置）。 */
    private fun literalHits(body: String, terms: List<String>): List<IntRange> {
        val lower = body.lowercase()
        val hits = ArrayList<IntRange>()
        for (term in terms) {
            var from = 0
            while (true) {
                val at = lower.indexOf(term, from)
                if (at < 0) break
                hits += at until (at + term.length)
                from = at + term.length
            }
        }
        return hits.sortedBy { it.first }
    }

    /**
     * 单个文本块是否命中全部查询词（AND）。
     *
     * 命中判定走 [NoteMatcher] 内核，因此字面、同音、全拼、首字母、模糊都算。
     */
    private fun contentHits(text: String, extra: String, terms: List<String>): Boolean {
        if (text.isBlank() && extra.isBlank()) return false
        for (term in terms) {
            val score = NoteMatcher.termScore(
                term = term,
                name = "",
                body = text,
                tags = "",
                todos = extra,
            )
            if (score < 0) return false
        }
        return true
    }
}
