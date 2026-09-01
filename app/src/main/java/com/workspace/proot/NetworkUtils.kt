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
}
