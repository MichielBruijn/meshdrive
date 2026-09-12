package nu.bruijn.rcvehicle.signaling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Exchanged as newline-delimited JSON over a plain TCP socket directly between the
 * two phones' Yggdrasil addresses (plan section 3) - no signaling server needed
 * since both addresses are fixed and known ahead of time. WebRTC's own API
 * (createOffer/setLocalDescription/setRemoteDescription/addIceCandidate) is
 * signaling-transport-agnostic by design, so a hand-rolled transport like this is
 * a normal, supported way to drive it.
 */
@Serializable
sealed class SignalingMessage {
    @Serializable
    @SerialName("offer")
    data class Offer(val sdp: String) : SignalingMessage()

    @Serializable
    @SerialName("answer")
    data class Answer(val sdp: String) : SignalingMessage()

    @Serializable
    @SerialName("ice")
    data class Ice(val sdpMid: String?, val sdpMLineIndex: Int, val candidate: String) : SignalingMessage()

    @Serializable
    @SerialName("ping")
    data object Ping : SignalingMessage()

    @Serializable
    @SerialName("bye")
    data object Bye : SignalingMessage()

    companion object {
        const val PORT = 7860
    }
}
