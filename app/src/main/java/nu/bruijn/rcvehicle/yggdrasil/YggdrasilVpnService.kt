package nu.bruijn.rcvehicle.yggdrasil

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import mobile.Mobile
import mobile.Yggdrasil
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Runs an embedded Yggdrasil mesh node in-process instead of relying on the
 * separate eu.neilalexander.yggdrasil app - per direct request, for control over
 * peer/node selection and one less app to install/manage. Ported from (and
 * structurally very close to) that same app's own PacketTunnelProvider.kt - it's
 * the reference implementation for exactly this, no reason to redesign the
 * reader/writer/updater thread shape that already works there.
 *
 * The actual mesh implementation (libs/yggdrasil-go, a git submodule) is bound
 * into app/libs/yggdrasil.aar via scripts/build_yggdrasil_aar.sh (gomobile bind
 * against yggdrasil-go's own contrib/mobile package - the same package the
 * official app binds) - not something this class talks to directly beyond the
 * mobile.Yggdrasil/mobile.Mobile Java API that produces.
 *
 * Identity (PrivateKey) persists across restarts in a config file under
 * filesDir - generated once, then only its "Peers" field gets rewritten each
 * time the mesh (re)connects (see [buildConfig]). Losing that file means losing
 * this node's Yggdrasil address, so it's deliberately never regenerated once it
 * exists.
 *
 * Callers pass a *candidate pool* of peer URIs via the START intent's
 * [EXTRA_PEERS] - not a fixed peer list. [PeerMonitor] measures reachability/
 * latency across the pool on a background thread and keeps the mesh connected
 * through whichever single candidate currently looks best, reconnecting when
 * the active one drops or another pulls meaningfully ahead (see its doc for the
 * exact thresholds). Deliberately only ever actually peers with ONE candidate
 * at a time rather than all of them - staying connected to a whole pool would
 * quietly change this node's exposure/relay posture on the wider mesh, which is
 * a decision for whoever built the candidate pool (vehicle vs. controller
 * privacy needs), not something this class should do as a side effect of
 * "picking the best one."
 */
