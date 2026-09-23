package com.workspace.proot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * 万条笔记规模基准：10,000 条笔记一次入库（走生产物化路径），对典型查询计时。
 * 这是「查询期零 .txt 读盘」的验收锁——旧实现每次击键要逐条读 1 万个文件（秒级），
 * 新实现只允许走库索引。
 *
 * 语料按真实笔记库构造：200 词短语池（查询目标词各占 ~5% 命中，与真实检索命中率同量级），
 * 两档验收：
 *  - 典型查询（单个词/短语/多词/拼音/首字母/容错/无命中）单次 < 50ms；
 *  - 最坏路径（bigram 命中全库、精排全量否决）单独设档，证明没有数量级悬崖。
 * 阈值留 CI/JVM/安全软件抖动余量，实测典型值见测试输出。
 */
class NotesScaleTest {

    private val phrases = listOf(
        "苹果", "超市", "明天", "下雨", "买牛奶", "面包店", "工作汇报", "旅行计划",
        "健身打卡", "读书笔记", "会议纪要", "项目进度", "家人聚餐", "换季衣服", "充值缴费",
        "重量重新"
    )

    private fun installDict() {
        PinyinDict.install(
            mapOf(
                '苹' to listOf("ping"), '果' to listOf("guo"),
                '超' to listOf("chao"), '市' to listOf("shi"),
                '明' to listOf("ming"), '天' to listOf("tian"),
                '下' to listOf("xia"), '雨' to listOf("yu"),
                '买' to listOf("mai"), '牛' to listOf("niu"), '奶' to listOf("nai"),
                '面' to listOf("mian"), '包' to listOf("bao"), '店' to listOf("dian"),
                '工' to listOf("gong"), '作' to listOf("zuo"),
                '汇' to listOf("hui"), '报' to listOf("bao"),
                '旅' to listOf("lv"), '行' to listOf("xing"),
                '计' to listOf("ji"), '划' to listOf("hua"),
                '健' to listOf("jian"), '身' to listOf("shen"),
                '打' to listOf("da"), '卡' to listOf("ka"),
                '读' to listOf("du"), '书' to listOf("shu"), '笔' to listOf("bi"), '记' to listOf("ji"),
                '会' to listOf("hui"), '议' to listOf("yi"), '纪' to listOf("ji"), '要' to listOf("yao"),
                '项' to listOf("xiang"), '目' to listOf("mu"),
                '进' to listOf("jin"), '度' to listOf("du"),
                '家' to listOf("jia"), '人' to listOf("ren"),
                '聚' to listOf("ju"), '餐' to listOf("can"),
                '换' to listOf("huan"), '季' to listOf("ji"),
                '衣' to listOf("yi"), '服' to listOf("fu"),
                '充' to listOf("chong"), '值' to listOf("zhi"),
                '缴' to listOf("jiao"), '费' to listOf("fei"),
                '重' to listOf("zhong", "chong"), '量' to listOf("liang"),
                '新' to listOf("xin")
            )
        )
    }

    @Test
    fun `ten thousand notes typical search stays far under a second`() {
        installDict()
        val dir = Files.createTempDirectory("notes-scale").toFile()
        try {
            val filler = "的一是了我不人在他有这个上们来到时大地为子中你说生国年着就那和要她出也得里后" +
                "自以会家可下而过天去能对小多然于心学么之都好看起发当没成只如事把还用第样道想作"
            val rnd = Random(42)
            // 词池 = 16 个查询目标词 + 184 个填充切片词；每条笔记取 10 词，
            // 目标词单篇命中率 ≈ 1-(1-1/200)^10 ≈ 5%（真实笔记库量级）
            val pool = ArrayList<String>(200)
            pool.addAll(phrases)
            while (pool.size < 200) {
                val s = rnd.nextInt(filler.length - 4)
                pool.add(filler.substring(s, s + 2 + rnd.nextInt(3)))
            }
            val writeMs = measureTimeMillis {
                repeat(NOTES) { i ->
                    val body = buildString {
                        append("第").append(i).append("条笔记。")
                        repeat(10) {
                            append(pool[rnd.nextInt(pool.size)])
                            val fs = rnd.nextInt(filler.length - 8)
                            append(filler, fs, fs + 8)
                            append("，")
                        }
                        if (i % 7 == 0) append("hello world from note $i。")
                        append("唯一码 token$i 结束。")
                    }
                    File(dir, "笔记$i.${NotesStore.EXT}").writeText(body)
                }
            }
            val store = NotesStore(dir) { JdbcNotesDb(it.absolutePath) }
            val ingestMs = measureTimeMillis { store.reload() }
            assertTrue("入库应成功", store.listNotes().size == NOTES)

            // 典型查询：单字 / 2 字 / 3-4 字 / 拼音全拼 / 首字母 / 容错 / 多词 AND /
            // 多音字副读音 / 精确 token / 无命中——单次必须 < 50ms
            val typical = listOf(
                "苹果", "明", "买牛奶", "工作汇报", "pingguo", "pg",
                "去超市买牛奶", "苹果 超市", "chongxin", "token5000", "草莓火箭"
            )
            // 最坏路径：前缀「条笔记」让 bigram 召回命中全库 1 万条，
            // 精排全量过一遍（一条全量否决 / 一条全量通过）——单独设档，证明无数量级悬崖
            val worstCase = listOf("笔记本电脑", "笔记")
            // 计时前全局预热：热遍 JIT + 给落盘后的磁盘余波（安全软件扫描等）留出平息窗口，
            // 预算不放松（50 / 500），只保证测的是稳定态而不是余波窗口
            for (q in typical + worstCase) store.searchNotesFull(q)
            runBench(store, typical, budgetMs = 50, label = "典型")
            runBench(store, worstCase, budgetMs = 500, label = "最坏")
            store.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun runBench(store: NotesStore, queries: List<String>, budgetMs: Long, label: String) {
        for (q in queries) {
            repeat(2) { store.searchNotesFull(q) } // 逐查询预热
            var best = Long.MAX_VALUE
            repeat(7) {
                best = minOf(best, measureTimeMillis { store.searchNotesFull(q) })
            }
            println("[$label] search [$q]: best=${best}ms / budget=${budgetMs}ms")
            assertTrue("查询 [$q] 耗时 ${best}ms 超${label}预算 ${budgetMs}ms", best < budgetMs)
        }
    }

    private companion object {
        const val NOTES = 10_000
    }
}
