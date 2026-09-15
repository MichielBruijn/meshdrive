package nu.bruijn.rcvehicle.control

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Wraps every ControlFrame packet with a truncated HMAC-SHA256 tag keyed by the
 * per-vehicle PairingSecret - so a UDP packet to a vehicle's control port is only
 * acted on if it came from whoever scanned that vehicle's QR code, not just
 * whoever learned or guessed its Yggdrasil address (see PairingSecret's doc for
 * why the address alone stopped being enough). Applied to every packet in both
 * directions, not just controller -> vehicle commands - an unauthenticated Pong
 * or Telemetry reply would let anyone on the mesh spoof "vehicle is online" or
 * feed the controller's debug overlay fake GPS/speed.
 */
object SignedFrame {
    private const val ALGORITHM = "HmacSHA256"

    // Truncated from the full 32-byte HMAC-SHA256 output - still computationally
    // infeasible to forge (128 bits of tag), and keeps the per-packet overhead
    // small on a channel that's deliberately tiny (9 bytes, ~33Hz) and DSCP-
    // prioritized for latency - doubling it for a full tag isn't worth it.
    private const val TAG_SIZE = 16

    fun sign(payload: ByteArray, secret: ByteArray): ByteArray {
        val tag = mac(secret).doFinal(payload).copyOf(TAG_SIZE)
        return payload + tag
    }

    /** Null if the tag is missing, too short, or doesn't match [secret] - callers
     * should treat that identically to "no packet arrived," not report why, so an
     * attacker probing with guessed secrets learns nothing from the difference. */
    fun verify(bytes: ByteArray, length: Int, secret: ByteArray): ByteArray? {
        if (length <= TAG_SIZE) return null
        val payloadLen = length - TAG_SIZE
        val expected = mac(secret).doFinal(bytes.copyOf(payloadLen)).copyOf(TAG_SIZE)
        // Constant-time comparison - a short-circuiting != would let a timing
        // attack narrow the tag down byte by byte.
        var diff = 0
        for (i in 0 until TAG_SIZE) diff = diff or (expected[i].toInt() xor bytes[payloadLen + i].toInt())
        return if (diff == 0) bytes.copyOf(payloadLen) else null
    }

    private fun mac(secret: ByteArray): Mac =
        Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(secret, ALGORITHM)) }
}
