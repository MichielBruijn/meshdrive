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
 * gathering never surfaces the Yggdrasil tun interface on a cellular-only phone,
 * even with the library's own "requestVPN" field trial enabled correctly (see
 * RtcClient's doc comment for the full investigation). Plain sockets (this class,
 * and SignalingSession's TCP) have never had this problem - they just use the
 * OS's normal IPv6 routing table, which already has the `200::/7 -> tun` route.
 * Since we already know a fixed, always-reachable address pair (proven via ping6
 * and the TCP signaling channel), we don't need ICE's dynamic NAT-traversal
 * discovery at all for this - it was solving a problem we don't have.
 *
 * Video (plan section 4's other half) hit the identical ICE/VPN problem and was
 * likewise moved off WebRTC onto plain UDP - see video/VideoSender.kt and
 * video/VideoReceiver.kt. RtcClient/the signaling classes are left unused in the
 * codebase for now rather than deleted, in case a future library fix makes them
 * viable again.
 */
class ControlLink {
    private var socket: DatagramSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiveJob: Job? = null
    private var pingJob: Job? = null

    /** Controller side: connect to the vehicle's known Yggdrasil address. */
    fun connect(address: String, port: Int) {
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

    /**
     * Controller side: periodically pings the vehicle over the same connected socket
     * used by send(), and reports round-trip time (ms) via onRtt as replies come back -
     * for the FPS/latency debug overlay. Must be called after connect(). The vehicle
     * side needs no matching call: listen() below replies to any Ping automatically.
     */
    fun startPing(intervalMs: Long = 1000, onRtt: (Long) -> Unit) {
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
                    val decoded = ControlFrame.decode(packet.data, packet.length)
                    if (decoded is ControlFrame.Decoded.Pong) {
                        onRtt(SystemClock.elapsedRealtime() - decoded.sentAtMs)
                    }
                } catch (_: Exception) {
                    if (!isActive) break
                }
            }
        }
    }

    private fun sendBytes(data: ByteArray) {
        scope.launch {
            try {
                socket?.send(DatagramPacket(data, data.size))
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
     */
    fun listen(port: Int, onReceived: (ControlFrame.Decoded, InetAddress) -> Unit) {
        val sock = DatagramSocket(port).apply { markHighPriority() }
        socket = sock
        receiveJob = scope.launch {
            val buffer = ByteArray(64)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isActive) {
                try {
                    sock.receive(packet)
                    val decoded = ControlFrame.decode(packet.data, packet.length) ?: continue
                    if (decoded is ControlFrame.Decoded.Ping) {
                        // Latency probe reply - handled here rather than surfaced to
                        // onReceived, since every caller of listen() just wants real
                        // control traffic, not to know about the debug ping/pong.
                        val pong = ControlFrame.encodePong(decoded.sentAtMs)
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
         * One-shot "is this vehicle reachable right now" check for ControllerActivity's
         * recent-vehicles list - a single Ping/Pong round trip on a throwaway socket, not
         * a full connect()/startPing() session. Works whenever the vehicle's own screen
         * is open (VehicleActivity now auto-starts listen() as soon as it opens), since
         * listen() replies to any Ping unconditionally, regardless of whether a real
         * control session is active. False on any failure or timeout - "not reachable"
         * covers both "vehicle off" and "genuinely no route", no need to distinguish
         * those for a simple online/offline dot.
         */
        suspend fun probeOnline(address: String, timeoutMs: Int = 1500): Boolean = withContext(Dispatchers.IO) {
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = timeoutMs
                    val inetAddress = InetAddress.getByName(address)
                    val ping = ControlFrame.encodePing(SystemClock.elapsedRealtime())
                    socket.send(DatagramPacket(ping, ping.size, inetAddress, PORT))
                    val buffer = ByteArray(64)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet) // throws SocketTimeoutException if nothing replies in time
                    ControlFrame.decode(packet.data, packet.length) is ControlFrame.Decoded.Pong
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
