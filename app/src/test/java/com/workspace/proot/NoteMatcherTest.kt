package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** NoteMatcher 分层打分的行为锁定：字面 → 同音容错 → 拼音 → 首字母，多词 AND / 标签 OR。 */
class NoteMatcherTest {

    @Before
    fun installDict() {
        PinyinDict.install(
            mapOf(
                '苹' to listOf("ping"), '果' to listOf("guo"), '菓' to listOf("guo"),
                '香' to listOf("xiang"), '蕉' to listOf("jiao"),
                '相' to listOf("xiang"), '交' to listOf("jiao"),
                '超' to listOf("chao"), '市' to listOf("shi"), '屎' to listOf("shi"),
                '牛' to listOf("niu"), '奶' to listOf("nai"),
                '重' to listOf("zhong", "chong"), '量' to listOf("liang"),
                '冲' to listOf("chong"), '凉' to listOf("liang"),
                '工' to listOf("gong"), '作' to listOf("zuo"),
                '梨' to listOf("li"), '狸' to listOf("li"), '花' to listOf("hua"),
                '明' to listOf("ming"), '天' to listOf("tian"), '去' to listOf("qu"),
                '买' to listOf("mai"), '水' to listOf("shui"), '和' to listOf("he"),
                '面' to listOf("mian"), '包' to listOf("bao"), '店' to listOf("dian")
            )
        )
    }

    @Test
    fun `terms splits lowercases and dedupes`() {
        assertEquals(listOf("foo", "bar"), NoteMatcher.terms("  Foo   BAR "))
        assertTrue(NoteMatcher.terms("   ").isEmpty())
        assertEquals(listOf("pg"), NoteMatcher.terms("PG pg"))
    }

    @Test
    fun `literal name beats body then miss`() {
        assertEquals(NoteMatcher.LAYER_NAME, NoteMatcher.termScore("苹果", "买苹果", ""))
        assertEquals(NoteMatcher.LAYER_BODY, NoteMatcher.termScore("苹果", "清单", "吃苹果"))
        assertEquals(NoteMatcher.MISS, NoteMatcher.termScore("苹果", "香蕉", "牛奶"))
    }

    @Test
    fun `homophone typo hits fuzzy layer`() {
        // 蕉→果：同音错别字，1 字差
        assertEquals(NoteMatcher.LAYER_FUZZY, NoteMatcher.termScore("苹菓", "苹果", ""))
        // 屎→市：同音错别字，正文命中
        assertEquals(NoteMatcher.LAYER_FUZZY, NoteMatcher.termScore("超屎", "购物清单", "去超市"))
    }

    @Test
    fun `random same-length chars do not leak`() {
        // 搜「苹果」不能因为正文有「水果」就乱蹦出来（差异字非同音 → 拒绝）
        assertEquals(NoteMatcher.MISS, NoteMatcher.termScore("苹果", "水果", ""))
        assertEquals(NoteMatcher.MISS, NoteMatcher.termScore("苹果", "清单", "吃水果和果然"))
    }

    @Test
    fun `short fragment alone cannot satisfy longer term`() {
        // 正文只剩一个「苹」的孤立字也构不成两字命中（等长窗口约束）
        assertEquals(NoteMatcher.MISS, NoteMatcher.termScore("苹菓", "别处", "只有苹"))
    }

    @Test
    fun `long term tolerates missing char`() {
        // 6 字词走编辑距离：正文少一个「买」仍命中
        assertEquals(
            NoteMatcher.LAYER_FUZZY,
            NoteMatcher.termScore("去超市买牛奶", "", "去超市牛奶")
        )
    }

    @Test
    fun `full pinyin matches chinese text`() {
        assertEquals(NoteMatcher.LAYER_PINYIN, NoteMatcher.termScore("pingguo", "苹果", ""))
        assertEquals(NoteMatcher.LAYER_PINYIN, NoteMatcher.termScore("xiangjiao", "香蕉", ""))
    }

    @Test
    fun `homophone sentence matches all-different chars`() {
        // 香蕉 vs 相交：两字全不同但全同音 → 拼音层（等长 1 字差规则拦不住第二个差异）
        assertEquals(NoteMatcher.LAYER_PINYIN, NoteMatcher.termScore("香蕉", "相交", ""))
        // 冲凉 vs 重量：同上，且重是多音字（chong 命中）
        assertEquals(NoteMatcher.LAYER_PINYIN, NoteMatcher.termScore("冲凉", "重量", ""))
    }

    @Test
    fun `polyphone matches both readings`() {
        assertEquals(NoteMatcher.LAYER_PINYIN, NoteMatcher.termScore("zhongliang", "重量", ""))
        assertEquals(NoteMatcher.LAYER_PINYIN, NoteMatcher.termScore("chongliang", "重量", ""))
    }

    @Test
    fun `initials need at least two letters`() {
        assertEquals(NoteMatcher.LAYER_INITIALS, NoteMatcher.termScore("pg", "苹果", ""))
        assertEquals(NoteMatcher.MISS, NoteMatcher.termScore("p", "苹果", ""))
    }

    @Test
    fun `note score is AND across terms`() {
        val body = "明天去超市买牛奶和面包"
        val terms = NoteMatcher.terms("超市 牛奶")
        assertTrue(NoteMatcher.noteScore("清单", body, terms) >= 0)
        // 「面包店」在正文里只有「面包」，三字词不构成命中 → 整条否决
        assertEquals(
            NoteMatcher.MISS,
            NoteMatcher.noteScore("清单", body, NoteMatcher.terms("超市 面包店"))
        )
        // 空词表视作全命中
        assertTrue(NoteMatcher.noteScore("任意", "", emptyList()) >= 0)
    }

    @Test
    fun `tags use any-term semantics`() {
        val tags = listOf("工作", "生活", "重要")
        val terms = NoteMatcher.terms("工 重")
        val hit = tags.filter { name -> terms.any { NoteMatcher.termScore(it, name, "") >= 0 } }
        assertEquals(listOf("工作", "重要"), hit)
    }

    @Test
    fun `pinyin layer degrades gracefully without dictionary`() {
        PinyinDict.install(emptyMap())
        assertEquals(NoteMatcher.MISS, NoteMatcher.termScore("pingguo", "苹果", ""))
        // 字面层不受影响
        assertEquals(NoteMatcher.LAYER_NAME, NoteMatcher.termScore("苹果", "苹果", ""))
    }

    @Test
    fun `ranking orders name over body over fuzzy`() {
        assertEquals(
            NoteMatcher.LAYER_NAME,
            NoteMatcher.noteScore("苹果派", "", NoteMatcher.terms("苹果"))
        )
        assertEquals(
            NoteMatcher.LAYER_BODY,
            NoteMatcher.noteScore("清单", "买苹果", NoteMatcher.terms("苹果"))
        )
        // 模糊命中（搜「苹菓」命中「苹果派」）排在正文命中之后
        assertTrue(
            NoteMatcher.noteScore("苹果派", "", NoteMatcher.terms("苹菓")) >
                NoteMatcher.noteScore("清单", "买苹果", NoteMatcher.terms("苹果"))
        )
    }

    @Test
    fun `dict parsing roundtrip keeps multi readings`() {
        PinyinDict.uninstall()
        java.io.ByteArrayInputStream(
            "重 zhong chong\n# comment\n果 guo\n".toByteArray(Charsets.UTF_8)
        ).use { s -> PinyinDict.ensureLoaded { s } }
        val readings = PinyinDict.readings
        assertEquals(listOf("zhong", "chong"), readings['重']?.toList())
        assertEquals(listOf("guo"), readings['果']?.toList())
    }
}
