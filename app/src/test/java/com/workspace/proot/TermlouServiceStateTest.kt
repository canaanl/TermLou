package com.workspace.proot

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 服务状态持久化的 JVM/Robolectric 回归。
 *
 * 进程/PRoot 本身无法在单测里启动，但“App 进程重启后旧活动记录必须变 FAILED、
 * 命令与退出码不能丢、list 排序稳定、缺失服务返回 not_found”都是纯状态逻辑，
 * 必须锁住，避免以后把持久化改回“看起来还活着”。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@Suppress("MagicNumber", "ThrowsCount")
class TermlouServiceStateTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        TermlouServiceManager.shutdownForTest()
        stateFile().delete()
    }

    @After
    fun tearDown() {
        TermlouServiceManager.shutdownForTest()
        stateFile().delete()
    }

    private fun stateFile(): File = File(TermlouDirs.base(ctx), "services.json")

    private fun writeState(json: String) {
        stateFile().parentFile?.mkdirs()
        stateFile().writeText(json)
    }

    private fun request(action: String, name: String = ""): TermlouServiceProtocol.Request =
        TermlouServiceProtocol.Request(
            id = "test",
            action = action,
            name = name,
            argv = emptyList(),
        )

    @Test
    fun `active records become failed after app restart`() {
        writeState(
            """{"services":[
              {"name":"magpie","command":"magpie serve --port 39080","state":"RUNNING","started_at":123,"exit_code":-1,"error":""},
              {"name":"old","command":"old --x","state":"STOPPED","started_at":100,"exit_code":0,"error":"exited"}
            ]}"""
        )
        TermlouServiceManager.attach(ctx)

        val list = TermlouServiceManager.handle(ctx, request("list"))
        val arr = list.optArr("services") ?: throw AssertionError("missing services")
        assertEquals(2, arr.length())

        val old = arr.getObj(0) ?: throw AssertionError("missing old")
        assertEquals("old", old.optString("name", ""))
        assertEquals("old --x", old.optString("command", ""))
        assertEquals("STOPPED", old.optString("state", ""))
        assertEquals(0, old.optInt("exit_code", -1))

        val magpie = arr.getObj(1) ?: throw AssertionError("missing magpie")
        assertEquals("magpie", magpie.optString("name", ""))
        assertEquals("magpie serve --port 39080", magpie.optString("command", ""))
        assertEquals("FAILED", magpie.optString("state", ""))
        assertEquals("app process restarted", magpie.optString("error", ""))
        assertEquals(-1, magpie.optInt("pid", 0))
        assertFalse(magpie.optBoolean("alive", true))
    }

    @Test
    fun `missing services report not_found and never fabricate state`() {
        TermlouServiceManager.attach(ctx)

        val status = TermlouServiceManager.handle(ctx, request("status", "ghost"))
        assertFalse(status.optBoolean("ok", true))
        assertEquals("not_found", status.optString("error", ""))

        val stop = TermlouServiceManager.handle(ctx, request("stop", "ghost"))
        assertFalse(stop.optBoolean("ok", true))
        assertEquals("not_found", stop.optString("error", ""))

        val list = TermlouServiceManager.handle(ctx, request("list"))
        assertEquals(0, list.optArr("services")?.length() ?: -1)
    }

    @Test
    fun `list is sorted by start time`() {
        writeState(
            """{"services":[
              {"name":"later","command":"later","state":"FAILED","started_at":200,"exit_code":-1,"error":""},
              {"name":"earlier","command":"earlier","state":"FAILED","started_at":100,"exit_code":-1,"error":""}
            ]}"""
        )
        TermlouServiceManager.attach(ctx)

        val arr = TermlouServiceManager.handle(ctx, request("list")).optArr("services")
            ?: throw AssertionError("missing services")
        assertEquals("earlier", arr.getObj(0)?.optString("name", ""))
        assertEquals("later", arr.getObj(1)?.optString("name", ""))
    }

    @Test
    fun `rm removes inactive record and its log`() {
        writeState(
            """{"services":[
              {"name":"old","command":"old","state":"STOPPED","started_at":100,"exit_code":0,"error":"exited"}
            ]}"""
        )
        val log = File(File(TermlouDirs.base(ctx), "services"), "old.log").apply {
            parentFile?.mkdirs()
            writeText("old log")
        }
        TermlouServiceManager.attach(ctx)

        val rm = TermlouServiceManager.handle(ctx, request("rm", "old"))
        assertEquals(true, rm.optBoolean("ok", false))
        assertEquals("old", rm.optString("removed", ""))
        assertFalse(log.exists())
        assertEquals(0, TermlouServiceManager.handle(ctx, request("list")).optArr("services")?.length() ?: -1)

        val missing = TermlouServiceManager.handle(ctx, request("rm", "old"))
        assertFalse(missing.optBoolean("ok", true))
        assertEquals("not_found", missing.optString("error", ""))
    }

    @Test
    fun `clean removes only inactive records`() {
        writeState(
            """{"services":[
              {"name":"a","command":"a","state":"STOPPED","started_at":100,"exit_code":0,"error":"exited"},
              {"name":"b","command":"b","state":"FAILED","started_at":200,"exit_code":1,"error":"exit 1"}
            ]}"""
        )
        val serviceDir = File(TermlouDirs.base(ctx), "services").apply { mkdirs() }
        val logA = File(serviceDir, "a.log").apply { writeText("a") }
        val logB = File(serviceDir, "b.log").apply { writeText("b") }
        TermlouServiceManager.attach(ctx)

        val clean = TermlouServiceManager.handle(ctx, request("clean"))
        assertEquals(true, clean.optBoolean("ok", false))
        assertEquals(2, clean.optInt("removed_count", -1))
        assertFalse(logA.exists())
        assertFalse(logB.exists())
        assertEquals(0, TermlouServiceManager.handle(ctx, request("list")).optArr("services")?.length() ?: -1)
    }
}
