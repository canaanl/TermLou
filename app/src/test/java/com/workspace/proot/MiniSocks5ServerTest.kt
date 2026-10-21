package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class MiniSocks5ServerTest {

    private fun server() = MiniSocks5Server("127.0.0.1", 0, { true }, { true })

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

    @Test
    fun `relay forwards payload before closing`() {
        val echo = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val echoThread = Thread {
            try {
                echo.accept().use { s ->
                    val input = s.getInputStream()
                    val output = s.getOutputStream()
                    val buf = ByteArray(5)
                    var off = 0
                    while (off < buf.size) {
                        val n = input.read(buf, off, buf.size - off)
                        if (n < 0) break
                        off += n
                    }
                    if (off > 0) {
                        output.write(buf, 0, off)
                        output.flush()
                    }
                }
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            start()
        }

        val protectBound = java.util.concurrent.atomic.AtomicBoolean(false)
        val proxy = MiniSocks5Server("127.0.0.1", 0, { s ->
            protectBound.set(s.isBound && s.localPort != 0)
            true
        }, { true })
        val proxyPort = proxy.startListening()
        try {
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 5000
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()

                output.write(byteArrayOf(5, 1, 0))
                output.flush()
                val greeting = ByteArray(2)
                input.readFully(greeting)
                assertEquals(5, greeting[0].toInt())
                assertEquals(0, greeting[1].toInt())

                output.write(
                    byteArrayOf(
                        5, 1, 0, 1, 127, 0, 0, 1,
                        ((echo.localPort shr 8) and 0xFF).toByte(),
                        (echo.localPort and 0xFF).toByte()
                    )
                )
                output.flush()
                val reply = ByteArray(10)
                input.readFully(reply)
                assertEquals(5, reply[0].toInt())
                assertEquals(0, reply[1].toInt())

                val payload = "hello".toByteArray(Charsets.US_ASCII)
                output.write(payload)
                output.flush()
                val echoed = ByteArray(payload.size)
                input.readFully(echoed)
                assertEquals("hello", String(echoed, Charsets.US_ASCII))
                assertTrue("protect 必须在 socket bind 之后调用", protectBound.get())
            }
        } finally {
            proxy.stopListening()
            runCatching { echo.close() }
            echoThread.join(2000)
        }
    }

    @Test
    fun `protect failure returns rep 1 without connecting`() {
        val target = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val proxy = MiniSocks5Server("127.0.0.1", 0, { false }, { true })
        val proxyPort = proxy.startListening()
        try {
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 5000
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()

                output.write(byteArrayOf(5, 1, 0))
                output.flush()
                val greeting = ByteArray(2)
                input.readFully(greeting)
                assertEquals(5, greeting[0].toInt())

                output.write(
                    byteArrayOf(
                        5, 1, 0, 1, 127, 0, 0, 1,
                        ((target.localPort shr 8) and 0xFF).toByte(),
                        (target.localPort and 0xFF).toByte()
                    )
                )
                output.flush()
                val reply = ByteArray(10)
                input.readFully(reply)
                assertEquals(5, reply[0].toInt())
                assertEquals(1, reply[1].toInt())
            }
        } finally {
            proxy.stopListening()
            runCatching { target.close() }
        }
    }
}
