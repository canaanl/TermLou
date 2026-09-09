package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SniParserTest {

    private fun u16(v: Int): ByteArray =
        byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun u24(v: Int): ByteArray =
        byteArrayOf(((v ushr 16) and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun varint(v: Int): ByteArray = when {
        v < 0x40 -> byteArrayOf(v.toByte())
        v < 0x4000 -> byteArrayOf((0x40 or (v ushr 8)).toByte(), (v and 0xFF).toByte())
        else -> throw IllegalArgumentException("test varint too large")
    }

    /** 构造完整 ClientHello 握手消息（type + u24 len + body）。 */
    private fun clientHelloMessage(host: String): ByteArray {
        val hostBytes = host.toByteArray()
        val snEntry = byteArrayOf(0x00) + u16(hostBytes.size) + hostBytes
        val snList = u16(snEntry.size) + snEntry
        val extData = snList
        val ext = byteArrayOf(0x00, 0x00) + u16(extData.size) + extData
        val body = byteArrayOf(0x03, 0x03) + ByteArray(0x20) +
            byteArrayOf(0x00, 0x00, 0x02, 0x13, 0x01, 0x01, 0x00, 0x00, 0x01) + ext
        val msg = byteArrayOf(0x01) + u24(body.size) + body
        return msg
    }

    private fun tlsRecord(msg: ByteArray): ByteArray =
        byteArrayOf(0x16, 0x03, 0x01) + u16(msg.size) + msg

    @Test
    fun `tcpClientHello - extracts host from full record`() {
        val rec = tlsRecord(clientHelloMessage("api.example.com"))
        assertEquals("api.example.com", SniParser.tcpClientHello(rec))
    }

    @Test
    fun `tcpClientHello - truncated record returns null`() {
        val rec = tlsRecord(clientHelloMessage("api.example.com"))
        val half = rec.copyOf(rec.size / 0x02)
        assertNull(SniParser.tcpClientHello(half))
    }

    @Test
    fun `tcpClientHello - non-handshake record returns null`() {
        val rec = tlsRecord(clientHelloMessage("api.example.com"))
        rec[0] = 0x17
        assertNull(SniParser.tcpClientHello(rec))
    }

    @Test
    fun `tlsSniffer - reassembles across fragments`() {
        val rec = tlsRecord(clientHelloMessage("frag.example.com"))
        val first = rec.copyOf(rec.size / 0x03)
        val rest = rec.copyOfRange(first.size, rec.size)
        val sniffer = TlsSniffer()
        assertNull(sniffer.feed(first, first.size))
        assertEquals("frag.example.com", sniffer.feed(rest, rest.size))
    }

    @Test
    fun `tlsSniffer - non-TLS first byte gives up`() {
        val sniffer = TlsSniffer()
        assertNull(sniffer.feed(byteArrayOf(0x00, 0x01, 0x02), 0x03))
    }

    private fun quicInitial(host: String): ByteArray {
        val hello = clientHelloMessage(host)
        val crypto = byteArrayOf(0x06, 0x00) + varint(hello.size) + hello
        val content = byteArrayOf(0x00) + crypto
        return byteArrayOf(
            0xC3.toByte(), 0x00, 0x00, 0x00, 0x01,
            0x01, 0xAA.toByte(),
            0x01, 0xBB.toByte(),
            0x00
        ) + varint(content.size) + content
    }

    @Test
    fun `quicClientHello - extracts host from initial packet`() {
        assertEquals("quic.example.com", SniParser.quicClientHello(quicInitial("quic.example.com")))
    }

    @Test
    fun `quicClientHello - short packet returns null`() {
        assertNull(SniParser.quicClientHello(byteArrayOf(0xC3.toByte(), 0x00)))
    }

    @Test
    fun `quicClientHello - handshake type packet returns null`() {
        val pkt = quicInitial("quic.example.com")
        pkt[0] = (pkt[0].toInt() or 0x10).toByte()
        assertNull(SniParser.quicClientHello(pkt))
    }
}
