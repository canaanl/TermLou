package com.workspace.proot

/**
 * Whether the page that actually landed is **usable** (5.9.4).
 *
 * ## Why this has to exist
 *
 * When a navigation fails (timeout, DNS failure, refused), the WebView loads its own
 * internal error page (`chrome-error://chromewebdata/`) — and **that error page still
 * fires `onPageFinished`**. So a plain `ready` flag reports success while the agent is
 * actually holding a `net::ERR_TIMED_OUT` page. That is exactly what happened on device:
 * a click "succeeded", reported `ready:true`, and the landed page was an error page.
 *
 * So readiness is split in two:
 *  - `ready`  = the page event fired (unchanged meaning, keeps old callers working)
 *  - `usable` = the page can actually be worked with (new field)
 *
 * Detection has two layers, used together:
 *  1. the error code recorded by `WebViewClient.onReceivedError` (most reliable);
 *  2. an in-page probe: `location.protocol` is `chrome-error:`, or the body carries a
 *     known `ERR_*` code.
 */
object WebPageUsable {

    /** WebView's own error pages use this scheme. */
    const val ERROR_PROTOCOL = "chrome-error:"

    /** Verdict. */
    sealed class Verdict {
        /** The page is usable. */
        data class Usable(val title: String, val protocol: String) : Verdict()

        /** Landed on WebView's internal error page. */
        data class ErrorPage(val url: String) : Verdict()

        /** Page loaded but its content carries a network error code. */
        data class ErrorContent(val code: String) : Verdict()
    }

    /**
     * Error codes the WebView can actually report.
     *
     * Only these are recognised: a page whose body merely mentions an identifier like
     * `ERR_WHATEVER` must not be mistaken for an error page. Better to miss a detection
     * than to flag a working page.
     */
    private val KNOWN_CODES = setOf(
        "ERR_TIMED_OUT", "ERR_NAME_NOT_RESOLVED", "ERR_CONNECTION_REFUSED",
        "ERR_INTERNET_DISCONNECTED", "ERR_ACCESS_DENIED", "ERR_UNSUPPORTED_SCHEME",
        "ERR_ADDRESS_UNREACHABLE", "ERR_CONNECTION_RESET", "ERR_CONNECTION_CLOSED",
        "ERR_CONNECTION_FAILED", "ERR_UNKNOWN_URL_SCHEME", "ERR_QUIC_PROTOCOL_ERROR",
        "ERR_CACHE_MISS", "ERR_BLOCKED_BY_CLIENT", "ERR_SSL_PROTOCOL_ERROR",
        "ERR_SSL_HANDSHAKE", "ERR_BAD_RESPONSE", "ERR_INVALID_URL", "ERR_FILE_NOT_FOUND",
        "ERR_FILE_NO_PERMISSION", "ERR_TOO_MANY_REQUESTS", "ERR_REDIRECT_LOOP",
        "ERR_EMPTY_RESPONSE", "ERR_IO", "ERR_FAILED", "ERR_INVALID_REDIRECT",
        "ERR_UNEXPECTED", "ERR_CONTENT_DECODING_FAILED", "ERR_OPERATION_FAILED"
    )

    /**
     * Decide whether the landed page is usable.
     *
     * @param protocol current `location.protocol` (empty string if unavailable)
     * @param url current `location.href`
     * @param title `document.title`
     * @param text page body text (a few hundred chars is enough)
     */
    fun judge(protocol: String, url: String, title: String, text: String): Verdict {
        // 1) WebView's own error page: the scheme itself is the proof
        if (protocol.startsWith(ERROR_PROTOCOL)) return Verdict.ErrorPage(url)
        // 2) fallback: some error pages keep the original url, so look at the content
        val code = errorCodeIn(text) ?: errorCodeIn(title)
        if (code != null) return Verdict.ErrorContent(code)
        return Verdict.Usable(title, protocol)
    }

    /**
     * Find a WebView error code inside text. Returns the first match that is a
     * **known** code, otherwise null.
     */
    fun errorCodeIn(text: String): String? {
        if (text.isEmpty()) return null
        var from = 0
        while (true) {
            val idx = text.indexOf("ERR_", from)
            if (idx < 0) return null
            var end = idx
            while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
            val candidate = text.substring(idx, end)
            if (candidate in KNOWN_CODES) return candidate
            from = idx + 4
        }
    }

    /**
     * Turn an `onReceivedError` code into a human sentence.
     *
     * The suffix after `net::ERR_` is kept in English on purpose: agents match on it
     * reliably, and translations of error codes are not a thing worth maintaining.
     */
    fun describeErrorCode(code: String?): String {
        val c = code?.trim()?.removePrefix("net::")?.removePrefix("ERROR_")?.uppercase().orEmpty()
        val net = when (c) {
            "TIMED_OUT", "TIMEOUT" -> "net::ERR_TIMED_OUT (load timed out)"
            "HOST_LOOKUP" -> "net::ERR_NAME_NOT_RESOLVED (DNS lookup failed)"
            "CONNECT" -> "net::ERR_CONNECTION_REFUSED (connection refused)"
            "INTERNET_DISCONNECTED" -> "net::ERR_INTERNET_DISCONNECTED (offline)"
            "ACCESS_DENIED" -> "net::ERR_ACCESS_DENIED (access denied)"
            "UNSUPPORTED_SCHEME" -> "net::ERR_UNSUPPORTED_SCHEME (scheme not supported)"
            "ADDRESS_UNREACHABLE" -> "net::ERR_ADDRESS_UNREACHABLE (address unreachable)"
            "IO" -> "net::ERR_IO (network I/O failed)"
            "UNKNOWN" -> "net::ERR_UNKNOWN (unknown error)"
            "" -> "unknown error"
            else -> "net::$c"
        }
        // 中文说明放在末尾：agent 按 net:: 前缀匹配即可，人也能看懂
        return net + " — 加载失败" + (if (c.isEmpty()) "" else "（$c）")
    }
}
