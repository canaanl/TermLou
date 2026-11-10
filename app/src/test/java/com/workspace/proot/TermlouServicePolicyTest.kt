package com.workspace.proot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 5.12.0 后台服务托管的源码级回归锁。
 *
 * 进程生命周期无法在 JVM 里完整跑（需要 Android/proot），但所有关键接线
 * 必须可检查：普通命令仍走一次性 runInProot；服务命令有独立 IPC、前台服务、
 * 主会话 token 和进程树清理。
 */
@Suppress("MagicNumber")
class TermlouServicePolicyTest {

    private fun code(name: String): String {
        var d: File? = File("").absoluteFile
        var hops = 0
        while (d != null && hops < 6) {
            val f = File(d, "app/src/main/java/com/workspace/proot/$name")
            if (f.isFile) {
                return f.readText().lines()
                    .map { it.trimStart().let { t -> if (t.startsWith("*") || t.startsWith("/*")) "" else it } }
                    .joinToString("\n")
            }
            d = d.parentFile
            hops++
        }
        throw AssertionError("找不到 $name，这条检查等于没跑")
    }

    private fun res(name: String): String {
        var d: File? = File("").absoluteFile
        var hops = 0
        while (d != null && hops < 6) {
            val f = File(d, "app/src/main/res/raw/$name")
            if (f.isFile) return f.readText()
            d = d.parentFile
            hops++
        }
        throw AssertionError("找不到 $name")
    }

    @Test
    fun `ordinary tile commands keep the one-shot runner`() {
        val runner = code("TermlouCommandRunner.kt")
        assertTrue("普通命令仍必须走 runInProot：\n$runner", runner.contains("runInProot("))
        assertTrue("普通命令仍必须使用临时 wsTmp：\n$runner", runner.contains("workspace/tmp/run-"))
        assertTrue("Runner 必须持有服务 IPC：\n$runner", runner.contains("TermlouServiceBridge.acquire"))
        assertTrue("Runner 必须释放服务 IPC：\n$runner", runner.contains("TermlouServiceBridge.release"))
    }

    @Test
    fun `service manager is independent from runInProot and tracks session tokens`() {
        val mgr = code("TermlouServiceManager.kt")
        assertTrue("主会话必须用 TerminalSession token：\n$mgr", mgr.contains("WeakHashMap<TerminalSession"))
        assertTrue("必须有主会话开始回调：\n$mgr", mgr.contains("onMainSessionStarted"))
        assertTrue("必须有主会话结束回调：\n$mgr", mgr.contains("onMainSessionFinished"))
        assertTrue("服务必须独立启动 PRoot：\n$mgr", mgr.contains("startBackground"))
        assertTrue("停止必须走可验证的进程树清理器：\n$mgr", mgr.contains("TermlouServiceReaper.kill"))
        assertFalse("服务管理器不能复用一次性 runInProot：\n$mgr", mgr.contains("runInProot("))
        assertTrue("必须有 60 秒兜底监控：\n$mgr", mgr.contains("MONITOR_MS"))
        assertTrue("必须限制同时托管数量：\n$mgr", mgr.contains("MAX_ACTIVE_SERVICES"))
        assertTrue("主会话结束时只能停止绑定服务：\n$mgr", mgr.contains("stopBoundToLocked"))
        assertTrue("停止未收干净必须报 stop_incomplete：\n$mgr", mgr.contains("stop_incomplete"))
        assertTrue("状态必须包含 pid：\n$mgr", mgr.contains("pid = s.pid"))
        assertTrue("启动必须有宽限期：\n$mgr", mgr.contains("STARTUP_GRACE_MS"))
        assertTrue("日志必须启动即创建：\n$mgr", mgr.contains("appendLog(s, \"--- service"))
        assertTrue("必须支持 rm/clean：\n$mgr", mgr.contains("removeServiceLocked") && mgr.contains("cleanServicesLocked"))
        assertTrue("rm 必须拒绝活动/有残留记录：\n$mgr", mgr.contains("s.state in ACTIVE || s.process != null || s.knownTargets.isNotEmpty()"))
        assertTrue("pid 必须通过 pidfile + /proc resolver 获取：\n$mgr", mgr.contains("ServiceProcessPidResolver.resolve(process, s.tempDir, pidFile)"))
        assertTrue("启动必须把 pidfile 交给独立 PRoot：\n$mgr", mgr.contains("startBackground(s.argv, pidFile)"))
        assertTrue("拿不到 pid 必须启动失败，不能留不可清理服务：\n$mgr", mgr.contains("failStartPidUnavailable"))
        assertFalse("manager 不能再依赖运行期不存在的 Process.pid 反射：\n$mgr", mgr.contains("getMethod(\"pid\")"))

        val resolver = code("ServiceProcessPidResolver.kt")
        assertTrue("pidfile 必须先读：\n$resolver", resolver.contains("readPidFile(pidFile)"))
        assertTrue("反射 pid 必须拒绝 -1/0：\n$resolver", resolver.contains("positivePid(raw)"))
        assertTrue("私有 pid 字段也必须拒绝 -1/0：\n$resolver", resolver.contains("positivePid(field.getInt(process).toLong())"))
        assertTrue("positivePid 必须只接受 > 0：\n$resolver", resolver.contains("value?.takeIf { it > 0 }?.toInt()"))

        val tm = code("TerminalManager.kt")
        assertTrue("服务进程启动必须保留 --kill-on-exit", tm.contains("--kill-on-exit"))
        assertTrue("服务包装不能是 login shell：\n$tm", !tm.contains("\"-lc\""))
        assertTrue("服务包装必须禁用 profile/rc：\n$tm", tm.contains("--noprofile") && tm.contains("--norc"))
        assertTrue("服务必须由宿主 shell 先写自身 PID 再 exec PRoot：\n$tm", tm.contains("/system/bin/sh") && tm.contains("shift; exec"))
        assertTrue("普通 runInProot 不能传 pidfile：\n$tm", tm.contains("private fun startProotProcessArgs(args: List<String>, pidFile: File? = null)"))
    }

