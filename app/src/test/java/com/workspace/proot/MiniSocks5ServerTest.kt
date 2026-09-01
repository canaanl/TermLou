package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class MiniSocks5ServerTest {

    private fun server() = MiniSocks5Server("127.0.0.1", 0, {}, {})

    @Test
    fun `parseRequest - CONNECT IPv4`() {
        val input = ByteArrayInputStream(byteArrayOf(5, 1, 0, 1, 172.toByte(), 217.toByte(), 14, 14, 1, 187.toByte()))
        val req = server().parseRequest(input)
        assertEquals(1, req.cmd)
        assertEquals(1, req.atyp)
        assertEquals("172.217.14.14", req.dst.hostAddress)
        assertEquals(443, req.port)
    }

    @Test
    fun `parseRequest - UDP ASSOCIATE all zeros`() {
        val input = ByteArrayInputStream(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
        val req = server().parseRequest(input)
        assertEquals(3, req.cmd)
        assertEquals(1, req.atyp)
        assertEquals("0.0.0.0", req.dst.hostAddress)
        assertEquals(0, req.port)
    }

    @Test
    fun `parseRequest - CONNECT IPv6`() {
        val ip = byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        val bytes = byteArrayOf(5, 1, 0, 4) + ip + byteArrayOf(1, 187.toByte())
        val req = server().parseRequest(ByteArrayInputStream(bytes))
        assertEquals(1, req.cmd)
        assertEquals(4, req.atyp)
        assertEquals("2001:db8:0:0:0:0:0:1", req.dst.hostAddress)
        assertEquals(443, req.port)
    }

    @Test
    fun `parseRequest - CONNECT domain`() {
        val domain = "8.8.8.8"
        val bytes = byteArrayOf(5, 1, 0, 3, domain.length.toByte()) +
            domain.toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 53)
        val req = server().parseRequest(ByteArrayInputStream(bytes))
        assertEquals(1, req.cmd)
        assertEquals(3, req.atyp)
        assertEquals("8.8.8.8", req.dst.hostAddress)
        assertEquals(53, req.port)
    }
}
