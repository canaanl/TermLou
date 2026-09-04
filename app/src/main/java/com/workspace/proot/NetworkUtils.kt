package com.workspace.proot

import android.content.Context
import android.net.ConnectivityManager

object NetworkUtils {
    fun getDnsServers(context: Context): List<String> {
        val servers = mutableListOf<String>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.activeNetwork?.let { net ->
                cm.getLinkProperties(net)?.dnsServers?.forEach { addr ->
                    addr.hostAddress?.let { servers.add(it) }
                }
            }
        } catch (_: Exception) {}
        return servers
    }

    fun getLanIp(context: Context): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val props = cm.activeNetwork?.let { cm.getLinkProperties(it) }
            props?.linkAddresses?.forEach { la ->
                val h = la.address?.hostAddress ?: return@forEach
                if (h.contains(".") && !h.startsWith("127.") && !h.contains("%")) return h
            }
        } catch (_: Exception) {}
        try {
            val ifs = java.net.NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val h = addrs.nextElement()?.hostAddress ?: continue
                    if (h.contains(".") && !h.startsWith("127.") && !h.contains("%")) return h
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
