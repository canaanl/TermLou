package com.workspace.proot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class MultipartScannerTest {

    private lateinit var spool: File

    @Before
    fun setup() {
        spool = File.createTempFile("multipart-test", ".bin")
    }

    @After
    fun teardown() {
        spool.delete()
    }

    private fun write(text: String) {
        spool.writeBytes(text.toByteArray(Charsets.ISO_8859_1))
    }

    private fun partText(spool: File, part: MultipartScanner.Part): String =
        RandomAccessFileReader.read(spool, part.bodyStart, part.bodyEnd - part.bodyStart)

    @Test
    fun `single part - header and body ranges are exact`() {
        write(
            "--B\r\n" +
                "Content-Disposition: form-data; name=\"f\"; filename=\"a.txt\"\r\n\r\n" +
                "HELLO\r\n--B--\r\n"
        )
        val parts = MultipartScanner.scan(spool, "--B".toByteArray(Charsets.ISO_8859_1))
        assertEquals(1, parts.size)
        val p = parts[0]
        val header = RandomAccessFileReader.read(spool, p.headerStart, p.headerEnd - p.headerStart)
        assertTrue(header.contains("filename=\"a.txt\""))
        assertEquals("HELLO", partText(spool, p))
    }

    @Test
    fun `multiple parts - each body extracted independently`() {
        write(
            "--B\r\nContent-Disposition: form-data; name=\"a\"; filename=\"1.txt\"\r\n\r\nAAA\r\n" +
                "--B\r\nContent-Disposition: form-data; name=\"b\"; filename=\"2.txt\"\r\n\r\nBB\r\n" +
                "--B--\r\n"
        )
        val parts = MultipartScanner.scan(spool, "--B".toByteArray(Charsets.ISO_8859_1))
        assertEquals(2, parts.size)
        assertEquals("AAA", partText(spool, parts[0]))
        assertEquals("BB", partText(spool, parts[1]))
        assertTrue(
            RandomAccessFileReader.read(spool, parts[1].headerStart, parts[1].headerEnd - parts[1].headerStart)
                .contains("filename=\"2.txt\"")
        )
    }

    @Test
    fun `boundary crossing chunk boundary is still detected`() {
        // 第二个 boundary 落在 512KB 块边界之后，必须靠跨块保留区才找得到
        val padding = "x".repeat(600 * 1024)
        write(
            "--B\r\nContent-Disposition: form-data; name=\"a\"; filename=\"big.txt\"\r\n\r\n" +
                padding +
                "\r\n--B\r\nContent-Disposition: form-data; name=\"b\"; filename=\"tail.txt\"\r\n\r\nTAIL\r\n" +
                "--B--\r\n"
        )
        val parts = MultipartScanner.scan(spool, "--B".toByteArray(Charsets.ISO_8859_1))
        assertEquals(2, parts.size)
        assertEquals(padding, partText(spool, parts[0]))
        assertEquals("TAIL", partText(spool, parts[1]))
    }

    @Test
    fun `empty body part and no trailing newline`() {
        write(
            "--B\r\nContent-Disposition: form-data; name=\"a\"; filename=\"e.txt\"\r\n\r\n\r\n--B--\r\n"
        )
        val parts = MultipartScanner.scan(spool, "--B".toByteArray(Charsets.ISO_8859_1))
        assertEquals(1, parts.size)
        assertEquals("", partText(spool, parts[0]))
    }

    @Test
    fun `garbage input yields no parts`() {
        write("not a multipart body at all")
        val parts = MultipartScanner.scan(spool, "--B".toByteArray(Charsets.ISO_8859_1))
        assertTrue(parts.isEmpty())
    }

    @Test
    fun `immediate terminator yields no parts`() {
        write("--B--\r\n")
        val parts = MultipartScanner.scan(spool, "--B".toByteArray(Charsets.ISO_8859_1))
        assertTrue(parts.isEmpty())
    }

    @Test
    fun `empty boundary or missing file yields no parts`() {
        assertTrue(MultipartScanner.scan(spool, ByteArray(0)).isEmpty())
        assertTrue(MultipartScanner.scan(File(spool.parentFile, "definitely-missing.bin"), "--B".toByteArray()).isEmpty())
    }
}

/** 测试侧的区间读取工具。 */
private object RandomAccessFileReader {
    fun read(file: File, start: Int, len: Int): String {
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start.toLong())
            val b = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = raf.read(b, off, len - off)
                if (n <= 0) break
                off += n
            }
            return String(b, 0, off, Charsets.ISO_8859_1)
        }
    }
}
