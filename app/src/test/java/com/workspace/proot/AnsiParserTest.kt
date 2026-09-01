package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiParserTest {

    @Test
    fun plainTextPassThrough() {
        val r = AnsiParser.parse("hello world")
        assertEquals("hello world", r.clean)
        assertEquals(0, r.spans.size)
    }

    @Test
    fun sgrColorParsed() {
        val r = AnsiParser.parse("\u001b[31mred\u001b[0mrest")
        assertEquals("redrest", r.clean)
        val red = 0xFFCD0000.toInt()
        assertTrue(r.spans.any { it.fg == red && it.start == 0 && it.end == 3 })
        // rest 无色，不产生 span
        assertTrue(r.spans.none { it.start == 3 && it.end == 7 })
    }

    @Test
    fun boldAndUnderlineFlags() {
        val r = AnsiParser.parse("\u001b[1;4mbold\u001b[0m")
        assertEquals("bold", r.clean)
        val span = r.spans.first()
        assertTrue(span.bold)
        assertTrue(span.underline)
    }

    @Test
    fun nonSgrCsiStripped() {
        val r = AnsiParser.parse("a\u001b[2Jb\u001b[Hc")
        assertEquals("abc", r.clean)
    }

    @Test
    fun multiSgrInOneSequence() {
        val r = AnsiParser.parse("\u001b[42m\u001b[31mX")
        assertEquals("X", r.clean)
        val bg = 0xFF00CD00.toInt()
        val fg = 0xFFCD0000.toInt()
        val span = r.spans.first()
        assertEquals(fg, span.fg)
        assertEquals(bg, span.bg)
    }

    @Test
    fun brightColors() {
        val r = AnsiParser.parse("\u001b[92mX")
        val brightGreen = 0xFF00FF00.toInt()
        assertEquals(brightGreen, r.spans.first().fg)
    }

    @Test
    fun emptyInput() {
        val r = AnsiParser.parse("")
        assertEquals("", r.clean)
        assertTrue(r.spans.isEmpty())
    }

    @Test
    fun inverseFlag() {
        val r = AnsiParser.parse("\u001b[7mX")
        assertTrue(r.spans.first().inverse)
        assertFalse(r.spans.first().bold)
    }

    @Test
    fun longColoredRunMergesIntoSingleSpan() {
        val text = "\u001b[31m" + "a".repeat(10000) + "\u001b[0m"
        val r = AnsiParser.parse(text)
        assertEquals(10000, r.clean.length)
        assertEquals(1, r.spans.size)
        assertEquals(0, r.spans[0].start)
        assertEquals(10000, r.spans[0].end)
    }

    @Test
    fun styleSwitchSplitsRuns() {
        val r = AnsiParser.parse("\u001b[31mab\u001b[32mcd\u001b[0m")
        assertEquals("abcd", r.clean)
        assertEquals(2, r.spans.size)
        assertEquals(0, r.spans[0].start)
        assertEquals(2, r.spans[0].end)
        assertEquals(2, r.spans[1].start)
        assertEquals(4, r.spans[1].end)
    }

    @Test
    fun plainAndStyledSegments() {
        val r = AnsiParser.parse("plain\u001b[1mbold\u001b[0mplain")
        assertEquals("plainboldplain", r.clean)
        assertEquals(1, r.spans.size)
        assertEquals(5, r.spans[0].start)
        assertEquals(9, r.spans[0].end)
        assertTrue(r.spans[0].bold)
    }
}
