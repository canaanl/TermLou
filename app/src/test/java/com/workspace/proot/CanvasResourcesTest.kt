package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Canvas 资源与脚本帮助的锁：
 *  - 新增 Canvas 界面文案必须中英文同时存在（漏一个键在英文系统会显示中文/崩溃）；
 *  - termlou-ui 的中英文 --help 必须同时包含 Canvas 的入口和 patch 通道。
 */
class CanvasResourcesTest {

    private val zh = File("src/main/res/values/strings.xml")
    private val en = File("src/main/res/values-en/strings.xml")
    private val script = File("src/main/res/raw/termlou_ui.sh")

    private fun keys(file: File, prefix: String): Set<String> {
        val re = Regex("""<string name="([^"]+)"""")
        return re.findAll(file.readText())
            .map { it.groupValues[1] }
            .filter { it.startsWith(prefix) || it.contains("canvas") }
            .toSet()
    }

    @Test
    fun `canvas string keys exist in both locales`() {
        assertTrue("values/strings.xml missing", zh.isFile)
        assertTrue("values-en/strings.xml missing", en.isFile)
        val zhKeys = keys(zh, "canvas_")
        val enKeys = keys(en, "canvas_")
        assertTrue("no canvas strings found", zhKeys.isNotEmpty())
        assertEquals("canvas keys differ between zh and en", zhKeys, enKeys)
    }

    @Test
    fun `canvas designer strings are present`() {
        val required = setOf(
            "canvas_designer_title", "canvas_layers", "canvas_props", "canvas_timeline",
            "canvas_tool_progress", "canvas_tool_input", "canvas_tool_select",
            "canvas_tool_toggle", "canvas_tool_button", "canvas_tool_button_bar",
            "canvas_import_path", "canvas_add_anim", "canvas_prop_key",
            "canvas_prop_options", "canvas_button_primary", "canvas_json_import",
            "canvas_export_cmd", "canvas_done", "canvas_settings_width_fixed",
            "dm_ctrl_canvas", "dm_summary_canvas_fmt", "dm_section_canvas",
            "dm_edit_canvas", "dm_canvas_hint", "dm_canvas_summary_fmt"
        )
        val zhKeys = keys(zh, "canvas_")
        val enKeys = keys(en, "canvas_")
        for (k in required) {
            assertTrue("missing zh string $k", k in zhKeys)
            assertTrue("missing en string $k", k in enKeys)
        }
    }

    private fun helpBlocks(): List<String> {
        val re = Regex("""cat <<'EOF'\n(.*?)\nEOF""", RegexOption.DOT_MATCHES_ALL)
        return re.findAll(script.readText()).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `help blocks stay in sync`() {
        val blocks = helpBlocks()
        assertEquals("expected exactly two help blocks", 2, blocks.size)
        val en = blocks[0]
        val zh = blocks[1]
        val option = Regex("""--[a-z][a-z-]*""")
        assertEquals(
            "option sets differ between English and Chinese help",
            option.findAll(en).map { it.value }.toSet(),
            option.findAll(zh).map { it.value }.toSet()
        )
        assertEquals("help line counts differ", en.lines().size, zh.lines().size)
        fun examples(block: String, marker: String): List<String> {
            val lines = block.lines()
            val start = lines.indexOfFirst { it.trim() == marker }
            if (start < 0) return emptyList()
            return lines.drop(start + 1).filter { it.trimStart().startsWith("termlou-ui") }
        }
        assertEquals(
            "example counts differ",
            examples(en, "Examples:").size,
            examples(zh, "示例:").size
        )
    }

    @Test
    fun `help documents canvas in both languages`() {
        val text = script.readText()
        assertTrue("script missing", script.isFile)
        // 中英文帮助都必须有初始场景、增量 patch、进度条和无超时说明。
        for (token in listOf("--canvas-update", "--canvas-progress", "--canvas-scene", "--no-timeout", "--canvas-key")) {
            val count = Regex(Regex.escape(token)).findAll(text).count()
            assertTrue("$token appears $count time(s), expected at least 2", count >= 2)
        }
        assertTrue("Chinese help missing", text.contains("Canvas 选项"))
        assertTrue("English help missing", text.contains("Canvas options"))
        assertTrue("Chinese scene docs missing", text.contains("Canvas 场景 JSON"))
        assertTrue("English scene docs missing", text.contains("Canvas scene JSON"))
        assertTrue("progress limits missing", text.contains("线性、确定值"))
        assertTrue("progress limits missing in English", text.contains("linear and determinate only"))
    }
}
