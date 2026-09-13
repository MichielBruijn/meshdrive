package nu.bruijn.rcvehicle.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    /** Controller side: connect to the vehicle's known Yggdrasil address. */
    fun connect(address: String, port: Int) {
        socket = DatagramSocket().apply {
            connect(InetAddress.getByName(address), port)
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
        val sock = DatagramSocket(port)
        socket = sock
        receiveJob = scope.launch {
            val buffer = ByteArray(64)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isActive) {
                try {
                    sock.receive(packet)
                    ControlFrame.decode(packet.data, packet.length)?.let { onReceived(it, packet.address) }
                } catch (_: Exception) {
                    if (!isActive) break
                }
            }
        }
    }

    fun close() {
        receiveJob?.cancel()
        socket?.close()
        socket = null
    }

    companion object {
        const val PORT = 7861
    }
}
