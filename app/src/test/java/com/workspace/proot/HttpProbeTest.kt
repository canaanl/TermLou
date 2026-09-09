package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpProbeTest {

    @Test
    fun `requestLine - GET with path`() {
        val chunk = "GET /api/search HTTP/1.1\r\nHost: a.com\r\n".toByteArray()
        assertEquals("GET /api/search", HttpProbe.requestLine(chunk))
    }

    @Test
    fun `requestLine - POST with path`() {
        val chunk = "POST /submit HTTP/1.1\r\n".toByteArray()
        assertEquals("POST /submit", HttpProbe.requestLine(chunk))
    }

    @Test
    fun `requestLine - unknown verb returns null`() {
        val chunk = "GRUMPY /x HTTP/1.1\r\n".toByteArray()
        assertNull(HttpProbe.requestLine(chunk))
    }

    @Test
    fun `requestLine - binary returns null`() {
        assertNull(HttpProbe.requestLine(byteArrayOf(0x01, 0x02, 0x03, 0x04)))
    }

    @Test
    fun `statusLine - 200`() {
        val chunk = "HTTP/1.1 200 OK\r\n\r\n".toByteArray()
        assertEquals("200", HttpProbe.statusLine(chunk))
    }

    @Test
    fun `statusLine - 404 http10`() {
        val chunk = "HTTP/1.0 404 Not Found\r\n".toByteArray()
        assertEquals("404", HttpProbe.statusLine(chunk))
    }

    @Test
    fun `statusLine - non status returns null`() {
        val chunk = "not an http response line at all\r\n".toByteArray()
        assertNull(HttpProbe.statusLine(chunk))
    }
}
