package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsParserTest {

    private fun queryPayload(name: ByteArray): ByteArray =
        byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) + name +
            byteArrayOf(0x00, 0x01, 0x00, 0x01)

    @Test
    fun `queryName - plain name`() {
        val name = byteArrayOf(
            3, 'w'.code.toByte(), 'w'.code.toByte(), 'w'.code.toByte(),
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0
        )
        assertEquals("www.example.com", DnsParser.queryName(queryPayload(name)))
    }

    @Test
    fun `queryName - response payload returns null`() {
        val q = queryPayload(byteArrayOf(3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0))
        q[2] = (q[2].toInt() or 0x80).toByte()
        assertEquals(null, DnsParser.queryName(q))
    }

    @Test
    fun `queryName - compressed question pointer returns null`() {
        val payload = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xC0.toByte(), 0x0C)
        assertEquals(null, DnsParser.queryName(payload))
    }

    @Test
    fun `queryName - empty name returns null`() {
        val payload = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertEquals(null, DnsParser.queryName(payload))
    }

    private fun buildResponse(): ByteArray {
        val header = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00)
        val question = byteArrayOf(
            3, 'w'.code.toByte(), 'w'.code.toByte(), 'w'.code.toByte(),
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0, 0x00, 0x01, 0x00, 0x01
        )
        val answerA = byteArrayOf(
            0xC0.toByte(), 0x0C,
            0x00, 0x01, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x3C,
            0x00, 0x04,
            0x5D.toByte(), 0xB8.toByte(), 0xD8.toByte(), 0x22
        )
val answerAaaa = byteArrayOf(
            0xC0.toByte(), 0x0C,
            0x00, 0x1C, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x3C,
            0x00, 0x10,
            0x20, 0x01, 0x0D.toByte(), 0xB8.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x01
        )
        return header + question + answerA + answerAaaa
    }

    @Test
    fun `responseMappings - A and AAAA with compressed names`() {
        val mappings = DnsParser.responseMappings(buildResponse())
        assertEquals(2, mappings.size)
        assertTrue(mappings.contains(Pair("www.example.com", "93.184.216.34")))
        assertTrue(mappings.contains(Pair("www.example.com", "2001:db8:0:0:0:0:0:1")))
    }

    @Test
    fun `responseMappings - query payload returns empty`() {
        assertTrue(DnsParser.responseMappings(queryPayload(byteArrayOf(0))).isEmpty())
    }

    @Test
    fun `responseMappings - answers with plain names`() {
        val header = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)
        val answer = byteArrayOf(
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0,
            0x00, 0x01, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x3C,
            0x00, 0x04,
            0x01, 0x02, 0x03, 0x04
        )
        val mappings = DnsParser.responseMappings(header + answer)
        assertEquals(1, mappings.size)
        assertEquals(Pair("example.com", "1.2.3.4"), mappings[0])
    }
}