package com.workspace.proot

/**
 * 笔记搜索查询：普通全文词 + 标签字段过滤。
 *
 * 标签过滤用字段语法 `tag:标签`（也接受全角冒号 `tag：标签` 和值前的 `#`），
 * 与普通词可混用：
 *
 * - `tag:工作` —— 只按标签过滤；
 * - `tag:工作 苹果` —— 标签必须是「工作」，正文/标题/待办再按「苹果」搜；
 * - `tag:工作 tag:重要` —— 两个标签都要有（AND）。
 *
 * 标签值由胶囊选择器写入，标签本身不含空白；手输时按空白分词。
 * 纯 Kotlin，无 Android 依赖，JVM 可直接测。
 */
data class NoteQuery(
    val textTerms: List<String>,
    val tagFilters: List<String>
) {

    val isEmpty: Boolean get() = textTerms.isEmpty() && tagFilters.isEmpty()

    /** 标签过滤是 AND：每个筛选标签都必须出现在笔记标签里。 */
    fun matchesTags(tags: List<String>): Boolean =
        tagFilters.all { filter -> tags.any { it.equals(filter, ignoreCase = true) } }

    companion object {

        /** 组合搜索框的规范查询串：标签字段在前，普通文字在后。 */
        fun compose(tags: List<String>, freeText: String): String {
            val tagPart = tags
                .map { normalizeTag(it) }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(" ") { "tag:$it" }
            val text = freeText.trim()
            return listOf(tagPart, text).filter { it.isNotEmpty() }.joinToString(" ")
        }

        /**
         * 解析查询串。带 `tag:` / `tag：` 前缀的词进入 [tagFilters]，
         * 其余词交给 [NoteMatcher.terms] 做与普通搜索完全一致的分词。
         */
        fun parse(raw: String): NoteQuery {
            val textTokens = ArrayList<String>()
            val tags = LinkedHashSet<String>()
            for (token in raw.trim().split(WHITESPACE)) {
                if (token.isEmpty()) continue
                val sep = token.indexOfFirst { it == ':' || it == '：' }
                val tag = if (sep > 0 && token.substring(0, sep).equals(TAG_KEY, ignoreCase = true)) {
                    normalizeTag(token.substring(sep + 1))
                } else {
                    ""
                }
                if (tag.isNotEmpty()) tags.add(tag) else textTokens.add(token)
            }
            return NoteQuery(
                textTerms = NoteMatcher.terms(textTokens.joinToString(" ")),
                tagFilters = tags.toList()
            )
        }

        /** 去掉值两侧空白、引号和前缀 `#`；标签存储本身不带 `#`。 */
        private fun normalizeTag(raw: String): String =
            raw.trim()
                .removeSurrounding("\"")
                .trim()
                .removePrefix("#")
                .trim()

        private const val TAG_KEY = "tag"
        private val WHITESPACE = Regex("\\s+")
    }
}
