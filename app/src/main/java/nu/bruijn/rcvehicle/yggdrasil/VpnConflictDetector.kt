package nu.bruijn.rcvehicle.yggdrasil

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Android only ever allows one VpnService-backed tunnel to be the system's
 * active VPN at a time - starting ours forcibly tears down whatever else
 * (WireGuard, a corporate VPN app, etc.) currently holds that slot, with no
 * prompt from the OS. Confirmed on real hardware: a phone already on
 * WireGuard silently became unreachable over it once MeshDrive's embedded
 * Yggdrasil took over.
 */
object VpnConflictDetector {
    /** True if some OTHER app's VPN is currently the active one - i.e. not
     * our own already-running Yggdrasil session (which also shows up as a
     * VPN-transport network, but starting "again" onto our own tunnel isn't
     * a conflict). Call this before [YggdrasilController.start]. */
    fun isForeignVpnActive(context: Context): Boolean {
        if (YggdrasilController.address() != null) return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        // Checking cm.allNetworks for a VPN entry isn't reliable - Android's
        // privacy rules mean an unprivileged app isn't guaranteed visibility
        // into another app's VPN network that way. Checking OUR OWN active
        // network's capabilities is: if some other VPN (WireGuard etc.) is
        // routing the device's traffic, this app's own traffic is routed
        // through it too, so its active network shows TRANSPORT_VPN.
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}
