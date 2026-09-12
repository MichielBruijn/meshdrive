package nu.bruijn.rcvehicle.rtc

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.NetworkMonitor
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shared PeerConnection wrapper for both controller and vehicle (plan section 4).
 * No STUN/TURN servers - Yggdrasil already solved NAT traversal at the network
 * layer, so ICE only ever gathers host candidates.
 *
 * CONFIRMED ON REAL HARDWARE (2026-09-13, Pixel 9 Pro <-> Samsung A52s): the
 * original plan assumption ("networkIgnoreMask = 0 should surface the Yggdrasil
 * tun interface like any other") was WRONG, and no per-app trick fixes it -
 * confirmed via logcat that org.webrtc.NetworkMonitorAutoDetect issues its
 * ConnectivityManager.registerNetworkCallback() request with NET_CAPABILITY_NOT_VPN
 * hardcoded by default, so the Yggdrasil VPN network is never even discovered
 * (networkIgnoreMask only filters networks AFTER discovery; bindProcessToNetwork()
 * doesn't affect this either - it's a separate registerNetworkCallback subscription
 * unrelated to the process's default network). The library's own source
 * (NetworkMonitorAutoDetect.java) has a purpose-built escape hatch for exactly
 * this: a "requestVPN" field trial that removes NET_CAPABILITY_NOT_VPN from that
 * request. Enabled below via PeerConnectionFactory.InitializationOptions -
 * without it, ICE has no mutually reachable candidate pair and goes to FAILED.
 *
 * With requestVPN enabled, WebRTC also (re)discovers cellular/wifi/ethernet as
 * candidate sources again - networkIgnoreMask filters those back out so ICE only
 * ever gathers from the Yggdrasil tun, which is the only path that could work
 * between these two phones anyway (avoids wasted/misleading candidate pairs,
 * e.g. if both phones happen to share a LAN during a bench test).
 *
 * SECOND GOTCHA (also confirmed via logcat): passing setFieldTrials() to
 * PeerConnectionFactory.InitializationOptions alone did NOT reach the network
 * monitor - `NetworkMonitor: Start monitoring ... fieldTrialsString:` logged empty
 * every time. Reason: org.webrtc.NetworkMonitor.getInstance().startMonitoring(...)
 * is lazy - it only builds its NetworkChangeDetector on the FIRST call, and the
 * native side calls a private overload with whatever field trial string IT has
 * (apparently not the global one we set), passing "". Fix: call the PUBLIC
 * `NetworkMonitor.getInstance().startMonitoring(context, fieldTrials)` OURSELVES
 * before creating the PeerConnectionFactory, so our field trial string wins the
 * race and the later native-triggered call just reuses the already-built detector.
 *
 * Fase 4 is data-channel-only: no video track here yet (that's the plan's next
 * pass, once host-candidate-over-Yggdrasil is proven on real hardware).
 */
class RtcClient(context: Context) {

    val eglBase: EglBase = EglBase.create()

    private val factory: PeerConnectionFactory
    var peerConnection: PeerConnection? = null
        private set
    private var dataChannel: DataChannel? = null

    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onIceConnectionChange: ((PeerConnection.IceConnectionState) -> Unit)? = null
    var onIceGatheringChange: ((PeerConnection.IceGatheringState) -> Unit)? = null
    var onControlReceived: ((steer: Float, throttle: Float, seq: Int) -> Unit)? = null
    var onDataChannelOpen: (() -> Unit)? = null

    init {
        // Must happen BEFORE PeerConnectionFactory.initialize() - that call's own
        // nativeInitializeAndroidGlobals() already triggers the native network monitor's
        // first (empty-field-trial) startMonitoring() call as a side effect, which wins
        // the "only create the detector once" race if we call this after. See class doc's
        // "SECOND GOTCHA". Symmetric stopMonitoring() call in close().
        NetworkMonitor.getInstance().startMonitoring(context, "requestVPN:true/")
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials("requestVPN:true/")
                .createInitializationOptions()
        )
        val options = PeerConnectionFactory.Options()
        // Ignoring everything except VPN turned out unreliable in practice (one real-
        // hardware run produced zero local candidates at all despite the VPN network
        // being visible). Leave nothing ignored instead: with requestVPN enabled, ICE
        // gathers cellular/wifi/VPN candidates and just tries every pair - the
        // non-Yggdrasil ones simply fail their connectivity check when the two phones
        // aren't otherwise reachable, which is harmless.
        options.networkIgnoreMask = 0
        factory = PeerConnectionFactory.builder().setOptions(options).createPeerConnectionFactory()
    }

    fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()) // no ICE servers - host candidates only
        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                onIceCandidate?.invoke(candidate)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                onIceConnectionChange?.invoke(state)
            }

            override fun onDataChannel(channel: DataChannel) {
                attachDataChannel(channel)
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                onIceGatheringChange?.invoke(state)
            }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: org.webrtc.RtpReceiver?, p1: Array<out MediaStream>?) {}
        })
    }

    /** Controller side creates the channel; vehicle side receives it via onDataChannel. */
    fun createControlDataChannel() {
        val init = DataChannel.Init().apply {
            ordered = false
            maxRetransmits = 0 // unreliable/unordered - a stale command is worse than a dropped one
        }
        attachDataChannel(peerConnection!!.createDataChannel("control", init))
    }

    private fun attachDataChannel(channel: DataChannel) {
        dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) onDataChannelOpen?.invoke()
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                if (bytes.size == 9 && bytes[0] == MSG_TYPE_CONTROL) {
                    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    bb.position(1)
                    val steer = bb.short / 1000f
                    val throttle = bb.short / 1000f
                    val seq = bb.int
                    onControlReceived?.invoke(steer, throttle, seq)
                }
            }
        })
    }

    /** steer/throttle normalized -1.0..1.0. */
    fun sendControl(steer: Float, throttle: Float, seq: Int) {
        val bb = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(MSG_TYPE_CONTROL)
        bb.putShort((steer.coerceIn(-1f, 1f) * 1000).toInt().toShort())
        bb.putShort((throttle.coerceIn(-1f, 1f) * 1000).toInt().toShort())
        bb.putInt(seq)
        bb.flip()
        dataChannel?.send(DataChannel.Buffer(bb, true))
    }

    suspend fun createOffer(): SessionDescription = suspendCancellableCoroutine { cont ->
        peerConnection!!.createOffer(sdpObserver(
            onCreate = { sdp -> setLocalAndResume(sdp, cont) },
            onFailure = { cont.resumeWithException(RuntimeException(it)) },
        ), MediaConstraints())
    }

    suspend fun createAnswer(): SessionDescription = suspendCancellableCoroutine { cont ->
        peerConnection!!.createAnswer(sdpObserver(
            onCreate = { sdp -> setLocalAndResume(sdp, cont) },
            onFailure = { cont.resumeWithException(RuntimeException(it)) },
        ), MediaConstraints())
    }

    private fun setLocalAndResume(sdp: SessionDescription, cont: kotlin.coroutines.Continuation<SessionDescription>) {
        peerConnection!!.setLocalDescription(sdpObserver(
            onSetSuccess = { cont.resume(sdp) },
            onFailure = { cont.resumeWithException(RuntimeException(it)) },
        ), sdp)
    }

    suspend fun setRemoteDescription(sdp: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        peerConnection!!.setRemoteDescription(sdpObserver(
            onSetSuccess = { cont.resume(Unit) },
            onFailure = { cont.resumeWithException(RuntimeException(it)) },
        ), sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun close() {
        peerConnection?.close()
        peerConnection = null
        eglBase.release()
        NetworkMonitor.getInstance().stopMonitoring() // balances the startMonitoring() call in init
    }

    private fun sdpObserver(
        onCreate: (SessionDescription) -> Unit = {},
        onSetSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {},
    ) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = onCreate(sdp)
        override fun onSetSuccess() = onSetSuccess()
        override fun onCreateFailure(error: String) = onFailure(error)
        override fun onSetFailure(error: String) = onFailure(error)
    }

    companion object {
        private const val MSG_TYPE_CONTROL: Byte = 0x01
    }
}
