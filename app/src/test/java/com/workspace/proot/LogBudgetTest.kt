@file:Suppress("MagicNumber")

package com.workspace.proot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class LogBudgetTest {

    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = File(System.getProperty("java.io.tmpdir"), "log_budget_${System.nanoTime()}")
        filesDir.mkdirs()
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `trim keeps total at or below one megabyte`() {
        val logDir = LogBudget.linuxLogDir(filesDir).apply { mkdirs() }
        val old = File(logDir, "old.log").apply {
            writeBytes(ByteArray(700 * 1024))
            setLastModified(1_000L)
        }
        val fresh = File(logDir, "new.log").apply {
            writeBytes(ByteArray(700 * 1024))
            setLastModified(2_000L)
        }

        LogBudget.trim(filesDir)

        val total = listOf(old, fresh).filter { it.isFile }.sumOf { it.length() }
        assertTrue("total=$total", total <= LogBudget.MAX_BYTES)
        assertTrue("newest log should survive", fresh.isFile)
    }

    @Test
    fun `trim leaves small logs alone`() {
        val logDir = LogBudget.linuxLogDir(filesDir).apply { mkdirs() }
        val log = File(logDir, "small.log").apply { writeText("small") }

        LogBudget.trim(filesDir)

        assertTrue(log.isFile)
        assertEquals(5L, log.length())
    }

    @Test
    fun `append capped keeps only the tail`() {
        val file = File(filesDir, "lan/error.log")
        LogBudget.appendCapped(file, "a".repeat(4_096), maxBytes = 1_024)

        assertTrue(file.length() <= 1_024)
        assertTrue(file.readText().isNotEmpty())
    }

    @Test
    fun `cap file truncates from the front and keeps the tail`() {
        val file = File(filesDir, "net/tun2socks.log")
        file.parentFile?.mkdirs()
        file.writeText("0123456789")

        LogBudget.capFile(file, maxBytes = 4)

        assertEquals("6789", file.readText())
    }
}