    @Test
    fun `main terminal publishes start and finish events`() {
        val tc = code("TerminalController.kt")
        assertTrue("创建主会话要登记：\n$tc", tc.contains("TermlouServiceManager.onMainSessionStarted"))
        assertTrue("恢复主会话要登记：\n$tc", tc.contains("onMainSessionStarted"))
        assertTrue("主会话结束要通知：\n$tc", tc.contains("TermlouServiceManager.onMainSessionFinished"))
        assertTrue("onDestroy 也要通知：\n$tc", tc.contains("onSessionFinished(finishedSession"))
    }

    @Test
    fun `service IPC is acquired by every shell entry point`() {
        for (name in listOf("MainActivity.kt", "LanShareService.kt", "TermlouCommandRunner.kt")) {
            val c = code(name)
            assertTrue("$name 必须 acquire 服务 IPC", c.contains("TermlouServiceBridge.acquire"))
            assertTrue("$name 必须 release 服务 IPC", c.contains("TermlouServiceBridge.release"))
        }
    }

    @Test
    fun `background host is a foreground service in manifest`() {
        val manifest = File("app/src/main/AndroidManifest.xml").let { f ->
            var d: File? = File("").absoluteFile
            var hops = 0
            while (d != null && hops < 6) {
                val candidate = File(d, "app/src/main/AndroidManifest.xml")
                if (candidate.isFile) return@let candidate.readText()
                d = d.parentFile
                hops++
            }
            throw AssertionError("找不到 AndroidManifest.xml")
        }
        assertTrue(manifest.contains("TermlouBackgroundService"))
        assertTrue(manifest.contains("foregroundServiceType=\"specialUse\""))
        val host = code("TermlouBackgroundService.kt")
        assertTrue("宿主必须 acquire IPC：\n$host", host.contains("TermlouServiceBridge.acquire"))
        assertTrue("宿主必须是 START_NOT_STICKY：\n$host", host.contains("START_NOT_STICKY"))
        assertTrue("宿主停止必须通知 manager：\n$host", host.contains("onHostDestroyed"))
    }

    @Test
    fun `service bridge uses its own directory and token-protected manager`() {
        val bridge = code("TermlouServiceBridge.kt")
        assertTrue("服务 IPC 必须独立目录：$bridge", bridge.contains("TermlouDirs.serviceReq"))
        assertTrue("服务 IPC 必须写响应：$bridge", bridge.contains("TermlouDirs.serviceRes"))
        assertTrue("服务 IPC 目录必须定义在 TermlouDirs", code("TermlouDirs.kt").contains("service/req"))
        assertTrue("服务请求必须解析协议：$bridge", bridge.contains("TermlouServiceProtocol.parse"))
        assertTrue("服务请求必须交给 manager：$bridge", bridge.contains("TermlouServiceManager.handle"))
    }

    @Test
    fun `termlou-ui has service commands and help`() {
        val sh = res("termlou_ui.sh")
        for (needle in listOf(
            "service start --name",
            "service status",
            "service list",
            "service stop",
            "service rm",
            "service clean",
            "STARTING | WAITING",
            "Long-running Linux services",
            "Linux 后台服务",
            "max 4 active",
            "stop_incomplete",
            "does NOT survive app process death",
            "pid = host PRoot wrapper PID",
        )) {
            assertTrue("termlou-ui help 缺少 $needle", sh.contains(needle))
        }
        assertTrue("service 分支必须写独立 req 目录", sh.contains("\$DIR/service/req"))
        assertTrue("service start 必须传递 argv 数组", sh.contains("\\\"argv\\\":[\$argv_json]"))
        assertTrue("service 必须有 rm 分支", sh.contains("status|stop|rm|remove"))
        assertTrue("service 必须有 clean 分支", sh.contains("list|clean"))
    }

    @Test
    fun `service logs stay inside the log budget`() {
        val budget = code("LogBudget.kt")
        assertTrue("服务日志必须纳入 1MB 预算：$budget", budget.contains(".termlou/services"))
    }

    @Test
    fun `service stop is PID reuse safe`() {
        val mgr = code("TermlouServiceManager.kt")
        val stop = mgr.substringAfter("private fun stopProcessLocked").substringBefore("private fun stopAllLocked")
        assertTrue("stop 只能在包装进程存活时 snapshot：\n$stop", stop.contains("process?.isAlive == true"))
        assertTrue("stop 只能在包装进程存活时传 root PID：\n$stop", stop.contains("if (process?.isAlive == true) pid ?: 0 else 0"))
        assertFalse(
            "stop 不能对已退出的 pid 做 snapshot（PID 可能复用）：\n$stop",
            stop.contains("if (pid != null) TermlouServiceReaper.snapshot"),
        )

        val exit = mgr.substringAfter("private fun onProcessExit").substringBefore("private fun stopProcessLocked")
        assertFalse(
            "onProcessExit 不能 snapshot 已退出/可能复用的 PID：\n$exit",
            exit.contains("TermlouServiceReaper.snapshot"),
        )
        assertTrue("onProcessExit 必须传 rootPid=0：\n$exit", exit.contains("TermlouServiceReaper.kill(0,"))

        val reaper = code("TermlouServiceReaper.kt")
        assertTrue(
            "root starttime 只能用 containsKey 保护后写入：\n$reaper",
            reaper.contains("if (!tracked.containsKey(rootPid)) tracked[rootPid] = rootStart"),
        )
    }
}
