package nu.bruijn.rcvehicle.rtc

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
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
 * layer, so ICE only ever gathers host candidates. `networkIgnoreMask = 0` is set
 * explicitly (matching the library default) so a future version bump can't
 * silently start filtering out the Yggdrasil tun interface's ADAPTER_TYPE_VPN.
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
    var onControlReceived: ((steer: Float, throttle: Float, seq: Int) -> Unit)? = null
    var onDataChannelOpen: (() -> Unit)? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        val options = PeerConnectionFactory.Options()
        options.networkIgnoreMask = 0 // don't filter any adapter type - see class doc
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
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
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
