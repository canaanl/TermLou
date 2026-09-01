package com.workspace.proot

/**
 * 本次会话内从 DNS 应答解析出的 域名↔IP 映射（本地、不上网）。
 * 用于：连接行显示域名、域名屏蔽展开为 IP、IP 反查域名。
 */
object DnsMap {
    private val lock = Any()
    private val ipToDomain = HashMap<String, String>()
    private val domainToIps = HashMap<String, MutableSet<String>>()

    fun record(domain: String, ip: String) {
        val d = domain.trim().lowercase().removeSuffix(".")
        if (d.isEmpty()) return
        synchronized(lock) {
            domainToIps.getOrPut(d) { mutableSetOf() }.add(ip)
            ipToDomain[ip] = d
        }
    }

    fun domainOf(ip: String): String? = synchronized(lock) { ipToDomain[ip] }

    /** 会话内全部 (域名, IP) 映射，用于域名规则反查 IP。 */
    fun entries(): List<Pair<String, String>> = synchronized(lock) {
        ipToDomain.map { Pair(it.value, it.key) }
    }

    fun ipsOf(domain: String): Set<String> {
        val d = domain.trim().lowercase().removeSuffix(".")
        if (d.isEmpty()) return emptySet()
        return synchronized(lock) { domainToIps[d]?.toSet() ?: emptySet() }
    }

    fun clear() = synchronized(lock) {
        ipToDomain.clear()
        domainToIps.clear()
    }
}