class YggdrasilVpnService : VpnService() {
    companion object {
        const val ACTION_START = "nu.bruijn.rcvehicle.yggdrasil.START"
        const val ACTION_STOP = "nu.bruijn.rcvehicle.yggdrasil.STOP"
        const val EXTRA_PEERS = "peers" // String[] - candidate pool, best is auto-selected

        private const val TAG = "YggdrasilVpnService"
        private const val CHANNEL_ID = "yggdrasil_vpn"
        private const val NOTIFICATION_ID = 2000

        // Tight retry while nothing's connected yet (first launch, or every
        // candidate just failed) vs. a relaxed steady-state cadence once
        // something's up - no reason to hammer candidates every few seconds once
        // one of them is already working fine.
        private const val RETRY_INTERVAL_MS = 5_000L
        private const val STEADY_INTERVAL_MS = 60_000L

        // ~15-20s of every single candidate being unreachable, every round -
        // see evaluate()'s doc for what this triggers (dropping a possibly-
        // degraded live-fetched peer cache). Long enough that a routine
        // measurement blip or a few-second network hiccup can't trip it, short
        // enough to self-heal within a demo rather than needing the app's data
        // cleared by hand.
        private const val TOTAL_FAILURE_CACHE_INVALIDATION_THRESHOLD = 3

        // A candidate has to beat the active one by more than this to be worth a
        // reconnect - otherwise routine measurement jitter (a few ms either way
        // on an already-fine link) would cause pointless reconnect churn, each of
        // which costs ControlLink a ~1-2s failsafe blip. 30% is a somewhat
        // arbitrary but deliberately conservative bar: "meaningfully better," not
        // "marginally better."
        private const val SWITCH_IMPROVEMENT_FACTOR = 0.7

        // Minimum gap between wifi-triggered forced reconnects - a flapping wifi
        // signal (weak AP, walking out of range) can fire onAvailable/onLost
        // repeatedly within seconds; without this, each flap would cost another
        // ~1-2s ControlLink failsafe blip on top of the last one.
        private const val WIFI_RECONNECT_DEBOUNCE_MS = 10_000L

        // How long after ANY successful connect (initial, a latency-triggered
        // switch, or a wifi-triggered redial) to ignore further reconnect
        // triggers entirely, whatever caused them. Confirmed on real hardware as
        // a real problem without this: ConnectivityManager.registerNetworkCallback()
        // always replays the *current* matching network(s) immediately on
        // registration - not a real "wifi changed" event - and capability
        // updates keep trickling in for a few seconds while a connection
        // settles, so a phone that was already on wifi when the service started
        // would get a spurious reconnect trigger seconds after its first
        // successful connect, landing while PeerMonitor's normal measurement
        // pass was *also* about to run and switch peers on nothing more than
        // routine latency-measurement noise between two similarly-good
        // candidates. Observed as a real switch 3 seconds after initial
        // connect, tcp://vpn.itrus.su:7991 (46ms) -> tcp://cirno.nadeko.net:44441
        // (15ms) - not a wrong choice exactly, but not worth the reconnect cost
        // for noise this close, this soon.
        private const val MIN_SETTLE_MS = 15_000L

        // Not a binder connection: callers (YggdrasilController) only ever need a
        // cheap "is it up, what's its address/peer status" poll from another
        // Activity, nowhere near hot enough to justify bindService()'s ceremony.
        @Volatile private var instance: YggdrasilVpnService? = null

        val runningAddress: String? get() = instance?.addressString
        /** Our own service is up (mesh connected or still retrying). */
        val isRunning: Boolean get() = instance?.running?.get() == true
        val activePeer: String? get() = instance?.activePeerUri
        val peerStatuses: List<PeerLatencyProbe.Status> get() = instance?.latestStatuses ?: emptyList()

        /** Timestamp of the most recent successful connectMesh() - changes on
         * every reconnect, not just the first one (a session dying and being
         * re-established after e.g. another app grabbing the OS VPN slot bumps
         * it too). Callers holding UDP sockets opened against the previous
         * session's routing (VideoSender/AudioSender/ControlLink) use a change
         * here as the signal to refresh them - see VideoSender.refreshSocket()'s
         * doc for why the session itself reporting "connected" again isn't
         * enough on its own. 0L if never connected yet. */
        val lastConnectAtMs: Long get() = instance?.lastConnectAtMs ?: 0L

        private const val CONFIG_FILE_NAME = "yggdrasil-config.json"

        /** Path to the persisted identity/config file - a plain function of [Context], not this
         * (possibly not-running) service instance, so debug UI can read/edit the identity
         * without needing a live session. Same file the instance-side buildConfig() reads. */
        fun configFile(context: Context) = File(context.filesDir, CONFIG_FILE_NAME)

        /** Current node's private key, hex-encoded (128 hex chars - ed25519.PrivateKey's 64 raw
         * bytes, config.go's KeyBytes.MarshalJSON()) - null if no identity has been generated
         * yet (Yggdrasil never started on this install) or the file's unreadable/corrupt. */
        fun currentPrivateKeyHex(context: Context): String? {
            val file = configFile(context)
            if (!file.exists()) return null
            return runCatching {
                JSONObject(file.readText(Charsets.UTF_8)).optString("PrivateKey").takeIf { it.isNotEmpty() }
            }.getOrNull()
        }

        /** Overwrites the persisted identity with [hex] - generates a fresh base config first
         * if none exists yet (a fresh install that's never started Yggdrasil). Returns false
         * without writing anything if [hex] isn't a well-formed key. Takes effect on the next
         * [YggdrasilController.start] - if a session is currently running, stop it first (this
         * doesn't do that itself, same as every other config change here - see buildConfig()'s
         * doc). */
        fun setPrivateKeyHex(context: Context, hex: String): Boolean {
            val normalized = hex.trim()
            if (normalized.length != 128 || normalized.any { it.lowercaseChar() !in "0123456789abcdef" }) return false
            val file = configFile(context)
            val json = if (file.exists()) {
                JSONObject(file.readText(Charsets.UTF_8))
            } else {
                JSONObject(String(Mobile.generateConfigJSON(), Charsets.UTF_8))
            }
            json.put("PrivateKey", normalized.lowercase())
            file.writeText(json.toString(), Charsets.UTF_8)
            return true
        }
    }

