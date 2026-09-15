package nu.bruijn.rcvehicle.yggdrasil

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
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
 * filesDir - generated once, then only its "Peers" field gets rewritten before
 * each start (see [buildConfig]). Losing that file means losing this node's
 * Yggdrasil address, so it's deliberately never regenerated once it exists.
 *
 * Peer selection itself (which servers to list in "Peers", vehicle vs. controller
 * needing different privacy postures) is NOT decided here - callers pass the
 * peer URIs to use via the START intent's [EXTRA_PEERS], keeping this class
 * itself policy-free.
 */
class YggdrasilVpnService : VpnService() {
    companion object {
        const val ACTION_START = "nu.bruijn.rcvehicle.yggdrasil.START"
        const val ACTION_STOP = "nu.bruijn.rcvehicle.yggdrasil.STOP"
        const val EXTRA_PEERS = "peers" // String[] of peer URIs, e.g. "tls://host:port"

        private const val TAG = "YggdrasilVpnService"
        private const val CHANNEL_ID = "yggdrasil_vpn"
        private const val NOTIFICATION_ID = 2000

        // Not a binder connection: callers (YggdrasilController) only ever need a
        // cheap "is it up, what's its address" poll from another Activity, nowhere
        // near hot enough to justify bindService()'s lifecycle ceremony.
        @Volatile private var instance: YggdrasilVpnService? = null

        val runningAddress: String? get() = instance?.addressString
    }

    private val yggdrasil = Yggdrasil()
    private val started = AtomicBoolean()

    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private var tunnel: ParcelFileDescriptor? = null
    private var readerStream: FileInputStream? = null
    private var writerStream: FileOutputStream? = null

    /** Last-known address/subnet, exposed for callers that started us to poll -
     * see [YggdrasilController] for the actual read side. Null until started. */
    @Volatile var addressString: String? = null
        private set

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stop()
                START_NOT_STICKY
            }
            else -> {
                val peers = intent?.getStringArrayExtra(EXTRA_PEERS)?.toList() ?: emptyList()
                start(peers)
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
        // The user pulled VPN consent from system Settings - Android calls this
        // instead of onDestroy() in that case, but we still need the same teardown.
        stop()
        super.onRevoke()
    }

    private fun configFile() = File(filesDir, "yggdrasil-config.json")

    /**
     * Loads the persisted config (generating+saving a fresh one, with a new
     * identity, only the very first time this ever runs), then overwrites just
     * its "Peers" array with [peers] - see the class doc for why identity and
     * peer list are treated so differently here.
     */
    private fun buildConfig(peers: List<String>): ByteArray {
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
        json.put("Peers", JSONArray(peers))
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

    private fun start(peers: List<String>) {
        if (!started.compareAndSet(false, true)) return

        val notification = Notification.Builder(this, notificationChannel())
            .setContentTitle(getString(nu.bruijn.rcvehicle.R.string.app_name))
            .setContentText(getString(nu.bruijn.rcvehicle.R.string.yggdrasil_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        try {
            yggdrasil.startJSON(buildConfig(peers))
        } catch (e: Exception) {
            Log.e(TAG, "yggdrasil startJSON failed", e)
            started.set(false)
            stopSelf()
            return
        }

        val address = yggdrasil.addressString
        addressString = address

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
            stop()
            return
        }
        tunnel = parcel
        readerStream = FileInputStream(parcel.fileDescriptor)
        writerStream = FileOutputStream(parcel.fileDescriptor)

        // Two plain background threads, not coroutines: both loops spend their
        // whole life blocked in a synchronous read (FileInputStream.read()/
        // Yggdrasil.recvBuffer()) - a dedicated OS thread each, interrupted on
        // stop(), is simpler and more predictable here than adapting blocking Go/
        // JNI calls to a coroutine dispatcher for no real benefit.
        readerThread = thread(name = "YggdrasilTunReader") { pumpTunToYggdrasil() }
        writerThread = thread(name = "YggdrasilTunWriter") { pumpYggdrasilToTun() }
    }

    private fun stop() {
        if (!started.compareAndSet(true, false)) return

        try {
            yggdrasil.stop()
        } catch (_: Exception) {
        }
        addressString = null

        readerStream?.let { runCatching { it.close() } }
        readerStream = null
        writerStream?.let { runCatching { it.close() } }
        writerStream = null
        tunnel?.let { runCatching { it.close() } }
        tunnel = null

        readerThread?.interrupt()
        readerThread = null
        writerThread?.interrupt()
        writerThread = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** TUN -> Yggdrasil: whatever the OS wants to send out over the mesh. */
    private fun pumpTunToYggdrasil() {
        val buf = ByteArray(65535)
        while (started.get() && !Thread.currentThread().isInterrupted) {
            val input = readerStream ?: break
            if (!input.fd.valid()) break
            try {
                val n = input.read(buf)
                if (n > 0) yggdrasil.sendBuffer(buf, n.toLong())
            } catch (e: Exception) {
                if (started.get()) Log.i(TAG, "tun->yggdrasil: $e")
                break
            }
        }
    }

    /** Yggdrasil -> TUN: whatever arrived over the mesh, headed back into Android. */
    private fun pumpYggdrasilToTun() {
        val buf = ByteArray(65535)
        while (started.get() && !Thread.currentThread().isInterrupted) {
            val output = writerStream ?: break
            if (!output.fd.valid()) break
            try {
                val n = yggdrasil.recvBuffer(buf)
                if (n > 0) output.write(buf, 0, n.toInt())
            } catch (e: Exception) {
                // Same ENOBUFS-is-not-fatal caveat the reference implementation
                // notes (AdguardForAndroid#724) - a full socket buffer momentarily
                // rejecting a write isn't a reason to tear the whole tunnel down.
                if (started.get() && !e.toString().contains("ENOBUFS")) {
                    Log.i(TAG, "yggdrasil->tun: $e")
                    break
                }
            }
        }
    }
}
