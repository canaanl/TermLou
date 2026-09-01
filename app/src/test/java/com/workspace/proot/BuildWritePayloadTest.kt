package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildWritePayloadTest {

    @Test
    fun `returns raw text when no placeholder`() {
        val raw = "git commit -m fix"
        assertEquals(raw, buildWritePayload(raw))
    }

    @Test
    fun `placeholder at end keeps preceding space and sends no cursor back`() {
        assertEquals("opencode run ", buildWritePayload("opencode run @{}"))
    }

    @Test
    fun `placeholder in middle emits single arrow left`() {
        assertEquals("git commit -m \"\"\u001b[D", buildWritePayload("git commit -m \"@{}\""))
    }

    @Test
    fun `multi char tail emits repeated arrow left`() {
        assertEquals("echo aa\u001b[D\u001b[D", buildWritePayload("echo @{}aa"))
    }

    @Test
    fun `only first placeholder positions cursor and all removed`() {
        assertEquals("ab\u001b[D", buildWritePayload("a@{}b@{}"))
    }

    @Test
    fun `single char before placeholder with empty tail`() {
        assertEquals("a", buildWritePayload("a@{}"))
    }

    @Test
    fun `opencode run with trailing placeholder keeps space`() {
        assertEquals("opencode run ", buildWritePayload("opencode run @{}"))
    }
}
