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
    fun `service manager is independent from runInProot and has no session binding`() {
        val mgr = code("TermlouServiceManager.kt")
        assertTrue("必须有主程序打开回调：\n$mgr", mgr.contains("fun onMainOpened"))
        assertTrue("必须有 App 关闭回调：\n$mgr", mgr.contains("onAppClosed"))
        assertTrue("服务必须独立启动 PRoot：\n$mgr", mgr.contains("startBackground"))
        assertTrue("停止必须走可验证的进程树清理器：\n$mgr", mgr.contains("TermlouServiceReaper.kill"))
        assertFalse("服务管理器不能复用一次性 runInProot：\n$mgr", mgr.contains("runInProot("))
        assertTrue("必须有 60 秒兜底监控：\n$mgr", mgr.contains("MONITOR_MS"))
        assertTrue("必须限制同时托管数量：\n$mgr", mgr.contains("MAX_ACTIVE_SERVICES"))
        assertTrue("主程序不在必须停止全部服务：\n$mgr", mgr.contains("stopAllLocked"))
        assertFalse("不能再有会话绑定模型：\n$mgr", mgr.contains("boundToken"))
        assertFalse("不能再有会话 token 表：\n$mgr", mgr.contains("sessionTokens"))
        assertFalse("不能再有 WAITING 状态：\n$mgr", mgr.contains("State.WAITING"))
        assertFalse("不能再依赖主 PRoot 追踪器：\n$mgr", mgr.contains("MainProotTracker"))
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

        // 启动路径不允许检查主程序：没打开过 TermLou 也必须能拉起服务。
        val launch = mgr.substringAfter("private fun startServiceLocked").substringBefore("private fun launchServiceLocked")
        assertFalse("启动服务不能要求主程序已打开：\n$launch", launch.contains("MainAppPresence"))
        assertFalse("启动服务不能要求主 PRoot 存在：\n$launch", launch.contains("MainProotTracker"))

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
    fun `monitor checks the main app every tick even without a session`() {
        val mgr = code("TermlouServiceManager.kt")
        val tick = mgr.substringAfter("private fun monitorTick").substringBefore("// ---------- 快照与持久化")
        assertTrue("监控必须检查主程序是否打开：\n$tick", tick.contains("MainAppPresence.isOpen()"))
        assertFalse("监控不能因为缺少会话而提前返回：\n$tick", tick.contains("mainToken ?: return"))
        assertFalse("监控不能用 PTY 状态代替主程序状态：\n$tick", tick.contains("isRunning"))
        assertFalse("监控不能再依赖主 PRoot：\n$tick", tick.contains("MainProotTracker"))
        assertTrue("主程序不在必须全停：\n$tick", tick.contains("stopAllLocked(\"main_app_missing\")"))
        assertTrue("监控单次异常不能终止后续周期：\n$tick", tick.contains("catch (e: Exception)"))
        assertTrue("监控周期必须是 60 秒：\n$mgr", mgr.contains("MONITOR_MS = 60_000L"))
    }

    @Test
    fun `main app presence is the single liveness source`() {
        val presence = code("MainAppPresence.kt")
        assertTrue("必须能标记打开：\n$presence", presence.contains("fun onMainOpened"))
        assertTrue("必须能标记关闭：\n$presence", presence.contains("fun onMainClosed"))
        assertTrue("必须能查询：\n$presence", presence.contains("fun isOpen"))
        assertTrue("默认必须是未打开：\n$presence", presence.contains("private var opened = false"))

        val main = code("MainActivity.kt")
        assertTrue("主程序创建必须登记打开：\n$main", main.contains("TermlouServiceManager.onMainOpened"))

        val proc = code("ProcLiveness.kt")
        assertTrue("僵尸必须判为不存活：\n$proc", proc.contains("i.state == \"Z\""))
        assertTrue("starttime 不一致必须判为不存活：\n$proc", proc.contains("i.startTime == expectedStart"))
        assertTrue("读不到 /proc 必须判为不存活：\n$proc", proc.contains("?: return false"))

        val reaper = code("TermlouServiceReaper.kt")
        assertTrue("服务清理器必须复用同一份存活判定：\n$reaper", reaper.contains("ProcLiveness.isAlive"))
        assertTrue("服务清理器必须复用同一份进程信息：\n$reaper", reaper.contains("ProcLiveness.info"))
    }

    @Test
    fun `main terminal no longer publishes session events`() {
        val tc = code("TerminalController.kt")
        assertFalse("主终端不应再向服务管理器登记会话：\n$tc", tc.contains("onMainSessionStarted"))
        assertFalse("主终端退出不应再影响服务：\n$tc", tc.contains("onMainSessionFinished"))

        val main = code("MainActivity.kt")
        assertTrue("关闭 TermLou 主程序必须停掉后台服务：\n$main", main.contains("TermlouServiceManager.onAppClosed"))
        val destroy = main.substringAfter("override fun onDestroy()").substringBefore("super.onDestroy()")
        assertTrue("必须在 isFinishing 时停后台服务：\n$destroy", destroy.contains("isFinishing"))

        val host = code("TermlouBackgroundService.kt")
        assertTrue("从最近任务划掉也必须停后台服务：\n$host", host.contains("onTaskRemoved"))
        val taskRemoved = host.substringAfter("override fun onTaskRemoved").substringBefore("override fun onBind")
        assertTrue("onTaskRemoved 必须调用 onAppClosed：\n$taskRemoved", taskRemoved.contains("onAppClosed"))
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
            "STARTING | RUNNING",
            "Long-running Linux services",
            "Linux 后台服务",
            "max 4 active",
            "stop_incomplete",
            "does NOT survive app process death",
            "pid = host PRoot wrapper PID",
            "No main PRoot is required to start a service",
            "check runs every 60",
            "A service can start before TermLou is opened",
            "the TermLou app is open",
            "Closing the terminal shell inside TermLou does not stop services",
        )) {
            assertTrue("termlou-ui help 缺少 $needle", sh.contains(needle))
        }
        assertFalse("help 不能残留 WAITING：$sh", sh.contains("WAITING"))
        assertFalse("help 不能残留 bound_session：$sh", sh.contains("bound_session"))
        assertFalse("help 不能残留绑定说法：$sh", sh.contains("binds when the main PRoot"))
        assertFalse("help 不能再说“主 PRoot 不在就停”：$sh", sh.contains("if no main PRoot exists"))
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
