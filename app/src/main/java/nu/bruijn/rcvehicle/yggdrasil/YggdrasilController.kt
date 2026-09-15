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

    fun start(context: Context, peers: List<String>) {
        val intent = Intent(context, YggdrasilVpnService::class.java).apply {
            action = YggdrasilVpnService.ACTION_START
            putExtra(YggdrasilVpnService.EXTRA_PEERS, peers.toTypedArray())
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
     * this rather than assuming [start] returning means it's ready. */
    fun address(): String? = YggdrasilVpnService.runningAddress
}
