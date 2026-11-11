package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("MagicNumber")
class TermlouServiceProtocolTest {

    private fun req(raw: String): TermlouServiceProtocol.Request? =
        TermlouServiceProtocol.parse(MiniJson.parse(raw))

    @Test
    fun `start parses argv as an array without quote loss`() {
        val r = req(
            """{"op":"service","id":"svc1","action":"start","name":"magpie","argv":["magpie","serve","--port","39080"]}"""
        )!!
        assertEquals("svc1", r.id)
        assertEquals("start", r.action)
        assertEquals("magpie", r.name)
        assertEquals(listOf("magpie", "serve", "--port", "39080"), r.argv)
    }

    @Test
    fun `empty and space-containing arguments survive`() {
        val r = req(
            """{"op":"service","action":"start","name":"x","argv":["cmd","a b","","a\"b"]}"""
        )!!
        assertEquals(listOf("cmd", "a b", "", "a\"b"), r.argv)
    }

    @Test
    fun `status list and stop parse`() {
        assertEquals("status", req("""{"op":"service","action":"status","name":"magpie"}""")!!.action)
        assertEquals("list", req("""{"op":"service","action":"list"}""")!!.action)
        assertEquals("stop", req("""{"op":"service","action":"stop","name":"magpie"}""")!!.action)
        assertEquals("rm", req("""{"op":"service","action":"rm","name":"magpie"}""")!!.action)
        assertEquals("clean", req("""{"op":"service","action":"clean"}""")!!.action)
    }

    @Test
    fun `invalid requests are rejected`() {
        assertNull(req("""{"op":"ui","action":"start","name":"x","argv":["x"]}"""))
        assertNull(req("""{"op":"service","action":"start","name":"x","argv":[]}"""))
        assertNull(req("""{"op":"service","action":"start","name":"x","argv":[""]}"""))
        assertNull(req("""{"op":"service","action":"start","name":"bad name","argv":["x"]}"""))
        assertNull(req("""{"op":"service","action":"start","name":"x"}"""))
        assertNull(req("""{"op":"service","action":"status"}"""))
        assertNull(req("""{"op":"service","action":"rm"}"""))
        assertNull(req("""{"op":"service","action":"bogus","name":"x"}"""))
        assertNull(req("""{"op":"service","action":"start","name":"x","argv":["x",1]}"""))
    }

    @Test
    fun `service name rules are explicit`() {
        assertTrue(TermlouServiceProtocol.validName("magpie"))
        assertTrue(TermlouServiceProtocol.validName("my.svc_1-a"))
        assertFalse(TermlouServiceProtocol.validName(""))
        assertFalse(TermlouServiceProtocol.validName("has space"))
        assertFalse(TermlouServiceProtocol.validName("x".repeat(65)))
        assertFalse(TermlouServiceProtocol.validName(".."))
        assertFalse(TermlouServiceProtocol.validName("."))
        assertFalse(TermlouServiceProtocol.validName("../x"))
    }

    @Test
    fun `snapshot json is stable and machine readable`() {
        val snapshot = TermlouServiceProtocol.ServiceSnapshot(
            name = "magpie",
            command = "magpie serve",
            state = TermlouServiceProtocol.State.RUNNING,
            startedAt = 123L,
            alive = true,
            exitCode = null,
            error = null,
            logPath = "/termlou/services/magpie.log",
            pid = 4321,
        )
        val json = TermlouServiceProtocol.ok(snapshot).toString()
        assertTrue(json.contains("\"ok\":true"))
        assertTrue(json.contains("\"name\":\"magpie\""))
        assertTrue(json.contains("\"state\":\"RUNNING\""))
        assertTrue(json.contains("\"pid\":4321"))
        assertTrue(json.contains("\"alive\":true"))
        assertTrue(json.contains("\"exit_code\":-1"))
        assertTrue(json.contains("/termlou/services/magpie.log"))
        assertFalse("不能再输出会话绑定字段：\n$json", json.contains("bound_session"))

        val list = TermlouServiceProtocol.okServices(listOf(snapshot)).toString()
        assertTrue(list.contains("\"services\":["))
        assertTrue(list.contains("\"name\":\"magpie\""))

        assertTrue(TermlouServiceProtocol.okRemoved("magpie").toString().contains("\"removed\":\"magpie\""))
        assertTrue(TermlouServiceProtocol.okRemovedCount(3).toString().contains("\"removed_count\":3"))
    }

    @Test
    fun `error response can carry current service state`() {
        val snapshot = TermlouServiceProtocol.ServiceSnapshot(
            name = "magpie",
            command = "magpie serve",
            state = TermlouServiceProtocol.State.RUNNING,
            startedAt = 1L,
            alive = true,
            exitCode = null,
            error = null,
            logPath = "/termlou/services/magpie.log",
        )
        val json = TermlouServiceProtocol.error("exists", snapshot).toString()
        assertTrue(json.contains("\"ok\":false"))
        assertTrue(json.contains("\"error\":\"exists\""))
        assertTrue(json.contains("\"state\":\"RUNNING\""))
    }
}
