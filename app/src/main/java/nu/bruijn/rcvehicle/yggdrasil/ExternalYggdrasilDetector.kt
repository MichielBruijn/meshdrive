package nu.bruijn.rcvehicle.yggdrasil

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * Detects an Yggdrasil address already present on the device from a source
 * other than our own [YggdrasilVpnService] - in practice, the standalone
 * eu.neilalexander.yggdrasil app, installed and running side by side with
 * MeshDrive as a debug-access path independent of it. Both a real Yggdrasil
 * "Address" (0x02-prefixed /128) and its paired "Subnet" (0x03-prefixed /64,
 * used for routing a LAN through the node - see the addressing writeup at
 * yggdrasil-network.github.io) share the same 200::/7 block, so either prefix
 * byte counts here.
 *
 * Only ever meaningful to call BEFORE starting our own YggdrasilVpnService:
 * once ours is running too, its own tun interface would also show up here,
 * indistinguishable from an external one by address shape alone. MainActivity
 * only calls this pre-start, in [nu.bruijn.rcvehicle.yggdrasil.YggdrasilController.address]'s
 * fallback role it's harmless either way since our own runningAddress is
 * always checked first.
 *
 * Per direct request: MeshDrive must keep working with nothing else installed
 * - this is purely an opportunistic shortcut, never a requirement. Finding
 * nothing here just means "start our own embedded instance," today's only
 * behavior.
 */
object ExternalYggdrasilDetector {
    fun currentAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet6Address>()
            .firstOrNull { addr ->
                val first = addr.address[0].toInt() and 0xFF
                (first == 0x02 || first == 0x03) && !addr.isLinkLocalAddress
            }
            ?.let { formatAddress(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * A stable id for whatever the OS's current active network object is - not
     * this device's Yggdrasil address, and deliberately not tied to any
     * particular app owning it (works the same whether that's MeshDrive's own
     * embedded YggdrasilVpnService or the standalone Android app). Changes
     * whenever the underlying network is torn down and a new one takes its
     * place, even if it ends up being "the same" VPN by address a moment
     * later - a UDP socket opened before the change can be left pointed at
     * the since-destroyed one (see VideoSender.refreshSocket()'s doc), so
     * callers holding such sockets poll this and refresh them on a change
     * rather than trusting Yggdrasil's own "Connected" status, which reports
     * fine again well before this settles. Null if there's currently no
     * active network at all.
     */
    fun currentNetworkHandle(context: Context): Long? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return cm.activeNetwork?.networkHandle
    }

    // Inet6Address.hostAddress can come back with a "%iface" scope suffix or
    // (on some Android versions) without the RFC 5952 canonical zero-
    // compression MeshDrive's own address formatting elsewhere relies on -
    // getHostAddress() already does the latter correctly, just strip a scope
    // suffix if present.
    private fun formatAddress(addr: Inet6Address): String {
        return addr.hostAddress?.substringBefore('%') ?: ""
    }
}