    private val yggdrasil = Yggdrasil()
    private val running = AtomicBoolean() // whole-service lifecycle (foreground notification, monitor thread)
    private val meshUp = AtomicBoolean() // just the current yggdrasil+TUN session - toggles on every reconnect

    private var monitorThread: Thread? = null
    private var peerMonitor: PeerMonitor? = null
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private var tunnel: ParcelFileDescriptor? = null
    private var readerStream: FileInputStream? = null
    private var writerStream: FileOutputStream? = null

    /** Last-known address/subnet, exposed for callers that started us to poll -
     * see [YggdrasilController] for the actual read side. Null until connected. */
    @Volatile var addressString: String? = null
        private set

    @Volatile private var activePeerUri: String? = null
    @Volatile private var latestStatuses: List<PeerLatencyProbe.Status> = emptyList()
    @Volatile private var lastConnectAtMs = 0L

    // Set from the wifi NetworkCallback (a binder/connectivity thread, not
    // PeerMonitor's own), consumed only by PeerMonitor.run() - keeps every
    // actual connectMesh()/disconnectMesh() call confined to that one thread,
    // same as the existing candidate-switch path, rather than racing it.
    private val forceReconnect = AtomicBoolean()
    @Volatile private var lastWifiReconnectAtMs = 0L
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stop()
                START_NOT_STICKY
            }
            else -> {
                val candidates = intent?.getStringArrayExtra(EXTRA_PEERS)?.toList() ?: emptyList()
                start(candidates)
                START_STICKY
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        stop()
        instance = null
        super.onDestroy()
    }

    override fun onRevoke() {
        // Android calls this either when the user pulls VPN consent via system
        // Settings, or - confirmed on real hardware as the actual scenario -
        // when a DIFFERENT app establishes its own VPN, forcibly reclaiming
        // the single OS-level VPN slot (seen with Microsoft Defender's Web
        // Protection periodically doing exactly this). The previous behavior
        // here was a full stop(): kills the whole service, foreground
        // notification included, leaving MeshDrive dead until the user
        // noticed and manually reopened the app. Reuse the same
        // "session died, keep the service alive and let PeerMonitor retry"
        // handling as a pump thread dying unexpectedly (onPumpThreadExited())
        // instead - self-healing once the other app's VPN goes away again
        // (calling establish() again simply reclaims the slot back, the same
        // way this app took it from something else in the first place), no
        // full restart or user action needed. A user-initiated revoke via
        // Settings looks identical from here and gets the same retry loop -
        // acceptable, since it'll just keep quietly failing to reclaim the
        // slot until they grant consent again, rather than needing special-
        // casing to tell the two apart.
        handleMeshSessionDied()
        super.onRevoke()
    }

    private fun configFile() = configFile(this)

    /**
     * Loads the persisted config (generating+saving a fresh one, with a new
     * identity, only the very first time this ever runs), then overwrites just
     * its "Peers" field with a single-element array containing [peer] - see the
     * class doc for why identity and peer stay so separate, and why only ever
     * one peer at a time.
     */
    private fun buildConfig(peer: String): ByteArray {
        val file = configFile()
        val json = if (file.exists()) {
            JSONObject(file.readText(Charsets.UTF_8))
        } else {
            val generated = JSONObject(String(Mobile.generateConfigJSON(), Charsets.UTF_8))
            file.writeText(generated.toString(), Charsets.UTF_8)
            generated
        }
        // AdminListen/IfName off: nothing on this phone needs the admin socket or
        // a second, OS-level network interface - VpnService.Builder's TUN below is
        // the only interface this app uses.
        json.put("AdminListen", "none")
        json.put("IfName", "none")
        json.put("Peers", JSONArray(listOf(peer)))
        // Yggdrasil has no "don't relay for others" flag - per its own config.go:
        // "all network traffic will transit peer connections." A node with
        // exactly one peer connection is a dead-end leaf that nothing can route
        // *through* by definition, so pinning to a single explicit peer (above)
        // already prevents this device from carrying strangers' traffic - EXCEPT
        // the generated default also has multicast peer discovery on for every
        // interface (Beacon+Listen, matching any interface name), which would
        // silently add a second, uncontrolled peer connection the moment this
        // phone shares a LAN with any other Yggdrasil node, undoing that
        // guarantee. Emptying it out (gomobile's core only starts multicast at
        // all `if len(MulticastInterfaces) > 0`) keeps the single configured
        // peer as the only connection this node ever makes - deliberate,
        // per direct request: mobile data is metered, this app's users didn't
        // sign up to carry the wider mesh's traffic on their bill.
        json.put("MulticastInterfaces", JSONArray())
        val updated = json.toString()
        file.writeText(updated, Charsets.UTF_8)
        return updated.toByteArray(Charsets.UTF_8)
    }

    private fun notificationChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Yggdrasil mesh", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return CHANNEL_ID
    }

    private fun start(candidates: List<String>) {
        if (!running.compareAndSet(false, true)) {
            // Already running (the service outlives mode picks) - still take a changed peer
            // list, else a custom peer saved in debug never applies until the app is killed.
            // PeerMonitor then sees the active peer missing from the new pool and switches.
            val monitor = peerMonitor ?: return
            if (monitor.candidates != candidates) {
                Log.i(TAG, "peer candidates changed - re-evaluating")
                monitor.candidates = candidates
                monitorThread?.interrupt()
            }
            return
        }

        val notification = Notification.Builder(this, notificationChannel())
            .setContentTitle(getString(nu.bruijn.rcvehicle.R.string.app_name))
            .setContentText(getString(nu.bruijn.rcvehicle.R.string.yggdrasil_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        registerWifiCallback()
        val monitor = PeerMonitor(candidates)
        peerMonitor = monitor
        monitorThread = thread(name = "YggdrasilPeerMonitor") { monitor.run() }
    }

    private fun stop() {
        if (!running.compareAndSet(true, false)) return
        unregisterWifiCallback()
        monitorThread?.interrupt()
        monitorThread = null
        peerMonitor = null
        disconnectMesh()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * A live yggdrasil peer connection doesn't migrate to a better network on
     * its own - it just keeps using whatever network it originally dialed out
     * on (typically mobile data, if that's what was available when the mesh
     * first connected). Watches specifically for wifi appearing/disappearing
     * and, when it does, asks PeerMonitor to redial the current peer - a fresh
     * outbound connection follows Android's normal default-network preference
     * (wifi over metered mobile data), so this is enough to make the mesh
     * actually move onto wifi once it's available, and fall back cleanly when
     * it isn't. See PeerMonitor.run()'s [forceReconnect] handling.
     */
    private fun registerWifiCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = triggerWifiReconnect("wifi available")
            override fun onLost(network: Network) = triggerWifiReconnect("wifi lost")
        }
        wifiNetworkCallback = callback
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { Log.i(TAG, "registerNetworkCallback failed: $it") }
    }

    private fun unregisterWifiCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        wifiNetworkCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        wifiNetworkCallback = null
    }

    private fun triggerWifiReconnect(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastWifiReconnectAtMs < WIFI_RECONNECT_DEBOUNCE_MS) return
        lastWifiReconnectAtMs = now
        Log.i(TAG, "$reason - will redial current peer to reconsider network")
        forceReconnect.set(true)
        monitorThread?.interrupt()
    }

    /** Brings the mesh + TUN up on exactly [peer]. Only ever called from
     * [PeerMonitor]'s thread - never concurrently with [disconnectMesh]. */
    private fun connectMesh(peer: String): Boolean {
        try {
            yggdrasil.startJSON(buildConfig(peer))
        } catch (e: Exception) {
            Log.e(TAG, "yggdrasil startJSON failed for $peer", e)
            return false
        }

        val address = yggdrasil.addressString
        val builder = Builder()
            .addAddress(address, 7)
            .addRoute("200::", 7)
            // Tricks Android's DNS resolver into believing it has "real" IPv6, so it
            // actually issues AAAA queries at all - see bionic's getaddrinfo.c. Without
            // this, DNS lookups of Yggdrasil-only hostnames silently never happen.
            .addRoute("2000::", 128)
            .allowFamily(OsConstants.AF_INET)
            .allowBypass()
            .setBlocking(true)
            .setMtu(yggdrasil.mtu.toInt())
            .setSession(getString(nu.bruijn.rcvehicle.R.string.app_name))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val parcel = builder.establish()
        if (parcel == null || !parcel.fileDescriptor.valid()) {
            Log.e(TAG, "VpnService.Builder.establish() failed")
            runCatching { yggdrasil.stop() }
            return false
        }
        tunnel = parcel
        readerStream = FileInputStream(parcel.fileDescriptor)
        writerStream = FileOutputStream(parcel.fileDescriptor)
        meshUp.set(true)
        addressString = address
        activePeerUri = peer
        lastConnectAtMs = System.currentTimeMillis()

        // Two plain background threads, not coroutines: both loops spend their
        // whole life blocked in a synchronous read (FileInputStream.read()/
        // Yggdrasil.recvBuffer()) - a dedicated OS thread each, interrupted on
        // disconnectMesh(), is simpler and more predictable here than adapting
        // blocking Go/JNI calls to a coroutine dispatcher for no real benefit.
        readerThread = thread(name = "YggdrasilTunReader") { pumpTunToYggdrasil() }
        writerThread = thread(name = "YggdrasilTunWriter") { pumpYggdrasilToTun() }
        return true
    }

    /** Tears down the current mesh + TUN session, if any - leaves the outer
     * foreground-service/monitor-thread lifecycle untouched, so [PeerMonitor]
     * can call this to switch peers without a full service restart. Any
     * in-flight UDP control/video packets are lost for the ~1-2s this takes;
     * ControlLink's own heartbeat/failsafe (>300ms silence -> neutral) is what
     * makes that an acceptable cost for "peer got meaningfully worse" rather
     * than something this class needs to hide. */
    private fun disconnectMesh() {
        if (!meshUp.compareAndSet(true, false)) return

        try {
            yggdrasil.stop()
        } catch (_: Exception) {
        }
        addressString = null
        activePeerUri = null

        readerStream?.let { runCatching { it.close() } }
        readerStream = null
        writerStream?.let { runCatching { it.close() } }
        writerStream = null
        tunnel?.let { runCatching { it.close() } }
        tunnel = null

        // join(), not just interrupt() - confirmed on real hardware as a genuine, session-
        // corrupting bug: yggdrasil is one shared instance reused across reconnects (see its
        // field doc), not a fresh one per session, and interrupt() doesn't reliably unblock a
        // thread stuck inside a blocking Go/JNI call (yggdrasil.recvBuffer() in particular -
        // only closing the TUN fd reliably woke the reader here, per its own logged
        // "read interrupted by close()", never the interrupt itself). Abandoning these threads
        // without waiting for them to actually exit let a stale writer - still blocked in
        // recvBuffer() when this ran - wake up holding data that belongs to the *new* session
        // (started immediately after this returns) and write it through its own stale,
        // already-closed writerStream: an EIO that then tore the brand-new session down too,
        // and a "mesh session died" misattributed to the new session by the shared meshUp flag
        // (see onPumpThreadExited()'s own doc). Bounded, not indefinite - a genuinely wedged
        // native call shouldn't hang reconnects forever, but should be rare now that closing
        // the streams above already unblocks the common case.
        // Skip joining whichever of these IS the calling thread - disconnectMesh() can run from
        // either pump thread itself (via handleMeshSessionDied()), not just PeerMonitor's, and
        // a thread can't join itself.
        readerThread?.interrupt()
        readerThread?.takeIf { it != Thread.currentThread() }?.let { runCatching { it.join(1000) } }
        readerThread = null
        writerThread?.interrupt()
        writerThread?.takeIf { it != Thread.currentThread() }?.let { runCatching { it.join(1000) } }
        writerThread = null
    }

    /**
     * Keeps the mesh connected through the best of [candidates], re-measuring
     * periodically and switching when it's worth the reconnect cost. Runs for
     * as long as the outer service does (see [running]) on its own thread.
     */
    private inner class PeerMonitor(@Volatile var candidates: List<String>) {
        private var activeLatencyMs: Long? = null
        private var consecutiveTotalFailures = 0

        fun run() {
            while (running.get()) {
                val sinceConnect = System.currentTimeMillis() - lastConnectAtMs
                if (meshUp.get() && sinceConnect < MIN_SETTLE_MS) {
                    // Just connected (for any reason) - discard whatever woke us
                    // early rather than act on it, and sleep only the remaining
                    // settle time so a genuine change noticed right after it
                    // still gets picked up promptly once settled, not after a
                    // full STEADY_INTERVAL_MS. See MIN_SETTLE_MS's doc.
                    forceReconnect.set(false)
                    try {
                        Thread.sleep(MIN_SETTLE_MS - sinceConnect)
                    } catch (_: InterruptedException) {
                    }
                    continue
                }
                if (forceReconnect.compareAndSet(true, false)) {
                    // yggdrasil.retryPeersNow() (contrib/mobile/mobile.go's RetryPeersNow():
                    // "resets the peer connection timer and tries to dial them immediately"),
                    // not a full disconnectMesh()+connectMesh() cycle - the TUN interface and
                    // its pump threads never cared which physical route the peer connection
                    // takes, only the Yggdrasil core's own dial logic does. This nudges the
                    // SAME already-configured peer to redial now instead of tearing down and
                    // rebuilding the whole session (TUN fd, both pump threads) just to end up
                    // dialing the identical peer again - what "redialing $peer (network
                    // change)" used to do here. Confirmed on real hardware as strictly worse
                    // than not reconnecting at all in one case: a stale pump thread still
                    // blocked in a Go/JNI call across the teardown wrote a newer session's data
                    // into the old, already-closed TUN fd (EIO), which then tore the *new*
                    // session down too and left VpnService.Builder.establish() permanently
                    // failing - see disconnectMesh()'s own doc for the join() mitigation added
                    // for the cases that still do need a real reconnect (evaluate()'s genuine
                    // peer switches, which target a different peer/config and still need
                    // startJSON() against it, not just a redial).
                    if (meshUp.get()) {
                        Log.i(TAG, "retrying peers now (network change)")
                        runCatching { yggdrasil.retryPeersNow() }
                    } else {
                        activePeerUri?.let { peer ->
                            Log.i(TAG, "redialing $peer (network change)")
                            disconnectMesh()
                            connectMesh(peer)
                        }
                    }
                }
                val results = PeerLatencyProbe.measureAll(candidates)
                latestStatuses = results
                evaluate(results)
                val interval = if (meshUp.get()) STEADY_INTERVAL_MS else RETRY_INTERVAL_MS
                try {
                    Thread.sleep(interval)
                } catch (_: InterruptedException) {
                    // Woken early - either stop() (running is now false, the loop
                    // condition above exits next iteration) or a forced reconnect
                    // trigger (handled at the top of the next iteration) - either
                    // way, falling through and re-looping is correct, not returning.
                }
            }
        }

        private fun evaluate(results: List<PeerLatencyProbe.Status>) {
            val best = results.filter { it.latencyMs != null }.minByOrNull { it.latencyMs!! } ?: run {
                // Nothing reachable this round - if we were connected, the active
                // peer is presumably among the now-unreachable ones; tear down so
                // the app-level UI reflects reality instead of showing a stale
                // "connected" address that no longer routes anywhere.
                if (meshUp.get()) disconnectMesh()

                // Confirmed on real hardware as a real, demo-blocking bug: a
                // candidate pool that never has anything reachable (e.g. a
                // corrupted or otherwise degraded live-fetched peer cache -
                // see YggdrasilPeerListCache's own doc) looked identical to
                // "temporarily offline" from here, and kept retrying the same
                // bad pool forever - restarting the app, toggling wifi/5G,
                // none of it helped, since every fresh start just reloaded
                // the same bad candidates from disk again. Only clearing the
                // app's data (wiping that cache along with everything else)
                // actually fixed it. After several consecutive fully-dead
                // rounds - long enough that this reads as "the pool itself is
                // bad," not routine bad luck on one measurement - drop the
                // live-fetched cache so the NEXT connect attempt (this
                // session's own Retry button, or a fresh app start) falls
                // back to the known-good baked-in snapshot instead. Doesn't
                // change this session's own already-fixed candidate list.
                consecutiveTotalFailures++
                if (consecutiveTotalFailures == TOTAL_FAILURE_CACHE_INVALIDATION_THRESHOLD) {
                    Log.i(TAG, "$consecutiveTotalFailures consecutive rounds with nothing reachable - invalidating live peer cache")
                    YggdrasilPeerListCache.invalidate(this@YggdrasilVpnService)
                }
                return
            }
            consecutiveTotalFailures = 0

            val current = activePeerUri
            val shouldSwitch = when {
                current == null -> true
                results.find { it.uri == current }?.latencyMs == null -> true // current died
                best.uri != current && activeLatencyMs != null &&
                    best.latencyMs!! < activeLatencyMs!! * SWITCH_IMPROVEMENT_FACTOR -> true
                else -> false
            }

            if (shouldSwitch) {
                if (meshUp.get()) disconnectMesh()
                if (connectMesh(best.uri)) {
                    activeLatencyMs = best.latencyMs
                    Log.i(TAG, "connected via ${best.uri} (${best.latencyMs}ms)")
                } else {
                    activeLatencyMs = null
                }
            } else if (best.uri == current) {
                activeLatencyMs = best.latencyMs
            }
        }
    }

    /** TUN -> Yggdrasil: whatever the OS wants to send out over the mesh. */
    private fun pumpTunToYggdrasil() {
        val buf = ByteArray(65535)
        while (meshUp.get() && !Thread.currentThread().isInterrupted) {
            val input = readerStream ?: break
            if (!input.fd.valid()) break
            try {
                val n = input.read(buf)
                if (n > 0) yggdrasil.sendBuffer(buf, n.toLong())
            } catch (e: Exception) {
                if (meshUp.get()) Log.i(TAG, "tun->yggdrasil: $e")
                break
            }
        }
        onPumpThreadExited()
    }

    /** Yggdrasil -> TUN: whatever arrived over the mesh, headed back into Android. */
    private fun pumpYggdrasilToTun() {
        val buf = ByteArray(65535)
        while (meshUp.get() && !Thread.currentThread().isInterrupted) {
            val output = writerStream ?: break
            if (!output.fd.valid()) break
            try {
                val n = yggdrasil.recvBuffer(buf)
                if (n > 0) output.write(buf, 0, n.toInt())
            } catch (e: Exception) {
                // Same ENOBUFS-is-not-fatal caveat the reference implementation
                // notes (AdguardForAndroid#724) - a full socket buffer momentarily
                // rejecting a write isn't a reason to tear the whole tunnel down.
                if (meshUp.get() && !e.toString().contains("ENOBUFS")) {
                    Log.i(TAG, "yggdrasil->tun: $e")
                    break
                }
            }
        }
        onPumpThreadExited()
    }

    /**
     * Confirmed on real hardware as a real bug: turning wifi off out from under
     * an active session (its TCP peer connection was bound to that now-gone
     * interface) killed both pump threads via IOExceptions, but nothing ever
     * reset [meshUp] - PeerMonitor kept thinking the session was fine, since its
     * own periodic probe opens a *fresh* connection each time (which happily
     * succeeds over whatever network is current, e.g. cellular after wifi
     * drops) rather than checking the specific dead session. The mesh never
     * reconnected on its own; "controller signal lost, doesn't come back."
     *
     * Called from whichever pump thread notices the break first - disconnectMesh()
     * is idempotent (guarded by meshUp's compareAndSet), so if both reader and
     * writer die around the same moment (the common case - the same network
     * loss affects both), only the first call here actually tears anything down.
     * The `meshUp.get()` check skips this entirely when the thread instead
     * exited because disconnectMesh() was already called deliberately elsewhere
     * (a peer switch, a wifi-triggered redial, or stop()) - that path already
     * has its own reconnect handling, this would just be redundant.
     */
    private fun onPumpThreadExited() {
        if (!meshUp.get()) return
        handleMeshSessionDied()
    }

    /** Tears the current session down (if still up) and wakes PeerMonitor to
     * retry immediately rather than waiting out its current sleep - shared by
     * both ways a live session can die from under this service: the tun pump
     * threads hitting an IOException ([onPumpThreadExited]) and Android
     * revoking the VPN grant because another app took the OS's single VPN
     * slot ([onRevoke]). Deliberately does NOT stop the outer service/
     * notification/monitor thread - this is meant to self-heal, not give up. */
    private fun handleMeshSessionDied() {
        Log.i(TAG, "mesh session died, forcing reconnect")
        if (meshUp.get()) disconnectMesh()
        forceReconnect.set(true)
        monitorThread?.interrupt()
    }
}
