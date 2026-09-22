package nu.bruijn.rcvehicle.yggdrasil

import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * Thin front door onto [YggdrasilVpnService] - the only thing MainActivity/
 * VehicleActivity should touch directly, so the service's own Intent-action/
 * extra-key details stay private to this package.
 */
object YggdrasilController {
    /** Null means consent is already granted (or this app already holds it from a
     * prior run) - go straight to [start]. Non-null must be launched for a result
     * via an Activity (VpnService.prepare()'s contract) before calling [start]. */
    fun prepareIntent(context: Context): Intent? = VpnService.prepare(context)

    /** [candidates] is a pool, not a fixed peer list - the service measures and
     * auto-selects the best one, and keeps re-evaluating for as long as it runs.
     * See YggdrasilVpnService/PeerMonitor's docs. */
    fun start(context: Context, candidates: List<String>) {
        val intent = Intent(context, YggdrasilVpnService::class.java).apply {
            action = YggdrasilVpnService.ACTION_START
            putExtra(YggdrasilVpnService.EXTRA_PEERS, candidates.toTypedArray())
        }
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, YggdrasilVpnService::class.java).apply {
            action = YggdrasilVpnService.ACTION_STOP
        }
        context.startService(intent)
    }

    /** Null until the node has actually come up and negotiated an address - poll
     * this rather than assuming [start] returning means it's ready. Falls back
     * to [ExternalYggdrasilDetector] so this still resolves when MainActivity
     * deliberately skipped [start] because some other app (the standalone
     * Yggdrasil app, most likely) already provides mesh connectivity. */
    fun address(): String? = YggdrasilVpnService.runningAddress ?: ExternalYggdrasilDetector.currentAddress()

    /** The one candidate the mesh is currently actually connected through, or
     * null if none is (yet). */
    fun activePeer(): String? = YggdrasilVpnService.activePeer

    /** See YggdrasilVpnService.lastConnectAtMs's doc - a "session epoch" for
     * detecting a reconnect (not just the initial connect) that stale sockets
     * opened against the previous session need to react to. */
    fun lastConnectAtMs(): Long = YggdrasilVpnService.lastConnectAtMs

    /** Latest latency-probe results across the whole candidate pool, refreshed
     * on PeerMonitor's own cadence - not re-measured on demand by calling this. */
    fun peerStatuses(): List<PeerLatencyProbe.Status> = YggdrasilVpnService.peerStatuses

    /** Current node's private key, hex-encoded - null if no identity exists yet
     * on this install. See YggdrasilVpnService.currentPrivateKeyHex()'s doc. */
    fun currentPrivateKeyHex(context: Context): String? = YggdrasilVpnService.currentPrivateKeyHex(context)

    /** Overwrites the persisted identity with [hex] - false if it's not a
     * well-formed key. Caller must [stop] first if a session is currently
     * running, then [start] again for the new identity to take effect - see
     * YggdrasilVpnService.setPrivateKeyHex()'s doc. */
    fun setPrivateKeyHex(context: Context, hex: String): Boolean = YggdrasilVpnService.setPrivateKeyHex(context, hex)
}
