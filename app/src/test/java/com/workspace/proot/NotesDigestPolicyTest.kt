package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 搜索聚合展示的 source lock（5.12.5）。
 *
 * 锁住三个容易回退的点：
 *  1. 聚合可用性必须由**文字词 + 内容命中**决定，不能退化成 `query.isNotBlank()`；
 *  2. 聚合页必须通过结果回传让宿主调用 `openNote`，保证返回行为与"搜索结果点进笔记"一致；
 *  3. 搜索 FAB 必须真的在搜索模式下切换成语义不同的按钮，而不是两个都开搜索。
 */
@Suppress("MagicNumber")
class NotesDigestPolicyTest {

    private fun code(name: String): String {
        var d: File? = File("").absoluteFile
        var hops = 0
        while (d != null && hops < 6) {
            val candidate = File(d, "app/src/main/java/com/workspace/proot/$name")
            if (candidate.isFile) return candidate.readText()
            d = d.parentFile
            hops++
        }
        throw AssertionError("找不到 $name")
    }

    private fun resFile(path: String): File {
        var d: File? = File("").absoluteFile
        var hops = 0
        while (d != null && hops < 6) {
            val candidate = File(d, path)
            if (candidate.isFile) return candidate
            d = d.parentFile
            hops++
        }
        throw AssertionError("找不到 $path")
    }

    @Test
    fun `digest availability uses text terms and content match only`() {
        for (name in listOf("NotesController.kt", "NotesStandaloneActivity.kt")) {
            val c = code(name)
            assertTrue(
                "$name 必须把 textTerms 传给聚合可用性判定（纯标签不算）：\n$c",
                c.contains("parsed.textTerms.isNotEmpty() && store.hasContentMatch(notes, parsed.textTerms)"),
            )
        }
    }

    @Test
    fun `digest page returns the picked note to the host`() {
        val digest = code("NotesDigestActivity.kt")
        assertTrue("聚合页必须回传笔记名：\n$digest", digest.contains("EXTRA_PICK"))
        assertTrue("聚合页必须 setResult：\n$digest", digest.contains("setResult(RESULT_OK"))

        for (name in listOf("NotesController.kt", "NotesStandaloneActivity.kt")) {
            val c = code(name)
            assertTrue("$name 必须注册聚合结果回调：\n$c", c.contains("NotesDigestActivity.EXTRA_PICK"))
            assertTrue("$name 必须用 openNote 打开选中的笔记：\n$c", c.contains("openNote(name)"))
            assertTrue("$name 必须把 launcher 交给 overlay：\n$c", c.contains("digestLauncher"))
        }
    }

    @Test
    fun `search fab becomes a distinct digest action in search mode`() {
        val menu = code("NotesFabMenu.kt")
        assertTrue("FAB 必须有聚合回调：\n$menu", menu.contains("onDigest"))
        assertTrue("搜索模式下点击必须走聚合：\n$menu", menu.contains("if (digestMode)") && menu.contains("onDigest()"))
        assertTrue("必须能切换聚合模式：\n$menu", menu.contains("fun setDigestMode"))
        assertTrue("必须用同一套禁用色：\n$menu", menu.contains("NeuFab.disabledIconColor"))
        assertTrue("可用时必须用品牌色：\n$menu", menu.contains("theme.primaryVisible"))
        assertTrue("图标必须用前景 Drawable：\n$menu", menu.contains("search.foreground = digestIcon"))
        assertFalse(
            "禁止回退到复合图标（会居左）：\n$menu",
            menu.contains("setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_notes_digest"),
        )
        assertFalse(
            "禁用态不能用 alpha 假装灰色：\n$menu",
            menu.contains("DISABLED_ALPHA"),
        )

        val overlay = code("NotesSearchOverlay.kt")
        assertTrue("打开搜索必须进入聚合模式：\n$overlay", overlay.contains("setDigestMode(enabled = true"))
        assertTrue("关闭搜索必须退出聚合模式：\n$overlay", overlay.contains("setDigestMode(enabled = false"))
        assertTrue("必须提供可用性入口：\n$overlay", overlay.contains("fun setDigestAvailable"))
    }

    @Test
    fun `digest icon is drawn centered and sized independently`() {
        val icon = code("DigestIconDrawable.kt")
        assertTrue("必须用 mutate 让 tint 独立生效：\n$icon", icon.contains(".mutate()"))
        assertTrue("必须显式按 bounds 中心绘制：\n$icon", icon.contains("bounds.exactCenterX()"))
        assertTrue("必须显式按 bounds 中心绘制：\n$icon", icon.contains("bounds.exactCenterY()"))
        assertTrue("尺寸必须是常量便于调整：\n$icon", icon.contains("ICON_DP"))
        assertTrue("尺寸必须小于 FAB 一半：\n$icon", icon.contains("const val ICON_DP = 18"))
    }

    @Test
    fun `digest data layer stays android free and content only`() {
        val digest = code("NoteDigest.kt")
        assertFalse("NoteDigest 不能依赖 Android（要能 JVM 单测）：\n$digest", digest.contains("import android"))
        assertTrue("内容匹配必须排除笔记名：\n$digest", digest.contains("name = \"\""))
        assertTrue("内容匹配必须排除标签：\n$digest", digest.contains("tags = \"\""))
        assertTrue("纯标签查询必须返回空：\n$digest", digest.contains("if (cleaned.isEmpty()) return emptyList()"))
    }

    @Test
    fun `digest activity is declared in manifest`() {
        val manifest = File("app/src/main/AndroidManifest.xml").let { f ->
            var d: File? = File("").absoluteFile
            var hops = 0
            while (d != null && hops < 6) {
                val candidate = File(d, "app/src/main/AndroidManifest.xml")
                if (candidate.isFile) return@let candidate.readText()
                d = d.parentFile
                hops++
            }
            throw AssertionError("找不到 AndroidManifest.xml")
        }
        assertTrue("必须声明聚合页：\n$manifest", manifest.contains("NotesDigestActivity"))
    }

    @Test
    fun `digest icon is a three-row checklist with round caps`() {
        val icon = resFile("app/src/main/res/drawable/ic_notes_digest.xml").readText()
        assertEquals(
            "必须是 3 个圆点 + 3 条线（共 6 个 path）：",
            6,
            Regex("<path").findAll(icon).count(),
        )
        assertEquals(
            "三条线都必须用圆头（方头会像表格）：",
            3,
            Regex("strokeLineCap=\"round\"").findAll(icon).count(),
        )
        assertEquals(
            "线条必须用 stroke 描边，不能用填充矩形：",
            3,
            Regex("android:strokeWidth=\"3\"").findAll(icon).count(),
        )
        assertFalse("不能用方头：", icon.contains("strokeLineCap=\"butt\""))
        assertTrue("第一行线到 21.5：", icon.contains("M8.5,6 L21.5,6"))
        assertTrue("第二行线到 21.5：", icon.contains("M8.5,12 L21.5,12"))
        assertTrue("第三行必须更短：", icon.contains("M8.5,18 L17,18"))
    }

    @Test
    fun `digest strings exist in both locales`() {
        val zh = resFile("app/src/main/res/values/strings.xml").readText()
        val en = resFile("app/src/main/res/values-en/strings.xml").readText()
        for (key in listOf("notes_digest_action", "notes_digest_title", "notes_digest_todo", "notes_digest_body", "notes_digest_empty")) {
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key", en.contains("name=\"$key\""))
        }
    }
}
