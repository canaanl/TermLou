package com.workspace.proot

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * **eval 包装器在真 JS 引擎里的语义回归**（5.13.0）。
 *
 * [WebJsRealEngineTest] 只回答“这段脚本编译得过吗”。本测试回答更实际的问题：
 * 它跑起来之后，**错误真的走信封了吗、await 真的落地了吗、条件真的返回 1/0 吗**。
 *
 * 这里的 `evaluateJavascript` 由 node 的 `vm.runInThisContext` 冒充：
 * 返回值同样是“页面脚本的最终字符串”，PENDING 轮询同样是第二次调用。
 */
@Suppress("MagicNumber")
class WebEvalSemanticsTest {

    @Test
    fun `eval 包装器在真引擎里返回信封并等待 Promise`() {
        val node = findNode()
        Assume.assumeTrue("这台机器上没有 node —— 语义这一关跳过了", node != null)

        val scripts = JSONObject()
            .put("title", WebEvalScripts.syncExpression("document.title", "a"))
            .put(
                "error",
                WebEvalScripts.syncExpression("(function(){throw new Error('boom')})()", "b")
            )
            .put("undefinedValue", WebEvalScripts.syncExpression("void 0", "c"))
            .put("statement", WebEvalScripts.syncStatement("var x=1; return x+1", "d"))
            .put(
                "asyncNumber",
                WebEvalScripts.asyncScript(WebEvalScripts.asyncExpression("await 1"), "e")
            )
            .put(
                "asyncStatement",
                WebEvalScripts.asyncScript(WebEvalScripts.asyncStatement("var y=await 2; return y"), "f")
            )
            .put("pollNumber", WebEvalScripts.poll("e"))
            .put("pollStatement", WebEvalScripts.poll("f"))
            .put("pollGone", WebEvalScripts.poll("e"))
            .put(
                "fetch",
                WebEvalScripts.fetchScript(
                    token = "g",
                    urlLiteral = "\"/api\"",
                    methodLiteral = "\"GET\"",
                    headersLiteral = "{}",
                    bodyLiteral = "null",
                    maxChars = 1000,
                )
            )
            .put("pollFetch", WebEvalScripts.poll("g"))
            .put("conditionTrue", WebEvalScripts.condition("1===1"))
            .put("conditionFalse", WebEvalScripts.condition("1===2"))
            .put(
                "conditionMissing",
                WebEvalScripts.conditionSelector(
                    WebSelector.pickJs(WebSelector.Kind.Css("#nope"))
                )
            )
            .put(
                "conditionError",
                WebEvalScripts.condition("(function(){throw new Error('bad')})()")
            )

        val dir = File(System.getProperty("java.io.tmpdir"), "termlou-eval-semantics")
        dir.mkdirs()
        val scriptsFile = File(dir, "scripts.json")
        scriptsFile.writeText(scripts.toString())
        val harness = File(dir, "harness.js")
        harness.writeText(HARNESS_JS)

        val out = run(node!!, harness.absolutePath, scriptsFile.absolutePath)
        assertTrue("真引擎语义检查失败：\n$out", out.contains("ALL_OK"))
    }

    private fun run(node: String, vararg args: String): String {
        val cmd = mutableListOf(node)
        cmd += args
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return text
    }

    /** 找 node（Windows 上是 node.exe）。**找不到就是找不到**，不猜别的引擎。 */
    private fun findNode(): String? {
        val candidates = listOf(
            "node", "node.exe",
            "C:\\Program Files\\nodejs\\node.exe",
            "/usr/local/bin/node", "/usr/bin/node"
        )
        for (c in candidates) {
            val r = runCatching {
                val p = ProcessBuilder(c, "--version").redirectErrorStream(true).start()
                val ok = p.waitFor() == 0
                p.inputStream.close()
                ok
            }.getOrDefault(false)
            if (r) return c
        }
        return null
    }

    companion object {
        private val HARNESS_JS = """
            const fs = require('fs');
            const vm = require('vm');
            global.window = global;
            global.document = {
              title: 'TermLou',
              querySelector: function () { return null; }
            };
            global.fetch = function () {
              return Promise.resolve({
                status: 200,
                redirected: false,
                headers: { get: function () { return 'application/json'; } },
                text: function () { return Promise.resolve('{\"a\":1}'); }
              });
            };
            const S = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
            function run(k) { return vm.runInThisContext(S[k]); }
            function env(k) {
              const r = run(k);
              if (typeof r !== 'string') throw new Error(k + ' did not return a string: ' + r);
              return JSON.parse(r);
            }
            let out = [];
            let bad = 0;
            function check(name, fn) {
              try { fn(); out.push('OK   ' + name); }
              catch (e) { bad++; out.push('FAIL ' + name + ' -> ' + e.message); }
            }
            check('sync title', function () {
              const e = env('title');
              if (!e.ok || e.t !== 'string' || e.s !== 'TermLou') throw new Error(JSON.stringify(e));
            });
            check('sync error envelope', function () {
              const e = env('error');
              if (e.ok !== false || e.name !== 'Error' || e.message !== 'boom') {
                throw new Error(JSON.stringify(e));
              }
              if (typeof e.stack !== 'string' || e.stack.length === 0) {
                throw new Error('stack missing');
              }
            });
            check('undefined stays undefined', function () {
              const e = env('undefinedValue');
              if (!e.ok || e.t !== 'undefined') throw new Error(JSON.stringify(e));
            });
            check('statement return works', function () {
              const e = env('statement');
              if (!e.ok || e.t !== 'number' || e.n !== 2) throw new Error(JSON.stringify(e));
            });
            check('waitfor true', function () {
              if (String(run('conditionTrue')) !== '1') throw new Error('expected 1');
            });
            check('waitfor false', function () {
              if (String(run('conditionFalse')) !== '0') throw new Error('expected 0');
            });
            check('waitfor missing selector keeps waiting', function () {
              if (String(run('conditionMissing')) !== '0') throw new Error('expected 0');
            });
            check('waitfor runtime error is reported', function () {
              const v = run('conditionError');
              if (v.indexOf('E:') !== 0) throw new Error('expected E:..., got ' + v);
            });
            check('async wrappers report pending synchronously', function () {
              if (run('asyncNumber') !== '__TERMLOU_PENDING__') throw new Error('number not pending');
              if (run('asyncStatement') !== '__TERMLOU_PENDING__') throw new Error('statement not pending');
              if (run('fetch') !== '__TERMLOU_PENDING__') throw new Error('fetch not pending');
            });
            setTimeout(function () {
              check('top-level await lands', function () {
                const e = env('pollNumber');
                if (!e.ok || e.t !== 'number' || e.n !== 1) throw new Error(JSON.stringify(e));
              });
              check('async statement await lands', function () {
                const e = env('pollStatement');
                if (!e.ok || e.t !== 'number' || e.n !== 2) throw new Error(JSON.stringify(e));
              });
              check('fetch returns status and body', function () {
                const e = env('pollFetch');
                if (!e.ok) throw new Error(JSON.stringify(e));
                const p = JSON.parse(e.j);
                if (p.status !== 200 || p.body !== '{\"a\":1}') throw new Error(e.j);
              });
              check('slot is gone after polling', function () {
                if (run('pollGone') !== '__TERMLOU_GONE__') throw new Error('slot still there');
              });
              console.log(out.join('\n'));
              console.log(bad === 0 ? 'ALL_OK' : 'FAILED ' + bad);
              process.exitCode = bad === 0 ? 0 : 1;
            }, 120);
        """.trimIndent()
    }
}
