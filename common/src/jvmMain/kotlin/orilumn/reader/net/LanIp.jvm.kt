package orilumn.reader.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * S35 `lanIpv4` 的 JVM 系 actual：android 与桌面共用（`androidMain.dependsOn(jvmMain)`）。
 */
actual fun lanIpv4(): String? {
    runCatching {
        java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { ni ->
            if (ni.isUp && !ni.isLoopback) {
                java.util.Collections.list(ni.inetAddresses).forEach { a ->
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val h = a.hostAddress
                        if (h != null && !h.startsWith("127.")) return h
                    }
                }
            }
        }
    }
    return null
}
