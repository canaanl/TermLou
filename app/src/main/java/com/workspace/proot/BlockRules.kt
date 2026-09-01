package com.workspace.proot

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 会话级屏蔽规则：停止抓包即清空（由 NetVpnService.teardown 调用 clear）。
 * blockedIps: 精确 IP 屏蔽
 * blockedDomains: 域名后缀屏蔽（覆盖该域名及其所有子域）
 */
object BlockRules {
    private val blockedIps = CopyOnWriteArrayList<String>()
    private val blockedDomains = CopyOnWriteArrayList<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun subscribe(l: () -> Unit) {
        listeners.add(l)
    }

    fun unsubscribe(l: () -> Unit) {
        listeners.remove(l)
    }

    fun clear() {
        blockedIps.clear()
        blockedDomains.clear()
        notifyChanged()
    }

    fun blockedIpsSnapshot(): List<String> = blockedIps.toList()

    fun blockedDomainsSnapshot(): List<String> = blockedDomains.toList()

    fun isBlockedIp(ip: String): Boolean = blockedIps.contains(ip)

    fun isBlockedDomain(domain: String): Boolean {
        val d = normalize(domain) ?: return false
        return blockedDomains.any { d == it || d.endsWith(".$it") }
    }

    fun isBlocked(ip: String, domain: String?): Boolean {
        if (blockedIps.contains(ip)) return true
        val d = domain?.let { normalize(it) } ?: return false
        return blockedDomains.any { d == it || d.endsWith(".$it") }
    }

    fun blockedDomainFor(domain: String): String? {
        val d = normalize(domain) ?: return null
        return blockedDomains.firstOrNull { d == it || d.endsWith(".$it") }
    }

    fun blockedDomainForIp(ip: String): String? {
        for ((domain, mappedIp) in DnsMap.entries()) {
            if (mappedIp != ip) continue
            val rule = blockedDomains.firstOrNull { d -> domain == d || domain.endsWith(".$d") }
            if (rule != null) return rule
        }
        return null
    }

    fun addBlockIp(ip: String) {
        if (blockedIps.contains(ip)) return
        blockedIps.add(ip)
        notifyChanged()
    }

    fun addBlockDomain(domain: String) {
        val d = normalize(domain) ?: return
        if (blockedDomains.contains(d)) return
        blockedDomains.add(d)
        notifyChanged()
    }

    fun removeBlockIp(ip: String) {
        if (blockedIps.remove(ip)) notifyChanged()
    }

    fun removeBlockDomain(domain: String) {
        val d = normalize(domain) ?: return
        if (blockedDomains.remove(d)) notifyChanged()
    }

    private fun normalize(domain: String): String? {
        val t = domain.trim().lowercase().removePrefix("*.").removeSuffix(".")
        return if (t.isEmpty()) null else t
    }

    private fun notifyChanged() {
        listeners.forEach { it.invoke() }
    }
}
