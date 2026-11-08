package com.workspace.proot

/**
 * 浏览器 UA 处理（5.11.0）。
 *
 * ## 为什么需要它
 *
 * Android WebView 的默认 UA 带两个会被网站当成"内嵌 WebView"的标记：
 *
 * ```text
 * Mozilla/5.0 (Linux; Android 13; …; wv)
 * AppleWebKit/537.36 (KHTML, like Gecko)
 * Version/4.0 Chrome/139.0.0.0 Mobile Safari/537.36
 * ```
 *
 * - `; wv`：WebView 标记，很多站点据此降级、拦截或要求"用浏览器打开"；
 * - `Version/4.0`：旧版浏览器标记，部分站点会因此给简化页。
 *
 * 本浏览器是给 Linux 侧 AI agent 用的，可访问性优先。这里从系统默认 UA
 * **派生**一个通用移动 Chrome UA：只去掉上面两个标记，Chrome 版本继续
 * 使用真实 WebView 引擎版本，不硬编码一个假版本（UA 与 `Sec-CH-UA`
 * 对不上反而会让站点判断错乱）。
 *
 * 纯字符串逻辑，不依赖 Android，可单测。
 */
object WebUserAgent {

    /**
     * 从 WebView 默认 UA 派生通用移动 Chrome UA。
     *
     * - 去掉 `; wv`（含分号前后可能的空白）；
     * - 去掉 ` Version/4.0`；
     * - 其余原样保留：Android 版本、机型、Chrome 主版本、`Mobile Safari` 等。
     *
     * 已经是干净 UA 时原样返回（幂等）。空串也原样返回，调用方据此决定
     * 是否真的写入 `WebSettings.userAgentString`。
     */
    fun genericMobile(defaultUa: String): String {
        if (defaultUa.isBlank()) return defaultUa
        return defaultUa
            .replace(Regex(""";\s*wv\b"""), "")
            .replace(Regex("""\s+Version/4\.0\b"""), "")
            .trim()
    }
}
