package nu.bruijn.rcvehicle.control

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Direct UDP socket between the two phones' known Yggdrasil addresses, replacing
 * the originally-planned WebRTC data channel for control (plan section 4).
 *
 * CONFIRMED ON REAL HARDWARE (2026-09-13): stream-webrtc-android's ICE candidate
 * gathering never surfaced the Yggdrasil tun interface on a cellular-only phone,
 * even with the library's own "requestVPN" field trial enabled correctly. Plain
 * sockets (this class) never had this problem - they just use the OS's normal
 * IPv6 routing table, which already has the `200::/7 -> tun` route. Since we
 * already know a fixed, always-reachable address pair (proven via ping6), we
 * don't need ICE's dynamic NAT-traversal discovery at all for this - it was
 * solving a problem we don't have.
 *
 * Video (plan section 4's other half) hit the identical ICE/VPN problem and was
 * likewise moved off WebRTC onto plain UDP - see video/VideoSender.kt and
 * video/VideoReceiver.kt. The now-fully-unused RtcClient/signaling classes and
 * the stream-webrtc-android dependency itself (its native libs alone were ~41MB
 * across 4 ABIs - the large majority of the app's entire size) were removed
 * outright rather than kept "in case a future library fix helps" - nothing had
 * used them in months and a real library fix, if one ever comes, is a plain
 * dependency re-add away.
 */
class ControlLink {
    private var socket: DatagramSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiveJob: Job? = null
    private var pingJob: Job? = null

    // Set by connect()/listen() - every send() signs with this, every receive
    // verifies against it and silently drops anything that doesn't match (see
    // SignedFrame's doc). Never null after either of those runs; a caller trying
    // to send/receive before one of them ran is a programming error, not
    // something to handle gracefully.
    private lateinit var secret: ByteArray

    /** Controller side: connect to the vehicle's known Yggdrasil address.
     * [secret] is the PairingSecret learned from that vehicle's QR code. */
    fun connect(address: String, port: Int, secret: ByteArray) {
        this.secret = secret
        socket = DatagramSocket().apply {
            connect(InetAddress.getByName(address), port)
            markHighPriority()
        }
    }

    /**
     * Marks this socket's packets DSCP Expedited Forwarding (0xB8) - a best-effort
     * request that any network hop honoring DSCP favors control traffic over the
     * (much heavier) video stream, which is left at default best-effort. Explicitly
     * best-effort: most consumer/cellular paths ignore DSCP entirely, but it's the
     * standard, near-zero-cost way to ask, and control traffic staying tiny (9 bytes,
     * ~33Hz) means it barely needs the concession anyway - the real headroom for
     * "control always wins" comes from video adapting its own bitrate down (see
     * VideoQuality / ControllerDriveActivity's auto-quality loop) rather than from
     * fighting video for the same pipe.
     */
    private fun DatagramSocket.markHighPriority() {
        try {
            trafficClass = 0xB8
        } catch (_: Exception) {
        }
    }

    /**
     * Callers (the 20-33Hz UI tick loops) call this from the main thread - actual
     * socket I/O is dispatched onto the background scope since DatagramSocket.send()
     * does a direct syscall and would throw NetworkOnMainThreadException otherwise
     * (unlike OkHttp/WebRTC's send(), which just enqueues onto their own thread).
     */
    fun send(steer: Float, throttle: Float, seq: Int) {
        sendBytes(ControlFrame.encodeControl(steer, throttle, seq))
    }

    /** Controller side: toggle the vehicle's Steering Limit. Rare, unlike send() above. */
    fun sendSteerLimit(enabled: Boolean) {
        sendBytes(ControlFrame.encodeSteerLimit(enabled))
    }

    /** Controller side: ask the vehicle to switch its video encoder to a different VideoQuality preset. */
    fun sendQuality(presetIndex: Int) {
        sendBytes(ControlFrame.encodeQuality(presetIndex))
    }

    /** Controller side: ask the vehicle to switch cameras - "rear view" toggle. */
    fun sendCamera(useFront: Boolean) {
        sendBytes(ControlFrame.encodeCamera(useFront))
    }

    /** Controller side: start/stop the vehicle's GPS/speed telemetry (debug overlay). */
    fun sendTelemetryEnable(enabled: Boolean) {
        sendBytes(ControlFrame.encodeTelemetryEnable(enabled))
    }

    /** Controller side: tune the speed-based steering limiter (debug sliders). */
    fun sendSteerLimitConfig(maxSpeedKmh: Float, reductionFraction: Float) {
        sendBytes(ControlFrame.encodeSteerLimitConfig(maxSpeedKmh, reductionFraction))
    }

    // Vehicle side: who to send Telemetry frames to - learned from whichever address
    // listen() last received a real packet from, same as maybeStartVideo()'s address
    // (VehicleActivity) - kept here too since sendTelemetry() can fire independently
    // of any particular incoming packet (on its own GPS-update cadence).
    @Volatile private var lastSenderAddress: InetAddress? = null
    @Volatile private var lastSenderPort: Int = -1

    /** Vehicle side: send the current GPS fix to whichever controller last talked to
     * this socket. No-op before that's known (listen() hasn't received anything yet). */
    fun sendTelemetry(hasFix: Boolean, speedKmh: Float, lat: Double, lon: Double) {
        val sock = socket ?: return
        val addr = lastSenderAddress ?: return
        val port = lastSenderPort
        val data = SignedFrame.sign(ControlFrame.encodeTelemetry(hasFix, speedKmh, lat, lon), secret)
        scope.launch {
            try {
                sock.send(DatagramPacket(data, data.size, addr, port))
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Controller side: periodically pings the vehicle over the same connected socket
     * used by send(), and reports round-trip time (ms) via onRtt as replies come back -
     * for the FPS/latency debug overlay. Must be called after connect(). The vehicle
     * side needs no matching call: listen() below replies to any Ping automatically.
     */
    fun startPing(intervalMs: Long = 1000, onRtt: (Long) -> Unit, onTelemetry: ((ControlFrame.Decoded.Telemetry) -> Unit)? = null) {
        val sock = socket ?: return
        pingJob = scope.launch {
            while (isActive) {
                sendBytes(ControlFrame.encodePing(SystemClock.elapsedRealtime()))
                delay(intervalMs)
            }
        }
        receiveJob = scope.launch {
            val buffer = ByteArray(64)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isActive) {
                try {
                    sock.receive(packet)
                    val verified = SignedFrame.verify(packet.data, packet.length, secret) ?: continue
                    when (val decoded = ControlFrame.decode(verified, verified.size)) {
                        is ControlFrame.Decoded.Pong -> onRtt(SystemClock.elapsedRealtime() - decoded.sentAtMs)
                        is ControlFrame.Decoded.Telemetry -> onTelemetry?.invoke(decoded)
                        else -> {}
                    }
                } catch (_: Exception) {
                    if (!isActive) break
                }
            }
        }
    }

    private fun sendBytes(data: ByteArray) {
        val signed = SignedFrame.sign(data, secret)
        scope.launch {
            try {
                socket?.send(DatagramPacket(signed, signed.size))
            } catch (_: Exception) {
                // best-effort, unreliable by design - a dropped frame is fine, the next
                // tick will send a fresh one
            }
        }
    }

    /**
     * Vehicle side: bind and receive control frames from whoever sends them.
     * onReceived also gets the packet's source address, so the vehicle can learn
     * the controller's Yggdrasil address from its control traffic and use it as
     * the destination for the video stream, without a separate discovery step.
     * [secret] is this vehicle's own PairingSecret - anything that doesn't carry
     * a valid tag for it is silently dropped before ever reaching ControlFrame.decode(),
     * same as a packet that never arrived (see SignedFrame's doc).
     */
    fun listen(port: Int, secret: ByteArray, onReceived: (ControlFrame.Decoded, InetAddress) -> Unit) {
        this.secret = secret
        val sock = DatagramSocket(port).apply { markHighPriority() }
        socket = sock
        receiveJob = scope.launch {
            val buffer = ByteArray(64)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isActive) {
                try {
                    sock.receive(packet)
                    val verified = SignedFrame.verify(packet.data, packet.length, secret) ?: continue
                    lastSenderAddress = packet.address
                    lastSenderPort = packet.port
                    val decoded = ControlFrame.decode(verified, verified.size) ?: continue
                    if (decoded is ControlFrame.Decoded.Ping) {
                        // Latency probe reply - handled here rather than surfaced to
                        // onReceived, since every caller of listen() just wants real
                        // control traffic, not to know about the debug ping/pong.
                        val pong = SignedFrame.sign(ControlFrame.encodePong(decoded.sentAtMs), secret)
                        try {
                            sock.send(DatagramPacket(pong, pong.size, packet.address, packet.port))
                        } catch (_: Exception) {
                        }
                    } else {
                        onReceived(decoded, packet.address)
                    }
                } catch (_: Exception) {
                    if (!isActive) break
                }
            }
        }
    }

    fun close() {
        receiveJob?.cancel()
        pingJob?.cancel()
        socket?.close()
        socket = null
    }

    companion object {
        const val PORT = 7861

        /**
         * "Is this vehicle reachable right now" check for ControllerActivity's
         * recent-vehicles list - up to [attempts] Ping/Pong round trips on a
         * throwaway socket (succeeds on the first that lands), not a full
         * connect()/startPing() session. Works whenever the vehicle's own screen
         * is open (VehicleActivity now auto-starts listen() as soon as it opens),
         * since listen() replies to any Ping unconditionally, regardless of
         * whether a real control session is active.
         *
         * A single attempt isn't enough over the public Yggdrasil mesh: measured
         * real-world hop latency alone ran to 800ms+ in testing, and any peer
         * reconnect (YggdrasilVpnService's PeerMonitor switching to a better
         * candidate, or its wifi-availability-triggered redial) costs a few
         * seconds of route reconvergence during which packets are simply
         * dropped, not delayed - confirmed on real hardware as a false
         * "offline" dot for a vehicle that came right back a few seconds later.
         * Retrying instead of just waiting longer once bounds the worst case
         * (a genuinely offline vehicle) to roughly attempts*timeoutMs instead of
         * one long wait, while still recovering from a single dropped probe.
         */
        suspend fun probeOnline(address: String, secret: ByteArray, attempts: Int = 3, timeoutMs: Int = 1200): Boolean = withContext(Dispatchers.IO) {
            val inetAddress = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return@withContext false
            for (attempt in 1..attempts) {
                try {
                    DatagramSocket().use { socket ->
                        socket.soTimeout = timeoutMs
                        val ping = SignedFrame.sign(ControlFrame.encodePing(SystemClock.elapsedRealtime()), secret)
                        socket.send(DatagramPacket(ping, ping.size, inetAddress, PORT))
                        val buffer = ByteArray(64)
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet) // throws SocketTimeoutException if nothing replies in time
                        val verified = SignedFrame.verify(packet.data, packet.length, secret)
                        if (verified != null && ControlFrame.decode(verified, verified.size) is ControlFrame.Decoded.Pong) {
                            return@withContext true
                        }
                    }
                } catch (_: Exception) {
                    // try again, unless this was the last attempt
                }
            }
            false
        }
    }
}
