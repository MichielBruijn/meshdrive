package nu.bruijn.rcvehicle.common

import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * Finds this device's own Yggdrasil address by scanning interfaces for one in the
 * 200::/7 range (plan section 2's pairing step: vehicle mode shows this so the
 * controller can be told the address once, manually, with no discovery protocol).
 */
object YggdrasilAddress {
    fun detect(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull()
            ?: return null
        for (iface in interfaces) {
            val addresses = runCatching { iface.inetAddresses?.toList() }.getOrNull() ?: continue
            for (addr in addresses) {
                if (addr is Inet6Address && isYggdrasilRange(addr)) {
                    return addr.hostAddress?.substringBefore('%') // strip any %scope-id suffix
                }
            }
        }
        return null
    }

    /** 200::/7 means the first 7 bits are 0000001 - i.e. the first byte is 0x02 or 0x03. */
    private fun isYggdrasilRange(addr: Inet6Address): Boolean {
        val firstByte = addr.address[0].toInt() and 0xFF
        return firstByte == 0x02 || firstByte == 0x03
    }
}
